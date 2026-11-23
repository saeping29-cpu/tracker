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
    private lateinit var regionInputs: List<EditText>
    private lateinit var targetInputs: List<EditText>
    private lateinit var timingInput: EditText
    private lateinit var sideButton: Button
    private var side = "right"

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
        sideButton = Button(this)
        root.addView(sideButton)
        sideButton.setOnClickListener {
            saveSide()
            side = if (side == "right") "left" else "right"
            prefs.edit().putString("selected_side", side).apply()
            loadSide()
        }
        label("자동 터치 좌표 X/Y (%)")
        xInput = field("터치 X", "50")
        yInput = field("터치 Y", "50")
        label("공 탐색 범위: 왼쪽 / 위 / 오른쪽 / 아래 (%)")
        regionInputs = listOf("왼쪽" to "30", "위" to "40", "오른쪽" to "70", "아래" to "60")
            .map { field(it.first, it.second) }
        root.addView(Button(this).apply { text = "공 탐색 범위 그리기"; setOnClickListener { openSelector("region") } })
        label("접촉 영역: 왼쪽 / 위 / 오른쪽 / 아래 (%)")
        targetInputs = listOf("왼쪽" to "45", "위" to "48", "오른쪽" to "55", "아래" to "52")
            .map { field(it.first, it.second) }
        root.addView(Button(this).apply { text = "접촉 선/면 그리기"; setOnClickListener { openSelector("target") } })
        root.addView(Button(this).apply { text = "화면에서 터치 위치 찍기"; setOnClickListener { openSelector("tap") } })
        label("타이밍 보정 (ms): 음수 = 예측으로 빠르게 / 양수 = 늦게, -150 ~ +150")
        timingInput = field("보정 ms", "0", true)
        root.addView(Button(this).apply { text = "현재 타석 설정 저장"; setOnClickListener { saveSide(); Toast.makeText(this@MainActivity, "설정 저장 완료", Toast.LENGTH_SHORT).show() } })
        root.addView(Button(this).apply { text = "자동 터치 접근성 권한 설정"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        root.addView(Button(this).apply {
            text = "화면 추적 시작"
            setOnClickListener {
                saveSide()
                // A projection permission token is single-use. Stop old session before requesting another.
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        })
        root.addView(Button(this).apply { text = "추적 중지"; setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) } })
        label("가로 화면 기준으로 좌표를 저장합니다. 타석/좌표 변경 시 추적을 다시 시작하세요. 음수 보정은 이동 예측이 가능한 경우에만 작동합니다.")
        side = prefs.getString("selected_side", "right") ?: "right"
        loadSide()
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun key(name: String) = "${side}_$name"
    private fun fields(fields: List<EditText>, defaults: IntArray): IntArray =
        fields.mapIndexed { index, edit -> (edit.text.toString().toIntOrNull() ?: defaults[index]).coerceIn(0, 100) }.toIntArray()

    private fun saveSide() {
        val region = fields(regionInputs, intArrayOf(30,40,70,60))
        val target = fields(targetInputs, intArrayOf(45,48,55,52))
        val tap = fields(listOf(xInput,yInput), intArrayOf(50,50))
        val timing = (timingInput.text.toString().toIntOrNull() ?: 0).coerceIn(-150, 150)
        prefs.edit().putString(key("region"), region.joinToString(","))
            .putString(key("target"), target.joinToString(","))
            .putString(key("tap"), tap.joinToString(","))
            .putInt(key("timing"), timing).apply()
    }

    private fun loadSide() {
        sideButton.text = if (side == "right") "현재: 우타  (누르면 좌타)" else "현재: 좌타  (누르면 우타)"
        fun restore(name: String, inputs: List<EditText>, defaults: List<String>) {
            // For migration, right-handed mode inherits previous single-mode settings.
            val old = if (side == "right") prefs.getString(name, null) else null
            val saved = prefs.getString(key(name), old)?.split(",")
            inputs.forEachIndexed { index, input -> input.setText(saved?.getOrNull(index) ?: defaults[index]) }
        }
        restore("region", regionInputs, listOf("30","40","70","60"))
        restore("target", targetInputs, listOf("45","48","55","52"))
        restore("tap", listOf(xInput,yInput), listOf("50","50"))
        timingInput.setText(prefs.getInt(key("timing"), 0).toString())
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
        if (requestCode != captureRequest || resultCode != RESULT_OK || data == null) return
        val tap = fields(listOf(xInput,yInput), intArrayOf(50,50))
        val intent = Intent(this, CaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("projectionData", data)
            putExtra("tapX", tap[0]); putExtra("tapY", tap[1])
            putExtra("region", fields(regionInputs, intArrayOf(30,40,70,60)))
            putExtra("target", fields(targetInputs, intArrayOf(45,48,55,52)))
            putExtra("timingMs", (timingInput.text.toString().toIntOrNull() ?: 0).coerceIn(-150,150))
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
}
