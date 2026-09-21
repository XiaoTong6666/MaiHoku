package io.github.xiaotong6666.maihoku.hook.dexkit

import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import java.io.File

class DexKitSession(
    private val module: XposedModule,
    private val apkPath: String,
) {
    fun <T> useBridge(block: (DexKitBridge) -> T): T {
        ensureLoaded()
        val bridge = checkNotNull(DexKitBridge.create(apkPath)) {
            "Unable to create DexKit bridge for $apkPath"
        }
        return bridge.use(block)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(LOAD_LOCK) {
            if (loaded) return

            val nativeLibraryDir = module.moduleApplicationInfo.nativeLibraryDir
            val library = nativeLibraryDir
                ?.let { File(it, "libdexkit.so") }
                ?.takeIf(File::exists)

            if (library != null) {
                System.load(library.absolutePath)
            } else {
                System.loadLibrary("dexkit")
            }
            loaded = true
        }
    }

    private companion object {
        val LOAD_LOCK = Any()

        @Volatile
        var loaded = false
    }
}
