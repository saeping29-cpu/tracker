package com.example.linetracker

import android.media.Image
import kotlin.math.abs
import kotlin.math.hypot

/** Experimental motion + separated moving candidate gate, not a trained pose model. */
class PitchDetector {
    private var previous = IntArray(0)
    private var motionAt = -10000L
    private var last = -10000L
    private var point: BallTracker.Point? = null
    fun reset() { previous = IntArray(0); motionAt = -10000L; last = -10000L; point = null }
    fun update(image: Image, candidate: BallTracker.Point?, now: Long): Boolean {
        val plane = image.planes[0]; val b = plane.buffer
        val values = IntArray(24*24)
        var changed = 0
        for (y in 0 until 24) for (x in 0 until 24) {
            val px = (image.width*(.44f+x*.12f/24)).toInt()
            val py = (image.height*(.25f+y*.27f/24)).toInt()
            val i = py*plane.rowStride+px*plane.pixelStride
            if (i+2 >= b.limit()) continue
            val v = ((b.get(i).toInt() and 255)+(b.get(i+1).toInt() and 255)+(b.get(i+2).toInt() and 255))/3
            val j=y*24+x; values[j]=v
            if (previous.size==values.size && abs(v-previous[j])>35) changed++
        }
        previous=values
        if (changed>=35) motionAt=now
        val old=point; point=candidate
        if (candidate==null || old==null || now-motionAt>450 || now-last<1800) return false
        val distance=hypot(candidate.x-old.x,candidate.y-old.y)
        if (distance<image.width*.003f || candidate.y<image.height*.38f || candidate.y-old.y<image.height*.002f) return false
        last=now; return true
    }
}
