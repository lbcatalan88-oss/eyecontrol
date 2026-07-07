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
    private var minCutoff: Double = 0.2, // 降低 minCutoff 提高靜止時的平滑度
    private var beta: Double = 0.015,     // 提高 beta 增加移動時的跟手反應速度
    private var dCutoff: Double = 1.0,
    private val deadBand: Double = 5.0,    // 5像素死區，過濾微小抖動
    private val maxVelocity: Double = 3500.0 // 限制最大變動速度，抑制MediaPipe突變雜訊
) {
    fun updateParams(minCutoff: Double, beta: Double) {
        this.minCutoff = minCutoff
        this.beta = beta
    }
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

        // 1. 突波限幅：如果變化率過大，進行截斷，防止畫面跳動
        var targetX = x
        val rawDelta = x - xPrev
        val rawVelocity = abs(rawDelta) / dt
        if (rawVelocity > maxVelocity) {
            val clampedDelta = (maxVelocity * dt) * kotlin.math.sign(rawDelta)
            targetX = xPrev + clampedDelta
        }

        // 2. 計算速度與其平滑值
        val dx = (targetX - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1 - aD) * dxPrev

        // 3. 自適應 minCutoff：當移動距離小於 deadBand 時，說明處於微調或靜止狀態，漸進降低 minCutoff 以提升平滑度，避免黏滯感
        val dist = abs(targetX - xPrev)
        val dynamicMinCutoff = if (dist < deadBand && deadBand > 0.0) {
            val ratio = dist / deadBand
            val factor = ratio * ratio * (3.0 - 2.0 * ratio) // smoothstep
            minCutoff * (0.15 + 0.85 * factor)
        } else {
            minCutoff
        }

        val cutoff = dynamicMinCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * targetX + (1 - a) * xPrev

        xPrev = xHat; dxPrev = dxHat
        return xHat
    }

    fun reset() { initialized = false }
}
