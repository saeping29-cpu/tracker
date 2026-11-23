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
    private var stopped = false

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
        if (hit) {
            val x = image.width * tapX / 100f
            val y = image.height * tapY / 100f
            tapRequests++
            val action = object : Runnable {
                override fun run() {
                    pendingTaps.remove(this)
                    if (stopped) return
                    val accepted = AutoTapService.tap(x, y)
                    if (accepted) tapAccepted++
                    showStatus("타격 요청 $tapRequests · 터치 접수 $tapAccepted" +
                        if (!accepted) " · 접근성 확인" else "")
                }
            }
            mainHandler.post {
                if (!stopped) {
                    pendingTaps.add(action)
                    tapHandler.postDelayed(action, timingMs.coerceAtLeast(0).toLong())
                }
            }
        }
        if (hit || now - lastNotify > 800L) {
            lastNotify = now
            mainHandler.post {
                if (!stopped) showStatus("${tracker.status} · 요청 $tapRequests / 접수 $tapAccepted")
            }
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
