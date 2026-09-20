package io.github.xiaotong6666.maihoku.hook

abstract class BaseHook {
    abstract val name: String
    abstract fun init()

    var isInit: Boolean = false
}
