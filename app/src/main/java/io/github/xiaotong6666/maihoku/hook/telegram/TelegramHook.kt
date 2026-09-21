package io.github.xiaotong6666.maihoku.hook.telegram

import android.util.Log
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime

object TelegramHook : BaseHook() {
    override val name: String = "TelegramHook"

    private val features = listOf(
        ApiIdentityFeature,
        SmsAuthProfileFeature,
        SmsFeeBillingFeature,
        MutualContactFeature,
        ProfileIdentityFeature,
        SourceFreeForwardFeature,
        IdentityAuditFeature,
    )

    override fun init(runtime: HookRuntime) {
        val telegram = TelegramRuntime(runtime)
        Log.i(
            TelegramRuntime.TAG,
            "target=${runtime.packageName} source=${runtime.applicationInfo.sourceDir}",
        )

        for (feature in features) {
            val state = feature.start(telegram)
            Log.i(TelegramRuntime.TAG, "${feature.id} $state")
        }
    }
}
