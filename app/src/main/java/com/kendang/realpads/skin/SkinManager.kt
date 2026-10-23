package com.kendang.realpads.skin

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pusat Skin Engine mixer. APK membawa 1 skin bawaan (assets/skins/default) yang langsung tampil seperti DTX;
 * skin lain bisa diunduh dari repo SKIN-MIXER-HP (cache offline).
 * Rantai fallback: skin terpilih -> skin bawaan -> UI klasik. Skin rusak / gagal render tidak membuat crash.
 */
object SkinManager {
    private const val TAG = "SkinManager"

    sealed class State {
        object Loading : State()
        /** UI klasik (fallback permanen). */
        object Classic : State()
        data class Active(val skin: SkinDefinition) : State()
        data class Failed(val reason: String) : State()
    }

    const val CLASSIC_ID = "classic"

    private const val PREFS = "skin_prefs"
    private const val KEY_SELECTED = "selected_skin_id"
    /** id lama dari versi yang masih membawa skin bawaan di APK. */
    /** Skin bawaan APK (assets/skins/default): tampil langsung seperti DTX, tanpa unduhan. */
    const val DEFAULT_ID = "default"

    @Volatile private var appContext: Context? = null
    @Volatile private var loadStarted = false
    @Volatile private var autoStarted = false
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var state: State by mutableStateOf<State>(State.Loading)
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectedId(context: Context): String = prefs(context).getString(KEY_SELECTED, null) ?: CLASSIC_ID

    /** true bila user (atau pemasangan otomatis) sudah pernah menentukan pilihan skin. */
    private fun hasChosen(app: Context): Boolean = prefs(app).contains(KEY_SELECTED)

    private fun saveSelected(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED, id).apply()
    }

    suspend fun ensureLoaded(context: Context) {
        if (state !is State.Loading || loadStarted) return
        loadStarted = true
        val app = context.applicationContext
        appContext = app
        try {
            // Belum memilih apa pun -> skin bawaan. Pilihan lain: muat; gagal -> bawaan -> UI klasik.
            if (!hasChosen(app)) saveSelected(app, DEFAULT_ID)
            state = if (selectedId(app) == CLASSIC_ID) State.Classic
                    else withContext(Dispatchers.IO) { loadSelected(app) }
        } finally {
            if (state is State.Loading) loadStarted = false
        }
    }

    private fun loadSelected(app: Context): State {
        val id = selectedId(app)
        val r = loadById(app, id)
        if (r is State.Active) return r
        if (id != DEFAULT_ID) {
            Log.w(TAG, "skin '$id' gagal dimuat, memakai skin bawaan")
            val d = loadById(app, DEFAULT_ID)
            if (d is State.Active) { saveSelected(app, DEFAULT_ID); return d }
        }
        Log.w(TAG, "skin '$id' gagal dimuat, memakai UI klasik")
        saveSelected(app, CLASSIC_ID)
        return r
    }

    private fun loadById(app: Context, id: String): State = when (id) {
        CLASSIC_ID -> State.Classic
        DEFAULT_ID -> loadFrom { SkinLoader.loadFromAssets(app, DEFAULT_ID) }
        else -> {
            val dir = resolveDownloadedDir(app, id)
            if (dir == null) State.Failed("skin '$id' belum tersedia") else loadFrom { SkinLoader.loadFromDir(dir) }
        }
    }

    /** Skin terunduh dari repo (cache offline); null bila belum terpasang / id tidak valid. */
    private fun resolveDownloadedDir(app: Context, id: String): java.io.File? = SkinRepository.cachedSkinDir(app, id)

    /** Pakai pilihan [id] (CLASSIC_ID atau skin terunduh). true bila aktif; gagal -> pilihan sebelumnya tetap. */
    suspend fun useSkin(context: Context, id: String): Boolean {
        val app = context.applicationContext
        appContext = app
        return withContext(Dispatchers.IO) {
            val r = loadById(app, id)
            if (r is State.Active || r is State.Classic) { saveSelected(app, id); state = r; true } else false
        }
    }

    /** Muat + validasi; tidak pernah melempar. */
    fun loadFrom(loader: () -> SkinDefinition): State {
        return try {
            val skin = loader()
            val v = SkinValidator.validate(skin)
            v.warnings.forEach { Log.w(TAG, "peringatan skin '${skin.name}': $it") }
            if (v.ok) {
                Log.i(TAG, "skin '${skin.name}' v${skin.version} aktif (${skin.controls.size} kontrol, ${skin.source})")
                State.Active(skin)
            } else {
                Log.e(TAG, "skin '${skin.name}' ditolak: ${v.summary()}")
                State.Failed(v.summary())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "skin gagal dimuat", e)
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Dipanggil SkinHost bila render fatal (OOM / AndroidSVG gagal): kembali ke UI klasik. */
    fun reportRenderFailure(reason: String) {
        Log.e(TAG, "render skin gagal: $reason")
        appContext?.let { saveSelected(it, CLASSIC_ID) }
        state = State.Failed(reason)
    }
}
