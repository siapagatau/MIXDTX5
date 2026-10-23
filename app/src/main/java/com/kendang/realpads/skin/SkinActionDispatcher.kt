package com.kendang.realpads.skin

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.kendang.realpads.AudioEngine
import com.kendang.realpads.GlobalEqEngine
import com.kendang.realpads.MicStateBus
import com.kendang.realpads.ReverbSettings
import com.kendang.realpads.SettingsBus
import com.kendang.realpads.SettingsStore
import com.kendang.realpads.eqKnobToDb
import com.kendang.realpads.pushAllSettingsToEngine
import com.kendang.realpads.startGlobalEq
import com.kendang.realpads.stopGlobalEq

/**
 * Function skin (FunctionRegistry) -> kode karaoke. Pola SAMA dengan panel floating: ubah ReverbSettings,
 * kirim ke engine native, simpan (SettingsStore) lalu umumkan (SettingsBus/MicStateBus) supaya UI klasik
 * & floating ikut sinkron. Sisi penerima bus hanya menyalin nilai ke SkinState (tidak menyimpan ulang).
 * Rentang knob/slider = rentang ReverbSettings (0..1), skala ke engine persis seperti KaraokeControls.
 */
class SkinActionDispatcher(
    private val context: Context,
    private val skin: SkinDefinition,
    private val state: SkinState
) {
    var hasMicPermission: () -> Boolean = { false }
    var requestMicPermission: () -> Unit = {}
    var onFloating: () -> Unit = {}
    var onTheme: () -> Unit = {}
    var onSkinPick: () -> Unit = {}

    private var cur: ReverbSettings = SettingsStore.load(context)
    private var micRunning: Boolean = AudioEngine.nativeIsRunning()

    /** Status ON/OFF per kunci template display (mic, reverb, echo, eqmic, eqaudio). */
    private val flags = mutableStateMapOf<String, Boolean>()

    // ---- tabel function -> (baca, tulis) ----
    private class V(val get: (ReverbSettings) -> Float, val set: (ReverbSettings, Float) -> ReverbSettings)
    private val values: Map<String, V> = mapOf(
        "mic.volume" to V({ it.micVol }, { s, v -> s.copy(micVol = v) }),
        "mic.gate" to V({ it.gate }, { s, v -> s.copy(gate = v) }),
        "reverb.wet" to V({ it.wet }, { s, v -> s.copy(wet = v) }),
        "reverb.dry" to V({ it.dry }, { s, v -> s.copy(dry = v) }),
        "reverb.width" to V({ it.width }, { s, v -> s.copy(width = v) }),
        "reverb.damp" to V({ it.damp }, { s, v -> s.copy(damp = v) }),
        "reverb.room" to V({ it.roomSize }, { s, v -> s.copy(roomSize = v) }),
        "reverb.cess" to V({ it.cess }, { s, v -> s.copy(cess = v) }),
        "echo.delay" to V({ it.echoDelay }, { s, v -> s.copy(echoDelay = v) }),
        "echo.feedback" to V({ it.echoFeedback }, { s, v -> s.copy(echoFeedback = v) }),
        "echo.mix" to V({ it.echoMix }, { s, v -> s.copy(echoMix = v) }),
        "eqmic.low" to V({ it.eqLow }, { s, v -> s.copy(eqLow = v) }),
        "eqmic.mid" to V({ it.eqMid }, { s, v -> s.copy(eqMid = v) }),
        "eqmic.high" to V({ it.eqHigh }, { s, v -> s.copy(eqHigh = v) }),
        "eqaudio.gain" to V({ it.gEqGain }, { s, v -> s.copy(gEqGain = v) }),
        "eqaudio.sub" to V({ it.gEqSub }, { s, v -> s.copy(gEqSub = v) }),
        "eqaudio.low" to V({ it.gEqLow }, { s, v -> s.copy(gEqLow = v) }),
        "eqaudio.mid" to V({ it.gEqMid }, { s, v -> s.copy(gEqMid = v) }),
        "eqaudio.high" to V({ it.gEqHigh }, { s, v -> s.copy(gEqHigh = v) }),
    )

    private fun toggleValue(function: String, s: ReverbSettings): Boolean = when (function) {
        "mic.toggle" -> micRunning
        "reverb.toggle" -> s.reverbEnabled
        "echo.toggle" -> s.echoEnabled
        "eqmic.toggle" -> s.eqEnabled
        "eqaudio.toggle" -> s.gEqEnabled
        else -> false
    }

    // ---- state -> skin ----

    /** Salin ReverbSettings (+ status mic) ke SkinState. */
    fun syncFrom(s: ReverbSettings) {
        cur = s
        for (c in skin.controls) {
            when (c.type) {
                ControlType.ROTARY, ControlType.SLIDER -> values[c.function]?.let { v ->
                    val nv = v.get(s)
                    if (kotlin.math.abs((state.values[c.id] ?: -1f) - nv) > 1e-4f) state.values[c.id] = nv
                }
                ControlType.TOGGLE -> state.toggles[c.id] = toggleValue(c.function, s)
                else -> {}
            }
        }
        flags["mic"] = micRunning
        flags["reverb"] = s.reverbEnabled
        flags["echo"] = s.echoEnabled
        flags["eqmic"] = s.eqEnabled
        flags["eqaudio"] = s.gEqEnabled
    }

    fun syncAll() {
        micRunning = AudioEngine.nativeIsRunning()
        syncFrom(SettingsStore.load(context))
    }

    fun setMicRunning(running: Boolean) {
        micRunning = running
        syncFrom(cur)
    }

    fun displayText(c: SkinControl): List<String> {
        val tpl = c.display?.lines ?: return emptyList()
        val re = Regex("\\{(\\w+):ONOFF\\}")
        return tpl.map { line -> re.replace(line) { m -> if (flags[m.groupValues[1]] == true) "ON" else "OFF" } }
    }

    // ---- skin -> aplikasi ----

    fun onValue(c: SkinControl, v: Float) {
        val entry = values[c.function] ?: return
        val s = entry.set(cur, v.coerceIn(0f, 1f))
        cur = s
        pushValue(c.function, s)
        persist(s)
    }

    private fun pushValue(function: String, s: ReverbSettings) {
        when {
            function == "mic.volume" -> AudioEngine.nativeSetMicGain(s.micVol * 2f)
            function == "mic.gate" -> AudioEngine.nativeSetGate(s.gate)
            function == "reverb.cess" -> AudioEngine.nativeSetCess(s.cess)
            function.startsWith("reverb.") -> AudioEngine.nativeSetReverbParams(s.wet, s.dry, s.width, s.damp, s.roomSize)
            function.startsWith("echo.") -> AudioEngine.nativeSetEchoParams(50f + s.echoDelay * 550f, s.echoFeedback * 0.75f, s.echoMix)
            function.startsWith("eqmic.") -> AudioEngine.nativeSetEQParams(eqKnobToDb(s.eqLow), eqKnobToDb(s.eqMid), eqKnobToDb(s.eqHigh))
            function.startsWith("eqaudio.") -> GlobalEqEngine.applySettings(s)
        }
    }

    fun onToggle(c: SkinControl, on: Boolean) {
        when (c.function) {
            "mic.toggle" -> toggleMic()
            "reverb.toggle" -> { cur = cur.copy(reverbEnabled = on); AudioEngine.nativeSetReverbEnabled(on); persist(cur) }
            "echo.toggle" -> { cur = cur.copy(echoEnabled = on); AudioEngine.nativeSetEchoEnabled(on); persist(cur) }
            "eqmic.toggle" -> { cur = cur.copy(eqEnabled = on); AudioEngine.nativeSetEQEnabled(on); persist(cur) }
            "eqaudio.toggle" -> {
                cur = cur.copy(gEqEnabled = on)
                persist(cur)   // simpan dulu: service membaca status ON dari SettingsStore
                if (on) startGlobalEq(context) else stopGlobalEq(context)
            }
        }
    }

    /** Sama dengan toggleEngine() di KaraokeControls. Status toggle dikoreksi dari kenyataan engine. */
    private fun toggleMic() {
        if (!hasMicPermission()) {
            requestMicPermission()
            syncFrom(cur)   // kembalikan tampilan toggle ke OFF
            return
        }
        if (micRunning) {
            AudioEngine.nativeStop()
            micRunning = false
            MicStateBus.publish(false)
        } else {
            if (AudioEngine.nativeStart()) {
                pushAllSettingsToEngine(cur)
                micRunning = true
                MicStateBus.publish(true)
            }
        }
        syncFrom(cur)
    }

    fun onButton(c: SkinControl) {
        when (c.function) {
            "floating.toggle" -> onFloating()
            "theme.open" -> onTheme()
            "skin.pick" -> onSkinPick()
        }
    }

    private fun persist(s: ReverbSettings) {
        SettingsStore.save(context, s)
        SettingsBus.publish(s)
        flags["reverb"] = s.reverbEnabled
        flags["echo"] = s.echoEnabled
        flags["eqmic"] = s.eqEnabled
        flags["eqaudio"] = s.gEqEnabled
    }
}
