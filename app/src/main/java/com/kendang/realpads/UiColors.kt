package com.kendang.realpads

import androidx.compose.ui.graphics.Color

// Palet panel/dialog. SEMUA turunan dari warna tema (ThemeState): hue warna tema
// dipakai sebagai "tint" tipis di permukaan gelap, jadi saat user ganti warna tema,
// panel, tombol, kartu, knob, switch, meter dan dialog ikut berubah serasi.
// Dibuat getter (bukan konstanta) supaya Compose recompose saat tema berganti.
val PanelColor: Color get() = ThemeState.palette.panel
val PadColor: Color get() = ThemeState.palette.pad
val PadTextColor: Color get() = ThemeState.palette.text
val CtrlPanelColor: Color get() = ThemeState.palette.ctrl
val TextDim: Color get() = ThemeState.palette.dim
val PadLineColor: Color get() = ThemeState.palette.line

// Latar layar paling belakang (di bawah panel / background gambar).
val BackdropColor: Color get() = ThemeState.palette.backdrop
