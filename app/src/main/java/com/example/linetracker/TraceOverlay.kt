package com.example.linetracker

import android.content.Context
import android.graphics.*
import android.view.View

 data class TraceFrame(val width: Int, val height: Int, val roi: IntArray, val target: IntArray,
    val point: BallTracker.Point?, val trail: List<BallTracker.Point>, val status: String,
    val contact: Boolean, val frames: Long, val candidates: Int)

class TraceOverlay(context: Context) : View(context) {
    var frame: TraceFrame? = null
        set(value) { field = value; invalidate() }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); frame?.let { drawTrace(canvas, it, width, height) } }
    companion object {
        fun drawTrace(c: Canvas, f: TraceFrame, w: Int, h: Int) {
            if (f.width <= 0 || f.height <= 0) return
            c.save()
            c.scale(w.toFloat()/f.width, h.toFloat()/f.height)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
            fun area(a: IntArray, color: Int) {
                p.color = color
                c.drawRect(minOf(a[0],a[2])*f.width/100f,minOf(a[1],a[3])*f.height/100f,
                    maxOf(a[0],a[2])*f.width/100f,maxOf(a[1],a[3])*f.height/100f,p)
            }
            area(f.roi, Color.rgb(0,150,255)); area(f.target, Color.rgb(255,45,45))
            p.color = Color.rgb(255,190,0); p.pathEffect = DashPathEffect(floatArrayOf(9f,7f),0f)
            for (i in 1 until f.trail.size) { val a=f.trail[i-1]; val b=f.trail[i]; c.drawLine(a.x,a.y,b.x,b.y,p) }
            p.pathEffect = null; p.color = Color.rgb(0,255,80)
            f.point?.let { q -> val r = maxOf(12f,q.radius+5f); c.drawCircle(q.x,q.y,r,p); c.drawLine(q.x-r-5,q.y,q.x+r+5,q.y,p); c.drawLine(q.x,q.y-r-5,q.x,q.y+r+5,p) }
            p.style = Paint.Style.FILL; p.color = Color.argb(190,0,0,0); c.drawRect(8f,8f,minOf(f.width-8f,850f),72f,p)
            p.color = Color.rgb(0,255,80); p.textSize = 24f
            c.drawText("#${f.frames} / candidates ${f.candidates} / ${if(f.contact) "CONTACT" else "TRACK"}",16f,34f,p)
            c.drawText(f.status,16f,62f,p); c.restore()
        }
    }
}
