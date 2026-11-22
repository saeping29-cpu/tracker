package com.example.linetracker

import android.media.Image
import kotlin.math.*

/** Experimental bright-ball tracker. Works best with a small ROI around the pitch path.
 * Coordinates are screen percentages; no general-purpose object recognition is claimed.
 */
class BallTracker {
    private data class Blob(val x: Float, val y: Float, val radius: Float, val pixels: Int)
    private var previous: Blob? = null
    private var previousTime = 0L
    private var armed = true
    private var missing = 0
    private var lastTrigger = 0L
    var count = 0; private set
    var status = "공 탐색 중"; private set

    fun process(image: Image, roi: IntArray, target: IntArray, lineMode: Boolean, now: Long): Boolean {
        val w = image.width; val h = image.height
        val l = (min(roi[0], roi[2]) * w / 100).coerceIn(0, w-1)
        val r = (max(roi[0], roi[2]) * w / 100).coerceIn(l+1, w)
        val t = (min(roi[1], roi[3]) * h / 100).coerceIn(0, h-1)
        val b = (max(roi[1], roi[3]) * h / 100).coerceIn(t+1, h)
        // 2px sampling reduces cost; narrow ROI is crucial for false-positive rejection.
        val step = 2
        val gw = max(1, (r-l)/step); val gh = max(1, (b-t)/step)
        val mask = BooleanArray(gw*gh)
        val p = image.planes[0]; val buffer = p.buffer
        for (y in 0 until gh) for (x in 0 until gw) {
            val offset = (t+y*step)*p.rowStride+(l+x*step)*p.pixelStride
            val red = buffer.get(offset).toInt() and 255
            val green = buffer.get(offset+1).toInt() and 255
            val blue = buffer.get(offset+2).toInt() and 255
            val minC = min(red,min(green,blue)); val maxC=max(red,max(green,blue))
            // White/near-white baseball; purple trail and colored field are rejected.
            mask[y*gw+x] = minC >= 185 && maxC-minC <= 65
        }
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val candidates = ArrayList<Blob>()
        for (i in mask.indices) {
            if (!mask[i] || seen[i]) continue
            var head=0; var tail=1; queue[0]=i; seen[i]=true
            var sumX=0L; var sumY=0L; var minX=gw; var maxX=0; var minY=gh; var maxY=0
            while(head<tail) {
                val at=queue[head++]; val x=at%gw; val y=at/gw
                sumX+=x; sumY+=y; minX=min(minX,x); maxX=max(maxX,x); minY=min(minY,y); maxY=max(maxY,y)
                val ns=intArrayOf(if(x>0) at-1 else -1, if(x<gw-1) at+1 else -1, if(y>0) at-gw else -1, if(y<gh-1) at+gw else -1)
                for (n in ns) if(n>=0 && mask[n] && !seen[n]) { seen[n]=true; queue[tail++]=n }
            }
            val bw=maxX-minX+1; val bh=maxY-minY+1
            if (tail in 4..900 && max(bw,bh).toFloat()/min(bw,bh).coerceAtLeast(1) < 2.4f && tail.toFloat()/(bw*bh) > .35f) {
                candidates.add(Blob(l+sumX.toFloat()/tail*step,t+sumY.toFloat()/tail*step, max(bw,bh)*step/2f,tail))
            }
        }
        val prev=previous
        val selected = if(prev==null) candidates.minByOrNull { it.pixels }
            else candidates.filter { hypot(it.x-prev.x,it.y-prev.y) < max(w,h)*.16f }
                .minByOrNull { hypot(it.x-prev.x,it.y-prev.y) + abs(it.radius-prev.radius)*2 }
        if(selected==null) {
            missing++; status="공 미검출 (ROI/밝기 확인)"
            if(missing>=4) { previous=null; armed=true }
            return false
        }
        missing=0; status="공 추적 중 (${selected.x.toInt()}, ${selected.y.toInt()})"
        val tl=min(target[0],target[2])*w/100f; val tr=max(target[0],target[2])*w/100f
        val tt=min(target[1],target[3])*h/100f; val tb=max(target[1],target[3])*h/100f
        fun touches(q: Blob): Boolean {
            val nearestX=q.x.coerceIn(tl,tr); val nearestY=q.y.coerceIn(tt,tb)
            return hypot(q.x-nearestX,q.y-nearestY)<=q.radius
        }
        val touching=touches(selected)
        // A short motion segment is also checked to catch a ball skipping across the target between frames.
        var crossed=false
        if(prev!=null && previousTime>0 && now-previousTime < 160 && !touches(prev)) {
            for (k in 1..8) {
                val f=k/8f
                if(touches(Blob(prev.x+(selected.x-prev.x)*f,prev.y+(selected.y-prev.y)*f,selected.radius,selected.pixels))) { crossed=true; break }
            }
        }
        val trigger=armed && prev!=null && (touching || crossed) && now-lastTrigger>=280
        if(trigger) { count++; armed=false; lastTrigger=now; status="접촉 감지: ${count}회" }
        if(!touching && !crossed && prev!=null && !touches(prev)) armed=true
        previous=selected; previousTime=now
        return trigger
    }
}
