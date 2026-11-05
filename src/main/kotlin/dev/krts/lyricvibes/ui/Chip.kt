package dev.krts.lyricvibes.ui

import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.anim.Spring
import dev.krts.lyricvibes.media.MediaBridge
import net.minecraft.client.MinecraftClient
import org.polyfrost.polyui.component.Drawable
import org.polyfrost.polyui.data.PolyImage
import org.polyfrost.polyui.unit.Vec2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Chip : Drawable(at = Vec2(1f, 1f), size = Vec2(10f, 10f)) {
    private val show = Spring(Spring.DEFAULT)
    private val expand = Spring(Spring.BOUNCY)
    private val w = Spring(Spring.SNAPPY, 120f)
    private val h = Spring(Spring.SNAPPY, 28f)
    private val bars = Array(5) { Spring(0.18f, 0.7f, 0.15f) }
    private val pauseFade = Spring(Spring.SMOOTH)
    private val hintShow = Spring(Spring.BOUNCY)
    private var hintText = ""
    private var hintUntil = 0L
    private var expandUntil = 0L
    private var lastKey: String? = null
    private var lastPlaying = false
    private var pausedSince = 0L
    private var image: PolyImage? = null
    private var imageKey: String? = null
    private var screenW = 0f
    private var marqueeStart = 0L

    val active get() = show.value > 0.002f || show.target > 0f || hintShow.value > 0.002f || hintShow.target > 0f

    fun hint(text: String) {
        hintText = text
        hintUntil = System.currentTimeMillis() + 1400
    }

    fun tick(dt: Float, sw: Float, sh: Float) {
        val mc = MinecraftClient.getInstance()
        val cfg = LyricVibes.config
        val np = MediaBridge.nowPlaying
        val now = System.currentTimeMillis()
        screenW = sw

        if (np != null) {
            if (np.key != lastKey) {
                lastKey = np.key
                marqueeStart = now
                if (cfg.expandOnChange) expandUntil = now + 4500
            }
            if (np.playing != lastPlaying) {
                lastPlaying = np.playing
                if (!np.playing) pausedSince = now
                if (cfg.expandOnChange && lastKey != null) expandUntil = max(expandUntil, now + 1800)
            }
        }
        val pausedLong = np != null && !np.playing && now - pausedSince > 4000
        val visible = cfg.chipEnabled && cfg.overlayState.chip && np != null && mc.world != null &&
            !mc.options.hudHidden && !Overlay.menu.open && !pausedLong
        if (visible && show.target == 0f && cfg.expandOnChange) expandUntil = max(expandUntil, now + 3500)
        show.to(if (visible) 1f else 0f).update(dt)
        hintShow.to(if (now < hintUntil && mc.world != null) 1f else 0f).update(dt)
        expand.to(if (now < expandUntil) 1f else 0f).update(dt)
        pauseFade.to(if (np?.playing == false) 1f else 0f).update(dt)

        val k = cfg.chipScale
        val e = expand.value.coerceIn(0f, 1.1f)
        val fresh = now - MediaBridge.bandsStampMs < 400 && np?.playing == true
        val src = MediaBridge.bands
        val groups = arrayOf(0..2, 3..5, 6..8, 9..11, 12..15)
        for (i in bars.indices) {
            val target = if (fresh && cfg.waveform) groups[i].map { src.getOrElse(it) { 0f } }.average().toFloat() else 0.12f
            bars[i].to(target.coerceIn(0.1f, 1f)).update(dt)
        }

        if (np != null) {
            val compactText = min(textW(np.title + "  ·  " + np.artist, 12f * k), 170f * k)
            val fullText = min(max(textW(np.title, 14f * k, true), textW(np.artist, 12f * k)), 230f * k)
            val wave = if (cfg.waveform) 26f * k else 0f
            val cw = 6f * k + 20f * k + 9f * k + compactText + 10f * k + wave + 8f * k
            val ew = 8f * k + 44f * k + 12f * k + fullText + 14f * k + wave + 12f * k
            w.to(cw + (ew - cw) * e).update(dt)
            h.to((28f + 28f * e) * k).update(dt)
        }
        x = (sw - w.value) / 2f
        y = 8f
        width = max(1f, w.value)
        height = max(1f, h.value)
        visibleSize = Vec2(width, height)
        clipped = false
    }

    private fun textW(s: String, size: Float, semi: Boolean = false): Float =
        if (!initialized) s.length * size * 0.55f else Paint.width(renderer, if (semi) Paint.semi else Paint.regular, s, size)

    override fun render() {
        val r = renderer
        val cfg = LyricVibes.config
        val k = cfg.chipScale
        val accent = Accent.current
        drawHint(k, accent)
        val a = show.value
        if (a <= 0.002f) return
        val np = MediaBridge.nowPlaying ?: return
        val e = expand.value.coerceIn(0f, 1f)
        val cw = w.value
        val ch = h.value
        val cx = (screenW - cw) / 2f
        val cy = 8f - (ch + 16f) * (1f - a)
        val radius = ch / 2f
        val alpha = a.coerceIn(0f, 1f)

        Paint.glass(r, cx, cy, cw, ch, radius, alpha, accent)

        val art = (20f + 24f * e) * k
        val pad = (ch - art) / 2f
        val artX = cx + pad + 2f * k
        val artY = cy + pad
        drawArt(artX, artY, art, (6f + 4f * e) * k, alpha, accent)

        val textX = artX + art + (9f + 3f * e) * k
        val wave = if (cfg.waveform) 26f * k else 0f
        val textMax = cx + cw - textX - wave - (10f + 6f * e) * k
        r.pushScissor(textX, cy, max(0f, textMax), ch)
        if (e < 0.98f) {
            val line = np.title + "  ·  " + np.artist
            Paint.text(r, Paint.regular, textX - marquee(line, 12f * k, textMax), cy + ch / 2f, line, Paint.fade(Paint.TEXT, alpha * (1f - e)), 12f * k)
        }
        if (e > 0.02f) {
            val mid = cy + ch / 2f
            Paint.text(r, Paint.semi, textX - marquee(np.title, 14f * k, textMax), mid - 8f * k, np.title, Paint.fade(Paint.TEXT, alpha * e), 14f * k)
            Paint.text(r, Paint.regular, textX, mid + 9f * k, Paint.ellipsize(r, Paint.regular, np.artist, 12f * k, textMax), Paint.fade(Paint.MUTED, alpha * e), 12f * k)
        }
        r.popScissor()

        if (cfg.waveform) drawWave(cx + cw - wave - (8f + 4f * e) * k, cy + ch / 2f, wave, (14f + 10f * e) * k, alpha, accent)
    }

    private fun marquee(text: String, size: Float, max: Float): Float {
        val over = Paint.width(renderer, Paint.regular, text, size) - max
        if (over <= 0f) return 0f
        val speed = 28f
        val travel = over / speed * 1000f
        val period = (travel + 1800f) * 2f
        val t = ((System.currentTimeMillis() - marqueeStart) % period.toLong()).toFloat()
        return when {
            t < 1800f -> 0f
            t < 1800f + travel -> (t - 1800f) / travel * over
            t < 3600f + travel -> over
            else -> over * (1f - (t - 3600f - travel) / travel).coerceIn(0f, 1f)
        }
    }

    private fun drawArt(x: Float, y: Float, s: Float, radius: Float, alpha: Float, accent: Int) {
        val art = MediaBridge.art
        val np = MediaBridge.nowPlaying
        if (art != null && np != null && art.key == np.key) {
            if (imageKey != art.key) {
                image?.let { renderer.delete(it) }
                image = PolyImage.of(art.file.toUri().toString())
                imageKey = art.key
            }
        }
        val img = image?.takeIf { imageKey == np?.key }
        if (img != null) {
            NVGExtras.imageCover(img, x, y, s, s, radius, alpha)
        } else {
            renderer.rect(x, y, s, s, Paint.c(Paint.fade(accent or 0x55000000, alpha)), radius)
            renderer.image(Paint.NOTE, x + s * 0.22f, y + s * 0.22f, s * 0.56f, s * 0.56f, 0f, Paint.fade(0xFFFFFFFF.toInt(), alpha))
        }
        val p = pauseFade.value
        if (p > 0.01f) {
            renderer.rect(x, y, s, s, Paint.c(Paint.fade(0x8C000000.toInt(), alpha * p)), radius)
            val bw = s * 0.12f
            val bh = s * 0.38f
            val col = Paint.c(Paint.fade(0xFFFFFFFF.toInt(), alpha * p))
            renderer.rect(x + s / 2f - bw * 1.4f, y + (s - bh) / 2f, bw, bh, col, bw / 2f)
            renderer.rect(x + s / 2f + bw * 0.4f, y + (s - bh) / 2f, bw, bh, col, bw / 2f)
        }
    }

    private fun drawWave(x: Float, cy: Float, w: Float, maxH: Float, alpha: Float, accent: Int) {
        val n = bars.size
        val gap = w / (n * 2.2f)
        val bw = (w - gap * (n - 1)) / n
        val t = System.currentTimeMillis() / 1000f
        for (i in 0 until n) {
            val idle = if (MediaBridge.nowPlaying?.playing == true) 0f else 0.04f * sin(t * 2f + i)
            val bh = max(bw, maxH * (bars[i].value + idle))
            renderer.rect(x + i * (bw + gap), cy - bh / 2f, bw, bh, Paint.c(Paint.fade(accent or 0xFF000000.toInt(), alpha)), bw / 2f)
        }
    }

    private fun drawHint(k: Float, accent: Int) {
        val a = hintShow.value
        if (a <= 0.002f) return
        val size = 12f * k
        val tw = Paint.width(renderer, Paint.medium, hintText, size)
        val hw = tw + 24f * k
        val hh = 24f * k
        val base = if (show.value > 0.01f) 8f + h.value * show.value + 6f else 8f
        val hx = (screenW - hw) / 2f
        val hy = base - (hh + 10f) * (1f - a)
        val alpha = a.coerceIn(0f, 1f)
        Paint.glass(renderer, hx, hy, hw, hh, hh / 2f, alpha, accent)
        Paint.text(renderer, Paint.medium, hx + 12f * k, hy + hh / 2f, hintText, Paint.fade(Paint.TEXT, alpha), size)
    }
}
