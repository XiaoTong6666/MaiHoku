package io.github.xiaotong6666.maihoku.features.mimessage.ui

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiActivationException
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiHumanVerificationClient
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiPassportCaptchaRequiredException
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiPassportChallengeCoordinator
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiPassportChallengeSession
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiPassportChallengeStore
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiPassportVerificationResult
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiPassportVerificationUrls
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimActivationCredentialStore
import io.github.xiaotong6666.maihoku.features.mimessage.account.XiaomiSimServiceTokenClient
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig
import java.net.HttpCookie
import java.util.UUID
import java.util.concurrent.Executors
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** In-app Passport verification subpage; it is not an exported or launcher Activity. */
class PassportCaptchaActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MaiHoku-Passport-Verify").apply { isDaemon = true }
    }

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var webView: WebView
    private lateinit var captchaInput: EditText
    private lateinit var submitButton: Button
    private lateinit var retryButton: Button
    private var session: XiaomiPassportChallengeSession? = null
    private var isFinishingFlow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildPage()
        val slot = intent.getIntExtra(XiaomiPassportChallengeCoordinator.EXTRA_SLOT, 0).coerceIn(0, 1)
        session = XiaomiPassportChallengeStore.load(this, slot)
        val current = session
        if (current == null) {
            showStatus(getString(R.string.mx_captcha_missing), false)
            return
        }
        if (current.verificationResult != null) {
            resumePassport(current.verificationResult)
        } else {
            startChallenge(current)
        }
    }

    private fun buildPage() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 248, 248))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            setText(R.string.mx_captcha_title)
            textSize = 18f
            setTextColor(Color.BLACK)
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(44), dp(48)))
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(toolbar)

        status = TextView(this).apply {
            setText(R.string.mx_captcha_preparing)
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(status)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(
            progress,
            LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            },
        )

        webView = WebView(this).apply {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = XiaomiHumanVerificationClient.webViewUserAgent(this@PassportCaptchaActivity)
            webViewClient = PassportWebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.WHITE)
        }
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        captchaInput = EditText(this).apply {
            setHint(R.string.mx_captcha_hint)
            setSingleLine(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        root.addView(captchaInput)
        submitButton = Button(this).apply {
            setText(R.string.mx_captcha_submit)
            visibility = View.GONE
            setOnClickListener { submitImageCaptcha() }
        }
        root.addView(submitButton)
        retryButton = Button(this).apply {
            setText(R.string.mx_captcha_retry)
            visibility = View.GONE
            setOnClickListener {
                session?.verificationResult?.let(::resumePassport) ?: session?.let(::startChallenge)
            }
        }
        root.addView(retryButton)
        setContentView(root)
    }

    private fun startChallenge(current: XiaomiPassportChallengeSession) {
        isFinishingFlow = false
        setBusy(true)
        val type = current.challenge.type.lowercase()
        if (type == "captcha" || type == "captchaview") {
            val captchaUrl = current.challenge.captchaUrl
            if (captchaUrl.isNullOrBlank()) {
                setBusy(false)
                showStatus(getString(R.string.mx_captcha_url_missing), true)
                retryButton.visibility = View.VISIBLE
                return
            }
            runCatching { XiaomiPassportVerificationUrls.requireOfficial(captchaUrl) }
                .onFailure {
                    setBusy(false)
                    showStatus(getString(R.string.mx_captcha_url_invalid), true)
                    retryButton.visibility = View.VISIBLE
                    return
                }
            setBusy(false)
            captchaInput.visibility = View.VISIBLE
            submitButton.visibility = View.VISIBLE
            showStatus(getString(R.string.mx_captcha_instructions), false)
            setPassportCookies(current, captchaUrl) {
                webView.loadUrl(captchaUrl, mapOf("User-Agent" to current.challenge.userAgent))
            }
            return
        }

        captchaInput.visibility = View.GONE
        submitButton.visibility = View.GONE
        current.verificationPageUrl?.let { savedUrl ->
            val page = runCatching { XiaomiPassportVerificationUrls.requireOfficial(savedUrl) }
            page.onSuccess {
                setBusy(false)
                showStatus(getString(R.string.mx_captcha_restored), false)
                webView.loadUrl(it)
            }.onFailure {
                setBusy(false)
                val updated = current.copy(verificationPageUrl = null)
                XiaomiPassportChallengeStore.save(this, updated)
                session = updated
                showStatus(getString(R.string.mx_captcha_saved_url_invalid), true)
                retryButton.visibility = View.VISIBLE
            }
            return
        }
        showStatus(
            getString(R.string.mx_captcha_initializing),
            false,
        )
        worker.execute {
            val result = runCatching { XiaomiHumanVerificationClient.prepare(this, "pwd-login") }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { bootstrap ->
                    when (bootstrap) {
                        is XiaomiHumanVerificationClient.BootstrapResult.OpenPage -> {
                            val page = runCatching { XiaomiPassportVerificationUrls.requireOfficial(bootstrap.url) }
                            page.onSuccess {
                                val updated = current.copy(verificationPageUrl = it)
                                if (XiaomiPassportChallengeStore.save(this, updated)) session = updated
                                setBusy(false)
                                webView.loadUrl(it)
                            }
                                .onFailure {
                                    setBusy(false)
                                    val updated = current.copy(verificationPageUrl = null)
                                    XiaomiPassportChallengeStore.save(this, updated)
                                    session = updated
                                    showStatus(getString(R.string.mx_captcha_url_invalid), true)
                                    retryButton.visibility = View.VISIBLE
                                }
                        }

                        is XiaomiHumanVerificationClient.BootstrapResult.Completed -> {
                            resumePassport(XiaomiPassportVerificationResult(vToken = bootstrap.token))
                        }
                    }
                }.onFailure { error ->
                    val kind = (error as? XiaomiActivationException)?.kind ?: "NETWORK_ERROR"
                    Log.w(TAG, "Passport verification bootstrap failed: $kind ${error.message.orEmpty()}")
                    setBusy(false)
                    val fallbackUrl = current.challenge.captchaUrl
                    if (!fallbackUrl.isNullOrBlank()) {
                        showStatus(getString(R.string.mx_captcha_fallback), true)
                        captchaInput.visibility = View.VISIBLE
                        submitButton.visibility = View.VISIBLE
                        setPassportCookies(current, fallbackUrl) {
                            webView.loadUrl(fallbackUrl, mapOf("User-Agent" to current.challenge.userAgent))
                        }
                    } else {
                        showStatus(getString(R.string.mx_captcha_init_failed, kind), true)
                        retryButton.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setPassportCookies(
        current: XiaomiPassportChallengeSession,
        origin: String,
        after: () -> Unit,
    ) {
        val manager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val pending = current.challenge.responseSetCookies.toMutableList()
        if (pending.isEmpty()) {
            after()
            return
        }
        var remaining = pending.size
        pending.forEach { cookie ->
            manager.setCookie(origin, cookie) {
                remaining--
                if (remaining == 0) {
                    manager.flush()
                    after()
                }
            }
        }
    }

    private fun submitImageCaptcha() {
        val current = session ?: return
        val code = captchaInput.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            showStatus(getString(R.string.mx_captcha_empty), true)
            return
        }
        val captchaUrl = current.challenge.captchaUrl ?: return
        val cookies = CookieManager.getInstance().getCookie(captchaUrl).orEmpty()
        val ick = cookies.split(';').asSequence().map(String::trim)
            .firstOrNull { it.startsWith("ick=") }?.substringAfter('=')
            ?: current.challenge.responseSetCookies.asSequence()
                .flatMap { runCatching { HttpCookie.parse(it) }.getOrDefault(emptyList()).asSequence() }
                .firstOrNull { it.name == "ick" }?.value
        if (ick.isNullOrBlank()) {
            showStatus(getString(R.string.mx_captcha_cookie_missing), true)
            retryButton.visibility = View.VISIBLE
            return
        }
        resumePassport(XiaomiPassportVerificationResult(captchaCode = code, captchaIck = ick))
    }

    private fun resumePassport(result: XiaomiPassportVerificationResult) {
        val current = session ?: return
        if (isFinishingFlow) return
        isFinishingFlow = true
        setBusy(true)
        showStatus(getString(R.string.mx_captcha_resuming), false)
        if (!XiaomiPassportChallengeStore.saveVerificationResult(this, current.slotIndex, result)) {
            isFinishingFlow = false
            setBusy(false)
            showStatus(getString(R.string.mx_captcha_save_failed), true)
            retryButton.visibility = View.VISIBLE
            return
        }
        session = current.copy(verificationResult = result)
        val currentSession = session ?: return
        worker.execute {
            val outcome = runCatching {
                val simUser = XiaomiSimActivationCredentialStore.loadSimUser(
                    this,
                    currentSession.slotIndex,
                    currentSession.simIdHash,
                ) ?: throw XiaomiActivationException(
                    "SIM_ACTIVATION_CREDENTIALS_MISSING",
                    "The matching SIM activation credentials are unavailable in private storage",
                )
                val client = XiaomiSimServiceTokenClient(
                    context = this,
                    simUserId = simUser.userId,
                    simPassToken = simUser.passToken,
                    activationDeviceIdHash = currentSession.activationDeviceIdHash,
                    serviceId = MiMessageConfig.SERVICE_ID,
                    oaid = currentSession.oaid,
                    stsDeviceIdHash = currentSession.stsDeviceIdHash,
                    userSpaceId = currentSession.userSpaceId,
                    trace = { event -> Log.i(TAG, event.toString()) },
                )
                val credentials = client.getSimAuthToken(result, currentSession.challenge.responseSetCookies)
                val resource = XiaomiSimActivationCredentialStore.getOrCreateResource(this, currentSession.slotIndex)
                check(XiaomiSimActivationCredentialStore.saveMixinCredentials(this, currentSession.slotIndex, credentials, resource)) {
                    "Could not save the Mixin credentials to MaiHoku private SharedPreferences"
                }
                XiaomiPassportChallengeStore.clear(this, currentSession.slotIndex)
                credentials
            }
            val challengeUpdate = (outcome.exceptionOrNull() as? XiaomiPassportCaptchaRequiredException)
                ?.let { currentSession.copy(challenge = it.challenge, verificationResult = null, verificationPageUrl = null) }
            challengeUpdate?.let { XiaomiPassportChallengeStore.save(this, it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.onSuccess { credentials ->
                    session = null
                    webView.visibility = View.GONE
                    captchaInput.visibility = View.GONE
                    submitButton.visibility = View.GONE
                    setBusy(false)
                    showStatus(
                        getString(R.string.mx_captcha_success, credentials.mid.take(4)),
                        false,
                    )
                    retryButton.setText(R.string.mx_return_settings)
                    retryButton.setOnClickListener { finish() }
                    retryButton.visibility = View.VISIBLE
                }.onFailure { error ->
                    isFinishingFlow = false
                    val challengeError = error as? XiaomiPassportCaptchaRequiredException
                    if (challengeError != null) {
                        session = challengeUpdate ?: currentSession.copy(
                            challenge = challengeError.challenge,
                            verificationResult = null,
                            verificationPageUrl = null,
                        )
                        setBusy(false)
                        showStatus(getString(R.string.mx_captcha_required_again), true)
                        retryButton.visibility = View.VISIBLE
                    } else {
                        setBusy(false)
                        val kind = (error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName
                        showStatus(getString(R.string.mx_captcha_resume_failed, kind), true)
                        retryButton.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        retryButton.visibility = View.GONE
        submitButton.isEnabled = !busy
        captchaInput.isEnabled = !busy
    }

    private fun showStatus(message: String, isError: Boolean) {
        status.text = message
        status.setTextColor(if (isError) Color.rgb(176, 55, 45) else Color.DKGRAY)
    }

    private inner class PassportWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = handleNavigation(request.url)

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleNavigation(Uri.parse(url))

        private fun handleNavigation(uri: Uri): Boolean {
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty()
            if (host == "verify.sec.xiaomi.com" && path.contains("/captcha/status")) {
                CookieManager.getInstance().flush()
                val code = uri.getQueryParameter("code")?.toIntOrNull()
                val flag = uri.getQueryParameter("flag")
                fun prepareFreshChallenge() {
                    val current = session ?: return
                    session = current.copy(verificationPageUrl = null)
                    XiaomiPassportChallengeStore.save(this@PassportCaptchaActivity, session!!)
                }
                when (code) {
                    0 -> if (!flag.isNullOrBlank()) {
                        resumePassport(XiaomiPassportVerificationResult(vToken = flag))
                    } else {
                        prepareFreshChallenge()
                        showStatus(getString(R.string.mx_captcha_token_missing), true)
                        retryButton.visibility = View.VISIBLE
                    }

                    3 -> resumePassport(XiaomiPassportVerificationResult(vToken = UUID.randomUUID().toString().replace("-", "")))

                    1 -> {
                        prepareFreshChallenge()
                        showStatus(getString(R.string.mx_captcha_cancelled), true)
                        retryButton.visibility = View.VISIBLE
                    }

                    2 -> {
                        prepareFreshChallenge()
                        showStatus(getString(R.string.mx_captcha_expired), true)
                        retryButton.visibility = View.VISIBLE
                    }

                    95008, 95009 -> {
                        prepareFreshChallenge()
                        showStatus(getString(R.string.mx_captcha_unavailable, code), true)
                        retryButton.visibility = View.VISIBLE
                    }

                    else -> {
                        prepareFreshChallenge()
                        showStatus(getString(R.string.mx_captcha_unknown, code ?: "unknown"), true)
                        retryButton.visibility = View.VISIBLE
                    }
                }
                return true
            }
            val official = host == "xiaomi.com" || host.endsWith(".xiaomi.com") || host == "mi.com" || host.endsWith(".mi.com")
            if (uri.scheme != "https" || !official || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) {
                showStatus(getString(R.string.mx_captcha_navigation_blocked), true)
                return true
            }
            return false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.removeAllViews()
        webView.destroy()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Passport"
    }
}
