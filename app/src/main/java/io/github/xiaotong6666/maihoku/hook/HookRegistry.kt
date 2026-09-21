package io.github.xiaotong6666.maihoku.hook

import io.github.libxposed.api.XposedInterface.HookHandle

class HookRegistry {
    private val handles = LinkedHashMap<String, HookHandle>()

    @Synchronized
    fun install(id: String, factory: () -> HookHandle): HookHandle {
        check(id !in handles) { "Hook id already installed: $id" }
        return factory().also { handles[id] = it }
    }

    @Synchronized
    fun unhook(id: String) {
        handles.remove(id)?.unhook()
    }

    @Synchronized
    fun installedIds(): Set<String> = handles.keys.toSet()
}
