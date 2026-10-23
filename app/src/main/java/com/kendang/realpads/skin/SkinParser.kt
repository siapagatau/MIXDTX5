package com.kendang.realpads.skin

import org.json.JSONArray
import org.json.JSONObject

/**
 * skin.json + skin.svg -> SkinDefinition. Posisi semua kontrol dibaca dari SVG (hit-* rect, data-* atribut);
 * tidak ada koordinat hardcode. Bila atribut SVG dan JSON berbeda, SVG yang dipakai.
 */
object SkinParser {

    fun parse(jsonText: String, svgText: String, source: String): SkinDefinition {
        val json = try { JSONObject(jsonText) } catch (e: Exception) {
            throw SkinException("skin.json bukan JSON yang valid: ${e.message}", e)
        }
        val doc = SvgDom.parse(svgText)
        val root = doc.documentElement
        if (root == null || root.tagName != "svg") throw SkinException("Root SVG bukan <svg>")
        val viewBox = parseRect(root.getAttribute("viewBox")) ?: throw SkinException("SVG tidak punya viewBox yang valid")

        val name = root.getAttribute("data-skin-name").ifBlank { json.optString("name", "") }
        val version = root.getAttribute("data-skin-version").ifBlank { json.optString("version", "") }
        val author = root.getAttribute("data-skin-author").ifBlank { json.optString("author", "") }
        val zone = json.optJSONObject("layout")?.optString("zone", "")?.let { parseRect(it) } ?: viewBox

        val mapping = json.optJSONObject("mapping") ?: JSONObject()
        val controlsJson = json.optJSONArray("controls") ?: JSONArray()
        val controls = ArrayList<SkinControl>()

        for (i in 0 until controlsJson.length()) {
            val c = controlsJson.optJSONObject(i) ?: continue
            val id = c.optString("id", "")
            val type = ControlType.from(c.optString("type", "")) ?: continue
            val m = mapping.optJSONObject(id) ?: JSONObject()
            val params = HashMap<String, Any?>()
            m.optJSONObject("params")?.let { p -> p.keys().forEach { k -> params[k] = p.opt(k) } }

            val hitId = c.optString("hit", "").ifBlank { null }
            val hitRect = hitId?.let { hid -> SvgDom.findById(root, hid)?.let { SvgDom.rectOf(it) } }
            val states = c.optJSONObject("states")
            fun stateId(key: String, fallback: String): String? {
                val v = states?.optString(key, "")?.ifBlank { null } ?: fallback
                return v
            }
            val el = SvgDom.findById(root, id)

            var rotary: RotaryInfo? = null
            if (type == ControlType.ROTARY && el != null) {
                val cx = SvgDom.attrFloat(el, "data-cx")
                val cy = SvgDom.attrFloat(el, "data-cy")
                if (cx != null && cy != null) {
                    rotary = RotaryInfo(
                        cx = cx, cy = cy,
                        angleMin = SvgDom.attrFloat(el, "data-angle-min") ?: c.optDouble("angleMin", -135.0).toFloat(),
                        angleMax = SvgDom.attrFloat(el, "data-angle-max") ?: c.optDouble("angleMax", 135.0).toFloat(),
                        defaultValue = (SvgDom.attrFloat(el, "data-value-default") ?: c.optDouble("defaultValue", 0.5).toFloat()).coerceIn(0f, 1f),
                        handleId = c.optString("handle", "").ifBlank { "$id-handle" }
                    )
                }
            }

            var slider: SliderInfo? = null
            if (type == ControlType.SLIDER && el != null) {
                val x1 = SvgDom.attrFloat(el, "data-x1"); val y1 = SvgDom.attrFloat(el, "data-y1")
                val x2 = SvgDom.attrFloat(el, "data-x2"); val y2 = SvgDom.attrFloat(el, "data-y2")
                if (x1 != null && y1 != null && x2 != null && y2 != null) {
                    slider = SliderInfo(
                        x1, y1, x2, y2,
                        (SvgDom.attrFloat(el, "data-value-default") ?: c.optDouble("defaultValue", 0.5).toFloat()).coerceIn(0f, 1f),
                        c.optString("handle", "").ifBlank { "$id-handle" }
                    )
                }
            }

            var meter: MeterInfo? = null
            if (type == ControlType.METER) {
                val ids = ArrayList<String>()
                c.optJSONArray("segments")?.let { a -> for (k in 0 until a.length()) ids.add(a.optString(k)) }
                val rects = ids.map { sid ->
                    SvgDom.findById(root, sid)?.let { e ->
                        if (e.tagName == "rect") SvgDom.rectOf(e)
                        else SvgDom.elements(e).firstOrNull { it.tagName == "rect" }?.let { r -> SvgDom.rectOf(r) }
                    }
                }
                meter = MeterInfo(ids, rects)
            }

            var display: DisplayInfo? = null
            if (type == ControlType.DISPLAY && el != null) {
                val rect = SvgDom.elements(el).firstOrNull { it.tagName == "rect" }?.let { SvgDom.rectOf(it) }
                if (rect != null) {
                    val tpl = m.optJSONArray("template")
                    val lines = ArrayList<String>()
                    if (tpl != null) for (k in 0 until tpl.length()) lines.add(tpl.optString(k))
                    display = DisplayInfo(rect, lines, parseColor(m.optString("textColor", ""), 0xFFB8F27C.toInt()))
                }
            }

            controls.add(
                SkinControl(
                    id = id, type = type, hitId = hitId, hit = hitRect,
                    function = m.optString("function", ""), params = params,
                    activeId = if (type == ControlType.BUTTON) stateId("active", "$id-active") else null,
                    onId = if (type == ControlType.TOGGLE) stateId("on", "$id-on") else null,
                    offId = if (type == ControlType.TOGGLE) stateId("off", "$id-off") else null,
                    rotary = rotary, slider = slider, meter = meter, display = display
                )
            )
        }

        val unsupported = ArrayList<String>()
        json.optJSONArray("unsupportedInReference")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("id", o.optString("label", ""))
                if (label.isNotBlank()) unsupported.add(label)
            }
        }
        return SkinDefinition(name, version, author, svgText, viewBox, zone, controls, unsupported, source)
    }

    private fun parseRect(s: String): SvgRect? {
        val p = s.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (p.size != 4 || p[2] <= 0f || p[3] <= 0f) return null
        return SvgRect(p[0], p[1], p[2], p[3])
    }

    private fun parseColor(s: String, fallback: Int): Int =
        try { if (s.isBlank()) fallback else android.graphics.Color.parseColor(s) } catch (e: Exception) { fallback }
}
