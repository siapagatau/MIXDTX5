package com.kendang.realpads.skin

import androidx.compose.ui.graphics.Color
import com.kendang.realpads.ThemePalette

private fun hex(c: Color): String {
    fun ch(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", ch(c.red), ch(c.green), ch(c.blue))
}

/**
 * Warna skin mengikuti tema: token di skin.svg (@ACCENT@, @ONACCENT@, @BACKDROP@, @CTRL@, @LINE@, @PANEL@, @PAD@,
 * @TEXT@, @DIM@) diganti dengan palet tema pilihan user. Struktur/kontrol skin tidak berubah (objek yang sama);
 * hanya svgText yang berbeda, sehingga render + cache dibedakan per warna tema.
 */
fun SkinDefinition.themed(p: ThemePalette, onAccent: Color): SkinDefinition {
    if (!svgText.contains('@')) return this
    val t = svgText
        .replace("@ACCENT@", hex(p.accent))
        .replace("@ONACCENT@", hex(onAccent))
        .replace("@BACKDROP@", hex(p.backdrop))
        .replace("@CTRL@", hex(p.ctrl))
        .replace("@LINE@", hex(p.line))
        .replace("@PANEL@", hex(p.panel))
        .replace("@PAD@", hex(p.pad))
        .replace("@TEXT@", hex(p.text))
        .replace("@DIM@", hex(p.dim))
    return SkinDefinition(name, version, author, t, viewBox, zone, controls, unsupported, source)
}
