package com.example.linetracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Surface encoder using the same captured bitmap; no second MediaProjection. */
class VideoWriter(file: File, private val width: Int, private val height: Int, fps: Int, bitrate: Int = 2000000) {
    private val codec = MediaCodec.createEncoderByType("video/avc")
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val surface: android.view.Surface
    private var track = -1
    private var started = false
    private val frameDurationUs = 1000000L / fps
    private var outputFrames = 0L
    private val info = MediaCodec.BufferInfo()
    init {
        val format = MediaFormat.createVideoFormat("video/avc", width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }
    fun write(bitmap: Bitmap, trace: TraceFrame?) {
        drain(false)
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawBitmap(bitmap,null,Rect(0,0,width,height),null)
            if (trace != null) TraceOverlay.drawTrace(canvas,trace,width,height)
        } finally { surface.unlockCanvasAndPost(canvas) }
        drain(false)
    }
    private fun drain(end: Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime()+3000L
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if(end) 10000L else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || android.os.SystemClock.elapsedRealtime() >= deadline) return
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
            } else if (index >= 0) {
                val buffer = codec.getOutputBuffer(index)
                if (started && buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    buffer.position(info.offset); buffer.limit(info.offset+info.size)
                    info.presentationTimeUs = outputFrames++ * frameDurationUs
                    muxer.writeSampleData(track,buffer,info)
                }
                codec.releaseOutputBuffer(index,false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }
    fun close() {
        runCatching { codec.signalEndOfInputStream(); drain(true) }
        runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { surface.release() }
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}
