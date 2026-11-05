package dev.krts.lyricvibes.ui

import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.anim.Spring
import dev.krts.lyricvibes.anim.lerpColor
import dev.krts.lyricvibes.media.MediaBridge
import org.polyfrost.polyui.PolyUI
import org.polyfrost.polyui.color.PolyColor
import org.polyfrost.polyui.color.argb
import org.polyfrost.polyui.data.Font
import org.polyfrost.polyui.data.PolyImage
import org.polyfrost.polyui.renderer.Renderer
import kotlin.math.min

object Paint {
    private val colors = HashMap<Int, PolyColor>()
    private val widths = HashMap<String, Float>()

    val regular: Font get() = PolyUI.defaultFonts.regular
    val medium: Font get() = PolyUI.defaultFonts.medium
    val semi: Font get() = PolyUI.defaultFonts.semiBold

    val PLAY: PolyImage = PolyImage.of("assets/lyricvibes/icons/play.svg")
    val PAUSE: PolyImage = PolyImage.of("assets/lyricvibes/icons/pause.svg")
    val NEXT: PolyImage = PolyImage.of("assets/lyricvibes/icons/next.svg")
    val PREV: PolyImage = PolyImage.of("assets/lyricvibes/icons/prev.svg")
    val NOTE: PolyImage = PolyImage.of("assets/lyricvibes/icons/note.svg")

    const val PANEL = 0xE0121218.toInt()
    const val SURFACE = 0x14FFFFFF
    const val STROKE = 0x1AFFFFFF
    const val TEXT = 0xF2FFFFFF.toInt()
    const val MUTED = 0x99FFFFFF.toInt()
    const val FAINT = 0x59FFFFFF

    fun c(argb: Int): PolyColor {
        if (colors.size > 2048) colors.clear()
        return colors.getOrPut(argb) { argb(argb) }
    }

    fun fade(argb: Int, alpha: Float): Int {
        val a = ((argb ushr 24) * alpha.coerceIn(0f, 1f)).toInt()
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    fun width(r: Renderer, font: Font, text: String, size: Float): Float {
        if (widths.size > 4096) widths.clear()
        return widths.getOrPut("${font.name}|$size|$text") { r.textBounds(font, text, size).x }
    }

    fun text(r: Renderer, font: Font, x: Float, cy: Float, text: String, argb: Int, size: Float) {
        if ((argb ushr 24) < 3) return
        r.text(font, x, cy - size * 0.62f, text, c(argb), size)
    }

    fun ellipsize(r: Renderer, font: Font, text: String, size: Float, max: Float): String {
        if (width(r, font, text, size) <= max) return text
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (width(r, font, text.take(mid) + "…", size) <= max) lo = mid else hi = mid - 1
        }
        return text.take(lo).trimEnd() + "…"
    }

    fun glass(r: Renderer, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float, accent: Int) {
        NVGExtras.shadow(x, y + 3f, w, h, radius, 22f, fade(0x66000000, alpha))
        r.rect(x, y, w, h, c(fade(PANEL, alpha)), radius)
        NVGExtras.vertical(x, y, w, h, radius, fade(0x12FFFFFF, alpha), 0)
        NVGExtras.horizontal(x, y, w, h, radius, fade(accent or 0x1F000000, alpha), 0)
        r.hollowRect(x, y, w, h, c(fade(STROKE, alpha)), 1f, radius)
    }

    fun clamp01(v: Float) = min(1f, maxOf(0f, v))
}

object Accent {
    var current = 0xA78BFA
        private set
    private var from = current
    private var to = current
    private val t = Spring(Spring.GENTLE, 1f)

    fun update(dt: Float) {
        val cfg = LyricVibes.config
        val np = MediaBridge.nowPlaying
        val art = MediaBridge.art
        val target = if (cfg.accentFromArt && np != null && art?.key == np.key && art.accent != null) art.accent else cfg.accent
        if (target != to) {
            from = current
            to = target
            t.snap(0f)
            t.to(1f)
        }
        current = lerpColor(from or 0xFF000000.toInt(), to or 0xFF000000.toInt(), t.update(dt)) and 0xFFFFFF
    }
}
