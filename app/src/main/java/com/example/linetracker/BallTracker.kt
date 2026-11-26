package com.example.linetracker

import android.media.Image
import kotlin.math.*

/** Bright moving ball detector. Diagnostic status distinguishes candidate and tracking failures. */
class BallTracker {
    private data class Blob(val x: Float, val y: Float, val radius: Float, val pixels: Int, val quality: Float = 0f)
    data class Point(val x: Float, val y: Float, val radius: Float)
    var point: Point? = null; private set
    var predictedPoint: Point? = null; private set
    var confidence = 0; private set
    var confirmed = false; private set
    private var streak = 0
    private data class Track(val blob: Blob, val time: Long, val vx: Float, val vy: Float, val hits: Int, val score: Float, val startX: Float, val startY: Float, val independent: Int = 0, val id: Long = 0)
    var debug = false
    val debugRows = StringBuilder()
    val debugTracks = StringBuilder()
    val trackId get() = activeId
    val velocityX get() = vx
    val velocityY get() = vy
    private var nextId = 0L
    private var activeId = 0L
    private var tracks = emptyList<Track>()
    private var frameIndex = 0
    private var bodyX = 0f
    private var bodyY = 0f
    private var bodyValid = false
    private var rowBytes = ByteArray(0)
    private var rgb = ByteArray(0)
    private var candidateTime = 0L
    var candidateCount = 0; private set
    var contact = false; private set
    private var previous: Blob? = null
    var launch = intArrayOf(35,25,65,55)
    fun reset() { arrival=null; tracks=emptyList(); activeId=0; candidateCount=0; status="대기"; frameIndex=0; bodyValid=false; previous=null; predictedPoint=null; confidence=0; confirmed=false; streak=0; candidateTime=0L; priorMask=null; lastCandidates=emptyList(); point=null; missing=0; vx=0f; vy=0f; ax=0f; ay=0f; radiusRate=0f; previousTime=0L; locked=false; contact=false }

    private var priorMask: BooleanArray? = null
    private var roiKey = ""
    data class Arrival(val ms: Float, val x: Float, val y: Float, val radius: Float)
    var arrival: Arrival? = null; private set
    var processingMs = 0f; private set
    private var spareMask = BooleanArray(0)
    private var seenBuffer = BooleanArray(0)
    private var queueBuffer = IntArray(0)
    var ax = 0f; private set
    var ay = 0f; private set
    var radiusRate = 0f; private set
    private var vx = 0f
    private var vy = 0f
    private var lastCandidates = emptyList<Blob>()
    private var previousTime = 0L
    private var missing = 0
    private var lastTrigger = -100000L
    private var locked = false
    private var lastSeen = 0L
    var count = 0; private set
    var status = "공 탐색 중"; private set

    fun process(image: Image, roi: IntArray, target: IntArray, lineMode: Boolean, now: Long, timingMs: Int = 0): Boolean {
        val started = System.nanoTime()
        try {
        debugRows.clear(); debugTracks.clear()
        arrival=null; point = null; predictedPoint=null; candidateCount = 0; contact = false
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || roi.size < 4 || target.size < 4) {
            status = "화면/좌표 오류"
            return false
        }
        val l = (min(roi[0], roi[2]) * w / 100).coerceIn(0, w - 1)
        val r = (max(roi[0], roi[2]) * w / 100).coerceIn(l + 1, w)
        val t = (min(roi[1], roi[3]) * h / 100).coerceIn(0, h - 1)
        val b = (max(roi[1], roi[3]) * h / 100).coerceIn(t + 1, h)
        val key = "$w:$h:$l:$r:$t:$b"
        if (key != roiKey) { reset(); roiKey = key }
        val step = 2
        val gw = max(1, (r - l + step - 1) / step)
        val gh = max(1, (b - t + step - 1) / step)
        val mask = if (spareMask.size == gw*gh) spareMask else BooleanArray(gw*gh)
        
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()
        // Bulk row reads avoid three direct-buffer JNI accesses per pixel.
        val rw=r-l; val rh=b-t
        if(rgb.size!=rw*rh*3) rgb=ByteArray(rw*rh*3)
        if(rowBytes.size!=rw*pixelStride) rowBytes=ByteArray(rw*pixelStride)
        val view=buffer.duplicate()
        for(y in 0 until rh) {
            val off=(t+y)*rowStride+l*pixelStride
            val len=min(rowBytes.size,limit-off).coerceAtLeast(0)
            if(len>0) { view.position(off); view.get(rowBytes,0,len) }
            for(x in 0 until rw) {
                val src=x*pixelStride; val dst=(y*rw+x)*3
                if(src+2<len) { rgb[dst]=rowBytes[src]; rgb[dst+1]=rowBytes[src+1]; rgb[dst+2]=rowBytes[src+2] }
                else { rgb[dst]=0; rgb[dst+1]=0; rgb[dst+2]=0 }
            }
        }
        fun bright(px: Int, py: Int): Boolean {
            if(px !in l until r || py !in t until b) return false
            val off=((py-t)*rw+px-l)*3
            val a=rgb[off].toInt() and 255; val c=rgb[off+1].toInt() and 255; val d=rgb[off+2].toInt() and 255
            val lo=min(a,min(c,d)); return lo>=175 && max(a,max(c,d))-lo<=90
        }
        frameIndex++
        var brightPixels = 0
        for (y in 0 until gh) for (x in 0 until gw) {
            val px = l + x * step
            val py = t + y * step
            if (px >= r || py >= b) continue
            val isBright = bright(px,py)
            mask[y * gw + x] = isBright
            if (isBright) brightPixels++
        }
        val oldMask = priorMask
        priorMask = mask
        spareMask = oldMask ?: BooleanArray(0)
        if (seenBuffer.size != mask.size) { seenBuffer = BooleanArray(mask.size); queueBuffer = IntArray(mask.size) }
        val seen = seenBuffer; seen.fill(false)
        val queue = queueBuffer
        val coarse = ArrayList<Blob>()
        var bodySumX=0f; var bodySumY=0f; var bodyWeight=0f
        for (i in mask.indices) {
            if (!mask[i] || seen[i]) continue
            var head = 0
            var tail = 1
            queue[0] = i
            seen[i] = true
            var sumX = 0L; var sumY = 0L; var changed = 0
            var minX = gw; var maxX = 0; var minY = gh; var maxY = 0
            while (head < tail) {
                val at = queue[head++]
                if (oldMask != null && oldMask.size == mask.size && !oldMask[at]) changed++
                val x = at % gw
                val y = at / gw
                sumX += x; sumY += y
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                if (x > 0) { val n = at - 1; if (mask[n] && !seen[n]) { seen[n] = true; queue[tail++] = n } }
                if (x < gw - 1) { val n = at + 1; if (mask[n] && !seen[n]) { seen[n] = true; queue[tail++] = n } }
                if (y > 0) { val n = at - gw; if (mask[n] && !seen[n]) { seen[n] = true; queue[tail++] = n } }
                if (y < gh - 1) { val n = at + gw; if (mask[n] && !seen[n]) { seen[n] = true; queue[tail++] = n } }
            }
            val bx=l+sumX.toFloat()/tail*step; val by=t+sumY.toFloat()/tail*step
            if(tail>=45 && abs(bx-w*.5f)<w*.09f && by in h*.24f..h*.55f) { bodySumX+=bx*tail; bodySumY+=by*tail; bodyWeight+=tail }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if(debug) debugRows.append("coarse,$bx,$by,${max(bw,bh)*step/2f},$tail,$bw,$bh,$changed,,${if(changed>=1 && changed.toFloat()/tail>=.08f && tail in 1..450 && max(bw,bh).toFloat()/min(bw,bh).coerceAtLeast(1)<=2.5f && tail.toFloat()/(bw*bh)>.30f) "pass" else listOfNotNull(if(changed<1 || changed.toFloat()/tail<.08f) "motion" else null, if(tail !in 1..450) "size" else null, if(max(bw,bh).toFloat()/min(bw,bh).coerceAtLeast(1)>2.5f) "aspect" else null, if(tail.toFloat()/(bw*bh)<=.30f) "density" else null).joinToString("|")}\n")
            if (changed >= 1 && changed.toFloat() / tail >= .08f && tail in 1..450 && max(bw, bh).toFloat() / min(bw, bh).coerceAtLeast(1) <= 2.5f &&
                tail.toFloat() / (bw * bh) > .30f) {
                coarse.add(Blob(l + sumX.toFloat() / tail * step,
                    t + sumY.toFloat() / tail * step, max(bw, bh) * step / 2f, tail, (changed.toFloat()/tail * 0.5f + tail.toFloat()/(bw*bh)*0.5f)))
            }
        }
        val newBodyX=if(bodyWeight>0) bodySumX/bodyWeight else w*.5f
        val newBodyY=if(bodyWeight>0) bodySumY/bodyWeight else h*.39f
        val bodyDx=if(bodyValid && bodyWeight>0) newBodyX-bodyX else 0f
        val bodyDy=if(bodyValid && bodyWeight>0) newBodyY-bodyY else 0f
        bodyX=newBodyX; bodyY=newBodyY; bodyValid=bodyWeight>0
        candidateCount = coarse.size
        // Bound expensive refinement. Prefer predicted location when a track exists.
        val prior = previous
        val age = (now-previousTime).coerceIn(0L,260L).toFloat()
        val expectedX = (prior?.x ?: 0f)+vx*age+0.5f*ax*age*age
        val expectedY = (prior?.y ?: 0f)+vy*age+0.5f*ay*age*age
        val refined = coarse.asSequence().filter { it.radius <= max(16f*w/2000f,(prior?.radius ?: 0f)*1.8f) }
            .sortedByDescending { q -> q.quality - if(prior!=null) min(2f,hypot(q.x-expectedX,q.y-expectedY)/max(30f,w*.06f)) else 0f }
            .take(32).plus(coarse.sortedByDescending { it.quality }.take(24).asSequence()).distinct().mapNotNull { q ->
                // Full resolution refinement around the coarse connected component.
                val rad = ceil(q.radius+2).toInt().coerceIn(2,24)
                val cx=q.x.toInt(); val cy=q.y.toInt()
                var n=0; var sx=0.0; var sy=0.0; var sw=0.0; var edge=0
                for (yy in max(t,cy-rad)..min(b-1,cy+rad)) for(xx in max(l,cx-rad)..min(r-1,cx+rad)) {
                    if (!bright(xx,yy)) continue
                    val off=((yy-t)*rw+xx-l)*3
                    val weight=(min(rgb[off].toInt() and 255,min(rgb[off+1].toInt() and 255,rgb[off+2].toInt() and 255))-174).toDouble()
                    n++; sw+=weight; sx+=(xx+0.5)*weight; sy+=(yy+0.5)*weight
                    if(abs(xx-cx)==rad || abs(yy-cy)==rad) edge++
                }
                if(n==0 || edge>max(5,n/3)) null else {
                    val rr=sqrt(n/PI).toFloat()
                    if(rr>max(14f*w/2000f,(prior?.radius ?: 0f)*1.8f)) null
                    else Blob((sx/sw).toFloat(),(sy/sw).toFloat(),rr,n,q.quality)
                }
            }.toList()
        // Native-resolution search near all surviving hypotheses and release region.
        val extra=ArrayList<Blob>()
        fun scan(cx:Int,cy:Int,rad:Int) {
            for(y in max(t+2,cy-rad)..min(b-3,cy+rad) step 1) for(x in max(l+2,cx-rad)..min(r-3,cx+rad) step 1) {
                if(!bright(x,y) || bright(x-2,y) || bright(x+2,y) || bright(x,y-2) || bright(x,y+2)) continue
                if(extra.any { abs(it.x-x)<4 && abs(it.y-y)<4 }) continue
                extra.add(Blob(x.toFloat(),y.toFloat(),1.5f,5,.65f))
                if(extra.size>=48) return
            }
        }
        for(q in tracks.take(5)) { val dt=(now-q.time).coerceIn(0L,200L); scan((q.blob.x+q.vx*dt).toInt(),(q.blob.y+q.vy*dt).toInt(),24) }
        if(tracks.isEmpty() || frameIndex%3==0) scan((launch[0]+launch[2])*w/200,(launch[1]+launch[3])*h/200,40)
        val candidates=(refined+extra).distinctBy { (it.x.toInt()/3)*10000+it.y.toInt()/3 }
        candidateCount=candidates.size
        val prev=previous
        val dtTrack=(now-previousTime).coerceIn(1L,260L).toFloat()
        val scale=w/2000f
        val next=ArrayList<Track>()
        for(tr in tracks) {
            val dt=(now-tr.time).toFloat()
            if(dt<=0 || dt>180) continue
            val ex=tr.blob.x+tr.vx*dt; val ey=tr.blob.y+tr.vy*dt
            val gate=if(tr.hits<2) 12f+dt*3.5f*scale else 22f*scale+hypot(tr.vx,tr.vy)*dt*.7f
            val matches=candidates.mapNotNull { q ->
                val dx=q.x-tr.blob.x; val dy=q.y-tr.blob.y; val dist=hypot(dx,dy)
                val err=if(tr.hits<2) dist else hypot(q.x-ex,q.y-ey)
                if(dist<2f*scale || err>gate || abs(q.radius-tr.blob.radius)>max(5f*scale,tr.blob.radius)) null
                else {
                    val progress=hypot(q.x-tr.startX,q.y-tr.startY)
                    val separation=hypot(q.x-bodyX,q.y-bodyY)
                    val relative=hypot(dx-bodyDx,dy-bodyDy)
                    val independent=if(relative>4f*scale) tr.independent+1 else (tr.independent-1).coerceAtLeast(0)
                    val bodyPenalty=if(bodyValid && separation<45f*scale) 22f else 0f
                    val score=(tr.score*.55f+q.quality*15f+18f-err/gate*14f+min(18f,progress/scale*.3f)+min(12f,independent*4f)-bodyPenalty).coerceIn(0f,100f)
                    Track(q,now,dx/dt,dy/dt,tr.hits+1,score,tr.startX,tr.startY,independent,tr.id)
                }
            }.sortedByDescending { it.score }.take(2)
            next.addAll(matches)
            if(matches.isEmpty() && dt<=120) next.add(tr.copy(score=tr.score-8))
        }
        for(q in candidates) if(q.x in min(launch[0],launch[2])*w/100f..max(launch[0],launch[2])*w/100f && q.y in min(launch[1],launch[3])*h/100f..max(launch[1],launch[3])*h/100f) next.add(Track(q,now,0f,0f,1,q.quality*15f,q.x,q.y,id=++nextId))
        tracks=next.sortedByDescending { it.score }.distinctBy { Triple(it.blob.x.toInt()/4,it.blob.y.toInt()/4,it.hits.coerceAtMost(3)) }.take(16)
        val winner=tracks.filter { (activeId==0L || it.id==activeId) && it.time==now && it.hits>=3 && it.independent>=2 && hypot(it.blob.x-it.startX,it.blob.y-it.startY)>=18f*scale && it.score>=48f && (!bodyValid || hypot(it.blob.x-bodyX,it.blob.y-bodyY)>35f*scale) }.maxByOrNull { it.score }
        if(debug) {
            for(q in candidates) debugRows.append("candidate,${q.x},${q.y},${q.radius},${q.pixels},,,,${q.quality},${if(q==winner?.blob) "selected" else "not_selected"}\n")
            for(tr in tracks) debugTracks.append("${tr.id},${tr.blob.x},${tr.blob.y},${tr.blob.radius},${tr.vx},${tr.vy},${tr.hits},${tr.independent},${tr.score},${tr.time},${tr==winner}\n")
        }
        val selected=winner?.blob
        if(winner!=null) { activeId=winner.id; confidence=winner.score.toInt().coerceIn(0,100) }
        candidateTime=now
        lastCandidates = candidates
        if (selected == null) {
            missing++
            if (prev != null && confirmed && now-lastSeen <= 70L) {
                val dt=(now-previousTime).toFloat()
                predictedPoint=Point((prev.x+vx*dt+0.5f*ax*dt*dt).coerceIn(0f,w-1f),(prev.y+vy*dt+0.5f*ay*dt*dt).coerceIn(0f,h-1f),(prev.radius+radiusRate*dt).coerceAtLeast(0.5f))
                confidence=(confidence-15).coerceAtLeast(0)
                status="예측 위치 · 누락 $missing · 신뢰 $confidence"
                return false
            }
            if (now-lastSeen >= 70L || (prev == null && missing >= 3)) { activeId=0L; previous = null; previousTime = 0L; vx = 0f; vy = 0f; ax=0f; ay=0f; radiusRate=0f; streak=0; confirmed=false; confidence=0 }
            if (now - lastSeen >= 650L) locked = false
            status = when {
                brightPixels == 0 -> "공 미검출: 밝은 픽셀 0"
                candidates.isEmpty() -> "공 미검출: 밝은 픽셀 ${brightPixels}, 후보 0"
                else -> "공 미검출: 후보 ${candidates.size}, 추적 연결 실패"
            }
            return false
        }
        streak=winner?.hits ?: 0
        confirmed = streak >= 3
        point = Point(selected.x, selected.y, selected.radius)
        missing = 0
        lastSeen = now
        val tl = min(target[0], target[2]) * w / 100f
        val tr = max(target[0], target[2]) * w / 100f
        val tt = min(target[1], target[3]) * h / 100f
        val tb = max(target[1], target[3]) * h / 100f
        fun touches(q: Blob): Boolean {
            val nearX = q.x.coerceIn(tl, tr)
            val nearY = q.y.coerceIn(tt, tb)
            return hypot(q.x - nearX, q.y - nearY) <= q.radius
        }
        var crossed = false
        var predicted = false
        val dt = now - previousTime
        if (prev != null && dt in 1..160 && hypot(selected.x-prev.x,selected.y-prev.y)<max(30f,dt*4f)) {
            if (!touches(prev)) {
                for (k in 1..8) {
                    val f = k / 8f
                    val point = Blob(prev.x + (selected.x - prev.x) * f,
                        prev.y + (selected.y - prev.y) * f, selected.radius, selected.pixels)
                    if (touches(point)) { crossed = true; break }
                }
            }
            if (timingMs < 0 && !touches(selected) && !crossed) {
                val vx = (selected.x - prev.x) / dt.toFloat()
                val vy = (selected.y - prev.y) / dt.toFloat()
                val speed = hypot(vx, vy)
                if (speed in .08f..20f) {
                    for (ms in 10..(-timingMs).coerceIn(0, 150) step 10) {
                        val ahead = Blob(selected.x + vx * ms + .5f*ax*ms*ms, selected.y + vy * ms + .5f*ay*ms*ms,
                            (selected.radius+radiusRate*ms).coerceAtLeast(.5f), selected.pixels)
                        if (touches(ahead)) { predicted = true; break }
                    }
                }
            }
        }
        contact = confirmed && winner != null && (touches(selected) || crossed)
        val trigger = confirmed && !locked && prev != null && now - lastTrigger >= 650L &&
            contact
        if (prev != null && dt in 1..120) {
            val nx=(selected.x-prev.x)/dtTrack; val ny=(selected.y-prev.y)/dtTrack
            ax=.8f*ax+.2f*((nx-vx)/dtTrack).coerceIn(-.08f,.08f)
            ay=.8f*ay+.2f*((ny-vy)/dtTrack).coerceIn(-.08f,.08f)
            radiusRate=.7f*radiusRate+.3f*(selected.radius-prev.radius)/dtTrack
            vx=.35f*vx+.65f*nx; vy=.35f*vy+.65f*ny
        } else { ax=0f; ay=0f; radiusRate=0f; vx=winner?.vx ?: 0f; vy=winner?.vy ?: 0f }
        // Diagnostic forecast only: first future overlap with the configured timing region.
        // This is not a calibrated strike probability or an actual swing command.
        if (confirmed && prev != null && dt in 1..100 && !contact && hypot(vx,vy) > .08f) {
            for (ms in 1..250) {
                val f=ms.toFloat()
                val x=selected.x+vx*f+.5f*ax*f*f
                val y=selected.y+vy*f+.5f*ay*f*f
                val rad=(selected.radius+radiusRate*f).coerceAtLeast(.5f)
                if (!x.isFinite() || !y.isFinite() || x !in 0f..w.toFloat() || y !in 0f..h.toFloat()) break
                if (hypot(x-x.coerceIn(tl,tr),y-y.coerceIn(tt,tb))<=rad) {
                    arrival=Arrival(f,x,y,rad); break
                }
            }
        }
        previous = selected
        previousTime = now
        if (trigger) {
            locked = true
            lastTrigger = now
            count++
            status = if (predicted && !contact) "예측 타격 ${count}회" else "접촉 타격 ${count}회"
        } else {
            status = "공 후보 ${candidates.size} · 추적 (${selected.x.toInt()}, ${selected.y.toInt()})" +
                (if (contact) " · 접촉" else "") + " · 신뢰 $confidence · ${if(confirmed) "확정" else "검증"}"
        }
        return trigger
        } finally { processingMs = (System.nanoTime()-started)/1_000_000f }
    }
}
