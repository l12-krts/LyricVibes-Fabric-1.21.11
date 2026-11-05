package dev.krts.lyricvibes.world

import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.anim.Spring
import dev.krts.lyricvibes.anim.lerpColor
import dev.krts.lyricvibes.anim.withAlpha
import dev.krts.lyricvibes.config.Side
import dev.krts.lyricvibes.config.WorldMode
import dev.krts.lyricvibes.lyrics.Line
import dev.krts.lyricvibes.lyrics.LrcParser
import dev.krts.lyricvibes.lyrics.LyricsService
import dev.krts.lyricvibes.lyrics.PlaybackClock
import dev.krts.lyricvibes.media.MediaBridge
import dev.krts.lyricvibes.ui.Accent
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.StyleSpriteSource
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

object WorldLyricsRenderer {
    private val FONT = StyleSpriteSource.Font(Identifier.of(LyricVibes.ID, "lyrics"))
    private val STYLE: Style = Style.EMPTY.withFont(FONT)
    private val UP = Vec3d(0.0, 1.0, 0.0)
    private const val GAP = -3
    private const val ROW_WIDTH = 210
    private const val WHITE = 0xFFFFFF
    private const val TRACK = -0.55f
    private const val LAYERS = 5
    private const val DEPTH = 0.45f
    private const val FRONT_ALPHA = 0.82f

    private val instances = ArrayList<LineInstance>()
    private var shownIndex = Int.MIN_VALUE
    private var shownKey: String? = null
    private val visibility = Spring(Spring.SMOOTH)
    private val pauseDim = Spring(Spring.SMOOTH, 1f)
    private var lastNanos = 0L

    private class Glyph(val chars: List<MutableText>, val offsets: FloatArray, val width: Float, val startMs: Long) {
        val spring = Spring(Spring.BOUNCY)
        var u = 0f
        var row = 0
    }

    private class LineInstance(
        val line: Line,
        val mode: WorldMode,
        val anchor: Vec3d,
        val viewer: Vec3d,
        val yaw: Float,
        val dir: Vec3d,
        val dots: Boolean,
    ) {
        val glyphs = ArrayList<Glyph>()
        val exit = Spring(Spring.SMOOTH)
        val born = System.nanoTime()
        var rows = 1
        var exiting = false
        val age get() = (System.nanoTime() - born) / 1e9f
    }

    fun styled(s: String): MutableText = Text.literal(s).setStyle(STYLE)

    fun render(ctx: WorldRenderContext) {
        val mc = MinecraftClient.getInstance()
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now
        val cfg = LyricVibes.config
        val np = MediaBridge.nowPlaying
        PlaybackClock.update(np, cfg.offsetMs)
        val lyrics = LyricsService.lyrics?.takeIf { it.synced || cfg.showUnsynced }

        val want = cfg.lyricsEnabled && cfg.overlayState.lyrics && np != null && lyrics != null && !mc.options.hudHidden
        visibility.to(if (want) 1f else 0f).update(dt)
        pauseDim.to(if (np?.playing == true) 1f else 0.4f).update(dt)
        val camera = mc.gameRenderer.camera
        val player = mc.player ?: return
        val eye = camera.cameraPos
        val yawRad = Math.toRadians(camera.yaw.toDouble())
        val forward = Vec3d(-sin(yawRad), 0.0, cos(yawRad))

        if (visibility.value < 0.003f && visibility.target == 0f) {
            instances.clear()
            shownIndex = Int.MIN_VALUE
            return
        }

        val pos = PlaybackClock.positionMs
        if (np?.key != shownKey) {
            instances.forEach { it.exiting = true }
            shownKey = np?.key
            shownIndex = Int.MIN_VALUE
        }
        if (lyrics != null) {
            val active = lyrics.activeAt(pos)
            val next = lyrics.nextAfter(pos)
            val idx = when {
                active != null -> active.index
                np?.playing == true && next != null && next.startMs - pos > 2500 -> GAP
                else -> -1
            }
            if (idx != shownIndex) {
                instances.forEach { it.exiting = true }
                shownIndex = idx
                val line = when (idx) {
                    GAP -> Line(-1, pos, next!!.startMs, "• • •", LrcParser.interpolate("• • •", pos, pos + 600))
                    -1 -> null
                    else -> active
                }
                if (line != null) instances += spawn(line, cfg.mode, eye, player.velocity, camera.yaw, idx == GAP)
            }
        }
        while (instances.size > 4) instances.removeAt(0)

        val ms = ctx.matrices()
        val consumers = ctx.consumers()
        val tr = mc.textRenderer
        val accent = Accent.current
        val layer = if (cfg.seeThrough) TextRenderer.TextLayerType.SEE_THROUGH else TextRenderer.TextLayerType.NORMAL
        val baseAlpha = cfg.opacity * visibility.value * pauseDim.value

        val iter = instances.iterator()
        while (iter.hasNext()) {
            val inst = iter.next()
            if (inst.mode == WorldMode.TRAIL && inst.anchor.subtract(eye).dotProduct(forward) < -0.5) inst.exiting = true
            inst.exit.to(if (inst.exiting) 1f else 0f).update(dt)
            if (inst.exiting && inst.exit.value > 0.97f) {
                iter.remove()
                continue
            }
            for (g in inst.glyphs) {
                val reveal = if (inst.dots) inst.age > g.startMs / 1000f else pos >= g.startMs
                g.spring.to(if (reveal || inst.exiting) 1f else 0f).update(dt)
            }
            val lineAlpha = baseAlpha * (1f - inst.exit.value)
            if (lineAlpha < 0.01f) continue
            val breathe = if (inst.dots) 0.55f + 0.45f * sin(inst.age * 3f) else 1f
            drawInstance(inst, ms, consumers, tr, eye, layer, lineAlpha * breathe, accent, pos)
        }
        if (consumers is VertexConsumerProvider.Immediate) consumers.draw()
    }

    private fun spawn(line: Line, mode: WorldMode, eye: Vec3d, velocity: Vec3d, yaw: Float, dots: Boolean): LineInstance {
        val cfg = LyricVibes.config
        val d = cfg.distance.toDouble()
        val yawRad = Math.toRadians(yaw.toDouble())
        val forward = Vec3d(-sin(yawRad), 0.0, cos(yawRad))
        val right = Vec3d(-cos(yawRad), 0.0, -sin(yawRad))
        val side = if (cfg.closeSide == Side.RIGHT) 1.0 else -1.0
        val horiz = Vec3d(velocity.x, 0.0, velocity.z)
        val dir = if (horiz.lengthSquared() > 0.0025) horiz.normalize() else forward
        val feetY = eye.y - (MinecraftClient.getInstance().player?.standingEyeHeight ?: 1.62f)
        val anchor = when (mode) {
            WorldMode.SKY -> eye.add(forward.multiply(34.0 * d)).add(0.0, 13.0 * d, 0.0)
            WorldMode.CLOSE -> eye.add(forward.multiply(3.0 * d)).add(right.multiply(1.8 * d * side)).add(0.0, -0.25, 0.0)
            WorldMode.TRAIL -> Vec3d(eye.x, feetY + 0.06, eye.z).add(dir.multiply(4.5 * d))
            WorldMode.ORBIT -> eye
        }
        val inst = LineInstance(line, mode, anchor, eye, yaw, dir, dots)
        val tr = MinecraftClient.getInstance().textRenderer
        val space = tr.getWidth(styled(" ")) * 0.85f
        var u = 0f
        var row = 0
        val rowWidths = ArrayList<Float>()
        for ((i, w) in line.words.withIndex()) {
            val g = glyph(w.text, tr, if (dots) i * 160L else w.startMs)
            if (mode != WorldMode.ORBIT && u > 0 && u + g.width > ROW_WIDTH) {
                rowWidths += u - space
                u = 0f
                row++
            }
            g.u = u + g.width / 2f
            g.row = row
            u += g.width + space
            inst.glyphs += g
        }
        rowWidths += (u - space).coerceAtLeast(0f)
        inst.rows = row + 1
        for (g in inst.glyphs) g.u -= rowWidths[g.row] / 2f
        return inst
    }

    private fun glyph(word: String, tr: TextRenderer, start: Long): Glyph {
        val chars = ArrayList<MutableText>()
        val offsets = ArrayList<Float>()
        var x = 0f
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val t = styled(String(Character.toChars(cp)))
            chars += t
            offsets += x
            x += tr.getWidth(t) + TRACK
            i += Character.charCount(cp)
        }
        return Glyph(chars, offsets.toFloatArray(), (x - TRACK).coerceAtLeast(0f), start)
    }

    private fun face(ms: MatrixStack, toward: Vec3d) {
        val n = toward.normalize()
        var up = UP.subtract(n.multiply(UP.dotProduct(n)))
        if (up.lengthSquared() < 1e-4) up = Vec3d(0.0, 0.0, 1.0)
        up = up.normalize()
        val r = n.multiply(-1.0).crossProduct(up).normalize()
        basis(ms, r, up, n)
    }

    private fun basis(ms: MatrixStack, r: Vec3d, u: Vec3d, n: Vec3d) {
        ms.peek().positionMatrix.mul(
            Matrix4f(
                r.x.toFloat(), r.y.toFloat(), r.z.toFloat(), 0f,
                u.x.toFloat(), u.y.toFloat(), u.z.toFloat(), 0f,
                n.x.toFloat(), n.y.toFloat(), n.z.toFloat(), 0f,
                0f, 0f, 0f, 1f,
            ),
        )
    }

    private fun drawInstance(
        inst: LineInstance, ms: MatrixStack, consumers: VertexConsumerProvider, tr: TextRenderer,
        eye: Vec3d, layer: TextRenderer.TextLayerType, alpha: Float, accent: Int, pos: Long,
    ) {
        val cfg = LyricVibes.config
        val d = cfg.distance
        val lh = tr.fontHeight + 3f
        val e = inst.exit.value
        if (inst.mode == WorldMode.ORBIT) {
            drawOrbit(inst, ms, consumers, tr, eye, layer, alpha, accent, pos)
            return
        }
        val s: Float
        ms.push()
        when (inst.mode) {
            WorldMode.SKY -> {
                s = 0.15f * cfg.scale * d
                val p = inst.anchor.add(0.0, inst.age * 0.08 + e * 3.0, 0.0)
                val rel = p.subtract(eye)
                ms.translate(rel.x, rel.y, rel.z)
                face(ms, inst.viewer.subtract(inst.anchor))
            }
            WorldMode.CLOSE -> {
                s = 0.014f * cfg.scale * d
                val rel = inst.anchor.add(0.0, e * 0.35, 0.0).subtract(eye)
                ms.translate(rel.x, rel.y, rel.z)
                val toward = inst.viewer.subtract(inst.anchor)
                face(ms, Vec3d(toward.x, 0.0, toward.z))
            }
            else -> {
                s = 0.03f * cfg.scale * d
                val rel = inst.anchor.subtract(eye)
                ms.translate(rel.x, rel.y, rel.z)
                val f = inst.dir
                basis(ms, Vec3d(-f.z, 0.0, f.x), f, UP)
            }
        }
        ms.scale(s, -s, s)
        val top = -(inst.rows * lh) / 2f
        for (g in inst.glyphs) {
            val a = g.spring.value
            if (a <= 0.002f) continue
            val y = top + g.row * lh + lh / 2f + (1f - a) * 7f - e * 6f
            drawGlyph(g, ms, consumers, tr, g.u, y, 0.65f + 0.35f * a, alpha * a.coerceIn(0f, 1f), accent, pos, layer)
        }
        ms.pop()
    }

    private fun drawOrbit(
        inst: LineInstance, ms: MatrixStack, consumers: VertexConsumerProvider, tr: TextRenderer,
        eye: Vec3d, layer: TextRenderer.TextLayerType, alpha: Float, accent: Int, pos: Long,
    ) {
        val cfg = LyricVibes.config
        val s = 0.019f * cfg.scale * cfg.distance
        val radius = (3.0 * cfg.distance + inst.exit.value * 1.4)
        val base = Math.toRadians(inst.yaw.toDouble()) + inst.age * 0.12
        for ((i, g) in inst.glyphs.withIndex()) {
            val a = g.spring.value
            if (a <= 0.002f) continue
            val theta = base + g.u * s * 1.25 / radius
            val bob = sin(inst.age * 1.4 + i * 0.7) * 0.05
            val p = Vec3d(-sin(theta) * radius, -0.15 + bob + (1f - a) * 0.12, cos(theta) * radius)
            ms.push()
            ms.translate(p.x, p.y, p.z)
            face(ms, Vec3d(-p.x, 0.0, -p.z))
            ms.scale(s, -s, s)
            drawGlyph(g, ms, consumers, tr, 0f, 0f, 0.65f + 0.35f * a, alpha * a.coerceIn(0f, 1f), accent, pos, layer)
            ms.pop()
        }
    }

    private fun shade(rgb: Int, f: Float): Int {
        fun ch(sh: Int) = (((rgb shr sh) and 0xFF) * f).toInt().coerceIn(0, 255) shl sh
        return ch(16) or ch(8) or ch(0)
    }

    private fun drawGlyph(
        g: Glyph, ms: MatrixStack, consumers: VertexConsumerProvider, tr: TextRenderer,
        cx: Float, cy: Float, scale: Float, alpha: Float, accent: Int, pos: Long, layer: TextRenderer.TextLayerType,
    ) {
        if (alpha < 0.02f) return
        val heat = ((pos - g.startMs) / 650f).coerceIn(0f, 1f)
        val rgb = lerpColor(accent or 0xFF000000.toInt(), WHITE or 0xFF000000.toInt(), heat) and 0xFFFFFF
        val x0 = -g.width / 2f
        val y0 = -tr.fontHeight / 2f
        ms.push()
        ms.translate(cx, cy, 0f)
        ms.scale(scale, scale, 1f)
        for (l in LAYERS - 1 downTo 0) {
            val color = if (l == 0) withAlpha(rgb, alpha * FRONT_ALPHA)
            else withAlpha(shade(rgb, 0.52f - l * 0.07f), alpha * (0.78f - l * 0.08f))
            if ((color ushr 24) < 5) continue
            ms.push()
            ms.translate(l * 0.12f, l * 0.3f, -l * DEPTH)
            val m = ms.peek().positionMatrix
            for (i in g.chars.indices) {
                tr.draw(g.chars[i], x0 + g.offsets[i], y0, color, false, m, consumers, layer, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE)
            }
            ms.pop()
        }
        ms.pop()
    }
}
