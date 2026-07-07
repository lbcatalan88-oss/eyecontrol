package com.example.eyecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * 游標覆蓋層外觀樣式配置
 */
data class CursorOverlayConfig(
    val dotColor: Int = Color.argb(220, 79, 195, 247),
    val lostColor: Int = Color.argb(150, 158, 158, 158),
    val ringColor: Int = Color.WHITE,
    val flashColor: Int = Color.argb(160, 255, 235, 59),
    val strokeWidthRing: Float = 5f,
    val strokeWidthLost: Float = 5f,
    val strokeWidthFlash: Float = 8f,
    val dotRadius: Float = 14f,
    val lostRadius: Float = 16f,
    val ringRadius: Float = 26f,
    val flashRadius: Float = 40f,
    val flashDurationMs: Long = 250L
)

/**
 * 全螢幕透明覆蓋層：畫游標圓點與 dwell 進度環。
 */
class CursorOverlayView(
    context: Context,
    val config: CursorOverlayConfig = CursorOverlayConfig()
) : View(context) {

    private var cursor: Triple<Float, Float, Float>? = null // x, y, dwellProgress
    private var faceLost = false
    private var flashUntil = 0L

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.dotColor
    }
    private val lostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.lostColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthLost
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.ringColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthRing
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.flashColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthFlash
    }

    fun setCursor(c: Triple<Float, Float, Float>?) {
        cursor = c
        faceLost = false
        invalidate()
    }

    /** 偵測不到臉：游標留在最後位置，改畫灰色空心圈。 */
    fun setFaceLost() {
        faceLost = true
        invalidate()
    }

    fun flashClick() {
        flashUntil = SystemClock.elapsedRealtime() + config.flashDurationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val c = cursor ?: return
        val (x, y, progress) = c
        if (faceLost) {
            canvas.drawCircle(x, y, config.lostRadius, lostPaint)
            return
        }
        canvas.drawCircle(x, y, config.dotRadius, dotPaint)
        if (progress > 0f) {
            val r = config.ringRadius
            canvas.drawArc(x - r, y - r, x + r, y + r, -90f, 360f * progress, false, ringPaint)
        }
        if (SystemClock.elapsedRealtime() < flashUntil) {
            canvas.drawCircle(x, y, config.flashRadius, flashPaint)
            postInvalidateDelayed(50)
        }
    }
}
