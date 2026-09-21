package io.github.xiaotong6666.maihoku.hook.telegram

internal abstract class TelegramFeature<R : Any> {
    abstract val id: String

    protected open fun isEnabled(runtime: TelegramRuntime): Boolean = true

    protected abstract fun resolve(runtime: TelegramRuntime): R

    protected abstract fun install(runtime: TelegramRuntime, resolution: R)

    fun start(runtime: TelegramRuntime): FeatureState {
        if (!isEnabled(runtime)) return FeatureState.DISABLED

        return try {
            val resolution = resolve(runtime)
            install(runtime, resolution)
            FeatureState.INSTALLED
        } catch (t: Throwable) {
            runtime.logUnsupported(id, t)
            FeatureState.UNSUPPORTED
        }
    }
}

internal enum class FeatureState {
    DISABLED,
    INSTALLED,
    UNSUPPORTED,
}
