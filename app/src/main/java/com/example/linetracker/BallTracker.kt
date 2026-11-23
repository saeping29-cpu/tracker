package com.example.linetracker

import android.media.Image
import kotlin.math.*

/** Tracks bright circular blobs and predicts arrival at the target from measured motion. */
class BallTracker {
    private data class Blob(val x: Float, val y: Float, val radius: Float, val pixels: Int)
    private var previous: Blob? = null
    private var previousTime = 0L
    private var missing = 0
    private var lastTrigger = -100000L
    private var locked = false
    private var lastSeen = 0L
    var count = 0; private set
    var status = "공 탐색 중"; private set

    fun process(image: Image, roi: IntArray, target: IntArray, lineMode: Boolean, now: Long, timingMs: Int = 0): Boolean {
        val w = image.width; val h = image.height
        val l = (min(roi[0], roi[2]) * w / 100).coerceIn(0, w - 1)
        val r = (max(roi[0], roi[2]) * w / 100).coerceIn(l + 1, w)
        val t = (min(roi[1], roi[3]) * h / 100).coerceIn(0, h - 1)
        val b = (max(roi[1], roi[3]) * h / 100).coerceIn(t + 1, h)
        val step = 2
        val gw = max(1, (r - l + step - 1) / step)
        val gh = max(1, (b - t + step - 1) / step)
        val mask = BooleanArray(gw * gh)
        val plane = image.planes[0]; val buffer = plane.buffer
        for (y in 0 until gh) for (x in 0 until gw) {
            val px = l + x * step; val py = t + y * step
            if (px >= r || py >= b) continue
            val offset = py * plane.rowStride + px * plane.pixelStride
            val red = buffer.get(offset).toInt() and 255
            val green = buffer.get(offset + 1).toInt() and 255
            val blue = buffer.get(offset + 2).toInt() and 255
            mask[y * gw + x] = min(red, min(green, blue)) >= 185 &&
                max(red, max(green, blue)) - min(red, min(green, blue)) <= 65
        }
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val candidates = ArrayList<Blob>()
        for (i in mask.indices) {
            if (!mask[i] || seen[i]) continue
            var head = 0; var tail = 1; queue[0] = i; seen[i] = true
            var sumX = 0L; var sumY = 0L; var minX = gw; var maxX = 0; var minY = gh; var maxY = 0
            while (head < tail) {
                val at = queue[head++]; val x = at % gw; val y = at / gw
                sumX += x; sumY += y
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                if (x > 0) { val n = at - 1; if (mask[n] && !seen[n]) { seen[n] = true; queue[tail++] = n } }
                if (x < gw - 1) { val n = at + 1; if (mask[n] && !seen[n]) { seen[n] = true; queue[tail++] = n } }
                if (y > 0) { val n = at - gw; if (mask[n] && !seen[n]) { seen[n] = true; queue[tail++] = n } }
                if (y < gh - 1) { val n = at + gw; if (mask[n] && !seen[n]) { seen[n] = true; queue[tail++] = n } }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (tail in 4..900 && max(bw, bh).toFloat() / min(bw, bh).coerceAtLeast(1) < 2.4f &&
                tail.toFloat() / (bw * bh) > .35f) {
                candidates.add(Blob(l + sumX.toFloat() / tail * step, t + sumY.toFloat() / tail * step,
                    max(bw, bh) * step / 2f, tail))
            }
        }
        val prev = previous
        val selected = if (prev == null) candidates.minByOrNull { it.pixels }
        else candidates.filter { hypot(it.x - prev.x, it.y - prev.y) < max(w, h) * .16f }
            .minByOrNull { hypot(it.x - prev.x, it.y - prev.y) + abs(it.radius - prev.radius) * 2 }
        if (selected == null) {
            missing++
            if (missing >= 4) previous = null
            // Require a substantial no-ball gap before rearming to avoid repeated taps in one pitch.
            if (now - lastSeen >= 650L) locked = false
            status = "공 미검출"
            return false
        }
        missing = 0; lastSeen = now
        val tl = min(target[0], target[2]) * w / 100f
        val tr = max(target[0], target[2]) * w / 100f
        val tt = min(target[1], target[3]) * h / 100f
        val tb = max(target[1], target[3]) * h / 100f
        fun touches(q: Blob): Boolean {
            val nearX = q.x.coerceIn(tl, tr); val nearY = q.y.coerceIn(tt, tb)
            return hypot(q.x - nearX, q.y - nearY) <= q.radius
        }
        var crossed = false
        var predicted = false
        val dt = now - previousTime
        if (prev != null && dt in 1..160) {
            if (!touches(prev)) {
                for (k in 1..8) {
                    val f = k / 8f
                    if (touches(Blob(prev.x + (selected.x - prev.x) * f,
                            prev.y + (selected.y - prev.y) * f, selected.radius, selected.pixels))) {
                        crossed = true; break
                    }
                }
            }
            // Negative timing: check whether current velocity reaches the target soon.
            // Reject near-static noise and do not predict more than 150 ms ahead.
            if (timingMs < 0 && !touches(selected) && !crossed) {
                val vx = (selected.x - prev.x) / dt.toFloat()
                val vy = (selected.y - prev.y) / dt.toFloat()
                val speed = hypot(vx, vy)
                if (speed >= .08f && speed <= 20f) {
                    val horizon = (-timingMs).coerceIn(0, 150)
                    for (ms in 10..horizon step 10) {
                        val ahead = Blob(selected.x + vx * ms, selected.y + vy * ms,
                            selected.radius, selected.pixels)
                        if (touches(ahead)) { predicted = true; break }
                    }
                }
            }
        }
        val contact = touches(selected) || crossed
        val trigger = !locked && prev != null && now - lastTrigger >= 650L &&
            if (timingMs < 0) (contact || predicted) else contact
        previous = selected; previousTime = now
        if (trigger) {
            locked = true; lastTrigger = now; count++
            status = if (predicted && !contact) "예측 타격 ${count}회" else "접촉 타격 ${count}회"
        } else status = "공 추적 중 (${selected.x.toInt()}, ${selected.y.toInt()})"
        return trigger
    }
}
