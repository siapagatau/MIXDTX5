package com.kendang.realpads.skin

import kotlin.math.hypot
import kotlin.math.max

/** Persegi di koordinat layar (piksel). */
data class PxRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
    fun contains(x: Float, y: Float): Boolean = x >= l && x <= r && y >= t && y <= b
    fun distanceTo(x: Float, y: Float): Float {
        val dx = max(max(l - x, 0f), x - r)
        val dy = max(max(t - y, 0f), y - b)
        return hypot(dx, dy)
    }
}

/**
 * Pemetaan wilayah SVG [svg] -> persegi layar [dst]. Skala X dan Y BOLEH berbeda: seluruh skin di-stretch
 * memenuhi layar landscape (PROMPT_SKIN_MIXER §4).
 */
class SkinZone(val svg: SvgRect, val dst: PxRect) {
    val sx: Float = dst.w / svg.w
    val sy: Float = dst.h / svg.h
    fun x(v: Float): Float = dst.l + (v - svg.x) * sx
    fun y(v: Float): Float = dst.t + (v - svg.y) * sy
    fun rect(r: SvgRect): PxRect = PxRect(x(r.x), y(r.y), x(r.right), y(r.bottom))
    fun svgX(px: Float): Float = svg.x + (px - dst.l) / sx
    fun svgY(py: Float): Float = svg.y + (py - dst.t) / sy

    companion object {
        fun of(skin: SkinDefinition, wPx: Float, hPx: Float) =
            SkinZone(skin.zone, PxRect(0f, 0f, max(wPx, 1f), max(hPx, 1f)))
    }
}
