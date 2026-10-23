package com.kendang.realpads.skin

/**
 * Katalog function RESMI skin mixer (PROMPT_SKIN_MIXER.md §5). Function di luar katalog = skin ditolak.
 * Tahap 3 menghubungkan tiap function ke kode karaoke (SkinActionDispatcher).
 */
object FunctionRegistry {
    val toggleFunctions: Set<String> = setOf("mic.toggle", "reverb.toggle", "echo.toggle", "eqmic.toggle", "eqaudio.toggle")
    val valueFunctions: Set<String> = setOf(
        "mic.volume", "mic.gate",
        "reverb.wet", "reverb.dry", "reverb.width", "reverb.damp", "reverb.room", "reverb.cess",
        "echo.delay", "echo.feedback", "echo.mix",
        "eqmic.low", "eqmic.mid", "eqmic.high",
        "eqaudio.gain", "eqaudio.sub", "eqaudio.low", "eqaudio.mid", "eqaudio.high"
    )
    val meterFunctions: Set<String> = setOf("meter.mic", "meter.output")
    val buttonFunctions: Set<String> = setOf("floating.toggle", "theme.open", "skin.pick")
    val displayFunctions: Set<String> = setOf("display.status")

    val official: Set<String> = toggleFunctions + valueFunctions + meterFunctions + buttonFunctions + displayFunctions

    fun allowedFor(type: ControlType): Set<String> = when (type) {
        ControlType.BUTTON -> buttonFunctions
        ControlType.TOGGLE -> toggleFunctions
        ControlType.ROTARY, ControlType.SLIDER -> valueFunctions
        ControlType.METER -> meterFunctions
        ControlType.DISPLAY -> displayFunctions
    }
}
