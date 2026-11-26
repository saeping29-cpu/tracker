package com.example.linetracker

import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Native grayscale frame difference; fallback to legacy mask if unavailable. */
class CvMotion {
    private val ready by lazy { try { OpenCVLoader.initLocal() } catch (_: Throwable) { false } }
    private var previous: Mat? = null
    private var oldX=0; private var oldY=0
    private var input: Mat? = null
    private var gray: Mat? = null
    private var diff: Mat? = null
    private var bytes = ByteArray(0)
    private var overlap = ByteArray(0)
    var status = "opencv_pending"; private set
    val available get() = ready
    fun reset() { previous?.release(); previous=null; input?.release(); input=null; gray?.release(); gray=null; diff?.release(); diff=null; oldX=0; oldY=0; status=if(ready) "opencv_pending" else "legacy_fallback" }
    fun update(rgb: ByteArray, w: Int, h: Int, x: Int, y: Int): ByteArray? {
        if (!ready) { status="legacy_fallback"; return null }
        return try {
            val src=input ?: Mat().also { input=it }; val g=gray ?: Mat().also { gray=it }; val d=diff ?: Mat().also { diff=it }
            src.create(h,w,CvType.CV_8UC3); src.put(0,0,rgb)
            Imgproc.cvtColor(src,g,Imgproc.COLOR_RGB2GRAY)
            val old=previous
            if(bytes.size!=w*h) bytes=ByteArray(w*h)
            // Unknown/newly exposed pixels are not motion. This avoids treating every
            // bright UI element as a moving ball during warm-up or a crop change.
            bytes.fill(0)
            val left=maxOf(x,oldX); val top=maxOf(y,oldY)
            val right=minOf(x+w,oldX+(old?.cols() ?: 0)); val bottom=minOf(y+h,oldY+(old?.rows() ?: 0))
            if(old!=null && right>left && bottom>top) {
                val cw=right-left; val ch=bottom-top
                val cur=g.submat(top-y,bottom-y,left-x,right-x)
                val prev=old.submat(top-oldY,bottom-oldY,left-oldX,right-oldX)
                try {
                    Core.absdiff(cur,prev,d); Imgproc.threshold(d,d,18.0,255.0,Imgproc.THRESH_BINARY)
                    if(overlap.size!=cw*ch) overlap=ByteArray(cw*ch)
                    d.get(0,0,overlap)
                    for(row in 0 until ch) System.arraycopy(overlap,row*cw,bytes,(top-y+row)*w+left-x,cw)
                } finally { cur.release(); prev.release() }
                status="opencv_motion"
            } else status="opencv_warmup"
            val saved=old ?: Mat().also { previous=it }; g.copyTo(saved); oldX=x; oldY=y
            bytes

        } catch (_: Exception) { reset(); status="legacy_fallback"; null }
    }
}
