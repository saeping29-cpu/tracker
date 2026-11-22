package com.example.linetracker

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.provider.Settings
import android.widget.Toast

class MainActivity : Activity() {
    private val captureRequest = 1001
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private lateinit var regionInputs: List<EditText>
    private lateinit var targetInputs: List<EditText>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 50, 36, 30); gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(TextView(this).apply { text = "LineTracker · Ball AutoTap"; textSize = 26f })
        root.addView(TextView(this).apply { text = "밝은 야구공을 추적해 지정한 접촉 영역에 닿으면 자동 터치합니다. 먼저 공이 지나가는 작은 탐색 영역과 접촉 영역을 각각 그려주세요. 실험용 추적기이며 오탐 가능성이 있습니다."; textSize = 16f; setPadding(0, 30, 0, 30) })
        root.addView(TextView(this).apply { text = "터치 위치: 화면 왼쪽 위 기준 X/Y 비율(0~100). 예: 중앙은 50, 50" })
        xInput = EditText(this).apply { hint = "X (%)"; setText("50"); inputType = 2 }
        yInput = EditText(this).apply { hint = "Y (%)"; setText("50"); inputType = 2 }
        root.addView(xInput); root.addView(yInput)
        root.addView(TextView(this).apply { text = "공 탐색 범위 (가능한 좁게): 왼쪽, 위, 오른쪽, 아래"; setPadding(0,20,0,8) })
        regionInputs = listOf("왼쪽 X (%)" to "30", "위쪽 Y (%)" to "40", "오른쪽 X (%)" to "70", "아래쪽 Y (%)" to "60").map { (hintText, default) ->
            EditText(this).apply { hint = hintText; setText(default); inputType = 2; root.addView(this) }
        }
        root.addView(Button(this).apply { text = "공 탐색 범위 그리기"; setOnClickListener { openSelector("region") } })
        root.addView(TextView(this).apply { text = "공이 닿으면 타격할 선/면 (아주 얇은 사각형 = 선)" })
        targetInputs = listOf("접촉 왼쪽" to "45", "접촉 위" to "48", "접촉 오른쪽" to "55", "접촉 아래" to "52").map { (hintText, default) ->
            EditText(this).apply { hint=hintText; setText(default); inputType=2; root.addView(this) }
        }
        root.addView(Button(this).apply { text = "접촉 선/면 그리기"; setOnClickListener { openSelector("target") } })
        root.addView(Button(this).apply { text = "화면에서 터치 위치 찍기"; setOnClickListener { openSelector("tap") } })
        getSharedPreferences("settings", MODE_PRIVATE).getString("region", null)?.split(",")?.takeIf { it.size == 4 }?.forEachIndexed { i, v -> regionInputs[i].setText(v) }
        getSharedPreferences("settings", MODE_PRIVATE).getString("target", null)?.split(",")?.takeIf { it.size == 4 }?.forEachIndexed { i, v -> targetInputs[i].setText(v) }
        getSharedPreferences("settings", MODE_PRIVATE).getString("tap", null)?.split(",")?.takeIf { it.size == 2 }?.let { xInput.setText(it[0]); yInput.setText(it[1]) }
        root.addView(Button(this).apply { text = "자동 터치 접근성 권한 설정"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        root.addView(Button(this).apply { text = "화면 추적 시작"; setOnClickListener { val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest) } })
        root.addView(Button(this).apply { text = "추적 중지"; setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) } })
        root.addView(TextView(this).apply { text = "추적 결과는 알림에 표시됩니다. 다른 앱을 연 상태에서도 캡처는 계속될 수 있지만, 보안 보호 화면은 분석할 수 없습니다."; setPadding(0, 28, 0, 0) })
        setContentView(root)
    }
    private fun openSelector(mode: String) {
        val service = AutoTapService.instance
        if (service == null) {
            Toast.makeText(this, "먼저 자동 터치 접근성 서비스를 켜주세요", Toast.LENGTH_LONG).show()
            return
        }
        service.select(mode) { result ->
            if (result != null) {
                if (mode == "region") regionInputs.forEachIndexed { i, field -> field.setText(result[i].toString()) }
                else if (mode == "target") targetInputs.forEachIndexed { i, field -> field.setText(result[i].toString()) }
                else { xInput.setText(result[0].toString()); yInput.setText(result[1].toString()) }
                getSharedPreferences("settings", MODE_PRIVATE).edit().apply {
                    if (mode == "region" || mode == "target") putString(mode, result.joinToString(","))
                    else putString("tap", result.joinToString(","))
                }.apply()
                Toast.makeText(this, "위치 저장 완료", Toast.LENGTH_SHORT).show()
            }
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val x = (xInput.text.toString().toIntOrNull() ?: 50).coerceIn(0, 100)
            val y = (yInput.text.toString().toIntOrNull() ?: 50).coerceIn(0, 100)
            val values = regionInputs.mapIndexed { index, field -> (field.text.toString().toIntOrNull() ?: listOf(30,40,70,60)[index]).coerceIn(0,100) }
            val target = targetInputs.mapIndexed { index, field -> (field.text.toString().toIntOrNull() ?: listOf(45,48,55,52)[index]).coerceIn(0,100) }
            val intent = Intent(this, CaptureService::class.java).putExtra("resultCode", resultCode).putExtra("projectionData", data).putExtra("tapX", x).putExtra("tapY", y).putExtra("region", values.toIntArray()).putExtra("target", target.toIntArray())
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
    }
}
