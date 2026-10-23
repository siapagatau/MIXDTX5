package com.kendang.realpads.skin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Ukuran area skin di layar (px). Seluruh zona skin di-stretch ke area ini (skala X dan Y boleh beda). */
class RenderSpec(val w: Int, val h: Int, val transparentBg: Boolean = false) {
    fun scaleX(skin: SkinDefinition): Float = w / skin.zone.w
    fun scaleY(skin: SkinDefinition): Float = h / skin.zone.h
}

/** Lapisan inti: [base] = seluruh skin tanpa bagian bergerak; [handles] = handle knob/slider (id kontrol -> bitmap). */
class SkinCore(val base: Bitmap, val handles: Map<String, Bitmap>)

/** Kunci layer detail (state). */
object LayerKey {
    fun toggleOn(id: String) = "on:$id"
    fun buttonActive(id: String) = "act:$id"
    fun meterSeg(id: String, index1: Int) = "seg:$id:$index1"
}

/**
 * Renderer skin mixer. AndroidSVG hanya MENGGAMBAR; posisi/skala lewat matriks Canvas. SVG dipecah jadi layer
 * (Document.cloneNode() melempar DOMException di Android -> tiap layer mem-parse ulang teks SVG yang sudah bersih):
 *  1. buildCore    : base (tanpa handle knob/slider, segmen meter, state on/active, hit-*, teks display) + handle.
 *  2. buildDetails : state toggle-on, button-active, segmen meter menyala; satu per satu.
 */
object SkinRenderer {
    private const val TAG = "SkinRenderer"
    private const val MAX_BITMAP_PIXELS = 6_000_000f

    fun buildCore(skin: SkinDefinition, spec: RenderSpec): SkinCore {
        val vb = skin.viewBox
        val sx = spec.scaleX(skin)
        val sy = spec.scaleY(skin)
        val svgText = normalizedSvg(skin)

        val baseSvg = SVG.getFromString(SvgDom.serialize(prepareBase(svgText, skin, spec.transparentBg)))
        val base = renderRegion(baseSvg, vb, skin.zone, skin.zone.w * sx, skin.zone.h * sy)

        val handles = HashMap<String, Bitmap>()
        try {
            val m = max(sx, sy)
            for (c in skin.controls) {
                val hit = c.hit ?: continue
                try {
                    when (c.type) {
                        ControlType.ROTARY -> {
                            val r = c.rotary ?: continue
                            val region = r.region(hit)
                            // knob digambar UTUH (skala seragam); SkinHost memutar lalu men-stretch-nya.
                            handles[c.id] = renderOnly(svgText, r.handleId, vb, region, region.w * m, region.h * m, clearTransform = true)
                        }
                        ControlType.SLIDER -> {
                            val s = c.slider ?: continue
                            handles[c.id] = renderOnly(svgText, s.handleId, vb, hit, hit.w * sx, hit.h * sy, clearTransform = false)
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "handle ${c.id} gagal dirender: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            base.recycle(); handles.values.forEach { it.recycle() }
            throw e
        }
        return SkinCore(base, handles)
    }

    fun buildDetails(skin: SkinDefinition, spec: RenderSpec, isAlive: () -> Boolean, onLayer: (String, Bitmap) -> Unit) {
        val vb = skin.viewBox
        val sx = spec.scaleX(skin)
        val sy = spec.scaleY(skin)
        val svgText = normalizedSvg(skin)
        // (kunci layer, id elemen, area). Tombol & toggle dulu (kecil & cepat), lalu segmen meter.
        val jobs = ArrayList<Triple<String, String, SvgRect>>()
        for (c in skin.controls) {
            val hit = c.hit ?: continue
            if (c.type == ControlType.BUTTON && c.activeId != null) jobs.add(Triple(LayerKey.buttonActive(c.id), c.activeId, hit))
            if (c.type == ControlType.TOGGLE && c.onId != null) jobs.add(Triple(LayerKey.toggleOn(c.id), c.onId, hit))
        }
        for (c in skin.controls) {
            val m = c.meter ?: continue
            m.segmentIds.forEachIndexed { i, sid -> m.segRects[i]?.let { jobs.add(Triple(LayerKey.meterSeg(c.id, i + 1), sid, it)) } }
        }
        for ((key, elementId, area) in jobs) {
            if (!isAlive()) return
            try {
                onLayer(key, renderOnly(svgText, elementId, vb, area, area.w * sx, area.h * sy, clearTransform = false))
            } catch (e: Exception) {
                Log.w(TAG, "layer $key gagal dirender: ${e.message}")   // kontrol tetap jalan tanpa layer ini
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private fun normalizedSvg(skin: SkinDefinition): String {
        val doc = SvgDom.parse(skin.svgText)
        val root = doc.documentElement
        SvgDom.elements(root).filter { it.tagName == "metadata" }.toList().forEach { SvgDom.remove(it) }
        val vb = skin.viewBox
        root.removeAttribute("width")
        root.removeAttribute("height")
        root.removeAttribute("preserveAspectRatio")
        root.setAttribute("viewBox", "${vb.x} ${vb.y} ${vb.w} ${vb.h}")
        return SvgDom.serialize(doc)
    }

    /** Dokumen dasar: tanpa bagian bergerak/state, tanpa hit-*, tanpa teks placeholder display. */
    private fun prepareBase(srcText: String, skin: SkinDefinition, transparentBg: Boolean): Document {
        val doc = SvgDom.parse(srcText)
        val root = doc.documentElement
        // Latar gambar buatan user (Tema -> Background): buang layer-bg skin supaya gambar tampil di belakang kontrol.
        if (transparentBg) SvgDom.findById(root, "layer-bg")?.let { SvgDom.remove(it) }
        fun drop(id: String?) { if (id != null) SvgDom.findById(root, id)?.let { SvgDom.remove(it) } }
        for (c in skin.controls) {
            when (c.type) {
                ControlType.ROTARY -> drop(c.rotary?.handleId)
                ControlType.SLIDER -> drop(c.slider?.handleId)
                ControlType.TOGGLE -> drop(c.onId)
                ControlType.BUTTON -> drop(c.activeId)
                ControlType.METER -> c.meter?.segmentIds?.forEach { drop(it) }
                ControlType.DISPLAY -> SvgDom.findById(root, c.id)?.let { g ->
                    SvgDom.elements(g).filter { it.tagName == "text" }.toList().forEach { SvgDom.remove(it) }
                }
            }
        }
        SvgDom.elements(root).filter { it.getAttribute("id").startsWith("hit-") }.toList().forEach { SvgDom.remove(it) }
        return doc
    }

    private fun renderOnly(
        srcText: String, targetId: String, vb: SvgRect, region: SvgRect, pxW: Float, pxH: Float, clearTransform: Boolean
    ): Bitmap {
        val doc = SvgDom.parse(srcText)
        val target = SvgDom.findById(doc.documentElement, targetId) ?: throw SkinException("elemen $targetId tidak ada")
        target.removeAttribute("display")
        if (clearTransform) target.removeAttribute("transform")
        pruneTo(doc, target)
        return renderRegion(SVG.getFromString(SvgDom.serialize(doc)), vb, region, pxW, pxH)
    }

    /** Hapus semua yang bukan <defs> dan bukan leluhur/target. */
    private fun pruneTo(doc: Document, target: Element) {
        var node: Node = target
        val root = doc.documentElement
        while (node !== root) {
            val parent = node.parentNode ?: break
            val kids = parent.childNodes
            val remove = ArrayList<Node>()
            for (i in 0 until kids.length) {
                val k = kids.item(i)
                if (k === node) continue
                if (k is Element && k.tagName == "defs") continue
                if (k is Element) remove.add(k)
            }
            remove.forEach { parent.removeChild(it) }
            node = parent
        }
    }

    private fun renderRegion(svg: SVG, vb: SvgRect, region: SvgRect, pxW: Float, pxH: Float): Bitmap {
        var w = max(1f, pxW)
        var h = max(1f, pxH)
        val f = min(1f, sqrt(MAX_BITMAP_PIXELS / (w * h)))
        w *= f; h *= f
        val bw = max(1, ceil(w).toInt())
        val bh = max(1, ceil(h).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(bw / region.w, bh / region.h)
        canvas.translate(-(region.x - vb.x), -(region.y - vb.y))
        svg.renderToCanvas(canvas, RenderOptions.create().viewPort(0f, 0f, vb.w, vb.h))
        return bmp
    }
}
