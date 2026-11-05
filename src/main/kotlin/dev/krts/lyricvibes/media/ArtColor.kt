package dev.krts.lyricvibes.media

import java.awt.Color
import javax.imageio.ImageIO

object ArtColor {
    fun accentOf(bytes: ByteArray): Int? {
        val img = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
        val buckets = 18
        val weight = FloatArray(buckets)
        val sumR = FloatArray(buckets)
        val sumG = FloatArray(buckets)
        val sumB = FloatArray(buckets)
        val step = maxOf(1, minOf(img.width, img.height) / 48)
        val hsb = FloatArray(3)
        for (y in 0 until img.height step step) for (x in 0 until img.width step step) {
            val rgb = img.getRGB(x, y)
            val r = rgb shr 16 and 0xFF
            val g = rgb shr 8 and 0xFF
            val b = rgb and 0xFF
            Color.RGBtoHSB(r, g, b, hsb)
            val w = hsb[1] * hsb[1] * hsb[2]
            if (w < 0.02f) continue
            val i = (hsb[0] * buckets).toInt().coerceIn(0, buckets - 1)
            weight[i] += w
            sumR[i] += r * w; sumG[i] += g * w; sumB[i] += b * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] < 0.5f) return null
        Color.RGBtoHSB(
            (sumR[best] / weight[best]).toInt(),
            (sumG[best] / weight[best]).toInt(),
            (sumB[best] / weight[best]).toInt(),
            hsb,
        )
        val sat = hsb[1].coerceIn(0.45f, 0.8f)
        val bri = hsb[2].coerceIn(0.78f, 0.95f)
        return Color.HSBtoRGB(hsb[0], sat, bri) and 0xFFFFFF
    }
}
