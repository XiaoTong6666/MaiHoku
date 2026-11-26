package io.github.xiaotong6666.maihoku.features.googleone

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.hook.ModuleResources
import java.util.WeakHashMap
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Adds the offer button within Google One. */
object GoogleOneHook : BaseHook() {
    override val name = "GoogleOneHook"

    private const val TAG = "GoogleOne"
    private const val BUTTON_TAG = "maihoku.googleone.offer.button"
    private val sessions = WeakHashMap<Activity, GoogleOneBillingSession>()

    override fun init(runtime: HookRuntime) {
        runtime.hooks.method(
            Activity::class.java.getDeclaredMethod("onResume"),
            "googleone.activity.resume",
        ) {
            after {
                val activity = thisObject as? Activity ?: return@after
                activity.runOnUiThread { installButton(activity) }
            }
        }
        runtime.hooks.method(
            Activity::class.java.getDeclaredMethod("onDestroy"),
            "googleone.activity.destroy",
        ) {
            before {
                val activity = thisObject as? Activity ?: return@before
                sessions.remove(activity)?.close()
            }
        }
        Log.i(TAG, "Google One offer inspector hooks installed")
    }

    private fun installButton(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView as? FrameLayout ?: return
        if (decor.findViewWithTag<View>(BUTTON_TAG) != null) return

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val button = TextView(activity).apply {
            tag = BUTTON_TAG
            text = ModuleResources.text(activity, R.string.googleone_query_button)
            contentDescription = ModuleResources.text(activity, R.string.googleone_query_button_description)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.rgb(41, 92, 179))
                cornerRadius = dp(28).toFloat()
            }
            setOnClickListener {
                if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
                val session = sessions.getOrPut(activity) { GoogleOneBillingSession(activity) }
                session.showMenu()
            }
        }
        val layout = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.BOTTOM,
        ).apply {
            marginEnd = dp(18)
            bottomMargin = dp(88)
        }
        decor.addView(button, layout)
    }
}
