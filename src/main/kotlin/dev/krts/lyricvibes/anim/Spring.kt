package dev.krts.lyricvibes.anim

import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

class Spring(
    var response: Float = DEFAULT.first,
    var damping: Float = DEFAULT.second,
    initial: Float = 0f,
) {
    constructor(preset: Pair<Float, Float>, initial: Float = 0f) : this(preset.first, preset.second, initial)

    var value = initial
    var velocity = 0f
    var target = initial

    val settled get() = value == target && velocity == 0f

    fun to(target: Float): Spring {
        this.target = target
        return this
    }

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    fun update(dt: Float): Float {
        if (settled) return value
        val omega = 2f * PI.toFloat() / response
        val k = omega * omega
        val c = 4f * PI.toFloat() * damping / response
        var remaining = min(dt, 0.1f)
        while (remaining > 0f) {
            val h = min(STEP, remaining)
            val accel = -k * (value - target) - c * velocity
            velocity += accel * h
            value += velocity * h
            remaining -= h
        }
        if (abs(value - target) < 1e-4f && abs(velocity) < 1e-3f) {
            value = target
            velocity = 0f
        }
        return value
    }

    companion object {
        private const val STEP = 1f / 240f
        val DEFAULT = 0.55f to 0.825f
        val SNAPPY = 0.38f to 0.86f
        val BOUNCY = 0.5f to 0.68f
        val SMOOTH = 0.5f to 1f
        val GENTLE = 0.9f to 1f
    }
}

class Spring3(preset: Pair<Float, Float> = Spring.SMOOTH) {
    private val x = Spring(preset)
    private val y = Spring(preset)
    private val z = Spring(preset)
    private var primed = false

    val value get() = Vec3d(x.value.toDouble(), y.value.toDouble(), z.value.toDouble())

    fun update(target: Vec3d, dt: Float): Vec3d {
        if (!primed || value.squaredDistanceTo(target) > 400.0) {
            x.snap(target.x.toFloat()); y.snap(target.y.toFloat()); z.snap(target.z.toFloat())
            primed = true
        }
        x.to(target.x.toFloat()).update(dt)
        y.to(target.y.toFloat()).update(dt)
        z.to(target.z.toFloat()).update(dt)
        return value
    }
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

fun lerpColor(a: Int, b: Int, t: Float): Int {
    val tt = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (lerp(((a shr s) and 0xFF).toFloat(), ((b shr s) and 0xFF).toFloat(), tt) + 0.5f).toInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

fun withAlpha(rgb: Int, alpha: Float) = ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (rgb and 0xFFFFFF)
