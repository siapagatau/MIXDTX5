package com.kendang.realpads.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs

/** Hasil render lengkap yang dimuat dari cache disk. */
class CachedLayers(val core: SkinCore, val layers: Map<String, Bitmap>)

/**
 * Cache render di `cacheDir/skin-render/<hash skin>/<WxH>/` (PNG + meta.json; meta ditulis TERAKHIR sebagai
 * penanda lengkap). Ukuran area yang mirip dipakai ulang ([fits]). Kehilangan cache = render ulang sekali.
 */
object SkinRenderCache {
    private const val TAG = "SkinRenderCache"
    private const val ROOT = "skin-render"
    /** Naikkan bila cara render berubah supaya cache lama tidak dipakai. */
    private const val RENDER_VERSION = 1
    private const val KEEP_SIZES = 3
    private const val KEEP_SKINS = 6

    fun fits(refW: Int, refH: Int, w: Float, h: Float): Boolean =
        w <= refW * 1.2f && h <= refH * 1.2f && w >= refW * 0.6f && h >= refH * 0.6f

    private fun hashOf(skin: SkinDefinition, transparentBg: Boolean): String {
        val md = MessageDigest.getInstance("SHA-1")
        fun add(s: String) { md.update(s.toByteArray(Charsets.UTF_8)); md.update(0) }
        add(RENDER_VERSION.toString()); add(skin.version); add(skin.svgText); add(skin.zone.toString()); add(if (transparentBg) "tbg" else "bg")
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun skinDir(context: Context, skin: SkinDefinition, spec: RenderSpec) =
        File(File(context.cacheDir, ROOT), hashOf(skin, spec.transparentBg))

    fun load(context: Context, skin: SkinDefinition, spec: RenderSpec): CachedLayers? {
        val base = skinDir(context, skin, spec)
        val dirs = base.listFiles { f -> f.isDirectory } ?: return null
        var best: File? = null
        var bestDiff = Long.MAX_VALUE
        for (d in dirs) {
            val meta = readMeta(d) ?: continue
            val w = meta.optInt("w"); val h = meta.optInt("h")
            if (!fits(w, h, spec.w.toFloat(), spec.h.toFloat())) continue
            val diff = abs(w.toLong() * h - spec.w.toLong() * spec.h)
            if (diff < bestDiff) { best = d; bestDiff = diff }
        }
        val dir = best ?: return null
        return try {
            val meta = readMeta(dir) ?: return null
            fun bmp(name: String): Bitmap = BitmapFactory.decodeFile(File(dir, name).path)
                ?: throw IllegalStateException("$name tidak bisa dibaca")
            val core = bmp("base.png")
            val handles = HashMap<String, Bitmap>()
            meta.optJSONObject("handles")?.let { j -> j.keys().forEach { k -> handles[k] = bmp(j.getString(k)) } }
            val layers = HashMap<String, Bitmap>()
            meta.optJSONObject("layers")?.let { j -> j.keys().forEach { k -> layers[k] = bmp(j.getString(k)) } }
            dir.setLastModified(System.currentTimeMillis())
            CachedLayers(SkinCore(core, handles), layers)
        } catch (e: Throwable) {
            Log.w(TAG, "cache rusak, dihapus: ${e.message}")
            dir.deleteRecursively()
            null
        }
    }

    fun save(context: Context, skin: SkinDefinition, spec: RenderSpec, core: SkinCore, layers: Map<String, Bitmap>) {
        val base = skinDir(context, skin, spec)
        val dir = File(base, "${spec.w}x${spec.h}")
        try {
            dir.deleteRecursively()
            if (!dir.mkdirs()) return
            fun write(name: String, b: Bitmap) {
                FileOutputStream(File(dir, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val meta = JSONObject()
            meta.put("w", spec.w); meta.put("h", spec.h)
            write("base.png", core.base)
            var n = 0
            val hj = JSONObject()
            for ((id, b) in core.handles) { val f = "h_${n++}.png"; write(f, b); hj.put(id, f) }
            meta.put("handles", hj)
            val lj = JSONObject()
            for ((k, b) in layers) { val f = "l_${n++}.png"; write(f, b); lj.put(k, f) }
            meta.put("layers", lj)
            File(dir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)   // penanda lengkap: TERAKHIR
            prune(base)
        } catch (e: Throwable) {
            Log.w(TAG, "gagal menyimpan cache: ${e.message}")
            dir.deleteRecursively()
        }
    }

    private fun readMeta(dir: File): JSONObject? {
        val f = File(dir, "meta.json")
        if (!f.isFile) return null
        return try { JSONObject(f.readText(Charsets.UTF_8)) } catch (e: Exception) { null }
    }

    private fun prune(base: File) {
        base.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }?.drop(KEEP_SIZES)?.forEach { it.deleteRecursively() }
        base.parentFile?.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }?.drop(KEEP_SKINS)?.forEach { it.deleteRecursively() }
    }
}
