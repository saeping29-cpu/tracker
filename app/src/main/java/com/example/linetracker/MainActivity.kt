package com.example.linetracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val captureRequest = 1001
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private lateinit var launchInputs: List<EditText>
    private lateinit var appInput: EditText
    private lateinit var pitchOnly: CheckBox
    private lateinit var battingGate: CheckBox
    private lateinit var bitrateSpinner: Spinner
    private lateinit var regionInputs: List<EditText>
    private lateinit var targetInputs: List<EditText>
    private lateinit var timingInput: EditText
    private lateinit var sideButton: Button
    private var side = "right"
    private var form = "three_quarter"
    private lateinit var formButton: Button
    private lateinit var armButton: Button
    private lateinit var pitcherInput: EditText
    private var arm = "right"
    private val forms = listOf("three_quarter", "sidearm", "underhand")
    private fun formName() = when (form) { "sidearm" -> "사이드암"; "underhand" -> "언더핸드"; else -> "스리쿼터" }
    private fun csv(value: String) = "\"" + value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""
    private var gameMode = "league"
    private val gameModes = listOf("league", "live", "practice")
    private fun gameModeName() = when (gameMode) { "live" -> "실시간 매치"; "practice" -> "연습 모드"; else -> "리그 모드" }
    private var collectOnly = true
    private val exportRequest = 1002
    private val bundleRequest = 1003
    private val mp4Request = 1004
    private var selectedVideo: java.io.File? = null
    private lateinit var pictures: CheckBox
    private lateinit var overlayCheck: CheckBox
    private lateinit var analysisCheck: CheckBox
    private lateinit var videoCheck: CheckBox
    private lateinit var fpsCheck: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Landscape UI. CaptureService also reads the current screen dimensions at start.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 28)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun label(value: String) { root.addView(TextView(this).apply { text = value; textSize = 16f; setPadding(0, 12, 0, 6) }) }
        fun field(hintText: String, default: String, signed: Boolean = false): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(default)
                inputType = if (signed) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else android.text.InputType.TYPE_CLASS_NUMBER
                root.addView(this)
            }
        }
        label("LineTracker · 좌타/우타 · 가로 화면")
        label("현재 버전: AI ${packageManager.getPackageInfo(packageName, 0).versionName}")
        sideButton = Button(this)
        root.addView(sideButton)
        sideButton.setOnClickListener {
            saveSide()
            side = if (side == "right") "left" else "right"
            prefs.edit().putString("selected_side", side).apply()
            loadSide()
        }
        form = prefs.getString("pitch_form", "three_quarter")?.takeIf { it in forms } ?: "three_quarter"
        arm = prefs.getString("pitch_arm", "right") ?: "right"
        formButton = Button(this).apply { text = "투구폼: ${formName()}" }
        root.addView(formButton)
        formButton.setOnClickListener {
            saveSide()
            form = forms[(forms.indexOf(form) + 1) % forms.size]
            prefs.edit().putString("pitch_form", form).apply()
            formButton.text = "투구폼: ${formName()}"
            loadSide()
        }
        armButton = Button(this).apply { text = if (arm == "left") "투수: 좌완" else "투수: 우완" }
        root.addView(armButton)
        armButton.setOnClickListener {
            saveSide()
            arm = if (arm == "left") "right" else "left"
            prefs.edit().putString("pitch_arm", arm).apply()
            armButton.text = if (arm == "left") "투수: 좌완" else "투수: 우완"
            loadSide()
        }
        pitcherInput = EditText(this).apply { hint = "투수 이름 (선택)"; setSingleLine(true); setText(prefs.getString("pitcher", "")) }
        root.addView(pitcherInput)
        gameMode = prefs.getString("game_mode", "league")?.takeIf { it in gameModes } ?: "league"
        root.addView(Button(this).apply {
            text = "게임 모드: ${gameModeName()}"
            setOnClickListener {
                gameMode = gameModes[(gameModes.indexOf(gameMode) + 1) % gameModes.size]
                prefs.edit().putString("game_mode", gameMode).apply()
                text = "게임 모드: ${gameModeName()}"
            }
        })
        label("자동 터치 좌표 X/Y (%)")
        xInput = field("터치 X", "50")
        yInput = field("터치 Y", "50")
        appInput = EditText(this).apply { hint = "게임 패키지 이름 (필수)"; setSingleLine(true); setText(prefs.getString("game_package", "")) }
        root.addView(appInput)
        root.addView(Button(this).apply { text = "게임 앱 선택"; setOnClickListener {
            val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).filter { it.activityInfo.packageName != packageName }.distinctBy { it.activityInfo.packageName }
            android.app.AlertDialog.Builder(this@MainActivity).setTitle("수집 대상 앱").setItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, i -> appInput.setText(apps[i].activityInfo.packageName) }.show()
        } })
        label("공 출발 영역 (%)")
        launchInputs = listOf("35","25","65","55").map { field("출발 좌표",it) }
        root.addView(Button(this).apply { text = "공 출발 영역 그리기"; setOnClickListener { openSelector("launch") } })
        pitchOnly = CheckBox(this).apply { text = "투구 후보 구간만 영상 및 사진 저장 (실험)"; isChecked = true }
        root.addView(pitchOnly)
        battingGate = CheckBox(this).apply { text = "타격 화면만 수집 (실험, 기본 OFF)"; isChecked = false }
        root.addView(battingGate)
        label("영상 비트레이트 (Mbps, 절반 해상도)")
        bitrateSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("1", "2", "4")); setSelection(1) }
        root.addView(bitrateSpinner)
        label("공 탐색 범위: 왼쪽 / 위 / 오른쪽 / 아래 (%)")
        regionInputs = listOf("왼쪽" to "38", "위" to "77", "오른쪽" to "62", "아래" to "28")
            .map { field(it.first, it.second) }
        root.addView(Button(this).apply { text = "공 탐색 범위 그리기"; setOnClickListener { openSelector("region") } })
        label("접촉 영역: 왼쪽 / 위 / 오른쪽 / 아래 (%)")
        targetInputs = listOf("왼쪽" to "45", "위" to "41", "오른쪽" to "55", "아래" to "63")
            .map { field(it.first, it.second) }
        root.addView(Button(this).apply { text = "접촉 선/면 그리기"; setOnClickListener { openSelector("target") } })
        root.addView(Button(this).apply { text = "화면에서 터치 위치 찍기"; setOnClickListener { openSelector("tap") } })
        label("타이밍 보정 (ms): 음수 = 예측으로 빠르게 / 양수 = 늦게, -150 ~ +150")
        timingInput = field("보정 ms", "0", true)
        root.addView(Button(this).apply { text = "현재 타석 설정 저장"; setOnClickListener { saveSide(); Toast.makeText(this@MainActivity, "설정 저장 완료", Toast.LENGTH_SHORT).show() } })
        root.addView(Button(this).apply { text = "자동 터치 접근성 권한 설정"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        root.addView(Button(this).apply {
            text = "학습 데이터 수집 시작 (자동 터치 없음)"
            setOnClickListener {
                if (appInput.text.toString().trim().isEmpty() || appInput.text.toString().trim() == packageName || AutoTapService.instance == null) {
                    Toast.makeText(this@MainActivity,"게임 앱 선택 및 접근성 활성화 필요",Toast.LENGTH_LONG).show(); return@setOnClickListener
                }
                collectOnly = true
                saveSide()
                // A projection permission token is single-use. Stop old session before requesting another.
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        })
        root.addView(Button(this).apply { text = "추적 중지"; setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) } })
        root.addView(Button(this).apply {
            text = "학습 데이터 CSV 내보내기"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/csv"
                    putExtra(Intent.EXTRA_TITLE, "linetracker_samples.csv")
                }, exportRequest)
            }
        })
        pictures = CheckBox(this).apply { text = "학습 화면 JPEG 저장 (최대 300장/세션, 약 6fps)"; isChecked = false }
        root.addView(pictures)
        overlayCheck = CheckBox(this).apply { text = "실시간 추적 표시 (접근성 필요; 학습 원본에 표시가 섞일 수 있음)"; isChecked = false }
        analysisCheck = CheckBox(this).apply { text = "포인터/영역/궤적 포함 분석 JPEG 저장"; isChecked = true }
        root.addView(overlayCheck); root.addView(analysisCheck)
        videoCheck = CheckBox(this).apply { text = "MP4 분석 영상 저장 (수집 시작/중지 연동)"; isChecked = true }
        fpsCheck = CheckBox(this).apply { text = "영상 30fps (기본 15fps)"; isChecked = false }
        root.addView(videoCheck); root.addView(fpsCheck)
        root.addView(Button(this).apply { text = "투구 결과 수동 기록"; setOnClickListener { recordLabel() } })
        root.addView(Button(this).apply {
            text = "전체 학습 자료 ZIP 내보내기"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, "linetracker_training.zip")
                }, bundleRequest)
            }
        })
        root.addView(Button(this).apply { text = "MP4 선택 내보내기"; setOnClickListener {
            val videos = java.io.File(filesDir,"training").walkTopDown().filter { it.isFile && it.extension == "mp4" && it.length()>0 }.sortedByDescending { it.lastModified() }.toList()
            if (videos.isEmpty()) Toast.makeText(this@MainActivity,"저장 영상 없음",Toast.LENGTH_SHORT).show()
            else android.app.AlertDialog.Builder(this@MainActivity).setItems(videos.map { "${it.parentFile?.name}/${it.name} (${it.length()/1024} KB)" }.toTypedArray()) { _, i ->
                selectedVideo=videos[i]; startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="video/mp4"; putExtra(Intent.EXTRA_TITLE,videos[i].name) },mp4Request)
            }.show()
        } })
        label("수집 중에는 직접 타격하세요. 타석은 수동 선택이며 공 후보는 아직 AI 검증 전입니다.")
        label("가로 화면 기준으로 좌표를 저장합니다. 타석/좌표 변경 시 추적을 다시 시작하세요. 음수 보정은 이동 예측이 가능한 경우에만 작동합니다.")
        side = prefs.getString("selected_side", "right") ?: "right"
        loadSide()
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun recordLabel() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun choice(title: String, values: List<String>): Spinner {
            panel.addView(TextView(this).apply { text = title })
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
                panel.addView(this)
            }
        }
        val pitch = choice("구종", listOf("미상", "직구", "슬라이더", "커브", "체인지업", "서클체인지업", "커터", "싱커", "포크", "기타"))
        val speed = EditText(this).apply { hint = "구속 km/h (선택)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; panel.addView(this) }
        val location = choice("실제 위치", listOf("미상", "상단", "우측", "하단", "좌측", "존 밖"))
        val guess = choice("게스히팅", listOf("미상/미선택", "상단", "우측", "하단", "좌측"))
        val verdict = choice("볼/스트라이크", listOf("미상", "볼", "스트라이크"))
        val swing = choice("스윙", listOf("미상", "스윙", "참음"))
        val timing = choice("타이밍", listOf("미상/해당 없음", "매우 빠름", "빠름", "조금 빠름", "좋음", "조금 느림", "느림", "매우 느림"))
        val result = choice("결과", listOf("미상", "홈런", "3루타", "2루타", "1루타", "파울", "아웃", "헛스윙", "볼", "루킹 스트라이크"))
        val frame = EditText(this).apply { hint = "해당 프레임 번호 (선택)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; panel.addView(this) }
        android.app.AlertDialog.Builder(this).setTitle("투구 결과 기록")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("취소", null).setPositiveButton("저장") { _, _ ->
                val file = java.io.File(filesDir, "labels_v3.csv")
                if (!file.exists()) file.appendText("label_elapsed_ms,game_mode,side,pitch_form,pitch_arm,pitcher,pitch,speed,location,guess,verdict,swing,timing,result,frame_hint\n")
                val values = listOf(android.os.SystemClock.elapsedRealtime().toString(), gameMode, side, form, arm, pitcherInput.text.toString(), pitch.selectedItem.toString(), speed.text.toString(), location.selectedItem.toString(), guess.selectedItem.toString(), verdict.selectedItem.toString(), swing.selectedItem.toString(), timing.selectedItem.toString(), result.selectedItem.toString(), frame.text.toString())
                file.appendText(values.joinToString(",") { csv(it) } + "\n")
                Toast.makeText(this, "기록 저장", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun key(name: String) = "${side}_${form}_${arm}_$name"
    private fun fields(fields: List<EditText>, defaults: IntArray): IntArray =
        fields.mapIndexed { index, edit -> (edit.text.toString().toIntOrNull() ?: defaults[index]).coerceIn(0, 100) }.toIntArray()

    private fun saveSide() {
        val region = fields(regionInputs, intArrayOf(38,77,62,28))
        val target = fields(targetInputs, intArrayOf(45,41,55,63))
        val tap = fields(listOf(xInput,yInput), intArrayOf(50,50))
        val timing = (timingInput.text.toString().toIntOrNull() ?: 0).coerceIn(-150, 150)
        prefs.edit().putString("game_package", appInput.text.toString().trim()).putString(key("launch"), fields(launchInputs,intArrayOf(35,25,65,55)).joinToString(",")).putString("pitcher", pitcherInput.text.toString()).putString(key("region"), region.joinToString(","))
            .putString(key("target"), target.joinToString(","))
            .putString(key("tap"), tap.joinToString(","))
            .putInt(key("timing"), timing).apply()
    }

    private fun loadSide() {
        sideButton.text = if (side == "right") "현재: 우타  (누르면 좌타)" else "현재: 좌타  (누르면 우타)"
        fun restore(name: String, inputs: List<EditText>, defaults: List<String>) {
            // For migration, right-handed mode inherits previous single-mode settings.
            val old = if (side == "right") prefs.getString(name, null) else null
            val saved = prefs.getString(key(name), prefs.getString("${side}_$name", old))?.split(",")
            inputs.forEachIndexed { index, input -> input.setText(saved?.getOrNull(index) ?: defaults[index]) }
        }
        restore("launch", launchInputs, listOf("35","25","65","55"))
        restore("region", regionInputs, listOf("38","77","62","28"))
        restore("target", targetInputs, listOf("45","41","55","63"))
        restore("tap", listOf(xInput,yInput), listOf("50","50"))
        timingInput.setText(prefs.getInt(key("timing"), prefs.getInt("${side}_timing", 0)).toString())
    }

    private fun openSelector(mode: String) {
        val service = AutoTapService.instance
        if (service == null) {
            Toast.makeText(this, "먼저 접근성 서비스를 켜주세요", Toast.LENGTH_LONG).show()
            return
        }
        service.select(mode) { result ->
            if (result != null) {
                when (mode) {
                    "launch" -> launchInputs.forEachIndexed { i,e -> result.getOrNull(i)?.let { e.setText(it.toString()) } }
                    "region" -> regionInputs.forEachIndexed { i, e -> result.getOrNull(i)?.let { e.setText(it.toString()) } }
                    "target" -> targetInputs.forEachIndexed { i, e -> result.getOrNull(i)?.let { e.setText(it.toString()) } }
                    "tap" -> { result.getOrNull(0)?.let { xInput.setText(it.toString()) }; result.getOrNull(1)?.let { yInput.setText(it.toString()) } }
                }
                saveSide()
                Toast.makeText(this, "${if(side == "right") "우타" else "좌타"} 좌표 저장 완료", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == mp4Request) {
            val uri=data?.data; val file=selectedVideo
            if(resultCode==RESULT_OK && uri!=null && file!=null) Thread {
                val result=runCatching { (contentResolver.openOutputStream(uri) ?: error("출력 실패")).use { out -> file.inputStream().use { it.copyTo(out) } } }
                runOnUiThread { Toast.makeText(this,if(result.isSuccess) "영상 저장 완료" else "영상 저장 실패",Toast.LENGTH_LONG).show() }
            }.start()
            return
        }
        if (requestCode == bundleRequest) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                Thread {
                    try {
                        val output = contentResolver.openOutputStream(uri) ?: error("출력 파일 열기 실패")
                        java.util.zip.ZipOutputStream(output).use { zip ->
                            filesDir.walkTopDown().filter { it.isFile && (it.name in listOf("samples.csv", "labels.csv", "samples_v2.csv", "labels_v2.csv", "samples_v3.csv", "labels_v3.csv", "samples_v4.csv", "diagnostics.txt") || it.relativeTo(filesDir).path.startsWith("training/")) }.forEach { file ->
                                zip.putNextEntry(java.util.zip.ZipEntry(file.relativeTo(filesDir).path))
                                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                            }
                        }
                        runOnUiThread { Toast.makeText(this, "ZIP 저장 완료", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show() } }
                }.start()
            }
            return
        }
        if (requestCode == exportRequest) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data ?: return
                try {
                    val file = java.io.File(filesDir, "samples_v4.csv")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        if (file.exists()) file.inputStream().use { it.copyTo(out) }
                    }
                    Toast.makeText(this, "CSV 내보내기 완료", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            return
        }
        if (requestCode != captureRequest || resultCode != RESULT_OK || data == null) return
        val tap = fields(listOf(xInput,yInput), intArrayOf(50,50))
        val intent = Intent(this, CaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("collectOnly", collectOnly)
            putExtra("gamePackage",appInput.text.toString().trim())
            putExtra("pitchOnly",pitchOnly.isChecked)
            putExtra("battingGate",battingGate.isChecked)
            putExtra("bitrate",listOf(1,2,4)[bitrateSpinner.selectedItemPosition])
            putExtra("launch",fields(launchInputs,intArrayOf(35,25,65,55)))
            putExtra("savePictures", pictures.isChecked)
            putExtra("showOverlay", overlayCheck.isChecked)
            putExtra("saveAnalysis", analysisCheck.isChecked)
            putExtra("recordVideo", videoCheck.isChecked)
            putExtra("videoFps", if (fpsCheck.isChecked) 30 else 15)
            putExtra("side", side)
            putExtra("gameMode", gameMode)
            putExtra("pitchForm", form)
            putExtra("pitchArm", arm)
            putExtra("pitcher", pitcherInput.text.toString())
            putExtra("projectionData", data)
            putExtra("tapX", tap[0]); putExtra("tapY", tap[1])
            putExtra("region", fields(regionInputs, intArrayOf(38,77,62,28)))
            putExtra("target", fields(targetInputs, intArrayOf(45,41,55,63)))
            putExtra("timingMs", (timingInput.text.toString().toIntOrNull() ?: 0).coerceIn(-150,150))
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
}
