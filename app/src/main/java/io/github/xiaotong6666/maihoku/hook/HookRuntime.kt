package io.github.xiaotong6666.maihoku.hook

import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedModule
import io.github.xiaotong6666.maihoku.hook.dexkit.DexKitSession

class HookRuntime(
    val module: XposedModule,
    val packageName: String,
    val classLoader: ClassLoader,
    val applicationInfo: ApplicationInfo,
) {
    val hooks = HookDsl(module, HookRegistry())
    val dexKit = DexKitSession(module, applicationInfo.sourceDir)
}
