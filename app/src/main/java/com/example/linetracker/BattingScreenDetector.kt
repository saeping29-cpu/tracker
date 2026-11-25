package com.example.linetracker

import android.media.Image

/** Optional heuristic: green batting toggle near upper right. Not a trained classifier. */
class BattingScreenDetector {
    fun matches(image: Image): Boolean {
        val p = image.planes[0]; val b = p.buffer
        var green = 0; var total = 0
        for (y in 0 until 12) for (x in 0 until 24) {
            val px = (image.width * (.90 + x * .055 / 24)).toInt()
            val py = (image.height * (.085 + y * .055 / 12)).toInt()
            val i = py*p.rowStride+px*p.pixelStride
            if (i+2 >= b.limit()) continue
            val r=b.get(i).toInt() and 255; val g=b.get(i+1).toInt() and 255; val bl=b.get(i+2).toInt() and 255
            total++; if(g>r+20 && g>bl+15 && g>85) green++
        }
        var white=0; var n=0
        for(y in 0 until 10) for(x in 0 until 16) {
            val px=(image.width*(.46+x*.08/16)).toInt(); val py=(image.height*(.82+y*.12/10)).toInt()
            val i=py*p.rowStride+px*p.pixelStride
            if(i+2>=b.limit()) continue
            val r=b.get(i).toInt() and 255; val g=b.get(i+1).toInt() and 255; val bl=b.get(i+2).toInt() and 255
            n++; if(minOf(r,g,bl)>170 && maxOf(r,g,bl)-minOf(r,g,bl)<45) white++
        }
        return total>0 && green*100>=total*8 && n>0 && white>=3
    }
}
