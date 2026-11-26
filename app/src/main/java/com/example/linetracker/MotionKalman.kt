package com.example.linetracker

/** Independent constant-velocity Kalman filters; time in milliseconds. */
class MotionKalman {
    private class Axis {
        var x=0f; var v=0f
        var a=4f; var b=0f; var c=0f; var d=.04f
        fun reset(x0:Float,v0:Float) { x=x0; v=v0; a=4f; b=0f; c=0f; d=.04f }
        fun update(z:Float,t:Float) {
            x+=v*t
            val q=.0000004f
            val aa=a+t*(b+c)+t*t*d+q*t*t*t*t/4f
            val bb=b+t*d+q*t*t*t/2f
            val cc=c+t*d+q*t*t*t/2f
            val dd=d+q*t*t
            val k=aa/(aa+2.25f); val l=cc/(aa+2.25f)
            val e=z-x; x+=k*e; v+=l*e
            a=(1-k)*aa; b=(1-k)*bb; c=cc-l*aa; d=dd-l*bb
        }
    }
    private val x=Axis(); private val y=Axis()
    val vx get()=x.v; val vy get()=y.v
    fun reset(px:Float,py:Float,vx:Float,vy:Float) { x.reset(px,vx); y.reset(py,vy) }
    fun update(px:Float,py:Float,dt:Float) { x.update(px,dt); y.update(py,dt) }
}
