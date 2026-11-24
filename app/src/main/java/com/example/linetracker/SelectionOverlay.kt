package com.example.linetracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.max

/** Fullscreen temporary editor; removed before screen capture starts. */
class SelectionOverlay(context: Context, private val mode: String, private val done: (IntArray?) -> Unit) : View(context) {
    private val paint = Paint(3)
    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f
    private var dragging = false
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0x33000000)
        paint.color = Color.WHITE; paint.textSize = 42f; paint.style = Paint.Style.FILL
        canvas.drawText(if (mode != "tap") "영역을 드래그하세요 · 상단 완료" else "터치 위치를 탭하세요 · 상단 완료", 20f, 68f, paint)
        paint.color = 0xFF1565C0.toInt(); canvas.drawRoundRect(12f, 88f, 210f, 170f, 12f, 12f, paint)
        paint.color = Color.WHITE; canvas.drawText("완료", 64f, 145f, paint)
        paint.color = Color.RED; paint.strokeWidth = 5f; paint.style = Paint.Style.STROKE
        if (mode != "tap") canvas.drawRect(min(sx,ex), min(sy,ey), max(sx,ex), max(sy,ey), paint)
        else { canvas.drawCircle(ex, ey, 25f, paint); canvas.drawLine(ex-38,ey,ex+38,ey,paint); canvas.drawLine(ex,ey-38,ex,ey+38,paint) }
        paint.style = Paint.Style.FILL
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat()); val y = event.y.coerceIn(0f,height.toFloat())
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (x < 220f && y in 85f..180f) {
                if (dragging) done(if(mode!="tap") intArrayOf(pct(sx,width),pct(sy,height),pct(ex,width),pct(ey,height)) else intArrayOf(pct(ex,width),pct(ey,height))) else done(null)
                return true
            }; sx=x;sy=y;ex=x;ey=y;dragging=true;invalidate() }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> { ex=x;ey=y;invalidate() }
        }
        return true
    }
    private fun pct(value: Float, total: Int) = (value * 100 / total.coerceAtLeast(1)).toInt().coerceIn(0,100)
}
