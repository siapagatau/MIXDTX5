package com.kendang.realpads.skin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Repo skin online (github.com/siapagatau/SKIN-MIXER-HP): index.json -> unduh -> verifikasi sha256 + size ->
 * validasi SkinValidator -> cache offline di filesDir/skin_repo/. Tidak pernah melempar ke pemanggil.
 *
 * Tata letak cache:  skin_repo/index.json  |  skin_repo/skins/<id>/{skin.json,skin.svg[,preview.svg]}
 * Pemasangan atomik: unduh ke tmp-<id> -> verifikasi -> validasi -> rename ke skins/<id> (versi lama jadi cadangan).
 */
object SkinRepository {
    private const val TAG = "SkinRepository"

    /** URL repo dibaca dari assets/config/skin-repo.json (sama seperti APK DTX); ini hanya cadangan bila config hilang. */
    private const val CONFIG_ASSET = "config/skin-repo.json"
    private const val DEFAULT_INDEX_URL = "https://raw.githubusercontent.com/siapagatau/SKIN-MIXER-HP/main/index.json"
    @Volatile private var baseUrls: List<String>? = null

    /** Basis URL repo: dari config, plus cabang alternatif (main <-> master) bila yang pertama gagal. */
    private fun bases(app: Context): List<String> {
        baseUrls?.let { return it }
        val cfg = try {
            JSONObject(app.assets.open(CONFIG_ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
                .optString("indexUrl", "").trim()
        } catch (e: Throwable) { "" }
        val idx = if (cfg.startsWith("https://") && cfg.endsWith("index.json")) cfg else DEFAULT_INDEX_URL
        val base = idx.removeSuffix("index.json")
        val alt = when {
            "/main/" in base -> base.replace("/main/", "/master/")
            "/master/" in base -> base.replace("/master/", "/main/")
            else -> null
        }
        return listOfNotNull(base, alt).also { baseUrls = it }
    }

    private fun httpGetRepo(app: Context, relPath: String, maxBytes: Int): ByteArray {
        var last: Throwable? = null
        for (b in bases(app)) {
            try { return httpGet(b + relPath, maxBytes) } catch (t: Throwable) { last = t }
        }
        throw last ?: IOException("repo tidak terjangkau")
    }

    private const val MAX_INDEX = 512 * 1024
    private const val MAX_FILE = 4 * 1024 * 1024
    private val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val SHA_RE = Regex("^[0-9a-f]{64}$")
    private val ALLOWED_FILES = setOf("skin.json", "skin.svg", "preview.svg")
    private val REQUIRED_FILES = listOf("skin.json", "skin.svg")
    private val RESERVED_IDS = setOf(SkinManager.CLASSIC_ID)

    data class FileInfo(val sha256: String, val size: Int)
    data class RepoSkin(val id: String, val name: String, val version: String, val author: String, val files: Map<String, FileInfo>)
    enum class Status { NOT_INSTALLED, INSTALLED, UPDATE }
    /** [error] != null: unduh index gagal; [skins] berasal dari cache (bisa kosong). */
    class IndexResult(val skins: List<RepoSkin>, val fromCache: Boolean, val error: String?)

    private val installLock = Mutex()
    private val previewMemo = ConcurrentHashMap<String, String>()

    // ---------- lokasi cache ----------
    private fun root(app: Context) = File(app.filesDir, "skin_repo")
    private fun skinsRoot(app: Context) = File(root(app), "skins")

    /** Folder skin terunduh yang siap dimuat, atau null. Id divalidasi (cegah path traversal). */
    fun cachedSkinDir(app: Context, id: String): File? {
        if (!ID_RE.matches(id) || id in RESERVED_IDS) return null
        val d = File(skinsRoot(app), id)
        return if (File(d, "skin.json").isFile && File(d, "skin.svg").isFile) d else null
    }

    // ---------- index ----------
    /** Unduh index.json (disimpan ke cache). Gagal jaringan/parse -> pakai cache terakhir. */
    suspend fun fetchIndex(app: Context): IndexResult = withContext(Dispatchers.IO) {
        val cacheFile = File(root(app), "index.json")
        try {
            val bytes = httpGetRepo(app, "index.json", MAX_INDEX)
            val skins = parseIndex(bytes.toString(Charsets.UTF_8))
            writeAtomic(cacheFile, bytes)
            IndexResult(skins, false, null)
        } catch (t: Throwable) {
            Log.w(TAG, "index online gagal: ${t.message}")
            val cached = try {
                if (cacheFile.isFile) parseIndex(cacheFile.readText(Charsets.UTF_8)) else emptyList()
            } catch (e: Throwable) { emptyList() }
            IndexResult(cached, true, t.message ?: t.javaClass.simpleName)
        }
    }

    /** Skin yang sudah terpasang tapi tidak ada di index (mode offline tanpa cache index). */
    fun installedSkins(app: Context): List<RepoSkin> =
        (skinsRoot(app).listFiles() ?: emptyArray()).mapNotNull { d ->
            val id = d.name
            if (!d.isDirectory || cachedSkinDir(app, id) == null) return@mapNotNull null
            try {
                val j = JSONObject(File(d, "skin.json").readText(Charsets.UTF_8))
                RepoSkin(id, j.optString("name", id), j.optString("version", "?"), j.optString("author", ""), emptyMap())
            } catch (e: Throwable) { null }
        }.sortedBy { it.id }

    fun parseIndex(text: String): List<RepoSkin> {
        val j = JSONObject(text)
        if (j.optInt("schemaVersion", 1) != 1) throw IOException("schemaVersion index tidak didukung")
        val arr = j.getJSONArray("skins")
        val out = ArrayList<RepoSkin>()
        for (n in 0 until arr.length()) {
            try { out.add(parseSkin(arr.getJSONObject(n))) }
            catch (e: Throwable) { Log.w(TAG, "entri index #$n dilewati: ${e.message}") }
        }
        return out
    }

    private fun parseSkin(o: JSONObject): RepoSkin {
        val id = o.getString("id")
        require(ID_RE.matches(id) && id !in RESERVED_IDS) { "id tidak valid: $id" }
        require(o.optString("path") == "skins/$id/") { "path tidak sesuai id" }
        val fo = o.getJSONObject("files")
        val files = LinkedHashMap<String, FileInfo>()
        for (name in fo.keys()) {
            require(name in ALLOWED_FILES) { "nama file tidak diizinkan: $name" }
            val f = fo.getJSONObject(name)
            val sha = f.getString("sha256").lowercase()
            val size = f.getInt("size")
            require(SHA_RE.matches(sha) && size in 1..MAX_FILE) { "sha256/size $name tidak valid" }
            files[name] = FileInfo(sha, size)
        }
        for (r in REQUIRED_FILES) require(r in files) { "index tanpa $r" }
        return RepoSkin(id, o.optString("name", id).ifBlank { id }, o.optString("version", "?"), o.optString("author", ""), files)
    }

    // ---------- status & pemasangan ----------
    fun status(app: Context, skin: RepoSkin): Status {
        val dir = cachedSkinDir(app, skin.id) ?: return Status.NOT_INSTALLED
        return try {
            val same = REQUIRED_FILES.all { n -> skin.files[n]?.sha256 == sha256Hex(File(dir, n).readBytes()) }
            if (same) Status.INSTALLED else Status.UPDATE
        } catch (e: Throwable) { Status.UPDATE }
    }

    /** Unduh + verifikasi + validasi + pasang. Mengembalikan null bila sukses, atau pesan error. */
    suspend fun install(app: Context, skin: RepoSkin): String? = withContext(Dispatchers.IO) {
        installLock.withLock<String?> {
            val tmp = File(root(app), "tmp-${skin.id}")
            val old = File(root(app), "old-${skin.id}")
            val finalDir = File(skinsRoot(app), skin.id)
            try {
                tmp.deleteRecursively(); old.deleteRecursively()
                if (!tmp.mkdirs()) return@withLock "tidak bisa membuat folder cache"
                for ((name, info) in skin.files) {
                    val optional = name !in REQUIRED_FILES
                    try {
                        val bytes = fetchVerified(app, skin.id, name, info)
                        File(tmp, name).writeBytes(bytes)
                    } catch (e: Throwable) {
                        if (!optional) throw e
                        Log.w(TAG, "$name opsional dilewati: ${e.message}")
                    }
                }
                val st = SkinManager.loadFrom { SkinLoader.loadFromDir(tmp) }
                if (st !is SkinManager.State.Active) {
                    return@withLock "skin ditolak: " + ((st as? SkinManager.State.Failed)?.reason ?: "tidak valid")
                }
                skinsRoot(app).mkdirs()
                val hadOld = finalDir.exists()
                if (hadOld && !finalDir.renameTo(old)) return@withLock "tidak bisa mengganti skin lama"
                if (!tmp.renameTo(finalDir)) {
                    if (hadOld) old.renameTo(finalDir)
                    return@withLock "tidak bisa memasang skin"
                }
                old.deleteRecursively()
                previewMemo.remove(skin.id)
                null
            } catch (t: Throwable) {
                Log.e(TAG, "install ${skin.id} gagal", t)
                t.message ?: t.javaClass.simpleName
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    /** Teks preview.svg terverifikasi (cache terpasang -> memori -> unduh). null bila tidak ada/gagal. */
    suspend fun previewText(app: Context, skin: RepoSkin): String? = withContext(Dispatchers.IO) {
        try {
            cachedSkinDir(app, skin.id)?.let { d ->
                val f = File(d, "preview.svg")
                if (f.isFile && f.length() <= MAX_FILE) return@withContext f.readText(Charsets.UTF_8)
            }
            val info = skin.files["preview.svg"] ?: return@withContext null
            val key = "${skin.id}@${info.sha256}"
            previewMemo[key]?.let { return@withContext it }
            val text = fetchVerified(app, skin.id, "preview.svg", info).toString(Charsets.UTF_8)
            previewMemo[key] = text
            text
        } catch (t: Throwable) {
            Log.w(TAG, "preview ${skin.id} gagal: ${t.message}")
            null
        }
    }

    // ---------- util ----------
    private fun fetchVerified(app: Context, id: String, name: String, info: FileInfo): ByteArray {
        val bytes = httpGetRepo(app, "skins/$id/$name", info.size)
        if (bytes.size != info.size) throw IOException("$name: ukuran ${bytes.size} != ${info.size}")
        if (sha256Hex(bytes) != info.sha256) throw IOException("$name: sha256 tidak cocok")
        return bytes
    }

    private fun httpGet(url: String, maxBytes: Int): ByteArray {
        require(url.startsWith("https://")) { "hanya https" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "KaraokeMixer-SkinRepo")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val result = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("respons melebihi $maxBytes byte")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            return result
        } finally {
            c.disconnect()
        }
    }

    private fun writeAtomic(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val t = File(f.parentFile, f.name + ".tmp")
        t.writeBytes(bytes)
        if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
    }

    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
