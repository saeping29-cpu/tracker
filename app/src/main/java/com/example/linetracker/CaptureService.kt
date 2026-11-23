package com.example.linetracker

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import kotlin.math.abs

class CaptureService : Service() {
    private val channel = "tracking"
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var count = 0
    private var lastState = false
    private val tracker = BallTracker()
    private var target = intArrayOf(45, 48, 55, 52)
    private var region = intArrayOf(30,40,70,60)
    private var inactiveFrames = 0
    private var lastCountAt = 0L
    private var processing = false
    private var tapX = 50
    private var tapY = 50
    private var screenWidth = 0
    private var screenHeight = 0
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(channel, "추적 상태", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(msg: String): Notification = Notification.Builder(this, channel).setContentTitle("LineTracker 실행 중").setContentText(msg).setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        tapX = intent?.getIntExtra("tapX", 50) ?: 50
        tapY = intent?.getIntExtra("tapY", 50) ?: 50
        region = intent?.getIntArrayExtra("region") ?: intArrayOf(30,40,70,60)
        target = intent?.getIntArrayExtra("target") ?: intArrayOf(45,48,55,52)
        startForeground(1, notification("움직임 분석 준비 중"))
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("projectionData", Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("projectionData")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
projection = mgr.getMediaProjection(
    intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        ?: Activity.RESULT_CANCELED,
    data
)

        projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels; val height = metrics.heightPixels
        screenWidth = width; screenHeight = height
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!processing) { processing = true; analyze(image); processing = false }
            } catch (_: Exception) { processing = false } finally { image.close() }
        }, handler)
        display = projection?.createVirtualDisplay("LineTrackerCapture", width, height, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
        return START_NOT_STICKY
    }
    private fun analyze(image: android.media.Image) {
        val hit = tracker.process(image, region, target, false, SystemClock.elapsedRealtime())
        if(hit) AutoTapService.tap(screenWidth * tapX / 100f, screenHeight * tapY / 100f)
        if(hit || tracker.count == 0) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, notification("${tracker.status} · ${tracker.count}회"))
    }
    override fun onDestroy() { reader?.setOnImageAvailableListener(null, null); display?.release(); reader?.close(); projection?.stop(); projection = null; super.onDestroy() }
}
