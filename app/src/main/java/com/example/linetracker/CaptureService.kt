package com.example.linetracker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager

class CaptureService : Service() {
    private val channel = "tracking"
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val tracker = BallTracker()
    private var target = intArrayOf(45, 48, 55, 52)
    private var region = intArrayOf(30, 40, 70, 60)
    private var timingMs = 0
    private var tapX = 50
    private var tapY = 50
    private var screenWidth = 0
    private var screenHeight = 0
    private var lastNotify = 0L
    private var tapRequests = 0
    private var tapAccepted = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val tapHandler = Handler(Looper.getMainLooper())
    private val pendingTaps = mutableSetOf<Runnable>()
    @Volatile private var stopped = false
    private var side = "right"
    private var gameMode = "league"
    private var pitchForm = "three_quarter"
    private var pitchArm = "right"
    private var pitcher = ""
    private var session = ""
    private var frames = 0L
    private var lastSample = 0L
    private var lastPicture = 0L
    private var savedPictures = 0
    private var savePictures = true

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(channel, "추적 상태", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(msg: String) = Notification.Builder(this, channel)
        .setContentTitle("LineTracker 실행 중")
        .setContentText(msg)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setOngoing(true)
        .build()

    private fun showStatus(msg: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, notification(msg))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        stopped = false
        savePictures = intent?.getBooleanExtra("savePictures", true) ?: true
        side = if (intent?.getStringExtra("side") == "left") "left" else "right"
        gameMode = intent?.getStringExtra("gameMode")?.takeIf { it in listOf("league", "live", "practice") } ?: "league"
        pitchForm = intent?.getStringExtra("pitchForm") ?: "three_quarter"
        pitchArm = intent?.getStringExtra("pitchArm") ?: "right"
        pitcher = intent?.getStringExtra("pitcher") ?: ""
        session = java.util.UUID.randomUUID().toString()
        tapX = intent?.getIntExtra("tapX", 50) ?: 50
        tapY = intent?.getIntExtra("tapY", 50) ?: 50
        region = intent?.getIntArrayExtra("region") ?: region
        target = intent?.getIntArrayExtra("target") ?: target
        timingMs = (intent?.getIntExtra("timingMs", 0) ?: 0).coerceIn(-150, 150)
        startForeground(1, notification("화면 캡처 준비 중"))
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("projectionData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("projectionData")
        }
        if (data == null) {
            showStatus("캡처 권한 데이터 없음")
            stopSelf()
            return START_NOT_STICKY
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection = mgr.getMediaProjection(
                intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED,
                data
            )
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, mainHandler)
            // Use physical display size so capture coordinates match landscape game coordinates.
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            workerThread = HandlerThread("BallAnalysis", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
            workerHandler = Handler(workerThread!!.looper)
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    analyze(image)
                } catch (e: Exception) {
                    Log.e("LineTracker", "공 분석 오류", e)
                    mainHandler.post { if (!stopped) showStatus("분석 오류: ${e.javaClass.simpleName}") }
                } finally {
                    image.close()
                }
            }, workerHandler)
            display = projection?.createVirtualDisplay(
                "LineTrackerCapture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, workerHandler
            )
            showStatus("캡처 시작 ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.e("LineTracker", "캡처 시작 오류", e)
            showStatus("캡처 시작 오류: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun analyze(image: Image) {
        val now = SystemClock.elapsedRealtime()
        val hit = tracker.process(image, region, target, false, now, timingMs)
        frames++
        if (now - lastSample >= 33L) {
            lastSample = now
            val file = java.io.File(filesDir, "samples_v3.csv")
            if (!file.exists()) file.appendText("session,game_mode,side,pitch_form,pitch_arm,pitcher,frame,elapsed_ms,image_timestamp_ns,width,height,roi,target,candidate_status,contact_candidate\n")
            val status = tracker.status.replace('"', '\'').replace('\n', ' ')
            val safePitcher = pitcher.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')
            file.appendText("$session,$gameMode,$side,$pitchForm,$pitchArm,\"$safePitcher\",$frames,$now,${image.timestamp},${image.width},${image.height},${region.joinToString(";")},${target.joinToString(";")},\"$status\",$hit\n")
        }
        if (savePictures && now - lastPicture >= 100L && savedPictures < 3000) {
            lastPicture = now
            val dir = java.io.File(filesDir, "training/$session").apply { mkdirs() }
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val bitmap = android.graphics.Bitmap.createBitmap(paddedWidth, image.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                plane.buffer.rewind()
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                try {
                    java.io.File(dir, "${now}_${frames}.jpg").outputStream().use { cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                    savedPictures++
                } finally { if (cropped !== bitmap) cropped.recycle() }
            } finally { bitmap.recycle() }
        }
        if (now - lastNotify > 800L) {
            lastNotify = now
            val message = "수집 $frames 프레임 / 사진 $savedPictures · ${tracker.status} · 자동 터치 OFF"
            mainHandler.post { if (!stopped) showStatus(message) }
        }
    }

    override fun onDestroy() {
        stopped = true
        for (action in pendingTaps) tapHandler.removeCallbacks(action)
        pendingTaps.clear()
        reader?.setOnImageAvailableListener(null, null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        workerThread?.quitSafely(); workerThread = null
        workerHandler = null
        super.onDestroy()
    }
}
