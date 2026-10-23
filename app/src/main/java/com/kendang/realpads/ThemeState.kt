package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// Warna aksen default: putih, dipakai kalau user belum pernah custom warna sendiri.
val DefaultAccentColor = Color(0xFFFFFFFF)

private const val THEME_PREFS = "mixer_theme"
private const val KEY_ACCENT_ARGB = "accent_argb"

// Nyimpen warna tema custom user (bebas, bukan dari daftar pilihan) dan bikin dia
// tetep nempel walau apk ditutup total, karena disimpen di SharedPreferences.
object ThemeState {
    var accentColor by mutableStateOf(DefaultAccentColor)
        private set

    // Warna teks/icon di atas accentColor otomatis nyesuain (gelap kalau warnanya terang,
    // terang kalau warnanya gelap) biar tetap kebaca walau usernya pilih warna lembut/pastel.
    val accentTextColor: Color
        get() = bestTextColorFor(accentColor)

    // Aksen yang dijamin kelihatan di atas permukaan gelap (kalau user pilih warna sangat
    // gelap, dipakai versi yang dicerahkan; hue tetap sama). Pakai ini untuk knob/switch/meter.
    val uiAccent: Color
        get() = palette.accent

    val uiAccentText: Color
        get() = bestTextColorFor(palette.accent)

    // Palet turunan: dihitung ulang hanya saat accentColor berubah.
    val palette: ThemePalette
        get() {
            val c = accentColor
            val cached = paletteCache
            if (cached != null && cached.source == c) return cached
            return buildPalette(c).also { paletteCache = it }
        }
    private var paletteCache: ThemePalette? = null

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ACCENT_ARGB)) {
            val argb = prefs.getInt(KEY_ACCENT_ARGB, DefaultAccentColor.toArgb())
            accentColor = Color(argb)
        }
    }

    fun set(context: Context, color: Color) {
        accentColor = color
        context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCENT_ARGB, color.toArgb())
            .apply()
    }
}

// Pilih warna teks kontras (hitam-kehangatan atau putih-krem) berdasar luminance background.
fun bestTextColorFor(bg: Color): Color {
    val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    return if (luminance > 0.6f) Color(0xFF201512) else Color(0xFFF2EDE9)
}

// "C97A5F" atau "#C97A5F" -> Color. Return null kalau formatnya gak valid.
fun parseHexColor(input: String): Color? {
    val clean = input.trim().removePrefix("#")
    if (clean.length != 6) return null
    return try {
        val r = clean.substring(0, 2).toInt(16) / 255f
        val g = clean.substring(2, 4).toInt(16) / 255f
        val b = clean.substring(4, 6).toInt(16) / 255f
        Color(r, g, b)
    } catch (e: NumberFormatException) {
        null
    }
}

fun colorToHex(c: Color): String {
    val r = (c.red * 255).toInt().coerceIn(0, 255)
    val g = (c.green * 255).toInt().coerceIn(0, 255)
    val b = (c.blue * 255).toInt().coerceIn(0, 255)
    return String.format("%02X%02X%02X", r, g, b)
}

// Satu set warna UI yang serasi dengan warna tema.
class ThemePalette(
    val source: Color,
    val accent: Color,
    val backdrop: Color,
    val panel: Color,
    val pad: Color,
    val ctrl: Color,
    val line: Color,
    val text: Color,
    val dim: Color,
)

private fun hsvColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))

// Permukaan selalu gelap (kontras teks aman) tapi diberi tint hue warna tema.
// Warna tema putih/abu (saturasi 0) menghasilkan permukaan abu netral ala DTX.
private fun buildPalette(src: Color): ThemePalette {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(src.toArgb(), hsv)
    val h = hsv[0]
    val tint = (hsv[1] * 0.28f).coerceAtMost(0.28f)
    val accent = if (hsv[2] < 0.55f && hsv[1] > 0.02f) hsvColor(h, hsv[1], 0.6f)
    else if (hsv[2] < 0.55f) hsvColor(h, 0f, 0.6f)
    else src
    return ThemePalette(
        source = src,
        accent = accent,
        backdrop = hsvColor(h, tint, 0.07f),
        ctrl = hsvColor(h, tint, 0.115f),
        line = hsvColor(h, tint, 0.145f),
        panel = hsvColor(h, tint, 0.235f),
        pad = hsvColor(h, tint, 0.30f),
        text = hsvColor(h, tint * 0.25f, 0.78f),
        dim = hsvColor(h, tint * 0.35f, 0.58f),
    )
}
