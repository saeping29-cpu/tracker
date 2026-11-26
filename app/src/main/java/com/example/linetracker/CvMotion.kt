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
    private var input: Mat? = null
    private var gray: Mat? = null
    private var diff: Mat? = null
    private var bytes = ByteArray(0)
    var status = "opencv_pending"; private set
    fun reset() { previous?.release(); previous=null; input?.release(); input=null; gray?.release(); gray=null; diff?.release(); diff=null }
    fun update(rgb: ByteArray, w: Int, h: Int): ByteArray? {
        if (!ready) { status="legacy_fallback"; return null }
        return try {
            val src=input ?: Mat().also { input=it }; val g=gray ?: Mat().also { gray=it }; val d=diff ?: Mat().also { diff=it }
            src.create(h,w,CvType.CV_8UC3); src.put(0,0,rgb)
            Imgproc.cvtColor(src,g,Imgproc.COLOR_RGB2GRAY)
            val old=previous
            if(old==null || old.rows()!=h || old.cols()!=w) { old?.release(); previous=g.clone(); status="opencv_warmup"; null }
            else {
                Core.absdiff(g,old,d); Imgproc.threshold(d,d,18.0,255.0,Imgproc.THRESH_BINARY)
                if(bytes.size!=w*h) bytes=ByteArray(w*h)
                d.get(0,0,bytes); g.copyTo(old); status="opencv_motion"; bytes
            }
        } catch (_: Exception) { reset(); status="legacy_fallback"; null }
    }
}
