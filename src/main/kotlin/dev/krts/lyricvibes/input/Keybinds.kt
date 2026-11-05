package dev.krts.lyricvibes.input

import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.config.Action
import dev.krts.lyricvibes.config.Bind
import dev.krts.lyricvibes.media.MediaBridge
import dev.krts.lyricvibes.ui.Overlay
import dev.krts.lyricvibes.ui.MenuScreen
import net.minecraft.client.MinecraftClient
import net.minecraft.client.input.KeyInput
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

object Keybinds {
    private val MODIFIER_KEYS = setOf(
        GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT,
        GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL,
        GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT,
        GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER,
    )

    fun isModifier(key: Int) = key in MODIFIER_KEYS

    @JvmStatic
    fun onKey(window: Long, action: Int, input: KeyInput): Boolean {
        val mc = MinecraftClient.getInstance()
        if (action != GLFW.GLFW_PRESS || window != mc.window.handle) return false
        if (mc.currentScreen != null) return false
        val cfg = LyricVibes.config
        val hit = Action.entries.firstOrNull { cfg.bind(it).matches(input.key(), input.modifiers()) } ?: return false
        run(hit)
        return true
    }

    fun run(action: Action) {
        val mc = MinecraftClient.getInstance()
        when (action) {
            Action.PLAY_PAUSE -> MediaBridge.togglePlay()
            Action.NEXT -> MediaBridge.send("next")
            Action.PREVIOUS -> MediaBridge.send("prev")
            Action.CYCLE_OVERLAY -> {
                val cfg = LyricVibes.config
                cfg.overlayState = cfg.overlayState.next()
                cfg.save()
                Overlay.hint(cfg.overlayState.label)
            }
            Action.OPEN_MENU -> mc.setScreen(MenuScreen(null))
        }
    }

    fun describe(bind: Bind): String {
        if (!bind.bound) return "Unbound"
        val parts = ArrayList<String>(4)
        if (bind.mods and GLFW.GLFW_MOD_CONTROL != 0) parts += "Ctrl"
        if (bind.mods and GLFW.GLFW_MOD_ALT != 0) parts += "Alt"
        if (bind.mods and GLFW.GLFW_MOD_SHIFT != 0) parts += "Shift"
        if (bind.mods and GLFW.GLFW_MOD_SUPER != 0) parts += "Win"
        parts += runCatching { InputUtil.Type.KEYSYM.createFromCode(bind.key).localizedText.string }.getOrElse { "Key ${bind.key}" }
        return parts.joinToString(" + ")
    }
}
