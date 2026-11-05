package dev.krts.lyricvibes.ui

import net.minecraft.client.MinecraftClient
import org.lwjgl.glfw.GLFW
import org.polyfrost.polyui.PolyUI
import org.polyfrost.polyui.data.Cursor
import org.polyfrost.polyui.renderer.Window

class McWindow : Window(1, 1, 1f) {
    override fun open(polyUI: PolyUI) = this
    override fun close() {}
    override fun supportsRenderPausing() = false
    override fun breakPause() {}
    override fun getClipboard(): String? = MinecraftClient.getInstance().keyboard.clipboard
    override fun setClipboard(text: String?) {
        MinecraftClient.getInstance().keyboard.clipboard = text ?: ""
    }
    override fun setCursor(cursor: Cursor) {}
    override fun getKeyName(keyCode: Int, scanCode: Int) = GLFW.glfwGetKeyName(keyCode, scanCode) ?: "?"
}
