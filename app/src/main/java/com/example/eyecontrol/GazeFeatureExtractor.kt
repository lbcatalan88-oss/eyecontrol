package com.example.eyecontrol

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot

/**
 * 一幀的視線觀測值。
 * features：餵給回歸模型的特徵向量（虹膜相對眼眶位置 + 頭部位置/距離 + 偏置項）。
 * leftOpen / rightOpen：眼睛睜開程度（眼高/眼寬），用來偵測眨眼、閉眼時凍結游標。
 */
data class GazeSample(
    val features: DoubleArray,
    val leftOpen: Double,
    val rightOpen: Double,
    val timestampMs: Long,
)

/**
 * 從 MediaPipe Face Landmarker（478 點，含虹膜）萃取視線特徵。
 *
 * 核心想法：虹膜中心在「眼眶座標系」內的相對位置，隨視線方向線性變化；
 * 再加上鼻尖位置與兩眼間距（頭部平移/遠近的代理變數），
 * 交給校正階段學出的線性模型去對應螢幕座標。
 */
object GazeFeatureExtractor {
    // MediaPipe FaceMesh 標準索引
    private const val L_IRIS = 468
    private const val R_IRIS = 473
    private const val L_EYE_OUTER = 33
    private const val L_EYE_INNER = 133
    private const val L_EYE_TOP = 159
    private const val L_EYE_BOTTOM = 145
    private const val R_EYE_INNER = 362
    private const val R_EYE_OUTER = 263
    private const val R_EYE_TOP = 386
    private const val R_EYE_BOTTOM = 374
    private const val NOSE_TIP = 1

    /** 特徵維度（含偏置項），校正與推論兩端共用。 */
    const val DIM = 22

    fun extract(lm: List<NormalizedLandmark>, timestampMs: Long): GazeSample? {
        if (lm.size <= R_IRIS) return null

        fun x(i: Int) = lm[i].x().toDouble()
        fun y(i: Int) = lm[i].y().toDouble()
        fun dist(a: Int, b: Int) = hypot(x(a) - x(b), y(a) - y(b))

        // 左眼：虹膜在眼眶內的相對位置（以眼寬為尺度做正規化，對距離變化不敏感）
        val lW = dist(L_EYE_OUTER, L_EYE_INNER)
        val rW = dist(R_EYE_INNER, R_EYE_OUTER)
        if (lW < 1e-6 || rW < 1e-6) return null

        val lCx = (x(L_EYE_OUTER) + x(L_EYE_INNER)) / 2.0
        val lCy = (y(L_EYE_OUTER) + y(L_EYE_INNER)) / 2.0
        val rCx = (x(R_EYE_INNER) + x(R_EYE_OUTER)) / 2.0
        val rCy = (y(R_EYE_INNER) + y(R_EYE_OUTER)) / 2.0

        // 兩眼連線的中點與距離 (Interocular Distance)
        val eyeMidX = (lCx + rCx) / 2.0
        val eyeMidY = (lCy + rCy) / 2.0
        val iod = hypot(rCx - lCx, rCy - lCy)
        if (iod < 1e-6) return null

        val lIrisX = (x(L_IRIS) - lCx) / lW
        val lIrisY = (y(L_IRIS) - lCy) / lW
        val rIrisX = (x(R_IRIS) - rCx) / rW
        val rIrisY = (y(R_IRIS) - rCy) / rW

        // 頭部姿態代理（以 iod 正規化，對距離不敏感）：
        //   pitch（抬頭/低頭）：鼻尖相對兩眼中線的「垂直」偏移——頭抬起來時鼻尖靠近眼睛
        //   yaw（左右轉頭）  ：鼻尖相對兩眼中線的「水平」偏移
        //   roll（歪頭）     ：兩眼連線的斜率
        val pitch = (y(NOSE_TIP) - eyeMidY) / iod
        val yaw = (x(NOSE_TIP) - eyeMidX) / iod
        val roll = (rCy - lCy) / iod

        // 計算旋轉餘弦和正弦，將特徵旋轉回頭部座標系中，實現旋轉不變性 (Rotation Invariance)
        val cosRoll = (rCx - lCx) / iod
        val sinRoll = (rCy - lCy) / iod

        val lIrisXRot = lIrisX * cosRoll + lIrisY * sinRoll
        val lIrisYRot = -lIrisX * sinRoll + lIrisY * cosRoll
        val rIrisXRot = rIrisX * cosRoll + rIrisY * sinRoll
        val rIrisYRot = -rIrisX * sinRoll + rIrisY * cosRoll

        val pitchRot = -yaw * sinRoll + pitch * cosRoll
        val yawRot = yaw * cosRoll + pitch * sinRoll

        // 眼睛與頭部姿態之交互作用項：捕捉頭部轉動時，眼球生理轉動特徵的非線性響應
        val eyeHeadXRot = lIrisXRot * yawRot
        val eyeHeadYRot = lIrisYRot * pitchRot

        // 二階項：更精準描述頭部姿態的非線性變化（如 pitch^2, yaw^2, pitch * yaw 等）
        val pitchRotSq = pitchRot * pitchRot
        val yawRotSq = yawRot * yawRot
        val pitchYawRot = pitchRot * yawRot

        // 額外優化特徵：雙眼平均、雙眼差分（輻輳）、以及姿態/距離的額外二次項，全面抑制噪聲
        val avgIrisXRot = (lIrisXRot + rIrisXRot) / 2.0
        val avgIrisYRot = (lIrisYRot + rIrisYRot) / 2.0
        val irisDiffXRot = rIrisXRot - lIrisXRot
        val irisDiffYRot = rIrisYRot - lIrisYRot
        val iodSq = iod * iod
        val rollSq = roll * roll

        val leftOpen = dist(L_EYE_TOP, L_EYE_BOTTOM) / lW
        val rightOpen = dist(R_EYE_TOP, R_EYE_BOTTOM) / rW

        val features = doubleArrayOf(
            lIrisXRot, lIrisYRot, rIrisXRot, rIrisYRot,
            eyeHeadXRot, eyeHeadYRot, iod,
            pitchRot, yawRot, roll,
            pitchRotSq, yawRotSq, pitchYawRot,
            avgIrisXRot, avgIrisYRot,
            irisDiffXRot, irisDiffYRot,
            iodSq, rollSq,
            leftOpen, rightOpen,
            1.0, // 偏置項
        )

        return GazeSample(features, leftOpen, rightOpen, timestampMs)
    }
}
