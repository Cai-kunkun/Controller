package dev.arrbrants.controller

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Small always-on-top banner at the top of the screen that shows what the
 * agent is currently doing. Non-interactive (taps pass through) so it never
 * steals the agent's own taps. Detached around screenshots so the model
 * never sees it and targets underneath stay visible.
 */
class AgentOverlay(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    fun show() = onMainSync {
        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.view_agent_overlay, null)
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Top-end: keeps the left side of the screen (where text fields
                // usually show their content) clear for both the model and the user.
                gravity = Gravity.TOP or Gravity.END
                x = 8
                y = 0
            }
        }
        if (!attached) {
            try {
                windowManager.addView(view, params)
                attached = true
            } catch (e: Exception) {
                // Overlay not permitted; the notification still shows progress.
            }
        }
    }

    fun update(title: String, action: String, thought: String?) = mainHandler.post {
        val v = view ?: return@post
        v.findViewById<TextView>(R.id.overlayTitle).text = title
        val actionView = v.findViewById<TextView>(R.id.overlayAction)
        actionView.text = action
        actionView.visibility = if (action.isBlank()) View.GONE else View.VISIBLE
        val thoughtView = v.findViewById<TextView>(R.id.overlayThought)
        thoughtView.text = thought.orEmpty()
        thoughtView.visibility = if (thought.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Detach so the next screenshot does not contain the banner. */
    fun hideForCapture() = onMainSync {
        if (attached) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // Already detached.
            }
            attached = false
        }
    }

    fun showAfterCapture() = onMainSync {
        val v = view ?: return@onMainSync
        if (!attached) {
            try {
                windowManager.addView(v, params)
                attached = true
            } catch (e: Exception) {
                // Keep silent; the notification still shows progress.
            }
        }
    }

    fun dismiss() = onMainSync {
        if (attached) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // Already detached.
            }
        }
        attached = false
        view = null
        params = null
    }

    private fun onMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
    }
}
