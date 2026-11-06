package dev.krts.lyricvibes.config

import com.google.gson.GsonBuilder
import dev.krts.lyricvibes.LyricVibes
import net.fabricmc.loader.api.FabricLoader
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path

enum class WorldMode(val label: String) { SKY("Sky"), CLOSE("Close"), TRAIL("Trail"), ORBIT("Orbit") }

enum class Side(val label: String) { LEFT("Left"), RIGHT("Right") }

enum class SpotifyMode(val label: String, val hint: String) {
    OFF("Off", "LRCLIB only"),
    FALLBACK("Fallback", "When LRCLIB has no sync"),
    PREFER("Prefer", "Spotify first, then LRCLIB"),
}

enum class OverlayState(val label: String, val chip: Boolean, val lyrics: Boolean) {
    ALL("Everything", true, true),
    LYRICS("Lyrics only", false, true),
    CHIP("Chip only", true, false),
    OFF("Hidden", false, false);

    fun next() = entries[(ordinal + 1) % entries.size]
}

enum class Action(val label: String) {
    PLAY_PAUSE("Play / Pause"),
    NEXT("Next track"),
    PREVIOUS("Previous track"),
    CYCLE_OVERLAY("Cycle overlay"),
    OPEN_MENU("Open menu"),
}

data class Bind(val key: Int = GLFW.GLFW_KEY_UNKNOWN, val mods: Int = 0) {
    val bound get() = key != GLFW.GLFW_KEY_UNKNOWN

    fun matches(key: Int, mods: Int) = bound && key == this.key && (mods and MOD_MASK) == this.mods

    companion object {
        const val MOD_MASK = GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT or GLFW.GLFW_MOD_SUPER
        val NONE = Bind()
    }
}

class LvConfig {
    var lyricsEnabled = true
    var mode = WorldMode.CLOSE
    var opacity = 0.9f
    var scale = 1f
    var distance = 1f
    var seeThrough = true
    var closeSide = Side.RIGHT
    var offsetMs = 0
    var showUnsynced = true
    var spotifyLyrics = SpotifyMode.FALLBACK
    var spotifyPort = 9271

    var chipEnabled = true
    var waveform = true
    var expandOnChange = true
    var accentFromArt = true
    var accent = 0xA78BFA
    var chipScale = 1f
    var overlayState = OverlayState.ALL

    var binds: MutableMap<Action, Bind> = defaultBinds()

    fun bind(action: Action) = binds[action] ?: Bind.NONE

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()
        val dir: Path = FabricLoader.getInstance().configDir.resolve("lyricvibes")
        private val file: Path = FabricLoader.getInstance().configDir.resolve("lyricvibes.json")

        val ACCENTS = intArrayOf(0xA78BFA, 0x60A5FA, 0x34D399, 0xFBBF24, 0xF472B6, 0xF87171, 0xE5E7EB)

        fun defaultBinds(): MutableMap<Action, Bind> = mutableMapOf(
            Action.PLAY_PAUSE to Bind(GLFW.GLFW_KEY_P, GLFW.GLFW_MOD_ALT),
            Action.NEXT to Bind(GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_MOD_ALT),
            Action.PREVIOUS to Bind(GLFW.GLFW_KEY_COMMA, GLFW.GLFW_MOD_ALT),
            Action.CYCLE_OVERLAY to Bind(GLFW.GLFW_KEY_L, GLFW.GLFW_MOD_ALT),
            Action.OPEN_MENU to Bind(GLFW.GLFW_KEY_RIGHT_SHIFT, 0),
        )

        fun load(): LvConfig {
            val cfg = runCatching {
                if (Files.exists(file)) Files.newBufferedReader(file).use { GSON.fromJson(it, LvConfig::class.java) } else null
            }.onFailure { LyricVibes.LOGGER.warn("Config unreadable, using defaults", it) }.getOrNull() ?: LvConfig()
            cfg.sanitize()
            return cfg
        }
    }

    private fun sanitize() {
        if (mode == null) mode = WorldMode.CLOSE
        if (closeSide == null) closeSide = Side.RIGHT
        if (overlayState == null) overlayState = OverlayState.ALL
        if (spotifyLyrics == null) spotifyLyrics = SpotifyMode.FALLBACK
        val fixed = defaultBinds()
        binds?.forEach { (k, v) -> if (k != null && v != null) fixed[k] = v }
        binds = fixed
        opacity = opacity.coerceIn(0.1f, 1f)
        scale = scale.coerceIn(0.5f, 2f)
        distance = distance.coerceIn(0.5f, 2f)
        chipScale = chipScale.coerceIn(0.75f, 1.5f)
        offsetMs = offsetMs.coerceIn(-3000, 3000)
        spotifyPort = spotifyPort.coerceIn(1024, 65535)
    }

    fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            Files.newBufferedWriter(file).use { GSON.toJson(this, it) }
        }.onFailure { LyricVibes.LOGGER.warn("Config save failed", it) }
    }
}
