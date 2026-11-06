package dev.krts.lyricvibes.ui

import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.anim.Spring
import dev.krts.lyricvibes.config.Action
import dev.krts.lyricvibes.config.Bind
import dev.krts.lyricvibes.config.LvConfig
import dev.krts.lyricvibes.config.OverlayState
import dev.krts.lyricvibes.config.Side
import dev.krts.lyricvibes.config.SpotifyMode
import dev.krts.lyricvibes.config.WorldMode
import dev.krts.lyricvibes.input.Keybinds
import dev.krts.lyricvibes.lyrics.LyricsService
import dev.krts.lyricvibes.lyrics.LyricsStatus
import dev.krts.lyricvibes.lyrics.PlaybackClock
import dev.krts.lyricvibes.media.MediaBridge
import dev.krts.lyricvibes.spotify.SpotifyLink
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import org.polyfrost.polyui.component.Drawable
import org.polyfrost.polyui.data.PolyImage
import org.polyfrost.polyui.event.Event
import org.polyfrost.polyui.unit.Vec2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class Row(
    val label: String,
    val sub: String?,
    val widget: Widget?,
    val live: (() -> String)? = null,
    val visible: () -> Boolean = { true },
)

private object MenuParts {
    private val cfg get() = LyricVibes.config
    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

    val tabs = listOf("Now Playing", "Lyrics", "Overlay", "Keybinds", "About")
        .mapIndexed { i, l -> TabButton(i, l) { Overlay.menu.select(it) } }

    val prev = IconButton({ Paint.PREV }, 36f, false) { MediaBridge.send("prev") }
    val play = IconButton({ if (MediaBridge.nowPlaying?.playing == true) Paint.PAUSE else Paint.PLAY }, 46f, true) { MediaBridge.togglePlay() }
    val next = IconButton({ Paint.NEXT }, 36f, false) { MediaBridge.send("next") }
    val retry = TextButton("Search again") { LyricsService.retry() }
    val spotifyLink = TextButton({ SpotifyLink.button }) { SpotifyLink.relaunch() }
    val folder = TextButton("Open config folder") { Util.getOperatingSystem().open(LvConfig.dir.toFile()) }
    val resetBinds = TextButton("Reset to defaults") {
        cfg.binds = LvConfig.defaultBinds()
        cfg.save()
    }

    val lyricRows = listOf(
        Row("Show lyrics", "Floating lines in your world", Toggle({ cfg.lyricsEnabled }) { cfg.lyricsEnabled = it }),
        Row("Mode", null, Segmented(WorldMode.entries.map { it.label }, get = { cfg.mode.ordinal }) { cfg.mode = WorldMode.entries[it] }),
        Row("Side", "Where Close mode floats", Segmented(Side.entries.map { it.label }, 150f, { cfg.closeSide.ordinal }, { cfg.closeSide = Side.entries[it] })) { cfg.mode == WorldMode.CLOSE },
        Row("Opacity", null, Slider(0.1f, 1f, 0.05f, { cfg.opacity }, { cfg.opacity = it }, ::pct)),
        Row("Size", null, Slider(0.5f, 2f, 0.05f, { cfg.scale }, { cfg.scale = it }, ::pct)),
        Row("Distance", null, Slider(0.5f, 2f, 0.05f, { cfg.distance }, { cfg.distance = it }, ::pct)),
        Row("Show through blocks", "Never hidden behind terrain", Toggle({ cfg.seeThrough }) { cfg.seeThrough = it }),
        Row("Sync offset", "Nudge if lines run early or late", Slider(-2000f, 2000f, 50f, { cfg.offsetMs.toFloat() }, { cfg.offsetMs = it.roundToInt() }) {
            val v = it.roundToInt()
            if (v > 0) "+$v ms" else "$v ms"
        }),
        Row("Unsynced lyrics", "Drift plain lyrics when timing is missing", Toggle({ cfg.showUnsynced }) { cfg.showUnsynced = it }),
        Row("Spotify lyrics", null, Segmented(SpotifyMode.entries.map { it.label }, 200f, { cfg.spotifyLyrics.ordinal }) {
            cfg.spotifyLyrics = SpotifyMode.entries[it]
            LyricsService.reload()
        }, live = { cfg.spotifyLyrics.hint }),
        Row("Spotify link", null, spotifyLink, live = { SpotifyLink.state.label }) { cfg.spotifyLyrics != SpotifyMode.OFF },
    )

    val overlayRows = listOf(
        Row("Now playing chip", "Small pill at the top of the screen", Toggle({ cfg.chipEnabled }) { cfg.chipEnabled = it }),
        Row("Waveform", "Live bars from your system audio", Toggle({ cfg.waveform }) { cfg.waveform = it }),
        Row("Expand on change", "Grow briefly when a song starts", Toggle({ cfg.expandOnChange }) { cfg.expandOnChange = it }),
        Row("Chip size", null, Slider(0.75f, 1.5f, 0.05f, { cfg.chipScale }, { cfg.chipScale = it }, ::pct)),
        Row("Accent from artwork", "Tint everything with the cover", Toggle({ cfg.accentFromArt }) { cfg.accentFromArt = it }),
        Row("Accent color", null, Swatches(LvConfig.ACCENTS, { cfg.accent }) { cfg.accent = it }) { !cfg.accentFromArt },
        Row("Visibility", "Your overlay key cycles this", Segmented(listOf("All", "Lyrics", "Chip", "Off"), get = { cfg.overlayState.ordinal }) { cfg.overlayState = OverlayState.entries[it] }),
    )

    val bindRows = Action.entries.map { Row(it.label, null, KeybindField(it)) }

    val contentRows = listOf(lyricRows, overlayRows, bindRows)
    val nowPlayingWidgets = listOf(prev, play, next, retry)

    val all: List<Widget> = tabs + nowPlayingWidgets + contentRows.flatten().mapNotNull { it.widget } + listOf(resetBinds, folder)
}

class MenuRoot : Drawable(*MenuParts.all.toTypedArray(), at = Vec2(1f, 1f), size = Vec2(10f, 10f)) {
    var open = false
        private set
    var tab = 0
        private set
    var capturing: KeybindField? = null

    private val openSpring = Spring(Spring.DEFAULT)
    private val tabPill = Spring(Spring.BOUNCY)
    private val contentFade = Spring(Spring.SMOOTH, 1f)
    private val scrollSpring = Spring(Spring.SNAPPY)
    private var scrollTarget = 0f
    private var contentHeight = 0f
    private var screen: MenuScreen? = null
    private var sw = 0f
    private var sh = 0f
    private var px = 0f
    private var py = 0f
    private var pw = 0f
    private var ph = 0f
    private var artImage: PolyImage? = null
    private var artKey: String? = null

    val active get() = openSpring.value > 0.002f || openSpring.target > 0f

    init {
        acceptsInput = true
    }

    fun attach(s: MenuScreen) {
        if (screen !== s && openSpring.value < 0.05f) {
            openSpring.snap(0f)
            contentFade.snap(1f)
        }
        screen = s
        open = true
    }

    fun detach(s: MenuScreen) {
        if (screen === s) {
            screen = null
            open = false
            capturing = null
        }
    }

    fun requestClose() {
        open = false
        capturing = null
    }

    fun select(i: Int) {
        if (i == tab) return
        tab = i
        capturing = null
        scrollTarget = 0f
        scrollSpring.snap(0f)
        contentFade.snap(0f)
    }

    fun scroll(amount: Float): Boolean {
        if (!open || tab !in 1..3) return false
        val view = ph - 76f
        scrollTarget = (scrollTarget - amount * 36f).coerceIn(0f, max(0f, contentHeight - view))
        return true
    }

    fun keyPressed(key: Int, mods: Int): Boolean {
        val field = capturing
        val cfg = LyricVibes.config
        if (field != null) {
            when {
                key == GLFW.GLFW_KEY_ESCAPE -> capturing = null
                key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE -> {
                    cfg.binds[field.action] = Bind.NONE
                    capturing = null
                }
                Keybinds.isModifier(key) -> return true
                else -> {
                    val bind = Bind(key, mods and Bind.MOD_MASK)
                    cfg.binds.entries.filter { it.key != field.action && it.value == bind }.forEach { cfg.binds[it.key] = Bind.NONE }
                    cfg.binds[field.action] = bind
                    capturing = null
                }
            }
            cfg.save()
            return true
        }
        if (key == GLFW.GLFW_KEY_ESCAPE || cfg.bind(Action.OPEN_MENU).matches(key, mods)) {
            requestClose()
            return true
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            select((tab + if (mods and GLFW.GLFW_MOD_SHIFT != 0) 4 else 1) % 5)
            return true
        }
        return false
    }

    override fun accept(event: Event): Boolean {
        if (event is Event.Mouse.Clicked) {
            capturing = null
            val mx = event.x
            val my = event.y
            if (mx < px || my < py || mx > px + pw || my > py + ph) requestClose()
            return true
        }
        return super.accept(event)
    }

    fun tick(dt: Float, w: Float, h: Float) {
        sw = w
        sh = h
        openSpring.to(if (open) 1f else 0f).update(dt)
        if (!open && openSpring.value < 0.02f) {
            screen?.let {
                screen = null
                it.finish()
            }
        }
        tabPill.to(tab.toFloat()).update(dt)
        contentFade.to(1f).update(dt)
        scrollSpring.to(scrollTarget).update(dt)

        x = 0f
        y = 0f
        width = w
        height = h
        visibleSize = Vec2(w, h)
        clipped = false
        renders = active
        isEnabled = open

        pw = min(620f, w - 24f)
        ph = min(420f, h - 24f)
        px = (w - pw) / 2f
        py = (h - ph) / 2f + (1f - openSpring.value) * 18f
        if (initialized) layoutWidgets(dt)
    }

    private fun layoutWidgets(dt: Float) {
        val fadeAll = openSpring.value.coerceIn(0f, 1f)
        val cf = contentFade.value.coerceIn(0f, 1f)
        val shift = (1f - cf) * 14f
        for (w in MenuParts.all) {
            w.shown = false
            w.clip = null
        }

        for ((i, t) in MenuParts.tabs.withIndex()) {
            t.shown = true
            t.fade = fadeAll
            t.place(px + 12f, py + 68f + i * 38f, 136f, 38f)
        }

        val cx = px + 172f + shift
        val cw = pw - 172f - 24f
        val top = py + 62f
        val clip = floatArrayOf(px + 160f, top - 4f, pw - 160f, ph - 62f - 8f)

        when (tab) {
            0 -> {
                val np = MediaBridge.nowPlaying
                val info = cx + 152f
                val baseY = top + 6f
                if (np != null) {
                    val ty = baseY + 132f - 46f
                    placeIn(MenuParts.prev, info, ty + 5f, fadeAll * cf, null)
                    placeIn(MenuParts.play, info + 46f, ty, fadeAll * cf, null)
                    placeIn(MenuParts.next, info + 102f, ty + 5f, fadeAll * cf, null)
                    val rw = MenuParts.retry.measure()
                    placeIn(MenuParts.retry, cx + cw - rw, baseY + 150f, fadeAll * cf, null, rw)
                }
            }
            1, 2, 3 -> {
                val rows = MenuParts.contentRows[tab - 1].filter { it.visible() }
                var ry = top - scrollSpring.value
                for (row in rows) {
                    val wdg = row.widget ?: continue
                    val ww = (wdg as? TextButton)?.measure() ?: wdg.preferredWidth
                    placeIn(wdg, cx + cw - ww, ry + (44f - wdg.preferredHeight) / 2f, fadeAll * cf, clip, ww)
                    ry += 44f
                }
                contentHeight = rows.size * 44f + if (tab == 3) 60f else 0f
                if (tab == 3) {
                    val rw = MenuParts.resetBinds.measure()
                    placeIn(MenuParts.resetBinds, cx, ry + 16f, fadeAll * cf, clip, rw)
                }
            }
            4 -> {
                val fw = MenuParts.folder.measure()
                placeIn(MenuParts.folder, cx, top + 230f, fadeAll * cf, null, fw)
            }
        }
        for (w in MenuParts.all) {
            if (!w.shown) w.place(-1000f, -1000f)
            w.tick(dt)
        }
    }

    private fun placeIn(w: Widget, x: Float, y: Float, fade: Float, clip: FloatArray?, width: Float = w.preferredWidth) {
        w.shown = true
        w.fade = fade
        w.clip = clip
        w.place(x, y, width, w.preferredHeight)
    }

    override fun render() {
        val r = renderer
        val a = openSpring.value.coerceIn(0f, 1f)
        val accent = Accent.current
        r.rect(0f, 0f, sw, sh, Paint.c(Paint.fade(0x73000000, a)), 0f)
        val s = 0.96f + 0.04f * openSpring.value
        r.translate(px + pw / 2f, py + ph / 2f)
        r.scale(s, s, 0f, 0f)
        r.translate(-(px + pw / 2f), -(py + ph / 2f))

        Paint.glass(r, px, py, pw, ph, 18f, a, accent)
        r.rect(px + 160f, py + 14f, 1f, ph - 28f, Paint.c(Paint.fade(Paint.STROKE, a)), 0f)

        NVGExtras.circle(px + 27f, py + 32f, 6f, Paint.fade(accent or 0xFF000000.toInt(), a))
        Paint.text(r, Paint.semi, px + 40f, py + 32f, "LyricVibes", Paint.fade(Paint.TEXT, a), 16f)
        Paint.text(r, Paint.regular, px + 22f, py + ph - 22f, "v${LyricVibes.VERSION}", Paint.fade(Paint.FAINT, a), 11f)
        val pillY = py + 70f + tabPill.value * 38f
        r.rect(px + 12f, pillY, 136f, 34f, Paint.c(Paint.fade(0x1FFFFFFF, a)), 10f)
        r.rect(px + 12f, pillY + 9f, 3f, 16f, Paint.c(Paint.fade(accent or 0xFF000000.toInt(), a)), 1.5f)

        val cf = contentFade.value.coerceIn(0f, 1f) * a
        val shift = (1f - contentFade.value) * 14f
        val cx = px + 172f + shift
        val cw = pw - 172f - 24f
        Paint.text(r, Paint.semi, cx, py + 34f, MenuParts.tabs[tab].label, Paint.fade(Paint.TEXT, cf), 18f)

        when (tab) {
            0 -> drawNowPlaying(cx, py + 68f, cw, cf, accent)
            1, 2, 3 -> drawRows(cx, py + 62f, cw, cf)
            4 -> drawAbout(cx, py + 68f, cw, cf, accent)
        }
    }

    private fun drawRows(cx: Float, top: Float, cw: Float, alpha: Float) {
        val r = renderer
        r.pushScissor(px + 160f, top - 4f, pw - 160f, ph - 62f - 8f)
        val rows = MenuParts.contentRows[tab - 1].filter { it.visible() }
        var ry = top - scrollSpring.value
        for ((i, row) in rows.withIndex()) {
            val mid = ry + 22f
            val sub = row.live?.invoke() ?: row.sub
            if (sub != null) {
                Paint.text(r, Paint.medium, cx, mid - 7f, row.label, Paint.fade(Paint.TEXT, alpha), 13f)
                Paint.text(r, Paint.regular, cx, mid + 9f, sub, Paint.fade(Paint.FAINT, alpha), 11f)
            } else {
                Paint.text(r, Paint.medium, cx, mid, row.label, Paint.fade(Paint.TEXT, alpha), 13f)
            }
            if (i < rows.size - 1) r.rect(cx, ry + 43.5f, cw, 0.6f, Paint.c(Paint.fade(0x0FFFFFFF, alpha)), 0f)
            ry += 44f
        }
        if (tab == 3) {
            Paint.text(r, Paint.regular, cx, ry + 64f, " ", Paint.fade(Paint.FAINT, alpha), 11f)
        }
        r.popScissor()
        if (contentHeight > ph - 76f) {
            val view = ph - 76f
            val barH = max(24f, view * view / contentHeight)
            val maxScroll = contentHeight - view
            val t = if (maxScroll > 0f) (scrollSpring.value / maxScroll).coerceIn(0f, 1f) else 0f
            r.rect(px + pw - 9f, top + (view - barH) * t, 3f, barH, Paint.c(Paint.fade(0x33FFFFFF, alpha)), 1.5f)
        }
    }

    private fun drawNowPlaying(cx: Float, top: Float, cw: Float, alpha: Float, accent: Int) {
        val r = renderer
        val np = MediaBridge.nowPlaying
        if (np == null) {
            val mid = top + 110f
            Paint.text(r, Paint.semi, cx, mid - 12f, "Nothing playing", Paint.fade(Paint.TEXT, alpha), 16f)
            Paint.text(r, Paint.regular, cx, mid + 12f, "Play something in Spotify, your browser or any media app.", Paint.fade(Paint.MUTED, alpha), 12f)
            Paint.text(r, Paint.regular, cx, mid + 32f, "Bridge: ${MediaBridge.status}", Paint.fade(Paint.FAINT, alpha), 11f)
            return
        }
        val art = MediaBridge.art?.takeIf { it.key == np.key }
        if (art != null && artKey != art.key) {
            artImage?.let { r.delete(it) }
            artImage = PolyImage.of(art.file.toUri().toString())
            artKey = art.key
        }
        val img = artImage?.takeIf { artKey == np.key }
        NVGExtras.shadow(cx, top + 4f, 132f, 132f, 14f, 18f, Paint.fade(0x80000000.toInt(), alpha))
        if (img != null) NVGExtras.imageCover(img, cx, top, 132f, 132f, 14f, alpha)
        else {
            r.rect(cx, top, 132f, 132f, Paint.c(Paint.fade(accent or 0x55000000, alpha)), 14f)
            r.image(Paint.NOTE, cx + 40f, top + 40f, 52f, 52f, 0f, Paint.fade(0xFFFFFFFF.toInt(), alpha))
        }

        val ix = cx + 152f
        val iw = cw - 152f
        Paint.text(r, Paint.semi, ix, top + 14f, Paint.ellipsize(r, Paint.semi, np.title, 18f, iw), Paint.fade(Paint.TEXT, alpha), 18f)
        Paint.text(r, Paint.medium, ix, top + 38f, Paint.ellipsize(r, Paint.medium, np.artist, 13f, iw), Paint.fade(Paint.MUTED, alpha), 13f)
        val sub = listOf(np.album, "via ${np.kind.label}").filter { it.isNotBlank() }.joinToString("  ·  ")
        Paint.text(r, Paint.regular, ix, top + 56f, Paint.ellipsize(r, Paint.regular, sub, 11f, iw), Paint.fade(Paint.FAINT, alpha), 11f)

        val pos = (PlaybackClock.positionMs - LyricVibes.config.offsetMs).coerceAtLeast(0)
        val dur = np.durationMs
        val by = top + 74f
        r.rect(ix, by, iw, 4f, Paint.c(Paint.fade(0x26FFFFFF, alpha)), 2f)
        if (dur > 0) r.rect(ix, by, max(4f, iw * (pos.toFloat() / dur).coerceIn(0f, 1f)), 4f, Paint.c(Paint.fade(accent or 0xFF000000.toInt(), alpha)), 2f)
        Paint.text(r, Paint.regular, ix, by + 14f, time(pos), Paint.fade(Paint.FAINT, alpha), 10f)
        if (dur > 0) {
            val d = time(dur)
            Paint.text(r, Paint.regular, ix + iw - Paint.width(r, Paint.regular, d, 10f), by + 14f, d, Paint.fade(Paint.FAINT, alpha), 10f)
        }

        val status = LyricsService.status
        val source = LyricsService.lyrics?.source
        val dot = when (status) {
            LyricsStatus.SYNCED -> accent or 0xFF000000.toInt()
            LyricsStatus.UNSYNCED -> 0xFFFBBF24.toInt()
            LyricsStatus.LOADING -> 0xFF60A5FA.toInt()
            else -> 0xFF6B7280.toInt()
        }
        val ly = top + 150f + 14f
        NVGExtras.circle(cx + 5f, ly, 4f, Paint.fade(dot, alpha))
        val label = listOfNotNull(status.label, source, SpotifyLink.hint(np)).joinToString("  ·  ")
        Paint.text(r, Paint.medium, cx + 16f, ly, label, Paint.fade(Paint.MUTED, alpha), 12f)
        Paint.text(r, Paint.regular, cx, ly + 30f, "Shortcuts: ${Keybinds.describe(LyricVibes.config.bind(Action.PLAY_PAUSE))} play/pause, " +
            "${Keybinds.describe(LyricVibes.config.bind(Action.CYCLE_OVERLAY))} overlay", Paint.fade(Paint.FAINT, alpha), 11f)
    }

    private fun drawAbout(cx: Float, top: Float, cw: Float, alpha: Float, accent: Int) {
        val r = renderer
        val lines = listOf(
            Triple(Paint.semi, 15f, "Lyrics that live in your world."),
            Triple(Paint.regular, 12f, "LyricVibes reads what Windows is playing (Spotify, browsers, players),"),
            Triple(Paint.regular, 12f, "finds synced lyrics and lets them drift through Minecraft beside you."),
            null,
            Triple(Paint.medium, 12f, "Lyrics  >  LRCLIB (lrclib.net), community synced lyrics"),
            Triple(Paint.medium, 12f, "Spotify  >  lyrics your Spotify app loads, once linked"),
            Triple(Paint.medium, 12f, "Interface  >  PolyUI by Polyfrost (LGPL-3.0)"),
            Triple(Paint.medium, 12f, "Font  >  Poppins (SIL Open Font License)"),
            null,
            Triple(Paint.regular, 11f, "Bridge: ${MediaBridge.status}   ·   Player: ${MediaBridge.nowPlaying?.app?.substringAfterLast('!')?.ifBlank { null } ?: "none"}   ·   Spotify: ${SpotifyLink.state.label}"),
            Triple(Paint.regular, 11f, "Config and cache in config/lyricvibes."),
        )
        var y = top + 8f
        for (l in lines) {
            if (l == null) {
                y += 12f
                continue
            }
            val col = if (l.first == Paint.semi) Paint.TEXT else if (l.first == Paint.medium) Paint.MUTED else Paint.FAINT
            Paint.text(r, l.first, cx, y, Paint.ellipsize(r, l.first, l.third, l.second, cw), Paint.fade(col, alpha), l.second)
            y += l.second + 10f
        }
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
