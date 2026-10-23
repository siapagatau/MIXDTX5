package com.kendang.realpads.skin

import kotlin.math.hypot

/** Persegi dalam koordinat SVG (viewBox). */
data class SvgRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
    fun contains(px: Float, py: Float): Boolean = px >= x && px <= right && py >= y && py <= bottom
    fun distanceTo(px: Float, py: Float): Float {
        val dx = maxOf(x - px, 0f, px - right)
        val dy = maxOf(y - py, 0f, py - bottom)
        return hypot(dx, dy)
    }
}

enum class ControlType {
    BUTTON, TOGGLE, ROTARY, SLIDER, METER, DISPLAY;

    companion object {
        fun from(s: String?): ControlType? = when (s) {
            "button" -> BUTTON
            "toggle" -> TOGGLE
            "rotary" -> ROTARY
            "slider" -> SLIDER
            "meter" -> METER
            "display" -> DISPLAY
            else -> null
        }
    }
}

/** Knob: handle digambar menunjuk jam 12 (sudut 0); engine memutar sebesar min + (max-min)*nilai. */
data class RotaryInfo(
    val cx: Float, val cy: Float,
    val angleMin: Float, val angleMax: Float,
    val defaultValue: Float,
    val handleId: String
) {
    /** Wilayah gambar handle: simetris terhadap pusat putar supaya rotasi bitmap pas. */
    fun region(hit: SvgRect): SvgRect {
        val hw = maxOf(cx - hit.x, hit.right - cx)
        val hh = maxOf(cy - hit.y, hit.bottom - cy)
        return SvgRect(cx - hw, cy - hh, hw * 2f, hh * 2f)
    }
}

/** Slider lurus: (x1,y1) = nilai 0, (x2,y2) = nilai 1. Handle digambar di posisi nilai default. */
data class SliderInfo(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val defaultValue: Float,
    val handleId: String
)

data class MeterInfo(val segmentIds: List<String>, val segRects: List<SvgRect?>)

data class DisplayInfo(val rect: SvgRect, val lines: List<String>, val textColor: Int)

data class SkinControl(
    val id: String,
    val type: ControlType,
    val hitId: String?,
    val hit: SvgRect?,
    /** Nama function resmi (FunctionRegistry), dari skin.json mapping. */
    val function: String,
    val params: Map<String, Any?>,
    val activeId: String?,
    val onId: String?,
    val offId: String?,
    val rotary: RotaryInfo?,
    val slider: SliderInfo?,
    val meter: MeterInfo?,
    val display: DisplayInfo?
)

class SkinDefinition(
    val name: String,
    val version: String,
    val author: String,
    val svgText: String,
    val viewBox: SvgRect,
    /** layout.zone: wilayah SVG yang di-stretch memenuhi layar (default = viewBox). */
    val zone: SvgRect,
    val controls: List<SkinControl>,
    val unsupported: List<String>,
    val source: String
) {
    val interactive: List<SkinControl> get() = controls.filter { it.hit != null }
    fun byId(id: String): SkinControl? = controls.firstOrNull { it.id == id }
}

class SkinException(message: String, cause: Throwable? = null) : Exception(message, cause)
