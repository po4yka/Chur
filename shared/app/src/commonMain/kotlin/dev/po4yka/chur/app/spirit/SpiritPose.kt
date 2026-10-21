package dev.po4yka.chur.app.spirit

import kotlin.math.sqrt

/** Column-major TRS interpolation. The bundled asset uses positive, nonsingular scale. */
fun blendSpiritPose(from: FloatArray, to: FloatArray, progress: Float): FloatArray {
    require(from.size == 16 && to.size == 16)
    val t = progress.coerceIn(0f, 1f)
    if (t == 0f) return from.copyOf()
    if (t == 1f) return to.copyOf()
    fun scale(m: FloatArray) = FloatArray(3) { c ->
        val i = c * 4; sqrt(m[i] * m[i] + m[i + 1] * m[i + 1] + m[i + 2] * m[i + 2]).coerceAtLeast(.000001f)
    }
    fun quaternion(m: FloatArray, s: FloatArray): FloatArray {
        val a = m[0] / s[0]; val b = m[4] / s[1]; val c = m[8] / s[2]
        val d = m[1] / s[0]; val e = m[5] / s[1]; val f = m[9] / s[2]
        val g = m[2] / s[0]; val h = m[6] / s[1]; val i = m[10] / s[2]
        val trace = a + e + i
        return when {
            trace > 0f -> { val q = sqrt(trace + 1f) * 2f; floatArrayOf((h - f) / q, (c - g) / q, (d - b) / q, q / 4f) }
            a > e && a > i -> { val q = sqrt(1f + a - e - i) * 2f; floatArrayOf(q / 4f, (b + d) / q, (c + g) / q, (h - f) / q) }
            e > i -> { val q = sqrt(1f + e - a - i) * 2f; floatArrayOf((b + d) / q, q / 4f, (f + h) / q, (c - g) / q) }
            else -> { val q = sqrt(1f + i - a - e) * 2f; floatArrayOf((c + g) / q, (f + h) / q, q / 4f, (d - b) / q) }
        }
    }
    val fs = scale(from); val ts = scale(to)
    val fq = quaternion(from, fs); val tq = quaternion(to, ts)
    val sign = if ((0..3).sumOf { (fq[it] * tq[it]).toDouble() } < 0) -1f else 1f
    val q = FloatArray(4) { fq[it] * (1f - t) + tq[it] * sign * t }
    val length = sqrt(q.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(.000001f)
    for (n in q.indices) q[n] /= length
    val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
    val out = floatArrayOf(
        1 - 2 * (y*y + z*z), 2 * (x*y + z*w), 2 * (x*z - y*w), 0f,
        2 * (x*y - z*w), 1 - 2 * (x*x + z*z), 2 * (y*z + x*w), 0f,
        2 * (x*z + y*w), 2 * (y*z - x*w), 1 - 2 * (x*x + y*y), 0f,
        0f, 0f, 0f, 1f,
    )
    for (col in 0..2) for (row in 0..2) out[col*4 + row] *= fs[col] * (1f-t) + ts[col] * t
    for (n in 12..14) out[n] = from[n] * (1f-t) + to[n] * t
    return out
}
