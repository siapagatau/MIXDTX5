package com.kendang.realpads.skin

/**
 * Touch layer: kontrol yang kena sentuh, dari hit area SVG yang sudah dipetakan ke layar.
 * 1) tepat di dalam hit area -> area terkecil menang; 2) bila tidak ada, target diperluas [expandDp]
 * (terdekat menang) supaya knob/tombol kecil mudah disentuh.
 */
class HitTester(private val targets: List<Pair<SkinControl, PxRect>>, private val expandDp: Float = 24f) {

    fun hit(x: Float, y: Float, density: Float): SkinControl? {
        var exact: SkinControl? = null
        var exactArea = Float.MAX_VALUE
        for ((c, r) in targets) {
            if (r.contains(x, y)) {
                val area = r.w * r.h
                if (area < exactArea) { exact = c; exactArea = area }
            }
        }
        if (exact != null) return exact
        val margin = expandDp * density
        var best: SkinControl? = null
        var bestD = Float.MAX_VALUE
        var bestC = Float.MAX_VALUE
        for ((c, r) in targets) {
            val d = r.distanceTo(x, y)
            if (d > margin) continue
            val cd = kotlin.math.hypot(r.cx - x, r.cy - y)
            if (d < bestD || (d == bestD && cd < bestC)) { best = c; bestD = d; bestC = cd }
        }
        return best
    }

    companion object {
        fun of(skin: SkinDefinition, zone: SkinZone): HitTester =
            HitTester(skin.controls.mapNotNull { c -> c.hit?.let { c to zone.rect(it) } })

        /** Posisi sentuh (layar) -> nilai 0..1 sepanjang jalur slider. */
        fun sliderValue(c: SkinControl, zone: SkinZone, px: Float, py: Float): Float {
            val s = c.slider ?: return 0f
            val dx = s.x2 - s.x1
            val dy = s.y2 - s.y1
            val len2 = dx * dx + dy * dy
            if (len2 <= 0f) return 0f
            return (((zone.svgX(px) - s.x1) * dx + (zone.svgY(py) - s.y1) * dy) / len2).coerceIn(0f, 1f)
        }
    }
}
