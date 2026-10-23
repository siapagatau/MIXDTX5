package com.kendang.realpads.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kendang.realpads.AudioEngine
import com.kendang.realpads.GlobalEqService
import com.kendang.realpads.MicStateBus
import com.kendang.realpads.MusicMeter
import com.kendang.realpads.SettingsBus
import com.kendang.realpads.startGlobalEq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

private fun levelFromAmp(amp: Float): Float =
    ((20f * log10(amp + 1e-6f) + 60f) / 60f).coerceIn(0f, 1f)

/**
 * Menghubungkan SkinState ke aplikasi: dispatcher (skin -> engine), sinkron dua arah lewat SettingsBus/MicStateBus
 * (UI klasik & floating ikut berubah), dan polling meter ~30 fps hanya saat layar RESUMED.
 */
@Composable
fun SkinBinding(
    skin: SkinDefinition,
    state: SkinState,
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onFloating: () -> Unit,
    onTheme: () -> Unit,
    onSkinPick: () -> Unit,
) {
    val context = LocalContext.current
    val d = remember(skin) { SkinActionDispatcher(context.applicationContext, skin, state) }

    SideEffect {
        d.hasMicPermission = { hasMicPermission }
        d.requestMicPermission = onRequestPermission
        d.onFloating = onFloating
        d.onTheme = onTheme
        d.onSkinPick = onSkinPick
        state.onValue = d::onValue
        state.onToggle = d::onToggle
        state.onButton = d::onButton
        state.displayText = d::displayText
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var active by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner, d) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { active = true; d.syncAll() }
            else if (event == Lifecycle.Event.ON_PAUSE) active = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(d) {
        state.initDefaults(skin)
        d.syncAll()
        // EQ Audio tersimpan ON tapi proses sempat mati -> nyalakan lagi (sama dengan KaraokeControls).
        if (com.kendang.realpads.SettingsStore.load(context).gEqEnabled && !GlobalEqService.running.value) startGlobalEq(context)
    }
    LaunchedEffect(d) { SettingsBus.updates.collect { d.syncFrom(it) } }
    LaunchedEffect(d) { MicStateBus.updates.collect { d.setMicRunning(it) } }

    LaunchedEffect(d, active, hasMicPermission) {
        if (!active) {
            skin.controls.forEach { if (it.type == ControlType.METER) state.meters[it.id] = 0f }
            return@LaunchedEffect
        }
        if (hasMicPermission) MusicMeter.acquire()
        try {
            while (true) {
                delay(33)
                val (micRms, outRms) = withContext(Dispatchers.Default) {
                    val running = AudioEngine.nativeIsRunning()
                    val mic = if (running) sqrt(AudioEngine.nativeGetMicMeanSquare()) else 0f
                    val engineOut = if (running) sqrt(AudioEngine.nativeGetOutputMeanSquare()) else 0f
                    // output = campuran akhir (musik + mic) bila Visualizer tersedia; kalau tidak, keluaran engine.
                    val mix = if (hasMicPermission) MusicMeter.readMixRms() else -1f
                    mic to (if (mix >= 0f) mix else engineOut)
                }
                for (c in skin.controls) {
                    if (c.type != ControlType.METER) continue
                    val now = levelFromAmp(if (c.function == "meter.mic") micRms else outRms)
                    val old = state.meters[c.id] ?: 0f
                    // naik langsung, turun cepat -> batang "mukul" mengikuti ketukan
                    state.meters[c.id] = if (now > old) now else max(now, old - 0.09f)
                }
            }
        } finally {
            if (hasMicPermission) MusicMeter.release()
        }
    }
}
