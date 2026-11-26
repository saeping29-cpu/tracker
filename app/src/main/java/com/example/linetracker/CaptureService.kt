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
    private var target = intArrayOf(45, 41, 55, 63)
    private var region = intArrayOf(25, 15, 75, 90)
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
    private var showOverlay = false
    private var saveAnalysis = true
    private val trail = ArrayList<BallTracker.Point>()
    private var lastPointTime = 0L
    private var lastOverlay = 0L
    private var gamePackage = ""
    private var pitchOnly = true
    private var battingGate = false
    private val battingDetector = BattingScreenDetector()
    private var batting = true
    private var lastBattingCheck = 0L
    private var bitrate = 2000000
    private var captureCount = 0
    private var captureFps = 0
    private var videoFpsActual = 0
    private var lastVideoCount = 0L
    private var wasRecording = false
    private data class BufferedFrame(val time: Long, val bitmap: android.graphics.Bitmap, val trace: TraceFrame)
    // Encoder-thread owned, bounded to 2 seconds at half resolution.
    private val preRoll = java.util.ArrayDeque<BufferedFrame>()
    private fun clearPreRoll() { while(preRoll.isNotEmpty()) preRoll.removeFirst().bitmap.recycle() }

    private var lastPitch = -10000L
    private var segmentStart = 0L
    private var segment = 0
    private var sessionStart = 0L
    private var video: VideoWriter? = null
    private var recordVideo = true
    private var videoFps = 15
    private val pitchDetector = PitchDetector()
    private var pitchUntil = 0L
    private val videoThread = HandlerThread("VideoEncoding")
    private lateinit var videoHandler: Handler
    private val videoBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var deep = true
    private var rawVideo: VideoWriter? = null
    private val debugPending = java.util.concurrent.atomic.AtomicInteger(0)
    private val debugThread = HandlerThread("DebugIO")
    private lateinit var debugHandler: Handler
    private var debugDropped = 0L
    private fun debugWrite(dir: java.io.File, name: String, header: String, rows: String) {
        if(rows.isEmpty()) return
        if(debugPending.incrementAndGet()>64) { debugPending.decrementAndGet(); debugDropped++; return }
        debugHandler.post { try { val f=java.io.File(dir,name); if(!f.exists()) f.writeText(header+"\n"); f.appendText(rows) } catch(e:Exception) { diagnostic("debug IO: $e") } finally { debugPending.decrementAndGet() } }
    }
    private val samples = StringBuilder()
    private var lastAnalysis = 0L
    private var lastVideo = 0L
    @Volatile private var videoFailed = false
    @Volatile private var retryAt = 0L
    private var unknownSince = 0L
    private var gateState = ""
    private var gateClosed = false
    private var lastGameSeen = -10000L
    @Volatile private var videoFrames = 0L
    private fun diagnostic(message: String) {
        Log.i("LineTracker", message)
        runCatching {
            val file = java.io.File(filesDir, "diagnostics.txt")
            if (file.length() > 512000) file.writeText("")
            file.appendText("${System.currentTimeMillis()} $session $message\n")
        }
    }
    private fun closeVideo(reason: String) {
        rawVideo?.let { runCatching { it.close() }.onFailure { e -> diagnostic("raw close: $e") } }; rawVideo=null
        val writer = video ?: return
        video = null
        runCatching { writer.close() }.onFailure { diagnostic("close error: $it") }
        diagnostic("MP4 closed: $reason; submitted=$videoFrames")
    }
    private var fpsStart = 0L
    private var fpsFrames = 0
    private var actualFps = 0

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        debugThread.start(); debugHandler=Handler(debugThread.looper)
        videoThread.start()
        videoHandler = Handler(videoThread.looper)
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
        gamePackage = intent?.getStringExtra("gamePackage") ?: ""
        pitchOnly = intent?.getBooleanExtra("pitchOnly",true) ?: true
        battingGate = intent?.getBooleanExtra("battingGate",false) ?: false
        bitrate = (intent?.getIntExtra("bitrate",2) ?: 2).coerceIn(1,4)*1000000
        tracker.launch = intent?.getIntArrayExtra("launch") ?: tracker.launch
        sessionStart = SystemClock.elapsedRealtime()
        recordVideo = intent?.getBooleanExtra("recordVideo", true) ?: true
        videoFps = (intent?.getIntExtra("videoFps",15) ?: 15).coerceIn(10,30)
        showOverlay = intent?.getBooleanExtra("showOverlay", false) ?: false
        saveAnalysis = intent?.getBooleanExtra("saveAnalysis", true) ?: true
        savePictures = intent?.getBooleanExtra("savePictures", true) ?: true
        side = if (intent?.getStringExtra("side") == "left") "left" else "right"
        gameMode = intent?.getStringExtra("gameMode")?.takeIf { it in listOf("league", "live", "practice") } ?: "league"
        pitchForm = intent?.getStringExtra("pitchForm") ?: "three_quarter"
        pitchArm = intent?.getStringExtra("pitchArm") ?: "right"
        pitcher = intent?.getStringExtra("pitcher") ?: ""
        session = java.util.UUID.randomUUID().toString()
        deep = intent?.getBooleanExtra("deepDebug",true) ?: true
        tracker.debug=deep
        if(deep) { pitchOnly=false; recordVideo=true; showOverlay=false; savePictures=false; videoFps=30; bitrate=4000000 }
        tapX = intent?.getIntExtra("tapX", 50) ?: 50
        tapY = intent?.getIntExtra("tapY", 50) ?: 50
        region = intent?.getIntArrayExtra("region") ?: region
        target = intent?.getIntArrayExtra("target") ?: target
        timingMs = (intent?.getIntExtra("timingMs", 0) ?: 0).coerceIn(-150, 150)
        if(deep) {
            val dir=java.io.File(filesDir,"training/$session").apply { mkdirs() }
            val settings=org.json.JSONObject().put("session",session).put("side",side).put("pitch_arm",pitchArm).put("pitch_form",pitchForm).put("roi",org.json.JSONArray(region.toList())).put("target",org.json.JSONArray(target.toList())).put("launch",org.json.JSONArray(tracker.launch.toList())).put("video_fps_target",videoFps).put("overlay",false)
            debugWrite(dir,"settings.json", "",settings.toString()+"\n")
        }
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
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
            reader?.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    analyze(image)
                } catch (e: Exception) {
                    diagnostic("analysis error: $e")
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
        if (stopped) return
        val active = AutoTapService.foregroundPackage
        val allowed = gamePackage.isNotBlank() && gamePackage != packageName && AutoTapService.instance != null && active == gamePackage
        val state = if (allowed) "game" else if (active.isBlank()) "unknown" else "other:$active"
        if (state != gateState) { diagnostic("gate $gateState -> $state target=$gamePackage"); gateState = state }
        if (!allowed) {
            if (unknownSince == 0L) unknownSince = now
            // Never collect unverified screens. Keep encoder alive briefly on transient unknown state.
            if (!gateClosed && ((active.isNotBlank() && active != "com.android.systemui") || now - lastGameSeen >= 1500L)) {
                gateClosed = true
                tracker.reset(); trail.clear(); lastPitch = -10000L
                pitchDetector.reset(); pitchUntil = 0L
                videoHandler.post { closeVideo("app gate $state"); clearPreRoll(); wasRecording=false }
                mainHandler.post { if (!stopped) AutoTapService.instance?.hideTrace() }
            }
            if (now-lastNotify > 800L) {
                lastNotify = now
                mainHandler.post { if (!stopped) showStatus("대기: $state / 대상 $gamePackage") }
            }
            return
        }
        unknownSince = 0L
        gateClosed = false
        lastGameSeen = now
        captureCount++
        if (now-lastAnalysis < 16L) return
        lastAnalysis = now
        val scene = battingDetector.observe(image)
        batting = scene.batting
        if (scene.cut || !batting) {
            tracker.reset(); pitchDetector.reset(); trail.clear(); pitchUntil=0L
            if (scene.cut) diagnostic("scene cut: reset")
            if (!deep && (wasRecording || scene.cut)) videoHandler.post { closeVideo("scene ended"); clearPreRoll(); wasRecording=false }
        }
        val hit = if (batting && !scene.cut) tracker.process(image, region, target, false, now, timingMs) else false
        frames++
        if(deep) {
            val dir=java.io.File(filesDir,"training/$session").apply { mkdirs() }
            val prefix="$frames,${image.timestamp},"
            val p=tracker.point; val pred=tracker.predictedPoint
            debugWrite(dir,"frames.csv","frame,image_ns,elapsed_ms,batting,cut,x,y,radius,pred_x,pred_y,pred_radius,track_id,confidence_score,confirmed,vx_px_ms,vy_px_ms,ax_px_ms2,ay_px_ms2,radius_rate,tracker_ms,debug_dropped,heap_bytes", "$prefix${now-sessionStart},$batting,${scene.cut},${p?.x ?: ""},${p?.y ?: ""},${p?.radius ?: ""},${pred?.x ?: ""},${pred?.y ?: ""},${pred?.radius ?: ""},${tracker.trackId},${tracker.confidence},${tracker.confirmed},${tracker.velocityX},${tracker.velocityY},${tracker.ax},${tracker.ay},${tracker.radiusRate},${if(batting && !scene.cut) tracker.processingMs else 0f},$debugDropped,${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()}\n")
            if(batting && !scene.cut) {
                debugWrite(dir,"candidates.csv","frame,image_ns,stage,x,y,radius,pixels,bw,bh,changed,quality,result",tracker.debugRows.lineSequence().filter{it.isNotEmpty()}.joinToString("") { prefix+it+"\n" })
                debugWrite(dir,"tracks.csv","frame,image_ns,id,x,y,radius,vx,vy,hits,independent,score,track_time,selected",tracker.debugTracks.lineSequence().filter{it.isNotEmpty()}.joinToString("") { prefix+it+"\n" })
            }
        }
        fpsFrames++
        if (fpsStart == 0L) fpsStart = now
        if (now-fpsStart >= 1000L) { actualFps = (fpsFrames*1000L/(now-fpsStart)).toInt(); captureFps=(captureCount*1000L/(now-fpsStart)).toInt(); videoFpsActual=((videoFrames-lastVideoCount)*1000L/(now-fpsStart)).toInt(); lastVideoCount=videoFrames; captureCount=0; fpsFrames=0; fpsStart=now }
        val rawPoint = tracker.point
        if(rawPoint!=null) lastPitch=now
        val confirmed = runCatching { pitchDetector.update(image, if(batting && tracker.confirmed) rawPoint else null, now) }.getOrElse { diagnostic("pitch detector error: $it"); false }
        if (confirmed && batting) pitchUntil = now + 1100L
        val pitchActive = batting && now <= pitchUntil
        val point = if(batting && tracker.confirmed) rawPoint else null
        val validContact = batting && tracker.confirmed && tracker.contact
        if (validContact) pitchUntil = minOf(pitchUntil, now+100L)
        
        if (now - lastPointTime > 70L || !tracker.confirmed) trail.clear()
        if (point != null && tracker.confirmed) { trail.add(point); lastPointTime = now; if (trail.size > 30) trail.removeAt(0) }
        val trace = TraceFrame(image.width,image.height,region,target,point,trail.toList(),"${if(now<=pitchUntil) "PITCH" else "WAIT"} / ${tracker.status} / ${actualFps}fps",validContact,frames,tracker.candidateCount,tracker.launch,if(batting && tracker.confirmed) tracker.predictedPoint else null,batting && tracker.confirmed)
        if (showOverlay && now-lastOverlay >= 16L) {
            lastOverlay=now
            mainHandler.post { if (!stopped) AutoTapService.instance?.showTrace(trace) }
        }
        if (now - lastSample >= 16L) {
            lastSample = now
            val file = java.io.File(filesDir, "samples_v4.csv")
            if (!file.exists()) file.appendText("session,game_mode,side,pitch_form,pitch_arm,pitcher,frame,elapsed_ms,image_timestamp_ns,width,height,roi,target,candidate_status,contact_candidate,x,y,radius,candidates,contact,overlay_enabled\n")
            val status = tracker.status.replace('"', '\'').replace('\n', ' ')
            val safePitcher = pitcher.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')
            samples.append("$session,$gameMode,$side,$pitchForm,$pitchArm,\"$safePitcher\",$frames,${now-sessionStart},${image.timestamp},${image.width},${image.height},${region.joinToString(";")},${target.joinToString(";")},\"$status\",${hit && tracker.confirmed},${point?.x ?: ""},${point?.y ?: ""},${point?.radius ?: ""},${tracker.candidateCount},${validContact},$showOverlay\n")
            if (samples.length >= 32768) { file.appendText(samples.toString()); samples.clear() }
        }
        val pictureDue = savePictures && (!pitchOnly || now <= pitchUntil) && now-lastPicture >= 150L && savedPictures < 300
        val videoDue = recordVideo && (!pitchOnly || batting) && now >= retryAt && now-lastVideo >= 1000L/videoFps && videoBusy.compareAndSet(false,true)
        if (pictureDue || videoDue) {
            if (pictureDue) lastPicture = now
            val dir = java.io.File(filesDir, "training/$session").apply { mkdirs() }
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val bitmap = android.graphics.Bitmap.createBitmap(paddedWidth, image.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                plane.buffer.rewind()
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                try {
                    if (videoDue) {
                        lastVideo = now
                        val activePitch = !pitchOnly || pitchActive
                        val copy = if(deep) requireNotNull(cropped.copy(android.graphics.Bitmap.Config.ARGB_8888,false)) else android.graphics.Bitmap.createScaledBitmap(cropped, (cropped.width/4)*2, (cropped.height/4)*2, true)
                        val debugFrame=frames; val imageNs=image.timestamp
                        videoHandler.post {
                            try {
                                if (!activePitch) {
                                    if(wasRecording) closeVideo("pitch ended")
                                    wasRecording=false
                                    preRoll.addLast(BufferedFrame(now,copy,trace))
                                    while(preRoll.size>videoFps*2 || (preRoll.isNotEmpty() && now-preRoll.peekFirst().time>2000L)) preRoll.removeFirst().bitmap.recycle()
                                } else {
                                    if(video!=null && now-segmentStart>=30000L) closeVideo("segment")
                                    if(video==null) {
                                        segmentStart=now; segment++
                                        video=VideoWriter(java.io.File(dir,"analysis_$segment.mp4"),copy.width,copy.height,videoFps,bitrate)
                                        if(deep) rawVideo=VideoWriter(java.io.File(dir,"raw_$segment.mp4"),copy.width,copy.height,videoFps,bitrate)
                                        while(preRoll.isNotEmpty()) {
                                            val f=preRoll.removeFirst()
                                            try { video?.write(f.bitmap,f.trace); videoFrames++ } finally { f.bitmap.recycle() }
                                        }
                                    }
                                    rawVideo?.write(copy,null)
                                    video?.write(copy,trace)
                                    if(deep) { val map=java.io.File(dir,"video_index.csv"); if(!map.exists()) map.writeText("segment,submitted_global,frame,image_ns,elapsed_ms\n"); map.appendText("$segment,$videoFrames,$debugFrame,$imageNs,${now-sessionStart}\n") }
                                    videoFrames++; wasRecording=true; videoFailed=false
                                }
                            } catch(e:Exception) { videoFailed=true; retryAt=now+3000L; diagnostic("MP4 error: $e"); closeVideo("encoder error"); clearPreRoll() }
                            finally { if(activePitch && !copy.isRecycled) copy.recycle(); videoBusy.set(false) }
                        }
                    }
                    if (pictureDue) {
                    java.io.File(dir, "${now}_${frames}.jpg").outputStream().use { cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                    if (saveAnalysis) {
                        val annotated = cropped.copy(android.graphics.Bitmap.Config.ARGB_8888,true)
                        if (annotated != null) try {
                            TraceOverlay.drawTrace(android.graphics.Canvas(annotated),trace,image.width,image.height)
                            val out = java.io.File(dir,"analysis").apply { mkdirs() }
                            java.io.File(out,"${now}_${frames}.jpg").outputStream().use { annotated.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,it) }
                        } finally { annotated.recycle() }
                    }
                    savedPictures++
                    }
                } finally { if (cropped !== bitmap) cropped.recycle() }
            } finally { bitmap.recycle() }
        }
        if (now - lastNotify > 800L) {
            lastNotify = now
            diagnostic("perf tracker_ms=${tracker.processingMs} analysis_ms=${SystemClock.elapsedRealtime()-now} fps=$actualFps capture=$captureFps video=$videoFpsActual candidates=${tracker.candidateCount}")
            val message = "수집 $frames / 캡처 $captureFps · 추적 $actualFps · 영상 $videoFpsActual fps / 사진 $savedPictures / MP4 $videoFrames 프레임 / ${if(videoFailed) "오류" else if(recordVideo) "ON" else "OFF"} · ${tracker.status} · 자동 터치 OFF"
            mainHandler.post { if (!stopped) showStatus(message) }
        }
    }

    override fun onDestroy() {
        stopped = true
        AutoTapService.instance?.hideTrace()
        for (action in pendingTaps) tapHandler.removeCallbacks(action)
        pendingTaps.clear()
        reader?.setOnImageAvailableListener(null, null)
        display?.release(); display = null
        val oldReader = reader; reader = null
        workerHandler?.post {
            if (samples.isNotEmpty()) { java.io.File(filesDir,"samples_v4.csv").appendText(samples.toString()); samples.clear() }
            oldReader?.close()
            debugThread.quitSafely()
            videoHandler.post { closeVideo("service stopped"); clearPreRoll(); videoThread.quitSafely() }
        }
        if (workerHandler == null) { videoThread.quitSafely(); debugThread.quitSafely() }
        projection?.stop(); projection = null
        workerThread?.quitSafely(); workerThread = null
        workerHandler = null
        super.onDestroy()
    }
}
