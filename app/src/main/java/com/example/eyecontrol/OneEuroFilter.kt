package com.example.eyecontrol

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro Filter：對抖動的即時訊號做平滑，同時保留快速移動時的反應速度。
 * 眼動訊號雜訊大，直接用會讓游標亂跳；這個濾波器是視線/滑鼠平滑的業界標準做法。
 *
 * 參數：
 *  minCutoff 越小越平滑（但延遲越高）；beta 越大，快速移動時越跟手。
 */
class OneEuroFilter(
    private var minCutoff: Double = 1.0,
    private var beta: Double = 0.007,
    private var dCutoff: Double = 1.0,
) {
    private var xPrev: Double = 0.0
    private var dxPrev: Double = 0.0
    private var tPrev: Double = 0.0
    private var initialized = false

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    /** @param tSeconds 當前時間戳（秒）；@param x 原始值。回傳平滑後的值。 */
    fun filter(x: Double, tSeconds: Double): Double {
        if (!initialized) {
            xPrev = x; dxPrev = 0.0; tPrev = tSeconds; initialized = true
            return x
        }
        val dt = (tSeconds - tPrev).coerceAtLeast(1e-3)
        tPrev = tSeconds

        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1 - aD) * dxPrev

        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * xPrev

        xPrev = xHat; dxPrev = dxHat
        return xHat
    }

    fun reset() { initialized = false }
}
