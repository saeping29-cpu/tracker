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
    private var candidateTime = 0L
    var candidateCount = 0; private set
    var contact = false; private set
    private var previous: Blob? = null
    var launch = intArrayOf(35,25,65,55)
    fun reset() { previous=null; predictedPoint=null; confidence=0; confirmed=false; streak=0; candidateTime=0L; priorMask=null; lastCandidates=emptyList(); point=null; missing=0; vx=0f; vy=0f; previousTime=0L; locked=false; contact=false }

    private var priorMask: BooleanArray? = null
    private var spareMask = BooleanArray(0)
    private var seenBuffer = BooleanArray(0)
    private var queueBuffer = IntArray(0)
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
        point = null; predictedPoint=null; candidateCount = 0; contact = false
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
        val step = 1
        val gw = max(1, (r - l + step - 1) / step)
        val gh = max(1, (b - t + step - 1) / step)
        val mask = if (spareMask.size == gw*gh) spareMask else BooleanArray(gw*gh)
        mask.fill(false)
        val plane = image.planes[0]
        val buffer = plane.buffer
        var brightPixels = 0
        for (y in 0 until gh) for (x in 0 until gw) {
            val px = l + x * step
            val py = t + y * step
            if (px >= r || py >= b) continue
            val offset = py * plane.rowStride + px * plane.pixelStride
            if (offset < 0 || offset + 2 >= buffer.limit()) continue
            val red = buffer.get(offset).toInt() and 255
            val green = buffer.get(offset + 1).toInt() and 255
            val blue = buffer.get(offset + 2).toInt() and 255
            val low = min(red, min(green, blue))
            val high = max(red, max(green, blue))
            val isBright = low >= 165 && high - low <= 75
            mask[y * gw + x] = isBright
            if (isBright) brightPixels++
        }
        val oldMask = priorMask
        priorMask = mask
        spareMask = oldMask ?: BooleanArray(0)
        if (seenBuffer.size != mask.size) { seenBuffer = BooleanArray(mask.size); queueBuffer = IntArray(mask.size) }
        val seen = seenBuffer; seen.fill(false)
        val queue = queueBuffer
        val candidates = ArrayList<Blob>()
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
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (changed >= 1 && changed.toFloat() / tail >= .18f && tail in 2..1800 && max(bw, bh).toFloat() / min(bw, bh).coerceAtLeast(1) < 3f &&
                tail.toFloat() / (bw * bh) > .30f) {
                candidates.add(Blob(l + sumX.toFloat() / tail * step,
                    t + sumY.toFloat() / tail * step, max(bw, bh) * step / 2f, tail, (changed.toFloat()/tail * 0.5f + tail.toFloat()/(bw*bh)*0.5f)))
            }
        }
        candidateCount = candidates.size
        val prev = previous
        val dtTrack = (now - previousTime).coerceIn(1L, 150L).toFloat()
        val scale = w / 2000f
        val dtSeed = (now-candidateTime).coerceIn(1L, 250L).toFloat()
        val selected = if (prev == null) {
            if (now-candidateTime > 200L) null else candidates.mapNotNull { q ->
                if (q.radius > 14f*scale || q.y < h*.27f) return@mapNotNull null
                val best = lastCandidates.mapNotNull { p ->
                    val inLaunch = p.x in (min(launch[0],launch[2])*w/100f)..(max(launch[0],launch[2])*w/100f) && p.y in (min(launch[1],launch[3])*h/100f)..(max(launch[1],launch[3])*h/100f)
                    val dx=q.x-p.x; val dy=q.y-p.y; val d=hypot(dx,dy)
                    if (!inLaunch || p.radius>14f*scale || dy<2f*scale || d<3f*scale || d>dtSeed*2.8f*scale+12f*scale || abs(q.radius-p.radius)>max(3f*scale,p.radius*.8f)) null
                    else 45f + q.quality*25f + min(15f,dy/scale) - abs(dx)*.08f/scale - q.radius/scale
                }.maxOrNull()
                if(best==null) null else q to best
            }.maxByOrNull { it.second }?.also { confidence=it.second.toInt().coerceIn(0,100) }?.first
        } else {
            val px=prev.x+vx*dtTrack; val py=prev.y+vy*dtTrack
            val gate=max(18f*scale, (24f+missing*12f)*scale+hypot(vx,vy)*dtTrack*.65f)
            candidates.mapNotNull { q ->
                val dx=q.x-prev.x; val dy=q.y-prev.y; val d=hypot(dx,dy)
                val err=hypot(q.x-px,q.y-py)
                val speed=hypot(vx,vy)
                val cosine=if(speed>.04f && d>1f) (dx*vx+dy*vy)/(d*speed) else 1f
                if(d<1.5f*scale || dy< -5f*scale || err>gate || cosine<-.15f || q.radius>max(14f*scale,prev.radius*1.8f) || abs(q.radius-prev.radius)>max(4f*scale,prev.radius*.85f)) null
                else q to (100f-err/gate*42f-abs(q.radius-prev.radius)/max(2f,prev.radius)*12f-(1f-cosine)*12f+q.quality*8f)
            }.filter { it.second>=48f }.maxByOrNull { it.second }?.also { confidence=it.second.toInt().coerceIn(0,100) }?.first
        }
        candidateTime=now
        lastCandidates = candidates
        if (selected == null) {
            missing++
            if (prev != null && confirmed && missing <= 3 && now-lastSeen <= 260L) {
                val dt=(now-previousTime).toFloat()
                predictedPoint=Point((prev.x+vx*dt).coerceIn(0f,w-1f),(prev.y+vy*dt).coerceIn(0f,h-1f),prev.radius)
                confidence=(confidence-15).coerceAtLeast(0)
                status="예측 위치 · 누락 $missing · 신뢰 $confidence"
                return false
            }
            if (missing >= 3 || now-lastSeen >= 260L) { previous = null; previousTime = 0L; vx = 0f; vy = 0f; streak=0; confirmed=false; confidence=0 }
            if (now - lastSeen >= 650L) locked = false
            status = when {
                brightPixels == 0 -> "공 미검출: 밝은 픽셀 0"
                candidates.isEmpty() -> "공 미검출: 밝은 픽셀 ${brightPixels}, 후보 0"
                else -> "공 미검출: 후보 ${candidates.size}, 추적 연결 실패"
            }
            return false
        }
        streak++
        confirmed = streak >= 2
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
        if (prev != null && dt in 1..160) {
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
                        val ahead = Blob(selected.x + vx * ms, selected.y + vy * ms,
                            selected.radius, selected.pixels)
                        if (touches(ahead)) { predicted = true; break }
                    }
                }
            }
        }
        contact = confirmed && (touches(selected) || crossed)
        val trigger = confirmed && !locked && prev != null && now - lastTrigger >= 650L &&
            (contact || (timingMs < 0 && predicted))
        if (prev != null) { val nx=(selected.x-prev.x)/dtTrack; val ny=(selected.y-prev.y)/dtTrack; vx=if(streak<=2) nx else .35f*vx+.65f*nx; vy=if(streak<=2) ny else .35f*vy+.65f*ny }
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
    }
}
