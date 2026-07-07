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
    private var collected = 0
    private var points: List<Pair<Float, Float>> = emptyList()
    private var finished = false

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
            engine.start(this)
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
        collected = 0
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
        allFeatures.add(sample.features)
        allTargets.add(target)
        collected++
        view.showTarget(target.first, target.second, collected / SAMPLES_PER_POINT.toFloat(),
            "${pointIndex + 1} / ${points.size}")

        if (collected >= SAMPLES_PER_POINT) {
            if (pointIndex + 1 < points.size) {
                startPoint(pointIndex + 1)
            } else {
                finishCalibration()
            }
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

        model.save(this)
        Toast.makeText(this, "校正完成！平均誤差約 $avgErr px", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
    }

    /** 畫校正目標點：外圈進度環 + 內圈實心點。 */
    private class CalibrationView(context: Context) : View(context) {
        private var tx = -1f
        private var ty = -1f
        private var progress = 0f
        private var label = ""

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(79, 195, 247) }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY; textSize = 42f; textAlign = Paint.Align.CENTER
        }

        fun showTarget(x: Float, y: Float, p: Float, text: String) {
            tx = x; ty = y; progress = p; label = text
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            if (tx < 0) return
            canvas.drawText(label, width / 2f, height / 2f + 120f, textPaint)
            canvas.drawCircle(tx, ty, 18f, dotPaint)
            // 進度環：收滿樣本時剛好畫滿一圈
            val r = 34f
            canvas.drawArc(
                tx - r, ty - r, tx + r, ty + r,
                -90f, 360f * min(progress, 1f), false, ringPaint,
            )
        }
    }

    companion object {
        private const val SETTLE_MS = 900L        // 每點先等 0.9 秒讓視線穩定
        private const val SAMPLES_PER_POINT = 15  // 每點收 15 幀
        private const val BLINK_THRESHOLD = 0.15  // 睜眼程度低於此值視為眨眼
    }
}
