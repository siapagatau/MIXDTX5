package com.kendang.realpads.skin

/** Hasil validasi: errors = skin DITOLAK, warnings = skin tetap dipakai. */
class SkinValidationResult(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
    fun summary(): String = if (ok) "OK (${warnings.size} peringatan)" else errors.joinToString("; ")
}

/** Validasi skin mixer sesuai PROMPT_SKIN_MIXER.md §3, §4, §6. Tidak pernah melempar exception. */
object SkinValidator {

    private const val MAX_VIEWBOX = 100_000f
    private const val MAX_ELEMENTS = 20_000
    private val forbiddenTags = setOf("script", "foreignObject", "image", "use", "audio", "video", "iframe", "metadata", "filter")

    fun validate(skin: SkinDefinition): SkinValidationResult {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        try {
            val root = SvgDom.parse(skin.svgText).documentElement
            if (root.tagName != "svg") errors.add("elemen akar bukan <svg> (${root.tagName})")
            if (skin.name.isBlank()) errors.add("nama skin kosong")
            if (skin.version.isBlank()) errors.add("versi skin kosong")
            val vb = skin.viewBox
            if (vb.w <= 0f || vb.h <= 0f || vb.w > MAX_VIEWBOX || vb.h > MAX_VIEWBOX) errors.add("viewBox tidak wajar (${vb.w} x ${vb.h})")
            if (vb.w < vb.h) warnings.add("viewBox bukan landscape (${vb.w} x ${vb.h})")
            if (SvgDom.elements(root).take(MAX_ELEMENTS + 1).count() > MAX_ELEMENTS) errors.add("elemen SVG terlalu banyak (> $MAX_ELEMENTS)")
            for (a in listOf("xmlns", "data-skin-name", "data-skin-version", "data-skin-author", "width", "height", "viewBox")) {
                if (!root.hasAttribute(a)) errors.add("root SVG tanpa atribut $a")
            }
            for (e in SvgDom.elements(root)) {
                if (e.tagName in forbiddenTags) errors.add("elemen terlarang <${e.tagName}>")
                val attrs = e.attributes
                for (i in 0 until attrs.length) {
                    val n = attrs.item(i).nodeName
                    val v = attrs.item(i).nodeValue ?: ""
                    if (n.startsWith("on")) errors.add("atribut event '$n' pada <${e.tagName}>")
                    if (n == "href" || n == "xlink:href") errors.add("referensi eksternal ($n) pada <${e.tagName}>")
                    if ((v.contains("http://", true) || v.contains("https://", true)) && n != "xmlns" && !n.startsWith("xmlns:")) {
                        errors.add("URL eksternal di atribut $n")
                    }
                }
            }
            val z = skin.zone
            if (z.x < vb.x || z.y < vb.y || z.right > vb.right || z.bottom > vb.bottom) errors.add("layout.zone di luar viewBox")
            for (lid in listOf("layer-bg", "layer-deco", "layer-controls", "layer-hit")) {
                if (SvgDom.findById(root, lid) == null) warnings.add("layer $lid tidak ada")
            }
            if (skin.controls.isEmpty()) errors.add("tidak ada kontrol di skin.json")

            fun exists(id: String?) = id != null && SvgDom.findById(root, id) != null
            val seen = HashSet<String>()
            for (c in skin.controls) {
                if (!seen.add(c.id)) errors.add("ID ganda: ${c.id}")
                if (!exists(c.id)) errors.add("${c.id}: elemen tidak ada di SVG")
                if (c.function.isBlank()) errors.add("${c.id}: mapping function kosong")
                else if (c.function !in FunctionRegistry.official) errors.add("${c.id}: function '${c.function}' tidak ada di katalog resmi")
                else if (c.function !in FunctionRegistry.allowedFor(c.type)) errors.add("${c.id}: function '${c.function}' tidak cocok untuk tipe ${c.type}")

                if (c.type != ControlType.METER && c.type != ControlType.DISPLAY) {
                    val hit = c.hit
                    if (c.hitId == null || hit == null) errors.add("${c.id}: hit area tidak ditemukan")
                    else if (hit.w <= 0f || hit.h <= 0f || hit.x < vb.x || hit.y < vb.y || hit.right > vb.right || hit.bottom > vb.bottom) {
                        errors.add("${c.id}: hit area di luar viewBox / ukuran tidak valid")
                    }
                }
                when (c.type) {
                    ControlType.BUTTON -> if (!exists(c.activeId)) warnings.add("${c.id}: state active tidak ada (pakai highlight bawaan)")
                    ControlType.TOGGLE -> {
                        if (!exists(c.onId)) errors.add("${c.id}: state on '${c.onId}' tidak ada")
                        if (!exists(c.offId)) errors.add("${c.id}: state off '${c.offId}' tidak ada")
                    }
                    ControlType.ROTARY -> {
                        if (c.rotary == null) errors.add("${c.id}: atribut data-cx/data-cy rotary tidak ada")
                        else if (!exists(c.rotary.handleId)) errors.add("${c.id}: handle '${c.rotary.handleId}' tidak ada")
                    }
                    ControlType.SLIDER -> {
                        if (c.slider == null) errors.add("${c.id}: atribut data-x1/y1/x2/y2 slider tidak ada")
                        else if (!exists(c.slider.handleId)) errors.add("${c.id}: handle '${c.slider.handleId}' tidak ada")
                    }
                    ControlType.METER -> {
                        val m = c.meter
                        if (m == null || m.segmentIds.isEmpty()) errors.add("${c.id}: meter tanpa segmen")
                        else m.segmentIds.forEachIndexed { i, sid ->
                            if (!exists(sid)) errors.add("${c.id}: segmen '$sid' tidak ada")
                            else if (m.segRects[i] == null) errors.add("${c.id}: segmen '$sid' tanpa <rect>")
                        }
                    }
                    ControlType.DISPLAY -> if (c.display == null) warnings.add("${c.id}: display tanpa rect")
                }
            }

            val hits = skin.interactive
            for (i in hits.indices) for (j in i + 1 until hits.size) {
                val a = hits[i].hit!!; val b = hits[j].hit!!
                if (a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom) {
                    warnings.add("hit area ${hits[i].id} dan ${hits[j].id} saling tumpuk")
                }
            }
        } catch (e: SkinException) {
            errors.add(e.message ?: "SVG tidak bisa dibaca")
        } catch (e: Exception) {
            errors.add("validasi gagal: ${e.javaClass.simpleName}: ${e.message}")
        }
        return SkinValidationResult(errors, warnings)
    }
}
