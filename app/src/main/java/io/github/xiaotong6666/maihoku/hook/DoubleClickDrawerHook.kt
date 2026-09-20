package io.github.xiaotong6666.maihoku.hook

import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.github.xiaotong6666.maihoku.TERMUX_PACKAGE
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

object DoubleClickDrawerHook : BaseHook() {

    override val name: String = "DoubleClickDrawerHook"

    private const val TAG = "TermuxDblClickDrawer"
    private const val TARGET_TERMINAL_VIEW = "com.termux.view.TerminalView"
    private const val DOUBLE_CLICK_TIMEOUT_MS = 260L
    private const val LEFT_EDGE_PX = 100

    @Volatile
    private var lastDownTime: Long = 0

    override fun init() {
        val onTouchEvent = findTerminalOnTouchEvent()
        if (onTouchEvent == null) {
            Log.e(TAG, "TerminalView.onTouchEvent not found, abort")
            return
        }

        onTouchEvent.createHook {
            before { param ->
                val event = param.arg(0) as? MotionEvent ?: return@before
                if (event.actionMasked != MotionEvent.ACTION_DOWN) return@before

                val now = System.currentTimeMillis()
                val previous = lastDownTime
                lastDownTime = now

                if (previous == 0L || now - previous >= DOUBLE_CLICK_TIMEOUT_MS) return@before
                if (event.x > LEFT_EDGE_PX) return@before

                val terminalView = param.thisObjectOrNull as? View ?: return@before
                openLeftDrawer(terminalView)
            }
        }

        Log.i(TAG, "Hooked ${onTouchEvent.declaringClass.name}.onTouchEvent")
    }

    /** Use the exact Termux method first; DexKit is only a lookup fallback. */
    private fun findTerminalOnTouchEvent(): Method? {
        val direct = try {
            MethodFinder.fromClass(TARGET_TERMINAL_VIEW)
                .filterByName("onTouchEvent")
                .filterByParamTypes(MotionEvent::class.java)
                .firstOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "Direct TerminalView lookup failed", t)
            null
        }
        if (direct != null) return direct

        return try {
            val apkPath = EzXposed.appContext.packageManager
                .getApplicationInfo(TERMUX_PACKAGE, 0).sourceDir
            DexKitBridge.create(apkPath)?.use { dexkit ->
                dexkit.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create().apply {
                            returnType = "boolean"
                            paramTypes = listOf("android.view.MotionEvent")
                        },
                    ),
                )
                    .mapNotNull { data ->
                        try {
                            data.getMethodInstance(ClassLoaderProvider.safeClassLoader)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    .firstOrNull {
                        it.name == "onTouchEvent" &&
                            it.declaringClass.name == TARGET_TERMINAL_VIEW
                    }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DexKit lookup failed", t)
            null
        }
    }

    private fun openLeftDrawer(terminalView: View) {
        val drawerId = terminalView.resources.getIdentifier("drawer_layout", "id", TERMUX_PACKAGE)
        if (drawerId == 0) {
            Log.w(TAG, "Termux drawer_layout resource not found")
            return
        }

        val drawerLayout = terminalView.rootView.findViewById<View>(drawerId)
        if (drawerLayout == null) {
            Log.w(TAG, "Termux drawer_layout view not found")
            return
        }

        try {
            val drawerClass = drawerLayout.javaClass
            val isOpen = drawerClass
                .getMethod("isDrawerOpen", Int::class.javaPrimitiveType)
                .invoke(drawerLayout, Gravity.LEFT) as Boolean
            if (!isOpen) {
                drawerClass
                    .getMethod("openDrawer", Int::class.javaPrimitiveType)
                    .invoke(drawerLayout, Gravity.LEFT)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open left drawer", t)
        }
    }
}
