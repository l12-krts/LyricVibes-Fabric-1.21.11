package dev.krts.lyricvibes.ui

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.input.KeyInput
import net.minecraft.text.Text

class MenuScreen(private val parent: Screen?) : Screen(Text.translatable("lyricvibes.menu.title")) {
    override fun init() = Overlay.menu.attach(this)

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {}

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {}

    override fun shouldPause() = false

    override fun mouseMoved(mouseX: Double, mouseY: Double) = Overlay.mouseMoved(mouseX, mouseY)

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        Overlay.mouseMoved(click.x(), click.y())
        Overlay.mousePressed(click.button())
        return true
    }

    override fun mouseReleased(click: Click): Boolean {
        Overlay.mouseMoved(click.x(), click.y())
        Overlay.mouseReleased(click.button())
        return true
    }

    override fun mouseDragged(click: Click, offsetX: Double, offsetY: Double): Boolean {
        Overlay.mouseMoved(click.x(), click.y())
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        Overlay.mouseScrolled(verticalAmount)
        return true
    }

    override fun keyPressed(input: KeyInput): Boolean = Overlay.menu.keyPressed(input.key(), input.modifiers())

    override fun close() = Overlay.menu.requestClose()

    override fun removed() = Overlay.menu.detach(this)

    fun finish() {
        val mc = MinecraftClient.getInstance()
        if (mc.currentScreen === this) mc.setScreen(parent)
    }
}
