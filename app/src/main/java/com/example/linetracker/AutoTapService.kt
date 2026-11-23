package com.example.linetracker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.WindowManager
import android.graphics.PixelForm
class AutoTapService : AccessibilityService() {
    companion object {
        @Volatile var instance: AutoTapService? = null
        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 65)).build()
            return service.dispatchGesture(gesture, null, null)
        }
    }
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
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }; overlay = null; instance = null; super.onDestroy() }
}
