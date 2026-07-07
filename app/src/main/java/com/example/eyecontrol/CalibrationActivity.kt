package com.example.eyecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * 9 點視線校正：依序顯示 3×3 目標點，使用者「頭保持不動、只用眼睛看」，
 * 每點先等視線穩定再收樣本，全部完成後訓練 GazeModel 並存檔。
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var view: CalibrationView
    private lateinit var engine: FaceTrackerEngine

    // 收集到的訓練資料
    private val allFeatures = mutableListOf<DoubleArray>()
    private val allTargets = mutableListOf<Pair<Float, Float>>()

    // 目前狀態（僅在主執行緒讀寫；樣本從背景執行緒 post 過來）
    private var pointIndex = 0
    private var settleUntil = 0L
    private var points: List<Pair<Float, Float>> = emptyList()
    private var finished = false

    /** 目前目標點收到的原始樣本，收滿後剔除離群值再併入訓練集。 */
    private val pointBuffer = mutableListOf<DoubleArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = CalibrationView(this)
        setContentView(view)

        // 隱藏系統列，確保校正座標涵蓋整個實體螢幕
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        engine = FaceTrackerEngine(this) { sample ->
            runOnUiThread { onSample(sample) }
        }

        view.post {
            points = buildPoints(view.width.toFloat(), view.height.toFloat())
            startPoint(0)
            // 先請無障礙服務放開相機，確認釋放後才綁定自己的——避免兩邊搶相機導致畫面串流被拔掉
            GazeAccessibilityService.requestPauseForCalibration {
                if (!isFinishing && !finished) engine.start(this)
            }
        }
    }

    /** 3×3 網格，邊緣留 8% 邊距。 */
    private fun buildPoints(w: Float, h: Float): List<Pair<Float, Float>> {
        val xs = listOf(0.08f, 0.5f, 0.92f).map { it * w }
        val ys = listOf(0.08f, 0.5f, 0.92f).map { it * h }
        return ys.flatMap { y -> xs.map { x -> x to y } }
    }

    private fun startPoint(index: Int) {
        pointIndex = index
        pointBuffer.clear()
        settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS
        val (x, y) = points[index]
        view.showTarget(x, y, 0f, "${index + 1} / ${points.size}")
    }

    private fun onSample(sample: GazeSample?) {
        if (finished || points.isEmpty()) return
        if (sample == null) return
        // 眨眼/閉眼期間的樣本會污染訓練資料，丟棄
        if (sample.leftOpen < BLINK_THRESHOLD || sample.rightOpen < BLINK_THRESHOLD) return

        val now = SystemClock.elapsedRealtime()
        if (now < settleUntil) return // 等視線移過去、穩定下來

        val target = points[pointIndex]
        pointBuffer.add(sample.features)
        view.showTarget(target.first, target.second,
            pointBuffer.size / SAMPLES_PER_POINT.toFloat(),
            "${pointIndex + 1} / ${points.size}")

        if (pointBuffer.size >= SAMPLES_PER_POINT) {
            commitPoint(target)
            if (pointIndex + 1 < points.size) {
                startPoint(pointIndex + 1)
            } else {
                finishCalibration()
            }
        }
    }

    /**
     * 剔除離群值後併入訓練集：以虹膜特徵（前 4 維）的樣本中心為基準，
     * 只保留距離最近的 KEEP_RATIO 比例——視線飄移、殘留眨眼幀都會被丟掉。
     */
    private fun commitPoint(target: Pair<Float, Float>) {
        val centroid = DoubleArray(4)
        for (f in pointBuffer) for (i in 0 until 4) centroid[i] += f[i] / pointBuffer.size

        val keep = (pointBuffer.size * KEEP_RATIO).toInt().coerceAtLeast(1)
        pointBuffer
            .sortedBy { f -> (0 until 4).sumOf { i -> (f[i] - centroid[i]) * (f[i] - centroid[i]) } }
            .take(keep)
            .forEach {
                allFeatures.add(it)
                allTargets.add(target)
            }
    }

    private fun finishCalibration() {
        finished = true
        val model = GazeModel.train(allFeatures, allTargets)

        // 用訓練資料回算平均誤差，讓使用者知道這次校正品質
        var errSum = 0.0
        for (i in allFeatures.indices) {
            val (px, py) = model.predict(allFeatures[i])
            errSum += hypot(px - allTargets[i].first, py - allTargets[i].second)
        }
        val avgErr = (errSum / allFeatures.size).toInt()

        val rating = when {
            avgErr < 60 -> "極佳 🌟"
            avgErr < 120 -> "優良 ✨"
            avgErr < 200 -> "普通 👍"
            else -> "偏差 ⚠️ (建議放穩手機重新校正)"
        }

        model.save(this)
        Toast.makeText(this, "校正完成！\n精度等級：$rating\n平均誤差：$avgErr px", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        // 通知服務重新載入校正結果並接回相機
        GazeAccessibilityService.resumeAfterCalibration()
    }

    /** 畫校正目標點：帶脈衝動畫的外圈進度環 + 呼吸內圈實心點。 */
    private class CalibrationView(context: Context) : View(context) {
        private var tx = -1f
        private var ty = -1f
        private var progress = 0f
        private var label = ""

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(79, 195, 247) }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 79, 195, 247)
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f
        }
        private val bgRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 8f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 200, 200); textSize = 44f; textAlign = Paint.Align.CENTER
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 120, 120); textSize = 36f; textAlign = Paint.Align.CENTER
        }

        fun showTarget(x: Float, y: Float, p: Float, text: String) {
            tx = x; ty = y; progress = p; label = text
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(18, 18, 24)) // 護眼深藍黑色背景
            if (tx < 0) return

            // 畫提示文字與進度文字
            canvas.drawText(label, width / 2f, height / 2f + 100f, textPaint)
            canvas.drawText("請保持頭部不動，用眼睛注視動態藍點", width / 2f, height / 2f + 160f, hintPaint)

            val time = System.currentTimeMillis()
            
            // 呼吸脈衝動畫：內圈點大小會隨時間縮放
            val pulseScale = 1.0f + 0.18f * kotlin.math.sin(time / 160.0).toFloat()
            val baseRadius = 20f
            
            // 畫外圈淡脈衝光暈
            val pulseRadius = baseRadius * (pulseScale + 0.3f)
            canvas.drawCircle(tx, ty, pulseRadius, pulsePaint)
            
            // 畫實心目標點
            canvas.drawCircle(tx, ty, baseRadius * pulseScale, dotPaint)

            // 畫進度軌道（淡灰色背景環）
            val r = 40f
            canvas.drawCircle(tx, ty, r, bgRingPaint)

            // 進度環：收滿樣本時剛好畫滿一圈
            canvas.drawArc(
                tx - r, ty - r, tx + r, ty + r,
                -90f, 360f * min(progress, 1f), false, ringPaint,
            )

            // 觸發下一幀動畫重繪
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val SETTLE_MS = 900L        // 每點先等 0.9 秒讓視線穩定
        private const val SAMPLES_PER_POINT = 20  // 每點收 20 幀（後續剔除離群值）
        private const val KEEP_RATIO = 0.7        // 每點保留最接近中心的 70% 樣本
        // 睜眼閾值：看螢幕下方時眼皮自然半垂，設太高會把「往下看」誤判成眨眼
        private const val BLINK_THRESHOLD = 0.08
    }
}
