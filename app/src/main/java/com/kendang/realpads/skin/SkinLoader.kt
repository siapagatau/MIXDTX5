package com.kendang.realpads.skin

import android.util.Log
import java.io.File

/**
 * Memuat skin dari folder (skin terunduh dari repo / cache). APK membawa 1 skin bawaan (assets/skins/default) + skin unduhan.
 * Tidak melakukan validasi; panggil SkinValidator setelahnya (lihat SkinManager).
 */
object SkinLoader {
    private const val TAG = "SkinLoader"
    private const val MAX_BYTES = 4 * 1024 * 1024 // batas wajar ukuran skin.svg / skin.json

    /** Skin bawaan APK (assets/skins/<id>/): dipakai langsung tanpa unduhan, juga saat offline. */
    fun loadFromAssets(context: android.content.Context, id: String): SkinDefinition {
        val am = context.applicationContext.assets
        val json = try { am.open("skins/$id/skin.json").use { readLimited(it) } }
                   catch (e: Exception) { throw SkinException("skin.json bawaan '$id' tidak bisa dibaca: ${e.message}", e) }
        val svgName = svgNameFrom(json)
        if (svgName.contains('/') || svgName.contains('\\') || svgName.contains("..")) throw SkinException("nama file svg tidak valid: $svgName")
        val svg = try { am.open("skins/$id/$svgName").use { readLimited(it) } }
                  catch (e: Exception) { throw SkinException("$svgName bawaan '$id' tidak bisa dibaca: ${e.message}", e) }
        return SkinParser.parse(json, svg, "assets:$id")
    }

    fun loadFromDir(dir: File): SkinDefinition {
        val jsonFile = File(dir, "skin.json")
        if (!jsonFile.isFile) throw SkinException("skin.json tidak ada di ${dir.path}")
        val json = readFile(jsonFile)
        val svgName = svgNameFrom(json)
        // svgName hanya boleh nama file biasa (cegah path traversal dari skin.json)
        if (svgName.contains('/') || svgName.contains('\\') || svgName.contains("..")) {
            throw SkinException("nama file svg tidak valid: $svgName")
        }
        val svgFile = File(dir, svgName)
        if (!svgFile.isFile) throw SkinException("$svgName tidak ada di ${dir.path}")
        return SkinParser.parse(json, readFile(svgFile), "dir:${dir.path}")
    }

    private fun svgNameFrom(json: String): String =
        try {
            org.json.JSONObject(json).optString("svg", "skin.svg").ifBlank { "skin.svg" }
        } catch (e: Exception) {
            throw SkinException("skin.json bukan JSON yang valid: ${e.message}", e)
        }

    private fun readFile(f: File): String =
        try {
            f.inputStream().use { readLimited(it) }
        } catch (e: SkinException) {
            throw e
        } catch (e: Exception) {
            throw SkinException("tidak bisa membaca ${f.path}: ${e.message}", e)
        }

    private fun readLimited(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw SkinException("file skin terlalu besar (> ${MAX_BYTES / 1024} KB)")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
