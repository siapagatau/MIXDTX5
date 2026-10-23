package com.kendang.realpads.skin

import androidx.compose.runtime.mutableStateMapOf

/**
 * Jembatan UI skin <-> aplikasi. SkinHost HANYA membaca/menulis objek ini; tahap 3 mengisinya dari
 * ReverbSettings/AudioEngine (dispatcher) dan memasang callback onValue/onToggle/onButton.
 * Kunci map = id kontrol di skin.json (mis. "knob-reverb-wet").
 */
class SkinState {
    val values = mutableStateMapOf<String, Float>()      // knob / slider: 0..1
    val toggles = mutableStateMapOf<String, Boolean>()   // toggle: ON/OFF
    val meters = mutableStateMapOf<String, Float>()      // meter: 0..1
    val pressed = mutableStateMapOf<String, Boolean>()   // tombol sedang ditekan

    /** Teks tiap baris display (template sudah diisi). Default: template mentah. */
    var displayText: (SkinControl) -> List<String> = { it.display?.lines ?: emptyList() }

    var onValue: (SkinControl, Float) -> Unit = { _, _ -> }
    var onToggle: (SkinControl, Boolean) -> Unit = { _, _ -> }
    var onButton: (SkinControl) -> Unit = {}

    /** Isi nilai awal dari skin (hanya untuk kunci yang belum ada). */
    fun initDefaults(skin: SkinDefinition) {
        for (c in skin.controls) {
            when (c.type) {
                ControlType.ROTARY -> if (c.id !in values) values[c.id] = c.rotary?.defaultValue ?: 0.5f
                ControlType.SLIDER -> if (c.id !in values) values[c.id] = c.slider?.defaultValue ?: 0.5f
                ControlType.TOGGLE -> if (c.id !in toggles) toggles[c.id] = false
                else -> {}
            }
        }
    }
}
