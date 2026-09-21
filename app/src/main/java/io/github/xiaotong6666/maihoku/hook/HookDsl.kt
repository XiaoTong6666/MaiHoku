package io.github.xiaotong6666.maihoku.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

class HookDsl(
    private val module: XposedModule,
    private val registry: HookRegistry,
) {
    fun unhook(id: String) {
        registry.unhook(id)
    }

    fun method(
        executable: Executable,
        id: String,
        block: HookSpec.() -> Unit,
    ): HookHandle = install(module.hook(executable), id, block)

    fun around(
        executable: Executable,
        id: String,
        block: AroundHookCall.() -> Any?,
    ): HookHandle = registry.install(id) {
        module.hook(executable)
            .setId(id)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain -> AroundHookCall(chain).block() }
    }

    fun classInitializer(
        clazz: Class<*>,
        id: String,
        block: HookSpec.() -> Unit,
    ): HookHandle = install(module.hookClassInitializer(clazz), id, block)

    private fun install(
        builder: XposedInterface.HookBuilder,
        id: String,
        block: HookSpec.() -> Unit,
    ): HookHandle {
        val spec = HookSpec().apply(block)
        return registry.install(id) {
            builder
                .setId(id)
                .setPriority(spec.priority)
                .setExceptionMode(spec.exceptionMode)
                .intercept { chain ->
                    val call = HookCall(chain)
                    spec.before?.invoke(call)

                    val result = chain.proceed(call.arguments.toTypedArray())
                    call.result = result
                    spec.after?.invoke(call)
                    call.result
                }
        }
    }
}

class AroundHookCall internal constructor(
    private val chain: XposedInterface.Chain,
) {
    val thisObject: Any?
        get() = chain.thisObject

    private val arguments = chain.args.toMutableList()

    val args: List<Any?>
        get() = arguments

    fun arg(index: Int): Any? = arguments[index]

    fun replaceArg(index: Int, value: Any?) {
        arguments[index] = value
    }

    fun proceed(): Any? = chain.proceed(arguments.toTypedArray())
}

class HookSpec {
    var priority: Int = XposedInterface.PRIORITY_DEFAULT
    var exceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE

    internal var before: (HookCall.() -> Unit)? = null
    internal var after: (HookCall.() -> Unit)? = null

    fun before(block: HookCall.() -> Unit) {
        before = block
    }

    fun after(block: HookCall.() -> Unit) {
        after = block
    }
}

class HookCall internal constructor(
    private val chain: XposedInterface.Chain,
) {
    val thisObject: Any?
        get() = chain.thisObject

    internal val arguments = chain.args.toMutableList()

    val args: List<Any?>
        get() = arguments

    var result: Any? = null

    fun arg(index: Int): Any? = arguments[index]

    fun replaceArg(index: Int, value: Any?) {
        arguments[index] = value
    }
}
