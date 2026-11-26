package io.github.xiaotong6666.maihoku.features.googleone

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.googleone.billing.GoogleOneReplacementPolicy
import io.github.xiaotong6666.maihoku.features.googleone.billing.GoogleOneSubscriptionSnapshot
import io.github.xiaotong6666.maihoku.features.googleone.billing.OfferPhase
import io.github.xiaotong6666.maihoku.features.googleone.billing.OfferPricing
import io.github.xiaotong6666.maihoku.features.googleone.catalog.GoogleOneSkuDiscovery
import io.github.xiaotong6666.maihoku.features.googleone.catalog.GoogleOneSkuParser
import io.github.xiaotong6666.maihoku.features.googleone.query.GoogleOneFoundSkuCache
import io.github.xiaotong6666.maihoku.features.googleone.query.GoogleOneQueryReport
import io.github.xiaotong6666.maihoku.hook.ModuleResources
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Activity-bound Billing client; all purchases require manual confirmation. */
internal class GoogleOneBillingSession(activity: Activity) {
    private val activityRef = WeakReference(activity)
    private val foundSkuCache = GoogleOneFoundSkuCache(activity.applicationContext)
    private var client: BillingClient? = null

    @Volatile private var requestId = 0

    @Volatile private var closed = false
    private var lastSkuInput = ""
    private var lastQueryReport: GoogleOneQueryReport? = null
    private var activeQueryReportId: Int? = null
    private var queryProgressDialog: AlertDialog? = null
    private var lastSubscriptionSnapshot: GoogleOneSubscriptionSnapshot? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private data class AvailableOffer(
        val product: ProductDetails,
        val details: ProductDetails.SubscriptionOfferDetails,
        val phases: List<OfferPhase>,
    ) {
        val isFreeTrial: Boolean get() = OfferPricing.hasFreeFirstPhase(phases)
        fun label(activity: Activity): String {
            val freeMonths = OfferPricing.freeMonths(phases)
            val freeDays = OfferPricing.freeDays(phases)
            val tag = when {
                freeMonths > 0 -> ModuleResources.quantityText(activity, R.plurals.googleone_offer_free_months, freeMonths, freeMonths)

                freeDays > 0 -> ModuleResources.quantityText(activity, R.plurals.googleone_offer_free_days, freeDays, freeDays)

                isFreeTrial -> ModuleResources.text(activity, R.string.googleone_offer_free)

                phases.firstOrNull()?.formattedPrice?.isNotBlank() == true ->
                    ModuleResources.text(activity, R.string.googleone_offer_paid_price, phases.first().formattedPrice)

                else -> ModuleResources.text(activity, R.string.googleone_offer_paid)
            }
            val offerId = details.offerId ?: details.basePlanId
            return ModuleResources.text(
                activity,
                R.string.googleone_offer_row,
                product.productId,
                offerId,
                tag,
            )
        }
    }

    private fun AvailableOffer.toReportOffer(): GoogleOneQueryReport.Offer = GoogleOneQueryReport.Offer(
        sku = product.productId,
        basePlanId = details.basePlanId,
        offerId = details.offerId,
        phases = phases.map {
            GoogleOneQueryReport.Phase(
                price = it.formattedPrice,
                micros = it.amountMicros,
                period = it.billingPeriod,
                cycles = it.billingCycles,
            )
        },
    )

    private fun offerFrom(
        product: ProductDetails,
        offer: ProductDetails.SubscriptionOfferDetails,
    ): AvailableOffer = AvailableOffer(
        product,
        offer,
        offer.pricingPhases.pricingPhaseList.map {
            OfferPhase(it.priceAmountMicros, it.formattedPrice, it.billingPeriod, it.billingCycleCount)
        },
    )

    private fun AvailableOffer.hasSameTerms(other: AvailableOffer): Boolean = product.productId == other.product.productId &&
        details.basePlanId == other.details.basePlanId &&
        details.offerId == other.details.offerId &&
        phases == other.phases

    fun showMenu() {
        val activity = activityRef.get() ?: return
        if (closed || activity.isFinishing || activity.isDestroyed) return
        val entries = arrayOf(
            text(activity, R.string.googleone_menu_default),
            text(activity, R.string.googleone_menu_custom),
            text(activity, R.string.googleone_menu_paid_presets),
            text(activity, R.string.googleone_menu_scan),
            text(activity, R.string.googleone_menu_status),
            text(activity, R.string.googleone_menu_report),
            text(activity, R.string.googleone_menu_cached_skus),
            text(activity, R.string.googleone_menu_play_subscriptions),
        )
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_menu_title))
            .setItems(entries) { _, index ->
                when (index) {
                    0 -> query(GoogleOneSkuParser.defaultProducts)
                    1 -> showSkuInput(activity)
                    2 -> showPaidPresets(activity)
                    3 -> showSkuScanMenu(activity)
                    4 -> showSubscriptionStatus()
                    5 -> showReport(activity)
                    6 -> showCachedSkuFilters(activity)
                    7 -> openPlaySubscriptions(activity)
                }
            }
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .show()
    }

    private fun showPaidPresets(activity: Activity) {
        val presets = GoogleOneSkuDiscovery.paidPresets
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_menu_paid_presets))
            .setItems(presets.toTypedArray()) { _, index ->
                AlertDialog.Builder(activity)
                    .setTitle(presets[index])
                    .setMessage(text(activity, R.string.googleone_paid_presets_warning))
                    .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                    .setPositiveButton(text(activity, R.string.googleone_query_button)) { _, _ ->
                        query(listOf(presets[index]))
                    }
                    .show()
            }
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .show()
    }

    private fun showSkuScanMenu(activity: Activity) {
        val families = GoogleOneSkuDiscovery.Family.entries
        val choices = arrayOf(
            text(activity, R.string.googleone_scan_observed),
            text(activity, R.string.googleone_scan_free),
            text(activity, R.string.googleone_scan_discount),
            text(activity, R.string.googleone_scan_mixed),
        )
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_menu_scan))
            .setItems(choices) { _, index ->
                confirmSkuScan(activity, choices[index], GoogleOneSkuDiscovery.candidates(families[index]))
            }
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .show()
    }

    private fun confirmSkuScan(activity: Activity, label: String, ids: List<String>) {
        AlertDialog.Builder(activity)
            .setTitle(label)
            .setMessage(
                text(
                    activity,
                    R.string.googleone_scan_confirm,
                    ids.size,
                    (ids.size + GoogleOneSkuParser.BATCH_SIZE - 1) / GoogleOneSkuParser.BATCH_SIZE,
                ),
            )
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .setPositiveButton(text(activity, R.string.googleone_scan_start)) { _, _ -> query(ids) }
            .show()
    }

    private fun openPlaySubscriptions(activity: Activity) {
        try {
            activity.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/account/subscriptions"),
                ),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Unable to open Play subscription management", error)
            notice(R.string.googleone_play_subscriptions_failed)
        }
    }

    private fun showReport(activity: Activity) {
        val report = lastQueryReport
        if (report == null) {
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_menu_report))
                .setMessage(text(activity, R.string.googleone_report_empty))
                .setPositiveButton(text(activity, R.string.googleone_ok), null)
                .show()
            return
        }
        val summary = report.summaryCounts()
        val state = when (report.status) {
            "complete" -> R.string.googleone_report_state_complete
            "failed" -> R.string.googleone_report_state_failed
            "canceled" -> R.string.googleone_report_state_canceled
            else -> R.string.googleone_report_state_running
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_menu_report))
            .setMessage(
                text(
                    activity,
                    R.string.googleone_report_summary,
                    text(activity, state),
                    summary.requested,
                    summary.returned,
                    summary.offers,
                    summary.notReturned,
                    summary.batches,
                ) + "\n" +
                    text(activity, R.string.googleone_report_extra_counts, summary.queried, summary.notQueried, summary.failed, summary.unexpected) +
                    "\n\n" +
                    text(activity, R.string.googleone_report_privacy),
            )
            .setNegativeButton(text(activity, R.string.googleone_close), null)
            .setNeutralButton(text(activity, R.string.googleone_report_copy)) { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(text(activity, R.string.googleone_report_clip_label), report.toJson()),
                )
                notice(R.string.googleone_report_copied)
            }
            .setPositiveButton(text(activity, R.string.googleone_report_share)) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, text(activity, R.string.googleone_report_subject))
                    putExtra(Intent.EXTRA_TEXT, report.toJson())
                }
                try {
                    activity.startActivity(
                        Intent.createChooser(send, text(activity, R.string.googleone_report_share)),
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to share query report", error)
                    notice(R.string.googleone_report_share_failed)
                }
            }
            .show()
    }

    private fun showCachedSkuFilters(activity: Activity) {
        val entries = foundSkuCache.entries()
        if (entries.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_menu_cached_skus))
                .setMessage(text(activity, R.string.googleone_cached_empty))
                .setPositiveButton(text(activity, R.string.googleone_ok), null)
                .show()
            return
        }
        val options = arrayOf(
            text(activity, R.string.googleone_cached_filter_all, entries.size),
            text(activity, R.string.googleone_cached_filter_free, entries.count { it.matches(GoogleOneFoundSkuCache.Filter.FREE) }),
            text(activity, R.string.googleone_cached_filter_paid, entries.count { it.matches(GoogleOneFoundSkuCache.Filter.PAID) }),
            text(activity, R.string.googleone_cached_filter_unknown, entries.count { it.matches(GoogleOneFoundSkuCache.Filter.UNCLASSIFIED) }),
            text(activity, R.string.googleone_filter_product),
        )
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_menu_cached_skus))
            .setItems(options) { _, index ->
                when (index) {
                    0 -> showCachedSkus(activity, GoogleOneFoundSkuCache.Filter.ALL)
                    1 -> showCachedSkus(activity, GoogleOneFoundSkuCache.Filter.FREE)
                    2 -> showCachedSkus(activity, GoogleOneFoundSkuCache.Filter.PAID)
                    3 -> showCachedSkus(activity, GoogleOneFoundSkuCache.Filter.UNCLASSIFIED)
                    4 -> showCachedSkuSearch(activity)
                }
            }
            .setNegativeButton(text(activity, R.string.googleone_close), null)
            .show()
    }

    private fun showCachedSkuSearch(activity: Activity) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            hint = text(activity, R.string.googleone_filter_product_hint)
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_filter_product))
            .setView(input)
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .setPositiveButton(text(activity, R.string.googleone_filter_button)) { _, _ ->
                showCachedSkus(activity, GoogleOneFoundSkuCache.Filter.ALL, input.text.toString().trim())
            }
            .show()
    }

    private fun showCachedSkus(
        activity: Activity,
        filter: GoogleOneFoundSkuCache.Filter,
        keyword: String = "",
        page: Int = 0,
    ) {
        val items = foundSkuCache.entries(filter, keyword)
        if (items.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_menu_cached_skus))
                .setMessage(text(activity, R.string.googleone_cached_no_matches))
                .setPositiveButton(text(activity, R.string.googleone_filter_button)) { _, _ -> showCachedSkuFilters(activity) }
                .show()
            return
        }
        val pages = items.chunked(CACHED_PAGE_SIZE)
        val current = page.coerceIn(pages.indices)
        val slice = pages[current]
        val labels = slice.map {
            if (it.freeOfferCount == null || it.paidOfferCount == null) {
                text(activity, R.string.googleone_cached_item_unknown, it.sku, it.offerCount)
            } else {
                text(activity, R.string.googleone_cached_item, it.sku, it.offerCount, it.freeOfferCount, it.paidOfferCount)
            }
        }.toMutableList()
        if (current > 0) labels += text(activity, R.string.googleone_page_previous)
        if (current < pages.lastIndex) labels += text(activity, R.string.googleone_page_next)
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_cached_title, items.size, current + 1, pages.size))
            .setItems(labels.toTypedArray()) { _, index ->
                when {
                    index < slice.size -> query(listOf(slice[index].sku))
                    current > 0 && index == slice.size -> showCachedSkus(activity, filter, keyword, current - 1)
                    else -> showCachedSkus(activity, filter, keyword, current + 1)
                }
            }
            .setNegativeButton(text(activity, R.string.googleone_filter_button)) { _, _ -> showCachedSkuFilters(activity) }
            .setNeutralButton(text(activity, R.string.googleone_cached_clear)) { _, _ ->
                AlertDialog.Builder(activity)
                    .setTitle(text(activity, R.string.googleone_cached_clear))
                    .setMessage(text(activity, R.string.googleone_cached_clear_confirm))
                    .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                    .setPositiveButton(text(activity, R.string.googleone_ok)) { _, _ -> foundSkuCache.clear() }
                    .show()
            }
            .setPositiveButton(text(activity, R.string.googleone_cached_refresh)) { _, _ ->
                query(items.map { it.sku })
            }
            .show()
    }

    private fun showSkuInput(activity: Activity) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            hint = text(activity, R.string.googleone_custom_skus_hint)
            setText(lastSkuInput)
            setSelection(this.text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_custom_skus_title))
            .setMessage(text(activity, R.string.googleone_custom_skus_help, GoogleOneSkuParser.MAX_SKUS))
            .setView(input)
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .setPositiveButton(text(activity, R.string.googleone_query_button)) { _, _ ->
                val raw = input.text.toString()
                lastSkuInput = raw
                try {
                    query(GoogleOneSkuParser.parse(raw))
                } catch (error: GoogleOneSkuParser.InvalidInput) {
                    Log.w(TAG, "Invalid SKU input: " + error.message)
                    val messageId = when (error.reason) {
                        GoogleOneSkuParser.InputError.TOO_LONG -> R.string.googleone_sku_error_too_long
                        GoogleOneSkuParser.InputError.EMPTY -> R.string.googleone_sku_error_empty
                        GoogleOneSkuParser.InputError.INVALID_ID -> R.string.googleone_sku_error_invalid
                        GoogleOneSkuParser.InputError.TOO_MANY -> R.string.googleone_sku_error_too_many
                    }
                    notice(messageId)
                }
            }
            .show()
    }

    private fun query(productIds: List<String>) {
        lastQueryReport?.cancel()
        activeQueryReportId = null
        val report = GoogleOneQueryReport(productIds)
        lastQueryReport = report
        startBillingSession(
            R.string.googleone_querying,
            onFailed = { code ->
                if (code != null) report.recordBillingEvent("billing_setup_failure", code)
                finishReportWithFailure(report, null)
            },
        ) { billing, id ->
            activeQueryReportId = id
            report.recordBillingEvent("billing_setup", BillingClient.BillingResponseCode.OK)
            onActivity(id) {
                showQueryProgress(it, id, productIds.size)
                queryProducts(billing, id, productIds, report)
            }
        }
    }

    private fun showQueryProgress(activity: Activity, id: Int, count: Int) {
        queryProgressDialog?.dismiss()
        queryProgressDialog = AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_query_progress_title))
            .setMessage(text(activity, R.string.googleone_query_progress, 0, count, 0, 0))
            .setNegativeButton(text(activity, R.string.googleone_scan_stop)) { _, _ ->
                cancelQuery(id)
            }
            .setOnCancelListener { cancelQuery(id) }
            .show()
    }

    private fun updateQueryProgress(id: Int, summary: GoogleOneQueryReport.Summary) {
        onActivity(id) { activity ->
            queryProgressDialog?.setMessage(
                text(
                    activity,
                    R.string.googleone_query_progress,
                    summary.queried,
                    summary.requested,
                    summary.returned,
                    summary.offers,
                ),
            )
        }
    }

    private fun cancelQuery(id: Int) {
        if (!isCurrent(id)) return
        lastQueryReport?.cancel()
        activeQueryReportId = null
        queryProgressDialog = null
        requestId++
        client?.endConnection()
        client = null
        notice(R.string.googleone_query_canceled)
    }

    private fun finishReportWithFailure(report: GoogleOneQueryReport, batch: Int?) {
        report.fail(batch)
        val activity = activityRef.get() ?: return
        activity.runOnUiThread {
            if (lastQueryReport === report) {
                queryProgressDialog?.dismiss()
                queryProgressDialog = null
            }
        }
    }

    private fun startBillingSession(
        noticeId: Int,
        onFailed: ((Int?) -> Unit)? = null,
        onReady: (BillingClient, Int) -> Unit,
    ) {
        if (closed) return
        val activity = activityRef.get() ?: return
        if (activeQueryReportId != null) lastQueryReport?.cancel()
        requestId += 1
        val currentRequest = requestId
        activeQueryReportId = null
        queryProgressDialog?.dismiss()
        queryProgressDialog = null
        client?.endConnection()
        client = null
        notice(noticeId)
        try {
            val billing = BillingClient.newBuilder(activity.applicationContext)
                .setListener(
                    PurchasesUpdatedListener { result, _ ->
                        if (isCurrent(currentRequest)) {
                            recordBillingEventForQuery(currentRequest, "purchase_callback", result.responseCode)
                            notice(R.string.googleone_purchase_callback, result.responseCode)
                        }
                    },
                )
                .enablePendingPurchases()
                .build()
            client = billing
            billing.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (!isCurrent(currentRequest)) return
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            onReady(billing, currentRequest)
                        } else {
                            onFailed?.invoke(result.responseCode)
                            failure(currentRequest, R.string.googleone_connection_failed, result.responseCode, result.debugMessage)
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        if (isCurrent(currentRequest)) {
                            onFailed?.invoke(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                            failure(currentRequest, R.string.googleone_connection_lost)
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            onFailed?.invoke(null)
            Log.e(TAG, "Billing client initialization failed", error)
            failure(currentRequest, R.string.googleone_init_failed, error.javaClass.simpleName)
        }
    }

    private fun recordBillingEventForQuery(id: Int, stage: String, code: Int) {
        if (isCurrent(id) && activeQueryReportId == id) {
            lastQueryReport?.recordBillingEvent(stage, code)
        }
    }

    private fun queryProducts(
        billing: BillingClient,
        id: Int,
        productIds: List<String>,
        report: GoogleOneQueryReport,
    ) {
        val batches = productIds.chunked(GoogleOneSkuParser.BATCH_SIZE)
        val found = LinkedHashMap<String, ProductDetails>()
        val attempts = IntArray(batches.size)
        val retryQueue = ArrayDeque<Int>()
        var nextIndex = 0
        var active = 0
        var completed = 0
        var stopped = false
        var pumpScheduled = false
        var concurrency = QUERY_MAX_CONCURRENCY
        var nextLaunchAt = 0L
        var pauseUntil = 0L
        lateinit var pump: () -> Unit

        fun stopOnFailure(index: Int, code: Int, message: String, timedOut: Boolean) {
            if (stopped || !isCurrent(id) || report.status != "running") return
            stopped = true
            report.recordBatch(batches[index], code, emptyList(), emptyList())
            finishReportWithFailure(report, index + 1)
            if (timedOut) {
                failure(id, R.string.googleone_query_timeout, index + 1, batches.size)
            } else {
                failure(id, R.string.googleone_query_batch_failed, index + 1, batches.size, code, message)
            }
        }

        fun onBatchResult(index: Int, code: Int, message: String, returned: List<ProductDetails>) {
            if (stopped || !isCurrent(id) || report.status != "running") return
            if (code != BillingClient.BillingResponseCode.OK) {
                val transient = code == BillingClient.BillingResponseCode.SERVICE_TIMEOUT ||
                    code == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ||
                    code == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ||
                    code == BillingClient.BillingResponseCode.ERROR
                if (transient && attempts[index] < QUERY_MAX_RETRIES) {
                    attempts[index]++
                    concurrency = 1
                    report.recordBillingEvent("query_retry", code)
                    retryQueue.addLast(index)
                    pauseUntil = maxOf(pauseUntil, SystemClock.uptimeMillis() + 500L * attempts[index])
                    pump()
                } else {
                    stopOnFailure(index, code, message, code == BillingClient.BillingResponseCode.SERVICE_TIMEOUT)
                }
                return
            }
            val requested = batches[index]
            val actual = returned.filter { it.productId in requested }
            val offers = actual.flatMap { product ->
                product.subscriptionOfferDetails.orEmpty().map { offer ->
                    offerFrom(product, offer).toReportOffer()
                }
            }
            report.recordBatch(requested, code, returned.map { it.productId }, offers)
            actual.forEach { found[it.productId] = it }
            runCatching { foundSkuCache.record(actual.map { it.productId }, offers) }
                .onFailure { Log.w(TAG, "Unable to save found SKUs", it) }
            completed++
            updateQueryProgress(id, report.summaryCounts())
            pump()
        }

        fun launchBatch(index: Int) {
            active++
            nextLaunchAt = SystemClock.uptimeMillis() + QUERY_LAUNCH_INTERVAL_MS
            val finished = AtomicBoolean(false)
            val watchdog = Runnable {
                if (!finished.compareAndSet(false, true)) return@Runnable
                active--
                onBatchResult(index, BillingClient.BillingResponseCode.SERVICE_TIMEOUT, "", emptyList())
            }
            uiHandler.postDelayed(watchdog, QUERY_BATCH_TIMEOUT_MS)
            try {
                val products = batches[index].map { sku ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(sku)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
                val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
                billing.queryProductDetailsAsync(
                    params,
                    ProductDetailsResponseListener { result, details ->
                        uiHandler.post {
                            if (!finished.compareAndSet(false, true)) return@post
                            uiHandler.removeCallbacks(watchdog)
                            active--
                            onBatchResult(index, result.responseCode, result.debugMessage, details.orEmpty())
                        }
                    },
                )
            } catch (error: Throwable) {
                if (finished.compareAndSet(false, true)) {
                    uiHandler.removeCallbacks(watchdog)
                    active--
                    Log.w(TAG, "Product query failed", error)
                    onBatchResult(index, BillingClient.BillingResponseCode.ERROR, error.javaClass.simpleName, emptyList())
                }
            }
        }

        pump = {
            if (!stopped && isCurrent(id) && report.status == "running") {
                if (completed == batches.size && active == 0) {
                    stopped = true
                    val offers = found.values.flatMap { product ->
                        product.subscriptionOfferDetails.orEmpty().map { offer -> offerFrom(product, offer) }
                    }.sortedWith(
                        compareByDescending<AvailableOffer> { it.isFreeTrial }
                            .thenByDescending { OfferPricing.freeDurationDays(it.phases) },
                    )
                    report.complete()
                    onActivity(id) {
                        queryProgressDialog?.dismiss()
                        queryProgressDialog = null
                        showOfferFilters(it, id, offers, productIds.size)
                    }
                } else if (!pumpScheduled && active < concurrency && (retryQueue.isNotEmpty() || nextIndex < batches.size)) {
                    val now = SystemClock.uptimeMillis()
                    val delay = maxOf(nextLaunchAt - now, pauseUntil - now, 0L)
                    if (delay > 0L) {
                        pumpScheduled = true
                        uiHandler.postDelayed({
                            pumpScheduled = false
                            pump()
                        }, delay)
                    } else {
                        val index = if (retryQueue.isEmpty()) nextIndex++ else retryQueue.removeFirst()
                        launchBatch(index)
                        pump()
                    }
                }
            }
        }
        pump()
    }

    private fun showOfferFilters(
        activity: Activity,
        id: Int,
        offers: List<AvailableOffer>,
        searchedCount: Int,
    ) {
        if (!isCurrent(id)) return
        if (offers.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_offers_title))
                .setMessage(text(activity, R.string.googleone_offers_empty_count, searchedCount))
                .setPositiveButton(text(activity, R.string.googleone_ok), null)
                .setNeutralButton(text(activity, R.string.googleone_menu_report)) { _, _ ->
                    showReport(activity)
                }
                .show()
            return
        }
        val categories = arrayOf(
            text(activity, R.string.googleone_filter_all, offers.size),
            text(activity, R.string.googleone_filter_free, offers.count { it.isFreeTrial }),
            text(activity, R.string.googleone_filter_paid, offers.count { !it.isFreeTrial }),
            text(activity, R.string.googleone_filter_product),
        )
        AlertDialog.Builder(activity)
            .setTitle(
                text(
                    activity,
                    R.string.googleone_offers_query_count,
                    searchedCount,
                    lastQueryReport?.summaryCounts()?.returned ?: 0,
                    offers.size,
                ),
            )
            .setItems(categories) { _, index ->
                when (index) {
                    0 -> showOfferPage(activity, id, offers, offers, searchedCount, 0)
                    1 -> showOfferPage(activity, id, offers, offers.filter { it.isFreeTrial }, searchedCount, 0)
                    2 -> showOfferPage(activity, id, offers, offers.filterNot { it.isFreeTrial }, searchedCount, 0)
                    3 -> showOfferProductFilter(activity, id, offers, searchedCount)
                }
            }
            .setNegativeButton(text(activity, R.string.googleone_close), null)
            .setNeutralButton(text(activity, R.string.googleone_menu_report)) { _, _ ->
                showReport(activity)
            }
            .show()
    }

    private fun showOfferProductFilter(
        activity: Activity,
        id: Int,
        offers: List<AvailableOffer>,
        searchedCount: Int,
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            hint = text(activity, R.string.googleone_filter_product_hint)
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_filter_product))
            .setView(input)
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .setPositiveButton(text(activity, R.string.googleone_ok)) { _, _ ->
                val keyword = input.text.toString().trim()
                showOfferPage(
                    activity,
                    id,
                    offers,
                    offers.filter { keyword.isEmpty() || it.product.productId.contains(keyword, ignoreCase = true) },
                    searchedCount,
                    0,
                )
            }
            .show()
    }

    private fun showOfferPage(
        activity: Activity,
        id: Int,
        allOffers: List<AvailableOffer>,
        filtered: List<AvailableOffer>,
        searchedCount: Int,
        page: Int,
    ) {
        if (!isCurrent(id)) return
        if (filtered.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_offers_title))
                .setMessage(text(activity, R.string.googleone_filter_empty))
                .setNegativeButton(text(activity, R.string.googleone_back)) { _, _ ->
                    showOfferFilters(activity, id, allOffers, searchedCount)
                }
                .show()
            return
        }
        val start = page * OFFER_PAGE_SIZE
        val end = minOf(start + OFFER_PAGE_SIZE, filtered.size)
        val displayed = filtered.subList(start, end)
        val previous = page > 0
        val next = end < filtered.size
        val entries = buildList {
            if (previous) add(text(activity, R.string.googleone_page_previous))
            addAll(displayed.map { it.label(activity) })
            if (next) add(text(activity, R.string.googleone_page_next))
        }
        AlertDialog.Builder(activity)
            .setTitle(
                text(
                    activity,
                    R.string.googleone_offers_page,
                    filtered.size,
                    page + 1,
                    (filtered.size + OFFER_PAGE_SIZE - 1) / OFFER_PAGE_SIZE,
                ),
            )
            .setItems(entries.toTypedArray()) { _, index ->
                if (!isCurrent(id)) return@setItems
                when {
                    previous && index == 0 -> showOfferPage(activity, id, allOffers, filtered, searchedCount, page - 1)

                    next && index == entries.lastIndex -> showOfferPage(activity, id, allOffers, filtered, searchedCount, page + 1)

                    else -> {
                        val offer = displayed[index - if (previous) 1 else 0]
                        showOfferDetail(activity, id, offer) {
                            showOfferPage(activity, id, allOffers, filtered, searchedCount, page)
                        }
                    }
                }
            }
            .setNegativeButton(text(activity, R.string.googleone_close), null)
            .setNeutralButton(text(activity, R.string.googleone_filter_button)) { _, _ ->
                showOfferFilters(activity, id, allOffers, searchedCount)
            }
            .show()
    }

    private fun showOfferDetail(activity: Activity, id: Int, offer: AvailableOffer, onBack: () -> Unit) {
        val message = buildString {
            append(text(activity, R.string.googleone_product_line, offer.product.productId))
            append(
                text(
                    activity,
                    R.string.googleone_offer_id_line,
                    offer.details.offerId ?: text(activity, R.string.googleone_base_plan),
                ),
            )
            append(text(activity, R.string.googleone_plan_id_line, offer.details.basePlanId))
            append(text(activity, R.string.googleone_pricing_header))
            append(describePhases(activity, offer.phases))
            append(text(activity, R.string.googleone_eligibility_notice))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_details_title))
            .setMessage(message)
            .setNegativeButton(text(activity, R.string.googleone_back)) { _, _ -> onBack() }
        dialog.setPositiveButton(text(activity, R.string.googleone_checkout)) { _, _ ->
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_confirm_title))
                .setMessage(
                    text(
                        activity,
                        R.string.googleone_confirm_offer_message,
                        offer.product.productId,
                        describePhases(activity, offer.phases),
                    ),
                )
                .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                .setPositiveButton(text(activity, R.string.googleone_open_checkout)) { _, _ ->
                    launchOfficialBilling(activity, id, offer)
                }
                .show()
        }
        dialog.setNeutralButton(text(activity, R.string.googleone_subscription_actions)) { _, _ ->
            showSubscriptionActions(activity, id, offer)
        }
        dialog.show()
    }

    private fun showSubscriptionActions(activity: Activity, id: Int, offer: AvailableOffer) {
        val actions = arrayOf(
            text(activity, R.string.googleone_resubscribe),
            text(activity, R.string.googleone_replace_subscription),
            text(activity, R.string.googleone_try_extend_subscription),
        )
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_subscription_actions))
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> queryResubscription(id, offer)
                    1 -> queryReplaceableSubscriptions(id, offer)
                    2 -> queryTimeExtensionCandidates(id, offer)
                }
            }
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .show()
    }

    /** Same-SKU resubscription omits SubscriptionUpdateParams. */
    private fun queryResubscription(id: Int, offer: AvailableOffer) {
        if (!isCurrent(id)) return
        val billing = client ?: return
        notice(R.string.googleone_querying_subscriptions)
        fetchPurchases(billing, id) { purchases ->
            captureSubscriptionBaseline(purchases)
            val matching = purchases.filter {
                GoogleOneReplacementPolicy.isCanceledMatchingProduct(it, offer.product.productId)
            }
            onActivity(id) { activity ->
                if (matching.isEmpty()) {
                    AlertDialog.Builder(activity)
                        .setTitle(text(activity, R.string.googleone_resubscribe))
                        .setMessage(
                            text(activity, R.string.googleone_resubscribe_unavailable, offer.product.productId),
                        )
                        .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                        .setPositiveButton(text(activity, R.string.googleone_menu_play_subscriptions)) { _, _ ->
                            openPlaySubscriptions(activity)
                        }
                        .show()
                    return@onActivity
                }
                AlertDialog.Builder(activity)
                    .setTitle(text(activity, R.string.googleone_resubscribe))
                    .setItems(matching.map { it.products.joinToString(", ") }.toTypedArray()) { _, index ->
                        val old = matching[index]
                        AlertDialog.Builder(activity)
                            .setTitle(text(activity, R.string.googleone_resubscribe_confirm_title))
                            .setMessage(
                                text(
                                    activity,
                                    R.string.googleone_resubscribe_confirm,
                                    offer.product.productId,
                                    describePhases(activity, offer.phases),
                                ),
                            )
                            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                            .setPositiveButton(text(activity, R.string.googleone_open_checkout)) { _, _ ->
                                recheckAndLaunchResubscription(id, offer, old)
                            }
                            .show()
                    }
                    .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                    .show()
            }
        }
    }

    private fun recheckAndLaunchResubscription(
        id: Int,
        offer: AvailableOffer,
        previous: Purchase,
    ) {
        if (!isCurrent(id)) return
        val billing = client ?: return
        notice(R.string.googleone_rechecking_subscription)
        fetchPurchases(billing, id) { purchases ->
            val fresh = purchases.firstOrNull {
                GoogleOneReplacementPolicy.isSameActiveSubscription(previous, it) &&
                    GoogleOneReplacementPolicy.isCanceledMatchingProduct(it, offer.product.productId)
            }
            if (fresh == null) {
                failure(id, R.string.googleone_subscription_changed)
                return@fetchPurchases
            }
            onActivity(id) { launchOfficialBilling(it, id, offer) }
        }
    }

    private fun queryReplaceableSubscriptions(id: Int, offer: AvailableOffer) {
        if (!isCurrent(id)) return
        val billing = client ?: return
        notice(R.string.googleone_querying_subscriptions)
        fetchPurchases(billing, id) { purchases ->
            captureSubscriptionBaseline(purchases)
            val candidates = purchases.filter(GoogleOneReplacementPolicy::isReplaceable)
            onActivity(id) { activity -> showReplacementCandidates(activity, id, offer, candidates) }
        }
    }

    /** Same-SKU replacement with CHARGE_FULL_PRICE; Play alone determines the resulting entitlement. */
    private fun queryTimeExtensionCandidates(id: Int, offer: AvailableOffer) {
        if (!isCurrent(id)) return
        val billing = client ?: return
        notice(R.string.googleone_querying_subscriptions)
        fetchPurchases(billing, id) { purchases ->
            captureSubscriptionBaseline(purchases)
            val candidates = purchases.filter {
                GoogleOneReplacementPolicy.isTimeExtensionCandidate(it, offer.product.productId)
            }
            onActivity(id) { activity -> showTimeExtensionCandidates(activity, id, offer, candidates) }
        }
    }

    private fun showTimeExtensionCandidates(
        activity: Activity,
        id: Int,
        offer: AvailableOffer,
        candidates: List<Purchase>,
    ) {
        if (candidates.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_try_extend_subscription))
                .setMessage(text(activity, R.string.googleone_time_extension_unavailable, offer.product.productId))
                .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                .setPositiveButton(text(activity, R.string.googleone_menu_play_subscriptions)) { _, _ ->
                    openPlaySubscriptions(activity)
                }
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_time_extension_choose))
            .setItems(candidates.map { it.products.joinToString(", ") }.toTypedArray()) { _, index ->
                val oldPurchase = candidates[index]
                AlertDialog.Builder(activity)
                    .setTitle(text(activity, R.string.googleone_time_extension_confirm_title))
                    .setMessage(
                        text(
                            activity,
                            R.string.googleone_time_extension_confirm,
                            oldPurchase.products.joinToString(", "),
                            offer.product.productId,
                            offer.details.offerId ?: offer.details.basePlanId,
                            describePhases(activity, offer.phases),
                        ),
                    )
                    .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                    .setPositiveButton(text(activity, R.string.googleone_open_checkout)) { _, _ ->
                        recheckAndLaunchReplacement(
                            id,
                            offer,
                            oldPurchase,
                            BillingFlowParams.ProrationMode.IMMEDIATE_AND_CHARGE_FULL_PRICE,
                            requireTimeExtensionCandidate = true,
                        )
                    }
                    .show()
            }
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .show()
    }

    private fun showSubscriptionStatus() {
        startBillingSession(R.string.googleone_querying_subscriptions) { billing, id ->
            fetchPurchases(billing, id) { purchases ->
                val googleOnePurchases = purchases.filter {
                    it.packageName == GOOGLE_ONE_PACKAGE
                }
                val current = GoogleOneSubscriptionSnapshot.from(googleOnePurchases)
                val changes = lastSubscriptionSnapshot?.let(current::compare)
                lastSubscriptionSnapshot = current
                onActivity(id) { showCurrentSubscriptions(it, googleOnePurchases, changes) }
            }
        }
    }

    private fun captureSubscriptionBaseline(purchases: List<Purchase>) {
        if (lastSubscriptionSnapshot == null) {
            lastSubscriptionSnapshot = GoogleOneSubscriptionSnapshot.from(purchases)
        }
    }

    private fun fetchPurchases(
        billing: BillingClient,
        id: Int,
        onSuccess: (List<Purchase>) -> Unit,
    ) {
        try {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            billing.queryPurchasesAsync(params) { result, purchases ->
                if (!isCurrent(id)) return@queryPurchasesAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    failure(id, R.string.googleone_subscription_query_failed, result.responseCode, result.debugMessage)
                    return@queryPurchasesAsync
                }
                onSuccess(purchases.orEmpty())
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Subscription query failed", error)
            failure(id, R.string.googleone_subscription_query_exception, error.javaClass.simpleName)
        }
    }

    private fun showCurrentSubscriptions(
        activity: Activity,
        purchases: List<Purchase>,
        changes: GoogleOneSubscriptionSnapshot.Delta?,
    ) {
        val summary = if (changes == null) {
            text(activity, R.string.googleone_status_first_snapshot)
        } else {
            text(
                activity,
                R.string.googleone_status_delta,
                changes.added,
                changes.missing,
                changes.changed,
                changes.same,
            )
        }
        if (purchases.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_status_title))
                .setMessage(summary + "\n\n" + text(activity, R.string.googleone_status_empty))
                .setPositiveButton(text(activity, R.string.googleone_ok), null)
                .show()
            return
        }
        val labels = purchases.map { purchase ->
            text(activity, R.string.googleone_subscription_product, purchase.products.joinToString(", "))
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_status_count, purchases.size))
            .setItems((listOf(summary) + labels).toTypedArray()) { _, index ->
                if (index == 0) {
                    AlertDialog.Builder(activity)
                        .setTitle(text(activity, R.string.googleone_status_title))
                        .setMessage(summary)
                        .setPositiveButton(text(activity, R.string.googleone_ok), null)
                        .show()
                    return@setItems
                }
                val purchase = purchases[index - 1]
                val purchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                val activeText = text(
                    activity,
                    if (purchased) R.string.googleone_status_purchased else R.string.googleone_status_pending,
                )
                val renewText = text(
                    activity,
                    if (purchase.isAutoRenewing) R.string.googleone_status_renewing else R.string.googleone_status_not_renewing,
                )
                val confirmedText = text(
                    activity,
                    if (purchase.isAcknowledged) R.string.googleone_status_acknowledged else R.string.googleone_status_unacknowledged,
                )
                val locale = activity.resources.configuration.locales[0] ?: Locale.getDefault()
                val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                    .format(Date(purchase.purchaseTime))
                AlertDialog.Builder(activity)
                    .setTitle(text(activity, R.string.googleone_status_title))
                    .setMessage(
                        text(
                            activity,
                            R.string.googleone_status_details,
                            purchase.products.joinToString(", "),
                            activeText,
                            renewText,
                            confirmedText,
                            time,
                        ),
                    )
                    .setPositiveButton(text(activity, R.string.googleone_ok), null)
                    .show()
            }
            .setNegativeButton(text(activity, R.string.googleone_close), null)
            .show()
    }

    private fun showReplacementCandidates(
        activity: Activity,
        id: Int,
        offer: AvailableOffer,
        candidates: List<Purchase>,
    ) {
        if (candidates.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_replace_subscription))
                .setMessage(text(activity, R.string.googleone_no_replaceable_subscription))
                .setPositiveButton(text(activity, R.string.googleone_ok), null)
                .show()
            return
        }
        val labels = candidates.map { purchase ->
            text(activity, R.string.googleone_subscription_product, purchase.products.joinToString(", ")) +
                text(
                    activity,
                    R.string.googleone_subscription_renewal_suffix,
                    text(
                        activity,
                        if (purchase.isAutoRenewing) R.string.googleone_status_renewing else R.string.googleone_status_not_renewing,
                    ),
                )
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_choose_subscription))
            .setItems(labels.toTypedArray()) { _, index ->
                showReplacementModes(activity, id, offer, candidates[index])
            }
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .show()
    }

    private fun showReplacementModes(
        activity: Activity,
        id: Int,
        offer: AvailableOffer,
        oldPurchase: Purchase,
    ) {
        val options = REPLACEMENT_MODES
        val labels = options.map { option ->
            text(activity, option.titleId) + "\n" + text(activity, option.descriptionId)
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_replace_mode_title))
            .setItems(labels.toTypedArray()) { _, index ->
                val option = options[index]
                val modeName = text(activity, option.titleId)
                AlertDialog.Builder(activity)
                    .setTitle(text(activity, R.string.googleone_confirm_replace_title))
                    .setMessage(
                        text(
                            activity,
                            R.string.googleone_confirm_replace_message_v2,
                            oldPurchase.products.joinToString(", "),
                            offer.product.productId,
                            modeName,
                            describePhases(activity, offer.phases),
                        ),
                    )
                    .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                    .setPositiveButton(text(activity, R.string.googleone_open_checkout)) { _, _ ->
                        recheckAndLaunchReplacement(id, offer, oldPurchase, option.mode)
                    }
                    .show()
            }
            .setNegativeButton(text(activity, R.string.googleone_cancel), null)
            .show()
    }

    private fun recheckAndLaunchReplacement(
        id: Int,
        offer: AvailableOffer,
        previous: Purchase,
        replacementMode: Int,
        requireTimeExtensionCandidate: Boolean = false,
    ) {
        if (!isCurrent(id)) return
        val billing = client ?: return
        notice(R.string.googleone_rechecking_subscription)
        fetchPurchases(billing, id) { purchases ->
            val fresh = purchases.firstOrNull { current ->
                GoogleOneReplacementPolicy.isSameActiveSubscription(previous, current) &&
                    (
                        !requireTimeExtensionCandidate ||
                            GoogleOneReplacementPolicy.isTimeExtensionCandidate(current, offer.product.productId)
                        )
            }
            if (fresh == null) {
                failure(id, R.string.googleone_subscription_changed)
                return@fetchPurchases
            }
            onActivity(id) { launchOfficialBilling(it, id, offer, fresh, replacementMode, requireTimeExtensionCandidate) }
        }
    }

    private fun launchOfficialBilling(
        activity: Activity,
        id: Int,
        offer: AvailableOffer,
        oldPurchase: Purchase? = null,
        replacementMode: Int = BillingFlowParams.ProrationMode.IMMEDIATE_AND_CHARGE_FULL_PRICE,
        requireTimeExtensionCandidate: Boolean = false,
    ) {
        if (!isCurrent(id) || activity.isFinishing || activity.isDestroyed) return
        val billing = client ?: return
        notice(R.string.googleone_refreshing_offer)
        try {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(offer.product.productId)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build(),
                    ),
                )
                .build()
            billing.queryProductDetailsAsync(
                params,
                ProductDetailsResponseListener { result, details ->
                    if (!isCurrent(id)) return@ProductDetailsResponseListener
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        failure(id, R.string.googleone_offer_refresh_failed, result.responseCode, result.debugMessage)
                        return@ProductDetailsResponseListener
                    }
                    val product = details.orEmpty().firstOrNull { it.productId == offer.product.productId }
                    val matched = product?.subscriptionOfferDetails.orEmpty().firstOrNull {
                        it.basePlanId == offer.details.basePlanId && it.offerId == offer.details.offerId
                    }
                    if (product == null || matched == null) {
                        failure(id, R.string.googleone_offer_unavailable)
                        return@ProductDetailsResponseListener
                    }
                    val refreshed = offerFrom(product, matched)
                    onActivity(id) { currentActivity ->
                        if (offer.hasSameTerms(refreshed)) {
                            launchWithCurrentOffer(currentActivity, id, refreshed, oldPurchase, replacementMode, requireTimeExtensionCandidate)
                        } else {
                            // Reconfirm changed terms before checkout.
                            AlertDialog.Builder(currentActivity)
                                .setTitle(text(currentActivity, R.string.googleone_offer_changed_title))
                                .setMessage(
                                    text(
                                        currentActivity,
                                        R.string.googleone_offer_changed_message,
                                        offer.product.productId,
                                        describePhases(currentActivity, offer.phases),
                                        describePhases(currentActivity, refreshed.phases),
                                    ),
                                )
                                .setNegativeButton(text(currentActivity, R.string.googleone_cancel), null)
                                .setPositiveButton(text(currentActivity, R.string.googleone_open_checkout)) { _, _ ->
                                    launchOfficialBilling(currentActivity, id, refreshed, oldPurchase, replacementMode, requireTimeExtensionCandidate)
                                }
                                .show()
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Offer refresh failed", error)
            failure(id, R.string.googleone_checkout_exception, error.javaClass.simpleName)
        }
    }

    private fun launchWithCurrentOffer(
        activity: Activity,
        id: Int,
        offer: AvailableOffer,
        oldPurchase: Purchase?,
        replacementMode: Int,
        requireTimeExtensionCandidate: Boolean,
    ) {
        if (!isCurrent(id) || activity.isFinishing || activity.isDestroyed) return
        if (oldPurchase == null) {
            if (requireTimeExtensionCandidate) {
                failure(id, R.string.googleone_subscription_changed)
                return
            }
            launchWithCurrentTerms(activity, id, offer, null, replacementMode, false)
            return
        }
        // Recheck the old purchase after refreshing pricing.
        val billing = client ?: return
        fetchPurchases(billing, id) { purchases ->
            val current = purchases.firstOrNull {
                GoogleOneReplacementPolicy.isSameActiveSubscription(oldPurchase, it) &&
                    (
                        !requireTimeExtensionCandidate ||
                            GoogleOneReplacementPolicy.isTimeExtensionCandidate(it, offer.product.productId)
                        )
            }
            if (current == null) {
                failure(id, R.string.googleone_subscription_changed)
                return@fetchPurchases
            }
            onActivity(id) {
                launchWithCurrentTerms(it, id, offer, current, replacementMode, requireTimeExtensionCandidate)
            }
        }
    }

    private fun launchWithCurrentTerms(
        activity: Activity,
        id: Int,
        offer: AvailableOffer,
        oldPurchase: Purchase?,
        replacementMode: Int,
        requireTimeExtensionCandidate: Boolean,
    ) {
        if (!isCurrent(id) || activity.isFinishing || activity.isDestroyed) return
        val billing = client ?: return
        if (requireTimeExtensionCandidate &&
            (
                oldPurchase == null ||
                    replacementMode != BillingFlowParams.ProrationMode.IMMEDIATE_AND_CHARGE_FULL_PRICE ||
                    !GoogleOneReplacementPolicy.isTimeExtensionCandidate(oldPurchase, offer.product.productId)
                )
        ) {
            failure(id, R.string.googleone_subscription_changed)
            return
        }
        try {
            val entry = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(offer.product)
                .setOfferToken(offer.details.offerToken)
                .build()
            val builder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(entry))
            if (oldPurchase != null) {
                if (!GoogleOneReplacementPolicy.isReplaceable(oldPurchase)) {
                    failure(id, R.string.googleone_no_replaceable_subscription)
                    return
                }
                builder.setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                        .setOldPurchaseToken(oldPurchase.purchaseToken)
                        .setReplaceProrationMode(replacementMode)
                        .build(),
                )
            }
            val params = builder.build()
            val result = billing.launchBillingFlow(activity, params)
            recordBillingEventForQuery(
                id,
                if (oldPurchase != null) "replacement_checkout_launch" else "checkout_launch",
                result.responseCode,
            )
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                failure(id, R.string.googleone_checkout_failed, result.responseCode, result.debugMessage)
            } else {
                Log.i(TAG, "Official Play checkout launched; replacement=" + (oldPurchase != null) + "; user must confirm manually")
            }
        } catch (error: Throwable) {
            recordBillingEventForQuery(id, "checkout_exception", BillingClient.BillingResponseCode.ERROR)
            Log.e(TAG, "Official checkout launch failed", error)
            failure(id, R.string.googleone_checkout_exception, error.javaClass.simpleName)
        }
    }

    private fun isCurrent(id: Int): Boolean = !closed && id == requestId

    private fun onActivity(id: Int, action: (Activity) -> Unit) {
        val activity = activityRef.get() ?: return
        activity.runOnUiThread {
            if (isCurrent(id) && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    action(activity)
                } catch (error: Throwable) {
                    Log.e(TAG, "Google One UI operation failed", error)
                }
            }
        }
    }

    private fun text(activity: Activity, resourceId: Int, vararg arguments: Any): String = ModuleResources.text(activity, resourceId, *arguments)

    private fun describePhases(activity: Activity, phases: List<OfferPhase>): String {
        if (phases.isEmpty()) return text(activity, R.string.googleone_pricing_unavailable)
        return phases.mapIndexed { index, phase ->
            val price = if (phase.amountMicros == 0L) {
                text(activity, R.string.googleone_free_price)
            } else {
                phase.formattedPrice
            }
            val cycleText = if (phase.billingCycles > 0) {
                text(activity, R.string.googleone_phase_cycles, phase.billingCycles)
            } else {
                text(activity, R.string.googleone_phase_ongoing)
            }
            text(activity, R.string.googleone_phase_line, index + 1, price, phase.billingPeriod, cycleText)
        }.joinToString("\n")
    }

    private fun failure(id: Int, resourceId: Int, vararg arguments: Any) {
        onActivity(id) { activity ->
            val message = text(activity, resourceId, *arguments)
            Log.w(TAG, message)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun notice(resourceId: Int, vararg arguments: Any) {
        val activity = activityRef.get() ?: return
        activity.runOnUiThread {
            if (!closed && !activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, text(activity, resourceId, *arguments), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun close() {
        closed = true
        requestId++
        activeQueryReportId = null
        lastQueryReport?.cancel()
        queryProgressDialog?.dismiss()
        queryProgressDialog = null
        client?.endConnection()
        client = null
        activityRef.clear()
    }

    private companion object {
        const val TAG = "GoogleOneBilling"
        const val GOOGLE_ONE_PACKAGE = "com.google.android.apps.subscriptions.red"
        const val OFFER_PAGE_SIZE = 25
        const val CACHED_PAGE_SIZE = 24
        const val QUERY_MAX_CONCURRENCY = 2
        const val QUERY_MAX_RETRIES = 2
        const val QUERY_LAUNCH_INTERVAL_MS = 100L
        const val QUERY_BATCH_TIMEOUT_MS = 20_000L
        private data class ReplacementModeOption(
            val mode: Int,
            val titleId: Int,
            val descriptionId: Int,
        )
        private val REPLACEMENT_MODES = listOf(
            ReplacementModeOption(
                BillingFlowParams.ProrationMode.IMMEDIATE_AND_CHARGE_FULL_PRICE,
                R.string.googleone_mode_full,
                R.string.googleone_mode_full_description,
            ),
            ReplacementModeOption(
                BillingFlowParams.ProrationMode.IMMEDIATE_WITH_TIME_PRORATION,
                R.string.googleone_mode_time,
                R.string.googleone_mode_time_description,
            ),
            ReplacementModeOption(
                BillingFlowParams.ProrationMode.IMMEDIATE_WITHOUT_PRORATION,
                R.string.googleone_mode_no_proration,
                R.string.googleone_mode_no_proration_description,
            ),
            ReplacementModeOption(
                BillingFlowParams.ProrationMode.DEFERRED,
                R.string.googleone_mode_deferred,
                R.string.googleone_mode_deferred_description,
            ),
            ReplacementModeOption(
                BillingFlowParams.ProrationMode.IMMEDIATE_AND_CHARGE_PRORATED_PRICE,
                R.string.googleone_mode_prorated,
                R.string.googleone_mode_prorated_description,
            ),
        )
    }
}
