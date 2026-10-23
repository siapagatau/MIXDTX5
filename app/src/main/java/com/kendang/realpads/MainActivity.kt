package com.kendang.realpads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kendang.realpads.skin.SkinBinding
import com.kendang.realpads.skin.SkinHost
import com.kendang.realpads.skin.themed
import com.kendang.realpads.skin.SkinManager
import com.kendang.realpads.skin.SkinPickerDialog
import com.kendang.realpads.skin.SkinState
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private var hasMicPermission by mutableStateOf(false)

    // True kalau user menekan switch Floating sebelum izin mic ada - begitu
    // izin diberikan, alur Floating dilanjutkan otomatis.
    private var pendingFloating = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted && pendingFloating) requestFloatingMode()
        pendingFloating = false
    }

    // Panel floating butuh 2 izin: notifikasi (Android 13+, wajib buat
    // foreground service-nya) dan "gambar di atas app lain". Alurnya:
    // minta notifikasi dulu (kalau perlu) -> baru cek/minta izin overlay ->
    // baru nyalain FloatingWindowService. Ini SAMA SEKALI gak nyentuh
    // AudioEngine, jadi gak ada risiko nambah delay mic.
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> proceedToOverlayCheck() }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (Settings.canDrawOverlays(this)) startFloatingService(this) }

    fun requestFloatingMode() {
        // Foreground service bertipe microphone WAJIB punya izin RECORD_AUDIO
        // (Android 14+ langsung crash kalau belum), jadi minta dulu.
        if (!hasMicPermission) {
            pendingFloating = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedToOverlayCheck()
    }

    private fun proceedToOverlayCheck() {
        if (Settings.canDrawOverlays(this)) {
            startFloatingService(this)
        } else {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // Landscape penuh: sembunyikan status/navigation bar (muncul sementara bila digeser dari tepi).
    private fun applyImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()   // dialog/izin bisa mengembalikan bar -> sembunyikan lagi
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seperti DTX: konten edge-to-edge, jarak aman diatur sendiri lewat safeDrawingPadding.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Output mic->speaker memakai Usage::Media (lihat openStreams() di
        // KaraokeEngine.cpp), jadi tombol fisik volume harus ke STREAM_MUSIC.
        // Dulu diarahkan ke STREAM_ALARM (sisa percobaan lama) -> tombol
        // malah menaikkan volume alarm, bukan suara mic.
        volumeControlStream = AudioManager.STREAM_MUSIC

        ThemeState.load(this)

        hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            KaraokeScreen(
                hasMicPermission = hasMicPermission,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onRequestFloatingMode = { requestFloatingMode() },
            )
        }

        // Minta izin mic langsung saat app pertama kali dibuka.
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Visual sentuhan global (sama dengan APK DTX): overlay di atas seluruh konten, tidak menangani input.
        val fxOverlay = TouchFxOverlay(this)
        touchFxOverlay = fxOverlay
        addContentView(
            fxOverlay,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val appCtx = applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val loaded = TouchFxState.loadFromDisk(appCtx)
            android.os.Handler(android.os.Looper.getMainLooper()).post { TouchFxState.applyLoaded(loaded) }
        }
    }

    private var touchFxOverlay: TouchFxOverlay? = null

    // Hanya MEMBACA event setelah jalur normal (kontrol/audio/UI) selesai - tidak mengubah,
    // menunda, atau mengonsumsi sentuhan.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        try { touchFxOverlay?.onTouch(ev) } catch (_: Throwable) {}
        return handled
    }

    override fun onDestroy() {
        super.onDestroy()
        // Lepas semua (stream + native object) pas activity beneran ditutup.
        AudioEngine.nativeShutdown()
    }
}

fun startFloatingService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, FloatingWindowService::class.java))
}

fun stopFloatingService(context: Context) {
    context.stopService(Intent(context, FloatingWindowService::class.java))
}

// Knob EQ 0..1 -> gain asli -15..+15 dB (0.5 = tengah = 0dB/flat), persis
// rentang EQ channel strip Yamaha MG yang jadi acuan fitur ini.
fun eqKnobToDb(knob: Float): Float = (knob - 0.5f) * 30f
fun dbToEqKnob(db: Float): Float = db / 30f + 0.5f

// Palet warna satu tempat biar seluruh layar konsisten.
// SEMUA warna turunan dari tema pilihan user (ThemeState -> UiColors.kt), tidak ada lagi
// warna acak per kartu: satu aksen + permukaan ber-tint hue yang sama.
private val BgColor: Color get() = BackdropColor
private val CardColor: Color get() = PanelColor
private val OffColor: Color get() = TextDim
private val BlueAccent: Color get() = ThemeState.uiAccent
private val OrangeAccent: Color get() = ThemeState.uiAccent
private val GreenAccent: Color get() = ThemeState.uiAccent

// Kartu seragam: judul di kiri, (opsional) switch on/off di kanan, isi di
// bawahnya. Dipakai EQ / Reverb / Echo biar tampilannya rapi & sama persis,
// bukan 3 blok kode kembar yang beda-beda dikit.
@Composable
private fun SectionCard(
    title: String,
    accent: Color,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardColor)
            .border(1.dp, PadLineColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = PadTextColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (checked != null) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.scale(0.8f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ThemeState.uiAccentText,
                        checkedTrackColor = accent,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = TextDim,
                        uncheckedTrackColor = CtrlPanelColor,
                        uncheckedBorderColor = Color.Transparent,
                    )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun KnobRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        content = content
    )
}

// Layar utama (APK): landscape. Header (judul, TEMA, Floating) + kontrol 3 kolom.
@Composable
fun KaraokeScreen(
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRequestFloatingMode: () -> Unit = {},
) {
    val context = LocalContext.current
    val floatingOn by FloatingWindowService.running.collectAsState()
    var showTheme by remember { mutableStateOf(false) }
    var showSkinPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SkinManager.ensureLoaded(context) }

    // Background gambar (Tema -> Background): dibaca & disalin ke internal storage langsung (sama dengan DTX).
    LaunchedEffect(Unit) {
        val loadedBg = withContext(Dispatchers.IO) { BackgroundState.loadFromDisk(context) }
        BackgroundState.applyLoaded(loadedBg)
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                val decoded = bytes?.let { b -> withContext(Dispatchers.IO) { BackgroundState.decodeAndSave(context, b) } }
                if (decoded != null) BackgroundState.applyImage(context, decoded)
                else Toast.makeText(context, "Gagal muat gambar background", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Gambar visual sentuhan (PNG transparan didukung).
    val touchFxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.let { TouchFxState.decodeAndSave(context, it) }
                }
                if (bmp != null) TouchFxState.applyImage(bmp)
                else Toast.makeText(context, "Gagal muat gambar sentuhan", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val bgImage = BackgroundState.imageBitmap
    val skinState = remember { SkinState() }
    val skinSt = SkinManager.state
    Box(Modifier.fillMaxSize().background(BgColor)) {
    if (bgImage != null) {
        Image(
            bitmap = bgImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = BackgroundState.opacity,
            modifier = Modifier.matchParentSize()
        )
    }
    val onFloat = { if (floatingOn) stopFloatingService(context) else onRequestFloatingMode() }
    if (skinSt is SkinManager.State.Active) {
        // Warna skin ikut tema: token warna di SVG diganti palet tema (lihat skin/SkinTheme.kt).
        val palette = ThemeState.palette
        val onAccent = ThemeState.uiAccentText
        val themedSkin = remember(skinSt.skin, palette, onAccent) { skinSt.skin.themed(palette, onAccent) }
        SkinBinding(
            skin = skinSt.skin, state = skinState,
            hasMicPermission = hasMicPermission, onRequestPermission = onRequestPermission,
            onFloating = onFloat,
            onTheme = { showTheme = true },
            onSkinPick = { showSkinPicker = true },
        )
        // Susunan DTX: SATU kotak skin berisi semua kontrol + strip tombol pendukung di luar kotak.
        DtxShell(
            ratio = themedSkin.zone.w / themedSkin.zone.h,
            floatingOn = floatingOn, onFloating = onFloat,
            onTheme = { showTheme = true }, onSkin = { showSkinPicker = true },
        ) {
            SkinHost(themedSkin, skinState, transparentBg = bgImage != null)
        }
    } else if (skinSt is SkinManager.State.Loading) {
        Box(Modifier.fillMaxSize().background(BgColor))
    } else {

    Surface(modifier = Modifier.fillMaxSize(), color = if (bgImage != null) Color.Transparent else BgColor) {
        // Tata letak seperti APK DTX: [panel kiri] [area tengah + strip tombol bawah] [panel kanan]. Tanpa scroll.
        KaraokeControls(
            hasMicPermission = hasMicPermission,
            onRequestPermission = onRequestPermission,
            landscape = true,
            dtx = DtxActions(
                floatingOn = floatingOn,
                onFloating = { if (floatingOn) stopFloatingService(context) else onRequestFloatingMode() },
                onTheme = { showTheme = true },
                onSkin = { showSkinPicker = true },
            ),
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
        )
    }

    }
    } // Box latar

    if (showSkinPicker) SkinPickerDialog(onDismiss = { showSkinPicker = false })

    if (showTheme) {
        ColorPickerDialog(
            initial = ThemeState.accentColor,
            backgroundImage = BackgroundState.imageBitmap,
            backgroundOpacity = BackgroundState.opacity,
            padOpacity = BackgroundState.padOpacity,
            onDismiss = { showTheme = false },
            onConfirm = { color ->
                ThemeState.set(context, color)
                showTheme = false
            },
            onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
            onClearBackground = { BackgroundState.clearImage(context) },
            onOpacityChange = { BackgroundState.setOpacity(context, it) },
            onPadOpacityChange = { BackgroundState.setPadOpacity(context, it) },
            touchFxPreview = TouchFxState.preview,
            touchFxSizeDp = TouchFxState.sizeDp,
            onPickTouchFx = { touchFxPicker.launch(arrayOf("image/*")) },
            onClearTouchFx = { TouchFxState.clearImage(context) },
            onTouchFxSizeChange = { TouchFxState.setSize(context, it) },
            touchFxFadeMs = TouchFxState.fadeMs,
            onTouchFxFadeChange = { TouchFxState.setFade(context, it) }
        )
    }
}

// Isi panel FLOATING: memakai KaraokeControls yang SAMA dengan layar utama
// (Mic, Volume, Gate, EQ, Reverb, Echo - semua knob, switch, bentuk, dan
// posisi persis seperti di APK), jadi live dua arah lewat SettingsBus /
// MicStateBus. Header (geser/minimize/tutup) dan handle resize ada di
// layout native floating_panel.xml.
@Composable
fun KaraokeFloatingBody(hasMicPermission: Boolean) {
    val ctx = LocalContext.current
    remember { ThemeState.load(ctx) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        KaraokeControls(hasMicPermission = hasMicPermission, onRequestPermission = {})
        Spacer(Modifier.height(36.dp)) // ruang buat handle resize di pojok
    }
}

// ---------------------------------------------------------------------------
// Meter LED: SATU batang per sumber (Mic & Musik), terdiri dari kotak-kotak
// kecil hijau -> kuning -> merah yang nyala berurutan dari kiri sesuai level
// suara. Gerakannya cepat naik / cepat turun jadi kelihatan ngikutin ketukan.
// (Tanpa peak-hold: kotak peak yang turun pelan kelihatan seperti LED
// nyasar/telat yang terpisah dari batang.)
// ---------------------------------------------------------------------------
private const val LED_SEGMENTS = 28
private val LedGreen: Color get() = ThemeState.uiAccent   // zona aman mengikuti tema
private val LedYellow = Color(0xFFFFD43B)
private val LedRed = Color(0xFFFF4B4B)

private class MeterState {
    var mic by mutableFloatStateOf(0f)
    var music by mutableFloatStateOf(0f)
}

// Warna kotak ke-i: 0..60% hijau, 60..82% kuning, sisanya merah.
private fun ledColor(i: Int): Color {
    val f = i / LED_SEGMENTS.toFloat()
    return when {
        f < 0.60f -> LedGreen
        f < 0.82f -> LedYellow
        else -> LedRed
    }
}

@Composable
private fun LedBar(level: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val lv = level().coerceIn(0f, 1f)
        val gap = 2.dp.toPx()
        val segW = (size.width - gap * (LED_SEGMENTS - 1)) / LED_SEGMENTS
        val lit = (lv * LED_SEGMENTS).toInt()
        for (i in 0 until LED_SEGMENTS) {
            val c = ledColor(i)
            val on = i < lit
            drawRoundRect(
                color = if (on) c else c.copy(alpha = 0.16f),
                topLeft = Offset(i * (segW + gap), 0f),
                size = Size(segW, size.height),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}

// Satu baris: label di kiri, batang LED di kanan.
@Composable
private fun LedRow(label: String, level: () -> Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, color = PadTextColor, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.width(52.dp)
        )
        LedBar(level, Modifier.weight(1f).height(18.dp))
    }
}

// Amplitudo (0..1) -> posisi meter 0..1 dengan skala dB (-60 dB .. 0 dB).
private fun levelFromAmp(amp: Float): Float =
    ((20f * log10(amp + 1e-6f) + 60f) / 60f).coerceIn(0f, 1f)

// Kartu Level: meter MIC dan meter MUSIK terpisah. Polling ~30 fps cuma jalan
// selama layar ini kelihatan (RESUMED), dan Visualizer musik dibuat/dilepas
// ikut itu.
@Composable
private fun rememberMeters(hasMicPermission: Boolean): MeterState {
    val m = remember { MeterState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var active by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val musicError by MusicMeter.error.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) active = true
            else if (event == Lifecycle.Event.ON_PAUSE) active = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(active, hasMicPermission) {
        if (!active) {
            m.mic = 0f; m.music = 0f
            return@LaunchedEffect
        }
        // Visualizer butuh izin mic (sudah diminta app ini).
        if (hasMicPermission) MusicMeter.acquire()
        try {
            while (true) {
                delay(33)
                val (micRms, musicRms) = withContext(Dispatchers.Default) {
                    val running = AudioEngine.nativeIsRunning()
                    val mic = if (running) sqrt(AudioEngine.nativeGetMicMeanSquare()) else 0f
                    val engineMs = if (running) AudioEngine.nativeGetOutputMeanSquare() else 0f
                    val mix = if (hasMicPermission) MusicMeter.readMixRms() else -1f
                    // Mix akhir = musik + suara mic engine -> kurangi daya engine.
                    val music = if (mix < 0f) 0f else sqrt(max(0f, mix * mix - engineMs))
                    mic to music
                }
                val micNow = levelFromAmp(micRms)
                val musicNow = levelFromAmp(musicRms)
                // Naik langsung, turun cepat -> batang "mukul" ngikutin ketukan.
                m.mic = if (micNow > m.mic) micNow else max(micNow, m.mic - 0.09f)
                m.music = if (musicNow > m.music) musicNow else max(musicNow, m.music - 0.09f)
            }
        } finally {
            if (hasMicPermission) MusicMeter.release()
        }
    }

    return m
}

@Composable
private fun LevelCard(hasMicPermission: Boolean) {
    val m = rememberMeters(hasMicPermission)
    val musicError by MusicMeter.error.collectAsState()
    SectionCard(title = "Level", accent = GreenAccent) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LedRow("Mic", { m.mic })
            LedRow("Musik", { m.music })
            if (musicError != null) {
                Text(musicError ?: "", color = OffColor, fontSize = 12.sp)
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Komponen tampilan ala APK DTX (panel kiri tab, area tengah, strip tombol bawah, meter kanan).
// ---------------------------------------------------------------------------
class DtxActions(
    val floatingOn: Boolean,
    val onFloating: () -> Unit,
    val onTheme: () -> Unit,
    val onSkin: () -> Unit,
)

private class KnobSpec(
    val label: String, val value: Float, val onChange: (Float) -> Unit, val enabled: Boolean = true,
)

// Grid knob yang ukurannya menyesuaikan ruang tersedia (tidak pernah perlu scroll).
@Composable
private fun KnobGrid(specs: List<KnobSpec>, perRow: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val rows = (specs.size + perRow - 1) / perRow
        val cellW = maxWidth / perRow
        val cellH = maxHeight / rows
        val raw = minOf(cellW - 8.dp, cellH - 22.dp)
        val knob = if (raw < 40.dp) 40.dp else if (raw > 96.dp) 96.dp else raw
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            specs.chunked(perRow).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach {
                        RotaryKnob(
                            label = it.label, value = it.value, onValueChange = it.onChange,
                            accentColor = ThemeState.uiAccent, enabled = it.enabled,
                            knobSize = knob, labelSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// Kerangka layar ala DTX: kotak skin (rasio mengikuti skin, tidak pernah gepeng) di tengah + strip tombol
// pendukung di bawahnya. Semua fungsi mixer ada DI DALAM kotak skin; strip hanya FLOATING / TEMA / SKIN.
@Composable
private fun DtxShell(
    ratio: Float,
    floatingOn: Boolean,
    onFloating: () -> Unit,
    onTheme: () -> Unit,
    onSkin: () -> Unit,
    box: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(6.dp)) {
        val stripH = 46.dp
        val gap = 6.dp
        val availH = (maxHeight - stripH - gap).coerceAtLeast(1.dp)
        val boxW = minOf(maxWidth, availH * ratio)
        val boxH = boxW / ratio
        Column(
            Modifier.align(Alignment.Center).width(boxW),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            Box(Modifier.fillMaxWidth().height(boxH).clip(RoundedCornerShape(6.dp))) { box() }
            Row(
                Modifier.fillMaxWidth().height(stripH),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
            ) {
                DtxButton("FLOATING", floatingOn, Modifier.width(140.dp), onFloating)
                DtxButton("TEMA", false, Modifier.width(140.dp), onTheme)
                DtxButton("SKIN", false, Modifier.width(140.dp), onSkin)
            }
        }
    }
}

@Composable
private fun DtxButton(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (active) ThemeState.uiAccent else PanelColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) ThemeState.uiAccentText else PadTextColor,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
        )
    }
}

// Judul bagian + (opsional) switch ON/OFF di kanan.
@Composable
private fun DtxHeader(title: String, checked: Boolean?, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = ThemeState.uiAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (checked != null) {
            Switch(
                checked = checked, onCheckedChange = onChecked,
                modifier = Modifier.scale(0.7f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ThemeState.uiAccentText, checkedTrackColor = ThemeState.uiAccent,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = TextDim, uncheckedTrackColor = CtrlPanelColor,
                    uncheckedBorderColor = Color.Transparent,
                )
            )
        }
    }
}

// Meter LED vertikal (panel kanan), kotak menyala dari bawah: hijau -> kuning -> merah.
@Composable
private fun VMeter(label: String, level: () -> Float, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.weight(1f).width(14.dp)) {
            val n = 20
            val lv = level().coerceIn(0f, 1f)
            val gap = 2.dp.toPx()
            val segH = (size.height - gap * (n - 1)) / n
            val lit = (lv * n).toInt()
            for (i in 0 until n) {
                val f = i / n.toFloat()
                val c = if (f < 0.60f) LedGreen else if (f < 0.82f) LedYellow else LedRed
                drawRoundRect(
                    color = if (i < lit) c else c.copy(alpha = 0.16f),
                    topLeft = Offset(0f, size.height - (i + 1) * segH - i * gap),
                    size = Size(size.width, segH),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextDim, fontSize = 9.sp, maxLines = 1)
    }
}

// Semua kartu kontrol + state-nya. Dipakai layar utama DAN panel floating.
@Composable
fun KaraokeControls(
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    landscape: Boolean = false,
    dtx: DtxActions? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Muat pengaturan terakhir yang kesimpen - sekali doang lewat `remember`.
    val saved = remember { SettingsStore.load(context) }

    var isRunning by remember { mutableStateOf(AudioEngine.nativeIsRunning()) }
    var wet by remember { mutableFloatStateOf(saved.wet) }
    var dry by remember { mutableFloatStateOf(saved.dry) }
    var width by remember { mutableFloatStateOf(saved.width) }
    var damp by remember { mutableFloatStateOf(saved.damp) }
    var roomSize by remember { mutableFloatStateOf(saved.roomSize) }
    var cess by remember { mutableFloatStateOf(saved.cess) }
    var gate by remember { mutableFloatStateOf(saved.gate) }
    // Knob 0..1 di-map ke gain 0..2x (tengah = 1.0x = suara asli).
    var micVol by remember { mutableFloatStateOf(saved.micVol) }
    var reverbEnabled by remember { mutableStateOf(saved.reverbEnabled) }
    var echoDelay by remember { mutableFloatStateOf(saved.echoDelay) }
    var echoFeedback by remember { mutableFloatStateOf(saved.echoFeedback) }
    var echoMix by remember { mutableFloatStateOf(saved.echoMix) }
    var echoEnabled by remember { mutableStateOf(saved.echoEnabled) }
    // EQ: 0.5 = flat (0 dB).
    var eqLow by remember { mutableFloatStateOf(saved.eqLow) }
    var eqMid by remember { mutableFloatStateOf(saved.eqMid) }
    var eqHigh by remember { mutableFloatStateOf(saved.eqHigh) }
    var eqEnabled by remember { mutableStateOf(saved.eqEnabled) }
    // EQ Audio global (suara app lain): gain + Sub/Low/Mid/High, 0.5 = flat.
    var gEqGain by remember { mutableFloatStateOf(saved.gEqGain) }
    var gEqSub by remember { mutableFloatStateOf(saved.gEqSub) }
    var gEqLow by remember { mutableFloatStateOf(saved.gEqLow) }
    var gEqMid by remember { mutableFloatStateOf(saved.gEqMid) }
    var gEqHigh by remember { mutableFloatStateOf(saved.gEqHigh) }
    var gEqEnabled by remember { mutableStateOf(saved.gEqEnabled) }
    val gEqError by GlobalEqEngine.error.collectAsState()

    fun applyFresh(f: ReverbSettings) {
        wet = f.wet; dry = f.dry; width = f.width; damp = f.damp
        roomSize = f.roomSize; cess = f.cess; gate = f.gate; micVol = f.micVol
        reverbEnabled = f.reverbEnabled
        echoDelay = f.echoDelay; echoFeedback = f.echoFeedback
        echoMix = f.echoMix; echoEnabled = f.echoEnabled
        eqLow = f.eqLow; eqMid = f.eqMid; eqHigh = f.eqHigh; eqEnabled = f.eqEnabled
        gEqGain = f.gEqGain; gEqSub = f.gEqSub; gEqLow = f.gEqLow
        gEqMid = f.gEqMid; gEqHigh = f.gEqHigh; gEqEnabled = f.gEqEnabled
    }

    // Sinkron saat layar ini balik ke depan (nilai bisa berubah lewat panel
    // floating selagi activity di-pause).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                applyFresh(SettingsStore.load(context))
                isRunning = AudioEngine.nativeIsRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Update real-time dua arah antara layar utama <-> panel floating (hanya
    // update tampilan lokal - jangan persist() lagi, sudah disimpan pengirimnya).
    LaunchedEffect(Unit) { SettingsBus.updates.collect { applyFresh(it) } }
    LaunchedEffect(Unit) { MicStateBus.updates.collect { isRunning = it } }
    // Kalau EQ Audio tersimpan ON tapi prosesnya sempat mati, nyalakan lagi.
    LaunchedEffect(Unit) {
        if (saved.gEqEnabled && !GlobalEqService.running.value) startGlobalEq(context)
    }

    fun current() = ReverbSettings(
        wet, dry, width, damp, roomSize, micVol, reverbEnabled,
        echoDelay, echoFeedback, echoMix, echoEnabled,
        eqLow, eqMid, eqHigh, eqEnabled, cess, gate,
        gEqGain = gEqGain, gEqSub = gEqSub, gEqLow = gEqLow,
        gEqMid = gEqMid, gEqHigh = gEqHigh, gEqEnabled = gEqEnabled,
    )

    fun persist() {
        val c = current()
        SettingsStore.save(context, c)
        SettingsBus.publish(c)
    }

    fun pushParams() {
        AudioEngine.nativeSetReverbParams(wet, dry, width, damp, roomSize)
        persist()
    }

    fun pushCess() {
        AudioEngine.nativeSetCess(cess)
        persist()
    }

    fun pushGate() {
        AudioEngine.nativeSetGate(gate)
        persist()
    }

    fun pushMicGain() {
        AudioEngine.nativeSetMicGain(micVol * 2f)
        persist()
    }

    fun pushReverbEnabled() {
        AudioEngine.nativeSetReverbEnabled(reverbEnabled)
        persist()
    }

    fun pushEQParams() {
        AudioEngine.nativeSetEQParams(eqKnobToDb(eqLow), eqKnobToDb(eqMid), eqKnobToDb(eqHigh))
        persist()
    }

    fun pushEQEnabled() {
        AudioEngine.nativeSetEQEnabled(eqEnabled)
        persist()
    }

    // Knob 0..1 -> delay 50-600ms, feedback 0-0.75, mix 0-1.
    fun pushEchoParams() {
        AudioEngine.nativeSetEchoParams(50f + echoDelay * 550f, echoFeedback * 0.75f, echoMix)
        persist()
    }

    fun pushEchoEnabled() {
        AudioEngine.nativeSetEchoEnabled(echoEnabled)
        persist()
    }

    fun pushGlobalEq() {
        GlobalEqEngine.applySettings(current())
        persist()
    }

    fun pushGlobalEqEnabled() {
        persist() // simpan dulu: service membaca status ON dari SettingsStore
        if (gEqEnabled) startGlobalEq(context) else stopGlobalEq(context)
    }

    fun toggleEngine() {
        if (!hasMicPermission) {
            onRequestPermission()
            return
        }
        if (isRunning) {
            AudioEngine.nativeStop()
            isRunning = false
            MicStateBus.publish(false)
        } else {
            if (AudioEngine.nativeStart()) {
                pushAllSettingsToEngine(current())
                isRunning = true
                MicStateBus.publish(true)
            }
        }
    }

    val micCard: @Composable () -> Unit = {
        // Kartu 1: tombol Mic ON/OFF (berdiri sendiri).
        SectionCard(title = "Mic", accent = OrangeAccent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) ThemeState.uiAccent.copy(alpha = 0.28f) else CtrlPanelColor)
                        .border(
                            width = 3.dp,
                            color = if (isRunning) ThemeState.uiAccent else PadLineColor,
                            shape = CircleShape
                        )
                        .clickable { toggleEngine() }
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = if (isRunning) "Matikan mic" else "Nyalakan mic",
                        tint = if (isRunning) ThemeState.uiAccent else PadTextColor,
                        modifier = Modifier.size(38.dp)
                    )
                }
                Spacer(Modifier.width(20.dp))
                Text(
                    if (isRunning) "ON" else "OFF",
                    color = if (isRunning) GreenAccent else OffColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

    }
    val levelCard: @Composable () -> Unit = {
        // Meter level: mic dan musik terpisah.
        LevelCard(hasMicPermission)

    }
    val volumeCard: @Composable () -> Unit = {
        // Kartu 2: Volume + Gate.
        SectionCard(title = "Volume", accent = OrangeAccent) {
            KnobRow {
                RotaryKnob(
                    label = "Volume",
                    value = micVol,
                    onValueChange = { micVol = it; pushMicGain() },
                    accentColor = OrangeAccent
                )
                RotaryKnob(
                    label = "Gate",
                    value = gate,
                    onValueChange = { gate = it; pushGate() },
                    accentColor = OrangeAccent
                )
            }
        }

    }
    val eqMicCard: @Composable () -> Unit = {
        SectionCard(
            title = "EQ Mic", accent = GreenAccent,
            checked = eqEnabled,
            onCheckedChange = { eqEnabled = it; pushEQEnabled() }
        ) {
            KnobRow {
                RotaryKnob("Low", eqLow, { eqLow = it; pushEQParams() }, enabled = eqEnabled, accentColor = GreenAccent)
                RotaryKnob("Mid", eqMid, { eqMid = it; pushEQParams() }, enabled = eqEnabled, accentColor = GreenAccent)
                RotaryKnob("High", eqHigh, { eqHigh = it; pushEQParams() }, enabled = eqEnabled, accentColor = GreenAccent)
            }
        }

    }
    val eqAudioCard: @Composable () -> Unit = {
        // EQ Audio GLOBAL: ngatur suara app lain (YouTube, TikTok, musik).
        SectionCard(
            title = "EQ Audio", accent = OrangeAccent,
            checked = gEqEnabled,
            onCheckedChange = { gEqEnabled = it; pushGlobalEqEnabled() }
        ) {
            KnobRow {
                RotaryKnob("Gain", gEqGain, { gEqGain = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
                RotaryKnob("Sub", gEqSub, { gEqSub = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
                RotaryKnob("Low", gEqLow, { gEqLow = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
            }
            Spacer(Modifier.height(14.dp))
            KnobRow {
                RotaryKnob("Mid", gEqMid, { gEqMid = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
                RotaryKnob("High", gEqHigh, { gEqHigh = it; pushGlobalEq() }, enabled = gEqEnabled, accentColor = OrangeAccent)
            }
            if (gEqError != null) {
                Spacer(Modifier.height(10.dp))
                Text(gEqError ?: "", color = OffColor, fontSize = 12.sp)
            }
        }

    }
    val reverbCard: @Composable () -> Unit = {
        SectionCard(
            title = "Reverb", accent = BlueAccent,
            checked = reverbEnabled,
            onCheckedChange = { reverbEnabled = it; pushReverbEnabled() }
        ) {
            KnobRow {
                RotaryKnob("Wet", wet, { wet = it; pushParams() }, enabled = reverbEnabled)
                RotaryKnob("Dry", dry, { dry = it; pushParams() }, enabled = reverbEnabled)
                RotaryKnob("Width", width, { width = it; pushParams() }, enabled = reverbEnabled)
            }
            Spacer(Modifier.height(14.dp))
            KnobRow {
                RotaryKnob("Damp", damp, { damp = it; pushParams() }, enabled = reverbEnabled)
                RotaryKnob("Room", roomSize, { roomSize = it; pushParams() }, enabled = reverbEnabled)
                RotaryKnob("Cess", cess, { cess = it; pushCess() }, enabled = reverbEnabled)
            }
        }

    }
    val echoCard: @Composable () -> Unit = {
        SectionCard(
            title = "Echo", accent = GreenAccent,
            checked = echoEnabled,
            onCheckedChange = { echoEnabled = it; pushEchoEnabled() }
        ) {
            KnobRow {
                RotaryKnob("Delay", echoDelay, { echoDelay = it; pushEchoParams() }, accentColor = GreenAccent, enabled = echoEnabled)
                RotaryKnob("Repeat", echoFeedback, { echoFeedback = it; pushEchoParams() }, accentColor = GreenAccent, enabled = echoEnabled)
                RotaryKnob("Mix", echoMix, { echoMix = it; pushEchoParams() }, accentColor = GreenAccent, enabled = echoEnabled)
            }
        }
    }
    if (dtx != null) {
        var tab by remember { mutableStateOf(0) }
        val meters = rememberMeters(hasMicPermission)
        val accent = ThemeState.uiAccent
        val hasBg = BackgroundState.imageBitmap != null
        val pa = if (hasBg) BackgroundState.padOpacity else 1f
        val tabs = listOf("MIC", "EQ MIC", "REVERB", "ECHO", "AUDIO")
        Row(modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Panel kiri: pilih bagian (seperti P1..P4 di DTX).
            Column(
                Modifier.width(76.dp).fillMaxHeight()
                    .background(CtrlPanelColor.copy(alpha = pa), RoundedCornerShape(6.dp)).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { i, t ->
                    val active = tab == i
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .background(if (active) accent else PanelColor, RoundedCornerShape(6.dp))
                            .clickable { tab = i },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            t, color = if (active) ThemeState.uiAccentText else TextDim,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp, maxLines = 1
                        )
                    }
                }
            }
            // Tengah: isi bagian terpilih + strip tombol bawah.
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .background(PanelColor.copy(alpha = pa), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    when (tab) {
                        0 -> {
                            DtxHeader("MIC", null) {}
                            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(72.dp).clip(CircleShape)
                                            .background(if (isRunning) accent else CtrlPanelColor)
                                            .clickable { toggleEngine() }
                                    ) {
                                        Icon(
                                            imageVector = if (isRunning) Icons.Filled.Mic else Icons.Filled.MicOff,
                                            contentDescription = if (isRunning) "Matikan mic" else "Nyalakan mic",
                                            tint = if (isRunning) ThemeState.uiAccentText else PadTextColor,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        if (isRunning) "ON" else "OFF",
                                        color = if (isRunning) accent else TextDim,
                                        fontSize = 16.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                                KnobGrid(
                                    listOf(
                                        KnobSpec("Volume", micVol, { micVol = it; pushMicGain() }),
                                        KnobSpec("Gate", gate, { gate = it; pushGate() }),
                                    ), perRow = 2, modifier = Modifier.weight(1.5f).fillMaxHeight()
                                )
                            }
                        }
                        1 -> {
                            DtxHeader("EQ MIC", eqEnabled) { eqEnabled = it; pushEQEnabled() }
                            KnobGrid(
                                listOf(
                                    KnobSpec("Low", eqLow, { eqLow = it; pushEQParams() }, eqEnabled),
                                    KnobSpec("Mid", eqMid, { eqMid = it; pushEQParams() }, eqEnabled),
                                    KnobSpec("High", eqHigh, { eqHigh = it; pushEQParams() }, eqEnabled),
                                ), perRow = 3, modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                        2 -> {
                            DtxHeader("REVERB", reverbEnabled) { reverbEnabled = it; pushReverbEnabled() }
                            KnobGrid(
                                listOf(
                                    KnobSpec("Wet", wet, { wet = it; pushParams() }, reverbEnabled),
                                    KnobSpec("Dry", dry, { dry = it; pushParams() }, reverbEnabled),
                                    KnobSpec("Width", width, { width = it; pushParams() }, reverbEnabled),
                                    KnobSpec("Damp", damp, { damp = it; pushParams() }, reverbEnabled),
                                    KnobSpec("Room", roomSize, { roomSize = it; pushParams() }, reverbEnabled),
                                    KnobSpec("Cess", cess, { cess = it; pushCess() }, reverbEnabled),
                                ), perRow = 3, modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                        3 -> {
                            DtxHeader("ECHO", echoEnabled) { echoEnabled = it; pushEchoEnabled() }
                            KnobGrid(
                                listOf(
                                    KnobSpec("Delay", echoDelay, { echoDelay = it; pushEchoParams() }, echoEnabled),
                                    KnobSpec("Repeat", echoFeedback, { echoFeedback = it; pushEchoParams() }, echoEnabled),
                                    KnobSpec("Mix", echoMix, { echoMix = it; pushEchoParams() }, echoEnabled),
                                ), perRow = 3, modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                        else -> {
                            DtxHeader("EQ AUDIO (suara app lain)", gEqEnabled) { gEqEnabled = it; pushGlobalEqEnabled() }
                            KnobGrid(
                                listOf(
                                    KnobSpec("Gain", gEqGain, { gEqGain = it; pushGlobalEq() }, gEqEnabled),
                                    KnobSpec("Sub", gEqSub, { gEqSub = it; pushGlobalEq() }, gEqEnabled),
                                    KnobSpec("Low", gEqLow, { gEqLow = it; pushGlobalEq() }, gEqEnabled),
                                    KnobSpec("Mid", gEqMid, { gEqMid = it; pushGlobalEq() }, gEqEnabled),
                                    KnobSpec("High", gEqHigh, { gEqHigh = it; pushGlobalEq() }, gEqEnabled),
                                ), perRow = 5, modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            if (gEqError != null) Text(gEqError ?: "", color = TextDim, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
                // Strip tombol bawah (seperti REC / TEMA / SKIN di DTX).
                Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DtxButton(if (isRunning) "MIC ON" else "MIC OFF", isRunning, Modifier.weight(1f)) { toggleEngine() }
                    DtxButton("FLOATING", dtx.floatingOn, Modifier.weight(1f)) { dtx.onFloating() }
                    DtxButton("TEMA", false, Modifier.weight(1f)) { dtx.onTheme() }
                    DtxButton("SKIN", false, Modifier.weight(1f)) { dtx.onSkin() }
                }
            }
            // Panel kanan: meter level Mic & Musik.
            Row(
                Modifier.width(76.dp).fillMaxHeight()
                    .background(CtrlPanelColor.copy(alpha = pa), RoundedCornerShape(6.dp)).padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                VMeter("MIC", { meters.mic }, Modifier.fillMaxHeight())
                VMeter("MUSIK", { meters.music }, Modifier.fillMaxHeight())
            }
        }
    } else if (landscape) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                micCard(); levelCard(); volumeCard()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                eqMicCard(); eqAudioCard()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                reverbCard(); echoCard()
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            micCard(); levelCard(); volumeCard(); eqMicCard(); eqAudioCard(); reverbCard(); echoCard()
        }
    }
}
