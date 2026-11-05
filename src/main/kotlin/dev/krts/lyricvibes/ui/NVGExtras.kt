package dev.krts.lyricvibes.ui

import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NanoVG.*
import org.polyfrost.polyui.data.PolyImage
import org.polyfrost.polyui.renderer.impl.NVGRenderer
import kotlin.math.max

object NVGExtras {
    private val a: NVGColor = NVGColor.malloc()
    private val b: NVGColor = NVGColor.malloc()

    private fun set(argb: Int, out: NVGColor) {
        nvgRGBA(
            (argb shr 16 and 0xFF).toByte(), (argb shr 8 and 0xFF).toByte(),
            (argb and 0xFF).toByte(), (argb ushr 24).toByte(), out,
        )
    }

    fun shadow(x: Float, y: Float, w: Float, h: Float, radius: Float, blur: Float, argb: Int) {
        if ((argb ushr 24) == 0) return
        val vg = NVGRenderer.vg
        val p = NVGRenderer.nvgPaint
        set(argb, a)
        set(argb and 0xFFFFFF, b)
        nvgBoxGradient(vg, x, y, w, h, radius, blur, a, b, p)
        nvgBeginPath(vg)
        nvgRect(vg, x - blur * 2f, y - blur * 2f, w + blur * 4f, h + blur * 4f)
        nvgRoundedRect(vg, x, y, w, h, radius)
        nvgPathWinding(vg, NVG_HOLE)
        nvgFillPaint(vg, p)
        nvgFill(vg)
    }

    fun vertical(x: Float, y: Float, w: Float, h: Float, radius: Float, top: Int, bottom: Int) =
        gradient(x, y, w, h, radius, top, bottom, x, y, x, y + h)

    fun horizontal(x: Float, y: Float, w: Float, h: Float, radius: Float, left: Int, right: Int) =
        gradient(x, y, w, h, radius, left, right, x, y, x + w, y)

    private fun gradient(x: Float, y: Float, w: Float, h: Float, radius: Float, c1: Int, c2: Int, sx: Float, sy: Float, ex: Float, ey: Float) {
        if ((c1 ushr 24) == 0 && (c2 ushr 24) == 0) return
        val vg = NVGRenderer.vg
        val p = NVGRenderer.nvgPaint
        set(c1, a)
        set(c2, b)
        nvgLinearGradient(vg, sx, sy, ex, ey, a, b, p)
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x, y, w, h, radius)
        nvgFillPaint(vg, p)
        nvgFill(vg)
    }

    fun circle(cx: Float, cy: Float, r: Float, argb: Int) {
        if ((argb ushr 24) == 0) return
        val vg = NVGRenderer.vg
        set(argb, a)
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, r)
        nvgFillColor(vg, a)
        nvgFill(vg)
    }

    fun ring(cx: Float, cy: Float, r: Float, width: Float, argb: Int) {
        if ((argb ushr 24) == 0) return
        val vg = NVGRenderer.vg
        set(argb, a)
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, r)
        nvgStrokeWidth(vg, width)
        nvgStrokeColor(vg, a)
        nvgStroke(vg)
    }

    fun imageCover(image: PolyImage, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float) {
        val vg = NVGRenderer.vg
        val p = NVGRenderer.nvgPaint
        val handle = NVGRenderer.getImage(image, 0f, 0f)
        val iw = image.size.x
        val ih = image.size.y
        var pw = w
        var ph = h
        if (iw > 0f && ih > 0f) {
            val s = max(w / iw, h / ih)
            pw = iw * s
            ph = ih * s
        }
        nvgImagePattern(vg, x + (w - pw) / 2f, y + (h - ph) / 2f, pw, ph, 0f, handle, alpha.coerceIn(0f, 1f), p)
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x, y, w, h, radius)
        nvgFillPaint(vg, p)
        nvgFill(vg)
    }
}
