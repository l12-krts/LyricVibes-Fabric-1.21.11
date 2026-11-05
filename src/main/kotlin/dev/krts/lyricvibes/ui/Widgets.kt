package dev.krts.lyricvibes.ui

import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.anim.Spring
import dev.krts.lyricvibes.anim.lerpColor
import dev.krts.lyricvibes.config.Action
import dev.krts.lyricvibes.input.Keybinds
import org.polyfrost.polyui.component.Drawable
import org.polyfrost.polyui.data.PolyImage
import org.polyfrost.polyui.event.Event
import org.polyfrost.polyui.unit.Vec2
import kotlin.math.max
import kotlin.math.roundToInt

abstract class Widget(val preferredWidth: Float, val preferredHeight: Float) : Drawable(at = Vec2(1f, 1f), size = Vec2(preferredWidth, preferredHeight)) {
    protected val hover = Spring(0.25f, 1f)
    protected val press = Spring(Spring.SNAPPY)
    var fade = 1f
    var shown = true
    var clip: FloatArray? = null

    init {
        acceptsInput = true
    }

    protected val hovered get() = initialized && shown && polyUI.inputManager.mouseOver === this
    protected val accent get() = Accent.current or 0xFF000000.toInt()

    open fun tick(dt: Float) {
        if (!initialized) return
        hover.to(if (hovered) 1f else 0f).update(dt)
        press.to(if (hovered && polyUI.mouseDown) 1f else 0f).update(dt)
    }

    fun place(px: Float, py: Float, pw: Float = preferredWidth, ph: Float = preferredHeight) {
        x = px
        y = py
        width = pw
        height = ph
        visibleSize = Vec2(pw, ph)
        clipped = false
        alpha = fade.coerceIn(0f, 1f)
        val c = clip
        val inside = c == null || (py + ph > c[1] && py < c[1] + c[3])
        renders = shown && fade > 0.01f && inside
        isEnabled = renders
    }

    abstract fun paint()

    override fun render() {
        clip?.let { renderer.pushScissorIntersecting(it[0], it[1], it[2], it[3]) }
        paint()
    }

    open fun onPress(mx: Float, my: Float) = false
    open fun onClick(button: Int) = false

    override fun accept(event: Event): Boolean {
        val handled = when (event) {
            is Event.Mouse.Pressed -> event.button == 0 && onPress(event.x, event.y)
            is Event.Mouse.Clicked -> onClick(event.button)
            else -> false
        }
        return super.accept(event) || handled
    }

    protected fun save() = LyricVibes.config.save()
}

class Toggle(private val get: () -> Boolean, private val set: (Boolean) -> Unit) : Widget(44f, 26f) {
    private val knob = Spring(Spring.BOUNCY, if (get()) 1f else 0f)

    override fun tick(dt: Float) {
        super.tick(dt)
        knob.to(if (get()) 1f else 0f).update(dt)
    }

    override fun onClick(button: Int): Boolean {
        if (button != 0) return false
        set(!get())
        save()
        return true
    }

    override fun paint() {
        val v = knob.value
        val track = lerpColor(0xFF3A3A44.toInt(), accent, v.coerceIn(0f, 1f))
        renderer.rect(x, y, width, height, Paint.c(track), height / 2f)
        val d = height - 6f
        val stretch = press.value * 5f
        val kx = x + 3f + (width - d - 6f - stretch) * v
        NVGExtras.shadow(kx, y + 3.5f, d + stretch, d, d / 2f, 4f, 0x40000000)
        renderer.rect(kx, y + 3f, d + stretch, d, Paint.c(0xFFFFFFFF.toInt()), d / 2f)
    }
}

class Slider(
    private val min: Float,
    private val max: Float,
    private val step: Float,
    private val get: () -> Float,
    private val set: (Float) -> Unit,
    private val format: (Float) -> String,
) : Widget(220f, 26f) {
    private var dragging = false
    private val knobScale = Spring(Spring.BOUNCY, 1f)
    private val shownValue = Spring(Spring.SNAPPY, (get() - min) / (max - min))
    private val labelW = 54f

    override fun tick(dt: Float) {
        super.tick(dt)
        if (dragging) {
            if (polyUI.mouseDown) apply(polyUI.mouseX) else finish(polyUI.mouseX)
        }
        knobScale.to(if (dragging) 1.25f else if (hovered) 1.08f else 1f).update(dt)
        shownValue.to((get() - min) / (max - min)).update(dt)
    }

    private fun apply(mx: Float) {
        val tw = width - labelW
        val t = ((mx - x - 8f) / (tw - 16f)).coerceIn(0f, 1f)
        val raw = min + t * (max - min)
        set(((raw / step).roundToInt() * step).coerceIn(min, max))
    }

    override fun onPress(mx: Float, my: Float): Boolean {
        dragging = true
        active = this
        apply(mx)
        return true
    }

    fun finish(mx: Float) {
        if (!dragging) return
        apply(mx)
        dragging = false
        active = null
        save()
    }

    companion object {
        var active: Slider? = null
    }

    override fun paint() {
        val tw = width - labelW
        val cy = y + height / 2f
        val v = shownValue.value.coerceIn(0f, 1f)
        val left = x + 8f
        val span = tw - 16f
        renderer.rect(left, cy - 2.5f, span, 5f, Paint.c(0xFF34343E.toInt()), 2.5f)
        renderer.rect(left, cy - 2.5f, max(5f, span * v), 5f, Paint.c(accent), 2.5f)
        val r = 8f * knobScale.value
        val kx = left + span * v
        NVGExtras.shadow(kx - r, cy - r + 1f, r * 2f, r * 2f, r, 5f, 0x55000000)
        NVGExtras.circle(kx, cy, r, 0xFFFFFFFF.toInt())
        val label = format(get())
        val lw = Paint.width(renderer, Paint.medium, label, 12f)
        Paint.text(renderer, Paint.medium, x + width - lw, cy, label, Paint.MUTED, 12f)
    }
}

class Segmented(
    private val options: List<String>,
    width: Float = 260f,
    private val get: () -> Int,
    private val set: (Int) -> Unit,
) : Widget(width, 28f) {
    private val pill = Spring(Spring.BOUNCY, get().toFloat())
    private var hoverIndex = -1

    override fun tick(dt: Float) {
        super.tick(dt)
        pill.to(get().toFloat()).update(dt)
        hoverIndex = if (hovered) ((polyUI.mouseX - x) / (width / options.size)).toInt().coerceIn(0, options.size - 1) else -1
    }

    override fun onClick(button: Int): Boolean {
        if (button != 0 || hoverIndex < 0) return false
        set(hoverIndex)
        save()
        return true
    }

    override fun paint() {
        val seg = width / options.size
        renderer.rect(x, y, width, height, Paint.c(Paint.SURFACE), height / 2f)
        val px = x + 3f + seg * pill.value
        NVGExtras.shadow(px, y + 3f, seg - 6f, height - 6f, (height - 6f) / 2f, 6f, 0x33000000)
        renderer.rect(px, y + 3f, seg - 6f, height - 6f, Paint.c(Paint.fade(accent, 0.9f)), (height - 6f) / 2f)
        for ((i, o) in options.withIndex()) {
            val tw = Paint.width(renderer, Paint.medium, o, 12f)
            val selected = i == get()
            val col = if (selected) 0xFF0E0E12.toInt() else if (i == hoverIndex) Paint.TEXT else Paint.MUTED
            Paint.text(renderer, Paint.medium, x + seg * i + (seg - tw) / 2f, y + height / 2f, o, col, 12f)
        }
    }
}

class KeybindField(val action: Action) : Widget(170f, 28f) {
    private val listen = Spring(Spring.SNAPPY)

    val listening get() = Overlay.menu.capturing === this

    override fun tick(dt: Float) {
        super.tick(dt)
        listen.to(if (listening) 1f else 0f).update(dt)
    }

    override fun onClick(button: Int): Boolean {
        Overlay.menu.capturing = if (listening) null else this
        return true
    }

    override fun paint() {
        val l = listen.value.coerceIn(0f, 1f)
        val bg = lerpColor(Paint.SURFACE, Paint.fade(accent, 0.25f), l)
        renderer.rect(x, y, width, height, Paint.c(lerpColor(bg, 0x24FFFFFF, hover.value * (1f - l))), 8f)
        renderer.hollowRect(x, y, width, height, Paint.c(lerpColor(Paint.STROKE, accent, l)), 1f, 8f)
        val label = if (listening) "Press keys…" else Keybinds.describe(LyricVibes.config.bind(action))
        val text = Paint.ellipsize(renderer, Paint.medium, label, 12f, width - 16f)
        val tw = Paint.width(renderer, Paint.medium, text, 12f)
        Paint.text(renderer, Paint.medium, x + (width - tw) / 2f, y + height / 2f, text, if (listening) Paint.TEXT else Paint.MUTED, 12f)
    }
}

class Swatches(private val colors: IntArray, private val get: () -> Int, private val set: (Int) -> Unit) :
    Widget(colors.size * 28f, 26f) {
    private val ring = Spring(Spring.BOUNCY, colors.indexOf(get()).coerceAtLeast(0).toFloat())
    private var hoverIndex = -1

    override fun tick(dt: Float) {
        super.tick(dt)
        ring.to(colors.indexOf(get()).toFloat()).update(dt)
        hoverIndex = if (hovered) ((polyUI.mouseX - x) / 28f).toInt().coerceIn(0, colors.size - 1) else -1
    }

    override fun onClick(button: Int): Boolean {
        if (hoverIndex < 0) return false
        set(colors[hoverIndex])
        save()
        return true
    }

    override fun paint() {
        val cy = y + height / 2f
        for ((i, c) in colors.withIndex()) {
            val r = if (i == hoverIndex) 9.5f else 8.5f
            NVGExtras.circle(x + 14f + i * 28f, cy, r, c or 0xFF000000.toInt())
        }
        if (ring.value >= -0.5f) NVGExtras.ring(x + 14f + ring.value * 28f, cy, 12.5f, 1.6f, 0xE6FFFFFF.toInt())
    }
}

class IconButton(private val icon: () -> PolyImage, private val diameter: Float, private val primary: Boolean, private val onClick: () -> Unit) :
    Widget(diameter, diameter) {
    override fun onClick(button: Int): Boolean {
        if (button != 0) return false
        onClick()
        return true
    }

    override fun paint() {
        val s = 1f - press.value * 0.08f
        val d = diameter * s
        val cx = x + width / 2f
        val cy = y + height / 2f
        val bg = if (primary) accent else lerpColor(0x00FFFFFF, 0x1FFFFFFF, hover.value)
        if (primary) NVGExtras.shadow(cx - d / 2f, cy - d / 2f + 2f, d, d, d / 2f, 10f, Paint.fade(accent, 0.45f))
        NVGExtras.circle(cx, cy, d / 2f, bg)
        val i = d * 0.46f
        renderer.image(icon(), cx - i / 2f, cy - i / 2f, i, i, 0f, if (primary) 0xFF0E0E12.toInt() else 0xFFFFFFFF.toInt())
    }
}

class TextButton(private val label: String, private val onClick: () -> Unit) : Widget(120f, 28f) {
    fun measure() = if (initialized) Paint.width(renderer, Paint.medium, label, 12f) + 28f else label.length * 7f + 28f

    override fun onClick(button: Int): Boolean {
        if (button != 0) return false
        onClick()
        return true
    }

    override fun paint() {
        val s = 1f - press.value * 0.04f
        val w = width * s
        val h = height * s
        val bx = x + (width - w) / 2f
        val by = y + (height - h) / 2f
        renderer.rect(bx, by, w, h, Paint.c(lerpColor(Paint.SURFACE, 0x2EFFFFFF, hover.value)), h / 2f)
        Paint.text(renderer, Paint.medium, bx + 14f, by + h / 2f, label, Paint.TEXT, 12f * s)
    }
}

class TabButton(val index: Int, val label: String, private val onSelect: (Int) -> Unit) : Widget(136f, 38f) {
    override fun onClick(button: Int): Boolean {
        onSelect(index)
        return true
    }

    override fun paint() {
        val selected = Overlay.menu.tab == index
        if (!selected && hover.value > 0.01f) renderer.rect(x, y + 2f, width, height - 4f, Paint.c(Paint.fade(0x14FFFFFF, hover.value)), 10f)
        Paint.text(renderer, if (selected) Paint.semi else Paint.medium, x + 14f, y + height / 2f, label, if (selected) Paint.TEXT else Paint.MUTED, 13f)
    }
}
