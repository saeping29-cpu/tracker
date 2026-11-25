package com.example.linetracker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.WindowManager
import android.graphics.PixelFormat
class AutoTapService : AccessibilityService() {
    companion object {
        @Volatile var foregroundPackage: String = ""
        @Volatile var instance: AutoTapService? = null
        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 65)).build()
            return service.dispatchGesture(gesture, null, null)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshForeground(); handler.postDelayed(this, 250L) }
    }
    private fun refreshForeground() {
        foregroundPackage = runCatching {
            val app = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            app?.root?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString().orEmpty()
        }.getOrDefault("")
    }
    private var trace: TraceOverlay? = null
    fun showTrace(frame: TraceFrame) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (trace?.isAttachedToWindow == false) hideTrace()
        if (trace == null) {
            val v = TraceOverlay(this)
            val params = WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
            try { wm.addView(v,params); trace = v } catch (e: Exception) { android.util.Log.e("LineTracker", "overlay attach failed", e); runCatching { java.io.File(filesDir,"diagnostics.txt").appendText("overlay attach failed: $e\n") }; return }
        }
        trace?.visibility = android.view.View.VISIBLE
        trace?.frame = frame
    }
    fun hideTrace() { trace?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }; trace = null }
    private var overlay: SelectionOverlay? = null
    fun select(mode: String, onSelected: (IntArray?) -> Unit) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay?.let { wm.removeView(it) }
        val view = SelectionOverlay(this, mode) { result ->
            overlay?.let { wm.removeView(it) }; overlay = null
            onSelected(result)
        }
        overlay = view
        wm.addView(view, WindowManager.LayoutParams(-1, -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT))
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        handler.removeCallbacks(refresh); handler.post(refresh)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { refreshForeground() }
    override fun onInterrupt() {}
    override fun onDestroy() { handler.removeCallbacks(refresh); hideTrace(); overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }; overlay = null; instance = null; foregroundPackage = ""; super.onDestroy() }
}
