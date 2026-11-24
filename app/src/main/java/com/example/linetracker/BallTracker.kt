package com.example.linetracker

import android.media.Image
import kotlin.math.*

/** Bright moving ball detector. Diagnostic status distinguishes candidate and tracking failures. */
class BallTracker {
    private data class Blob(val x: Float, val y: Float, val radius: Float, val pixels: Int)
    data class Point(val x: Float, val y: Float, val radius: Float)
    var point: Point? = null; private set
    var candidateCount = 0; private set
    var contact = false; private set
    private var previous: Blob? = null
    private var priorMask: BooleanArray? = null
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
        point = null; candidateCount = 0; contact = false
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
        val step = 2
        val gw = max(1, (r - l + step - 1) / step)
        val gh = max(1, (b - t + step - 1) / step)
        val mask = BooleanArray(gw * gh)
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
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
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
            if (changed >= 1 && changed.toFloat() / tail >= .18f && tail in 2..900 && max(bw, bh).toFloat() / min(bw, bh).coerceAtLeast(1) < 3f &&
                tail.toFloat() / (bw * bh) > .25f) {
                candidates.add(Blob(l + sumX.toFloat() / tail * step,
                    t + sumY.toFloat() / tail * step, max(bw, bh) * step / 2f, tail))
            }
        }
        candidateCount = candidates.size
        val prev = previous
        val dtTrack = (now - previousTime).coerceIn(1L, 150L).toFloat()
        val selected = if (prev == null) {
            candidates.filter { q -> lastCandidates.any { p ->
                val d = hypot(q.x-p.x,q.y-p.y)
                d >= 2f && d <= max(w,h)*.06f && abs(q.radius-p.radius) <= max(4f,p.radius)
            } }.minByOrNull { it.pixels }
        } else {
            val px = prev.x + vx*dtTrack
            val py = prev.y + vy*dtTrack
            candidates.filter { q ->
                hypot(q.x-prev.x,q.y-prev.y) >= 1.5f &&
                hypot(q.x-px,q.y-py) <= max(12f,max(w,h)*.025f + missing*8f) &&
                abs(q.radius-prev.radius) <= max(5f,prev.radius)
            }.minByOrNull { hypot(it.x-px,it.y-py) + abs(it.radius-prev.radius)*2f }
        }
        lastCandidates = candidates
        if (selected == null) {
            missing++
            if (missing >= 3) { previous = null; previousTime = 0L; vx = 0f; vy = 0f }
            if (now - lastSeen >= 650L) locked = false
            status = when {
                brightPixels == 0 -> "공 미검출: 밝은 픽셀 0"
                candidates.isEmpty() -> "공 미검출: 밝은 픽셀 ${brightPixels}, 후보 0"
                else -> "공 미검출: 후보 ${candidates.size}, 추적 연결 실패"
            }
            return false
        }
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
        contact = touches(selected) || crossed
        val trigger = !locked && prev != null && now - lastTrigger >= 650L &&
            (contact || (timingMs < 0 && predicted))
        if (prev != null) { vx = (selected.x-prev.x)/dtTrack; vy = (selected.y-prev.y)/dtTrack }
        previous = selected
        previousTime = now
        if (trigger) {
            locked = true
            lastTrigger = now
            count++
            status = if (predicted && !contact) "예측 타격 ${count}회" else "접촉 타격 ${count}회"
        } else {
            status = "공 후보 ${candidates.size} · 추적 (${selected.x.toInt()}, ${selected.y.toInt()})" +
                if (contact) " · 접촉" else ""
        }
        return trigger
    }
}
