package dev.krts.lyricvibes.ui

import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.lyrics.PlaybackClock
import dev.krts.lyricvibes.media.MediaBridge
import net.minecraft.client.MinecraftClient
import org.polyfrost.polyui.PolyUI
import org.polyfrost.polyui.Settings
import org.polyfrost.polyui.renderer.impl.NVGRenderer
import org.polyfrost.polyui.unit.Vec2

object Overlay {
    private var polyUI: PolyUI? = null
    private val window = McWindow()
    private val chip = Chip()
    val menu = MenuRoot()
    private var failed = false
    private var lastNanos = 0L

    var uiScale = 1f
        private set

    @JvmStatic
    fun renderFrame() {
        if (failed) return
        var saved = false
        try {
            val mc = MinecraftClient.getInstance()
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
            lastNanos = now
            PlaybackClock.update(MediaBridge.nowPlaying, LyricVibes.config.offsetMs)
            Accent.update(dt)

            val win = mc.window
            val fbW = win.framebufferWidth
            val fbH = win.framebufferHeight
            if (fbW <= 0 || fbH <= 0) return
            uiScale = (fbH / 720f).coerceIn(1f, 3f)
            val lw = fbW / uiScale
            val lh = fbH / uiScale

            chip.tick(dt, lw, lh)
            menu.tick(dt, lw, lh)
            if (!chip.active && !menu.active) return

            GlState.save()
            saved = true
            GlState.prepare(fbW, fbH)
            val ui = polyUI ?: create(lw, lh).also { polyUI = it }
            val m = ui.master
            if (m.width != lw || m.height != lh || m.visibleSize.x != lw || m.visibleSize.y != lh) {
                m.x = 0f
                m.y = 0f
                m.width = lw
                m.height = lh
                m.visibleSize = Vec2(lw, lh)
                ui.inputManager.recalculate()
            }
            window.pixelRatio = uiScale
            window.width = fbW
            window.height = fbH
            ui.master.needsRedraw = true
            ui.render()
        } catch (t: Throwable) {
            failed = true
            LyricVibes.LOGGER.error("LyricVibes overlay crashed and was disabled", t)
        } finally {
            if (saved) GlState.restore()
        }
    }

    private fun create(w: Float, h: Float): PolyUI {
        val settings = Settings().apply {
            cleanupOnShutdown = false
            renderPausingEnabled = false
            framebuffersEnabled = false
            enableDebugKeybind = false
            debug = false
        }
        return PolyUI(chip, menu, renderer = NVGRenderer, settings = settings, size = Vec2(w, h)).also { it.window = window }
    }

    fun hint(text: String) = chip.hint(text)

    private fun toLogical(v: Double) = (v * MinecraftClient.getInstance().window.scaleFactor / uiScale).toFloat()

    fun mouseMoved(x: Double, y: Double) {
        polyUI?.inputManager?.mouseMoved(toLogical(x), toLogical(y))
    }

    fun mousePressed(button: Int) {
        polyUI?.inputManager?.mousePressed(button)
    }

    fun mouseReleased(button: Int) {
        polyUI?.let { if (button == 0) Slider.active?.finish(it.inputManager.mouseX) }
        polyUI?.inputManager?.mouseReleased(button)
    }

    fun mouseScrolled(amount: Double) {
        if (!menu.scroll(amount.toFloat())) polyUI?.inputManager?.mouseScrolled(0f, amount.toFloat())
    }
}
