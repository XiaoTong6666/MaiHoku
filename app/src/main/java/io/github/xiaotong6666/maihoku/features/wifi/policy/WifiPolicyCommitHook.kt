package io.github.xiaotong6666.maihoku.features.wifi.policy

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.wifi.core.WifiConfigurationOverride
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.hook.ModuleResources
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/**
 * A requested manual security policy is staged alongside the new WifiConfiguration.
 * It is NOT committed when Settings merely calls getConfig(), or if the framework
 * rejects the save/connect. ActionListener.onSuccess marks successful submission.
 */
internal object WifiPolicyCommitHook : BaseHook() {
    override val name = "WifiPolicyCommit"
    private const val TAG = "WifiPolicyCommit"

    data class Pending(
        val context: Context,
        val ssid: String,
        val expectation: WifiSaveExpectation,
        val networkId: Int,
        val profileKey: String?,
        val createdAtMs: Long = SystemClock.elapsedRealtime(),
    )

    private val pending = Collections.synchronizedMap(WeakHashMap<WifiConfiguration, Pending>())

    // A Settings Activity/Fragment result parcels WifiConfiguration into a distinct instance.
    // Key copies by their *complete profile identity*, never SSID alone: two security
    // variants and BSSID-specific profiles can otherwise steal each other's staged policy.
    private val pendingByProfile = LinkedHashMap<String, Pending>()
    private const val PENDING_TTL_MS = 20_000L

    @Synchronized
    fun stage(config: WifiConfiguration, context: Context, ssid: String, expectation: WifiSaveExpectation) {
        val request = Pending(
            context.applicationContext ?: context,
            ssid,
            expectation,
            config.networkId,
            WifiSecurityPolicyStore.keyForSubmission(config),
        )
        pending[config] = request
        pendingByProfile.entries.removeAll {
            SystemClock.elapsedRealtime() - it.value.createdAtMs > PENDING_TTL_MS
        }
        request.profileKey?.let { pendingByProfile[it] = request }
        while (pendingByProfile.size > 8) pendingByProfile.remove(pendingByProfile.keys.first())
    }

    @Synchronized
    private fun take(config: WifiConfiguration): Pending? {
        val actualKey = WifiSecurityPolicyStore.keyForSubmission(config)
        val request = pending.remove(config) ?: actualKey?.let(pendingByProfile::get) ?: return null
        // A preview object must not consume a newer request for the same identity.
        if (request.profileKey == null || pendingByProfile[request.profileKey] !== request) return null
        val fresh = SystemClock.elapsedRealtime() - request.createdAtMs <= PENDING_TTL_MS
        val matches = WifiPolicySubmissionMatch.matches(
            request.profileKey,
            actualKey,
            request.networkId,
            config.networkId,
        )
        if (!fresh || !matches) {
            Log.w(
                TAG,
                "Rejecting stale or different Wi-Fi profile submission (fresh=$fresh " +
                    "sameIdentity=$matches); original save remains untouched",
            )
            if (!fresh) pendingByProfile.remove(request.profileKey)
            return null
        }
        // Reject a parcelled/native preview that no longer carries the explicit fields.
        val enabled = WifiSecurityPolicyStore.enabledSecurityTypes(config).toSet()
        val flags = WifiConfigurationOverride.savedFlags(config)
        val pmf = flags.groupBy { it.type }.mapValues { (_, entries) -> entries.map { it.pmf }.toSet() }
        val h2e = flags.filter { it.type == 4 }.map { it.h2e }.toSet()
        if (!request.expectation.matches(enabled, pmf, h2e, setOf(config.BSSID?.lowercase(java.util.Locale.ROOT)))) {
            Log.w(TAG, "Wi-Fi submission no longer matches staged advanced fields")
            return null
        }
        pendingByProfile.remove(request.profileKey)
        return request
    }

    override fun init(runtime: HookRuntime) {
        val listenerType = WifiManager::class.java.declaredClasses.singleOrNull {
            it.simpleName == "ActionListener" && it.isInterface
        } ?: run {
            Log.w(TAG, "WifiManager.ActionListener unavailable; policy commit disabled")
            return
        }
        val methods = WifiManager::class.java.declaredMethods.filter {
            it.name in setOf("save", "connect") && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == WifiConfiguration::class.java &&
                it.parameterTypes[1] == listenerType
        }
        for (method in methods) {
            runtime.hooks.around(method, "wifi.policy.commit.${method.name}") {
                val config = arg(0) as? WifiConfiguration ?: return@around proceed()
                val request = take(config)
                val submission = safeBegin(config, request)
                if (submission == null) return@around proceed()
                val original = arg(1)
                val listener = Proxy.newProxyInstance(
                    listenerType.classLoader,
                    arrayOf(listenerType),
                ) { proxy, invoked, args ->
                    runCatching {
                        when (invoked.name) {
                            "onSuccess" -> accepted(submission, method.name)
                            "onFailure" -> rejected(submission)
                        }
                    }.onFailure { Log.w(TAG, "Wi-Fi save callback bookkeeping failed", it) }
                    forwardListener(proxy, invoked, args, original)
                }
                replaceArg(1, listener)
                try {
                    proceed()
                } catch (t: Throwable) {
                    finish(submission)
                    throw t
                }
            }
        }
        val syncMethods = WifiManager::class.java.declaredMethods.filter {
            it.name in setOf("addNetwork", "updateNetwork") &&
                it.parameterTypes.contentEquals(arrayOf(WifiConfiguration::class.java)) &&
                it.returnType == Int::class.javaPrimitiveType
        }
        for (method in syncMethods) {
            runtime.hooks.around(method, "wifi.policy.commit.sync.${method.name}") {
                val config = arg(0) as? WifiConfiguration ?: return@around proceed()
                val submission = safeBegin(config, take(config)) ?: return@around proceed()
                val nativeResult = try {
                    proceed()
                } catch (t: Throwable) {
                    finish(submission)
                    throw t
                }
                val id = nativeResult as? Int
                if (id != null && id >= 0) {
                    accepted(submission, method.name, id)
                } else {
                    rejected(submission)
                }
                nativeResult
            }
        }
        // Settings also calls WifiEntry.forget()/WifiManager.forget() for saved networks.
        // Clear an override *only after* the native delete succeeds, not on a user tap.
        val forget = WifiManager::class.java.declaredMethods.firstOrNull {
            it.name == "forget" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, listenerType))
        }
        if (forget != null) {
            runtime.hooks.around(forget, "wifi.policy.clear-on-forget") {
                val id = arg(0) as? Int ?: return@around proceed()
                val context = settingsContext() ?: return@around proceed()
                val deletion = safeDeletion(context, id) ?: return@around proceed()
                val original = arg(1)
                replaceArg(
                    1,
                    Proxy.newProxyInstance(
                        listenerType.classLoader,
                        arrayOf(listenerType),
                    ) { proxy, invoked, args ->
                        runCatching {
                            if (invoked.name == "onSuccess") acceptedDeletion(deletion)
                            if (invoked.name == "onFailure") rejected(deletion)
                        }.onFailure { Log.w(TAG, "Wi-Fi deletion callback bookkeeping failed", it) }
                        forwardListener(proxy, invoked, args, original)
                    },
                )
                try {
                    proceed()
                } catch (t: Throwable) {
                    finish(deletion)
                    throw t
                }
            }
        }
        val remove = WifiManager::class.java.declaredMethods.firstOrNull {
            it.name == "removeNetwork" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                it.returnType == Boolean::class.javaPrimitiveType
        }
        if (remove != null) {
            runtime.hooks.around(remove, "wifi.policy.clear-on-remove") {
                val id = arg(0) as? Int ?: return@around proceed()
                val context = settingsContext()
                val deletion = context?.let { safeDeletion(it, id) } ?: return@around proceed()
                val nativeResult = try {
                    proceed()
                } catch (t: Throwable) {
                    finish(deletion)
                    throw t
                }
                if (nativeResult == true) acceptedDeletion(deletion) else rejected(deletion)
                nativeResult
            }
        }
        Log.i(
            TAG,
            "Monitoring ${methods.size} asynchronous and ${syncMethods.size} " +
                "synchronous saves; forget=${forget != null}, remove=${remove != null}",
        )
    }

    private fun settingsContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    private fun forwardListener(
        proxy: Any,
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
        original: Any?,
    ): Any? {
        if (original != null) {
            return try {
                method.invoke(original, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
        return if (method.declaringClass == Any::class.java) {
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "MaiHokuWifiPolicyListener"
                else -> null
            }
        } else {
            null
        }
    }

    private val generations = WifiSubmissionGenerations()
    private val active = mutableMapOf<Long, Submission>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private data class Submission(
        val generation: Long,
        val aliases: Set<String>,
        val context: Context,
        val ssid: String,
        val id: Int,
        val key: String?,
        val previousKeys: Set<String>,
        val expectation: WifiSaveExpectation?,
        val token: String?,
        val startedAt: Long = SystemClock.elapsedRealtime(),
        var callbackAccepted: Boolean = false,
    )

    private fun aliases(id: Int, keys: Set<String>): Set<String> = keys + listOfNotNull("id:$id".takeIf { id >= 0 })

    private fun safeBegin(config: WifiConfiguration, request: Pending?): Submission? = runCatching { begin(config, request) }.onFailure { Log.w(TAG, "Cannot stage Wi-Fi save", it) }.getOrNull()

    private fun safeDeletion(context: Context, id: Int): Submission? = runCatching { beginDeletion(context, id) }.onFailure { Log.w(TAG, "Cannot stage Wi-Fi deletion", it) }.getOrNull()

    /** Serialize publication, invalidation and final policy writes in Settings. */
    @Synchronized
    private fun begin(config: WifiConfiguration, request: Pending?): Submission? {
        val context = request?.context ?: settingsContext() ?: return null
        val key = WifiSecurityPolicyStore.keyForSubmission(config)
        val ssid = config.SSID.orEmpty()
        // Avoid a binder query on untouched native saves when no operation is pending.
        if (request == null && active.isEmpty()) return null
        val profiles = WifiSecurityPolicyStore.savedNetworks(context, ssid).groupBy { it.networkId }
        val id = if (config.networkId >= 0) {
            config.networkId
        } else {
            profiles.filterValues { key != null && key in WifiSecurityPolicyStore.profileKeys(it) }
                .keys.singleOrNull() ?: -1
        }
        val previous = profiles[id].orEmpty()
        val previousKeys = WifiSecurityPolicyStore.profileKeys(previous)
        val claims = aliases(id, previousKeys + listOfNotNull(key))
        if (claims.isEmpty()) return null
        // A later native save also invalidates pending manual work. Do not modify
        // policy for a completely untouched native form.
        invalidate(claims)
        if (request == null || key == null) return null
        val mode = request.expectation.mode
            ?: WifiSecurityPolicyStore.storedModeForProfiles(context, previous, profiles.size == 1)
        val expectation = request.expectation.copy(mode = mode)
        val generation = generations.begin(claims)
        val token = runCatching {
            expectation.mode?.let { WifiSecurityPolicyStore.beginSubmission(context, key, it) }
        }.onFailure { Log.w(TAG, "Cannot publish temporary Wi-Fi policy", it) }.getOrNull()
        return Submission(
            generation, claims, context, ssid, id, key, previousKeys,
            expectation, token,
        ).also(::track)
    }

    @Synchronized
    private fun beginDeletion(context: Context, id: Int): Submission? {
        val saved = WifiSecurityPolicyStore.savedNetworksById(context, id)
        val keys = WifiSecurityPolicyStore.profileKeys(saved)
        val claims = aliases(id, keys)
        invalidate(claims)
        if (saved.isEmpty()) return null
        return Submission(
            generations.begin(claims), claims, context, saved.first().SSID.orEmpty(),
            id, null, keys, null, null,
        ).also(::track)
    }

    private fun invalidate(claims: Set<String>) {
        active.values.filter { it.aliases.any(claims::contains) }.toList().forEach(::finish)
    }

    private fun track(submission: Submission) {
        active[submission.generation] = submission
        handler.postDelayed({ finish(submission) }, PENDING_TTL_MS)
    }

    private fun current(submission: Submission): Boolean = SystemClock.elapsedRealtime() - submission.startedAt in 0L..PENDING_TTL_MS &&
        generations.isCurrent(submission.generation, submission.aliases) &&
        (
            submission.token == null || runCatching {
                WifiSecurityPolicyStore.isCurrentSubmission(
                    submission.context,
                    checkNotNull(submission.key),
                    submission.token,
                )
            }.getOrDefault(false)
            )

    @Synchronized
    private fun finish(submission: Submission) {
        if (active.remove(submission.generation) == null) return
        generations.finish(submission.generation)
        runCatching { WifiSecurityPolicyStore.endSubmission(submission.context, submission.key, submission.token) }
            .onFailure { Log.w(TAG, "Cannot clear temporary Wi-Fi policy", it) }
    }

    @Synchronized
    private fun accepted(submission: Submission, operation: String, resultId: Int = submission.id) {
        if (!current(submission) || submission.callbackAccepted) return
        submission.callbackAccepted = true
        pollPersistedSecurity(submission, resultId, operation, 6)
    }

    @Synchronized
    private fun rejected(submission: Submission) {
        if (!current(submission) || submission.callbackAccepted) return
        finish(submission)
    }

    private fun pollPersistedSecurity(submission: Submission, id: Int, operation: String, remaining: Int) {
        handler.postDelayed({
            runCatching { verifyAndCommit(submission, id, operation, remaining) }
                .onFailure {
                    Log.w(TAG, "Wi-Fi save verification failed", it)
                    finish(submission)
                }
        }, 650L)
    }

    @Synchronized
    private fun verifyAndCommit(submission: Submission, id: Int, operation: String, remaining: Int) {
        if (!current(submission)) {
            finish(submission)
            return
        }
        val expectation = checkNotNull(submission.expectation)
        val key = checkNotNull(submission.key)
        val profiles = WifiSecurityPolicyStore.readSavedNetworks(submission.context)?.filter {
            WifiSecurityPolicy.normalizeSsid(it.SSID.orEmpty()) == WifiSecurityPolicy.normalizeSsid(submission.ssid)
        }?.groupBy { it.networkId }
        val matches = profiles.orEmpty().filter { (candidateId, rows) ->
            (id < 0 || candidateId == id) && key == WifiSecurityPolicyStore.canonicalKey(rows)
        }
        val match = matches.entries.singleOrNull()
        val rows = match?.value.orEmpty()
        val enabledByRow = rows.map(WifiSecurityPolicyStore::enabledSecurityTypes)
        val enabled = enabledByRow.flatten().toSet()
        val flagsByRow = rows.map(WifiConfigurationOverride::savedFlags)
        val flags = flagsByRow.flatten()
        val readable = enabledByRow.all { it.isNotEmpty() } &&
            (
                (expectation.pmf == null && expectation.h2e == null) ||
                    enabledByRow.zip(flagsByRow).all { (types, values) ->
                        types.toSet() == values.map { it.type }.toSet()
                    }
                )
        val pmf = flags.groupBy { it.type }.mapValues { (_, entries) -> entries.map { it.pmf }.toSet() }
        val h2e = flags.filter { it.type == 4 }.map { it.h2e }.toSet()
        val bssids = rows.map { it.BSSID?.lowercase(java.util.Locale.ROOT) }.toSet()
        if (match != null && readable && expectation.matches(enabled, pmf, h2e, bssids)) {
            // Check identity and generation again before writing. Other network IDs
            // sharing an indistinguishable key must never lose their own policy.
            val otherKeys = profiles.orEmpty().filterKeys { it != match.key }.values
                .flatMap(WifiSecurityPolicyStore::profileKeys).toSet()
            val committed = runCatching {
                if (key in otherKeys || !current(submission)) return@runCatching false
                val mode = expectation.mode
                if (mode != null && !WifiSecurityPolicyStore.putByKey(submission.context, key, mode)) {
                    return@runCatching false
                }
                val retained = if (mode == null) WifiSecurityPolicyStore.profileKeys(rows) else setOf(key)
                val obsolete = WifiPolicySubmissionMatch.obsoleteKeys(submission.previousKeys, retained, otherKeys)
                var ok = true
                for (old in obsolete) if (!WifiSecurityPolicyStore.clearKey(submission.context, old)) ok = false
                if (mode != null || obsolete.isNotEmpty()) {
                    if (!WifiSecurityPolicyStore.clearLegacy(
                            submission.context,
                            submission.ssid,
                            match.key,
                            profiles?.size == 1,
                        )
                    ) {
                        ok = false
                    }
                }
                ok
            }.onFailure { Log.w(TAG, "Wi-Fi policy persistence failed", it) }.getOrDefault(false)
            finish(submission)
            if (committed) {
                Log.i(TAG, "Verified saved Wi-Fi options via $operation; enabled=$enabled")
            } else {
                notifyFailure(submission)
            }
        } else if (remaining > 1) {
            pollPersistedSecurity(submission, id, operation, remaining - 1)
        } else {
            finish(submission)
            Log.w(TAG, "Wi-Fi $operation accepted but requested fields were not persisted; enabled=$enabled")
            notifyFailure(submission)
        }
    }

    @Synchronized
    private fun acceptedDeletion(submission: Submission) {
        if (!current(submission) || submission.callbackAccepted) return
        submission.callbackAccepted = true
        completeDeletion(submission)
    }

    @Synchronized
    private fun completeDeletion(submission: Submission, remaining: Int = 6) {
        if (!current(submission)) {
            finish(submission)
            return
        }
        val saved = WifiSecurityPolicyStore.readSavedNetworks(submission.context)
        if (saved == null || saved.any { it.networkId == submission.id }) {
            if (remaining > 1) {
                handler.postDelayed({
                    runCatching { completeDeletion(submission, remaining - 1) }
                        .onFailure {
                            Log.w(TAG, "Wi-Fi deletion verification failed", it)
                            finish(submission)
                        }
                }, 650L)
            } else {
                finish(submission)
            }
            return
        }
        val otherKeys = saved.groupBy { it.networkId }.values.flatMap(WifiSecurityPolicyStore::profileKeys).toSet()
        runCatching {
            for (key in submission.previousKeys - otherKeys) {
                if (!WifiSecurityPolicyStore.clearKey(submission.context, key)) Log.w(TAG, "Could not clear Wi-Fi policy")
            }
            if (!WifiSecurityPolicyStore.clearLegacy(
                    submission.context,
                    submission.ssid,
                    submission.id,
                    saved.none {
                        WifiSecurityPolicy.normalizeSsid(it.SSID.orEmpty()) ==
                            WifiSecurityPolicy.normalizeSsid(submission.ssid)
                    },
                )
            ) {
                Log.w(TAG, "Could not clear legacy Wi-Fi policy")
            }
        }.onFailure { Log.w(TAG, "Failed to clear forgotten Wi-Fi policy", it) }
        finish(submission)
    }

    private fun notifyFailure(submission: Submission) {
        runCatching {
            Toast.makeText(
                submission.context,
                ModuleResources.text(
                    submission.context,
                    R.string.wifi_security_not_applied,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }.onFailure { Log.w(TAG, "Unable to display Wi-Fi save verification notice", it) }
    }
}
