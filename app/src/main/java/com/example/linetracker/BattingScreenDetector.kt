package com.example.linetracker
import android.media.Image
import kotlin.math.abs
class BattingScreenDetector {
    data class Scene(val batting:Boolean,val cut:Boolean)
    private val prior=IntArray(96)
    private var initialized=false
    private var votes=0
    fun observe(image:Image):Scene {
        val p=image.planes[0]; val b=p.buffer
        fun rgb(x:Float,y:Float):Int {
            val i=(y*image.height).toInt()*p.rowStride+(x*image.width).toInt()*p.pixelStride
            if(i+2>=b.limit()) return 0
            return ((b.get(i).toInt() and 255) shl 16) or ((b.get(i+1).toInt() and 255) shl 8) or (b.get(i+2).toInt() and 255)
        }
        var green=0
        for(y in 0 until 12) for(x in 0 until 24) {
            val c=rgb(.90f+x*.055f/24,.085f+y*.055f/12)
            val r=c shr 16; val g=(c shr 8) and 255; val bl=c and 255
            if(g>r+20 && g>bl+15 && g>85) green++
        }
        var changed=0
        for(y in 0 until 8) for(x in 0 until 12) {
            val c=rgb(.08f+x*.84f/12,.18f+y*.70f/8)
            val v=((c shr 16)+((c shr 8) and 255)+(c and 255))/3
            val j=y*12+x
            if(initialized && abs(v-prior[j])>45) changed++
            prior[j]=v
        }
        val cut=initialized && changed>=42
        initialized=true
        votes=if(cut) 0 else if(green>=23) (votes+1).coerceAtMost(3) else (votes-2).coerceAtLeast(0)
        return Scene(votes>=2,cut)
    }
}
