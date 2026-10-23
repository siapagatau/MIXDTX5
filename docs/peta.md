# Peta Tahap 0 — DTX → Mixer
> Dokumen peta awal (tahap 0); status akhir proyek ada di README.md dan CHECKPOINT.md.

Sumber: DTX = `DTX-VISUAL-TOUCH-FIX-MSC-master` (pkg `com.kendang.realpads`), Karaoke = proyek ini (pkg `com.kendang.realpads`).
Paket baru skin di mixer: `com.kendang.realpads.skin`.

## A. File DTX yang DIPAKAI (port, ganti package, buang bagian pad)
| File DTX (`.../realpads/`) | Tahap | Penyesuaian |
|---|---|---|
| `ThemeState.kt` (69 brs) | 1 | salin, ganti package |
| `ColorPickerDialog.kt` (470 brs) | 1 | buang param `onPickTouchFx/onClearTouchFx/onTouchFxSize/onTouchFxFade` + bagian "Visual Sentuhan" |
| `skin/SkinModel.kt`, `SvgDom.kt`, `SkinParser.kt` | 2 | tambah tipe `TOGGLE/SLIDER/METER`; buang logika `pad-N` & `indicators` |
| `skin/SkinValidator.kt` | 2 | buang `PresetStorage`/pad berurutan; aturan di PROMPT_SKIN_MIXER §3,4,6 |
| `skin/SkinLoader.kt`, `SkinRenderCache.kt` | 2 | salin; naikkan RENDER_VERSION |
| `skin/SkinRenderer.kt` | 2 | layer: base + on-state toggle + rotary + slider handle + meter segmen |
| `skin/HitTester.kt`, `SkinLayout.kt` | 2 | stretch penuh (X≠Y), tanpa zona pad |
| `skin/SkinManager.kt` | 2/5 | buang `BuildConfig.IS_PREMIUM`; rantai fallback: cache → assets default → UI klasik |
| `skin/SkinHost.kt` (24 KB) | 2/3 | buang multi-touch pad; pertahankan knob (circular+vertikal); tambah slider, toggle, meter |
| `skin/FunctionRegistry.kt`, `SkinActionDispatcher.kt`, `SkinActions.kt` | 3 | ganti isi dengan katalog PROMPT_SKIN_MIXER §5 |
| `skin/SkinRepository.kt`, `SkinPickerDialog.kt`, `SkinLoadingScreen.kt` | 5 | URL → SKIN-MIXER-HP; `config/skin-repo.json` |
| `assets/config/skin-repo.json` | 5 | indexUrl baru |
| `.github/workflows/build-apk.yml`, dep `com.caverock:androidsvg-aar:1.4` | 2/6 | tambah dep; workflow karaoke dipertahankan |

## B. File DTX yang TIDAK dipakai
`PadEngine.*`, `native-lib.cpp` DTX, `MainActivity.kt` (169 KB), semua `*Storage`, `Preset*`, `Music*`, `Online*`, `Midi*`, `Aac*`, `ScreenRecordService`, `TouchFx.kt`, `TutorialOverlay`, `PadVolumeDialog`, `BackgroundState`, `skin/PadModes.kt`, `skin/SkinToggleTab.kt`, flavor free/premium, `docs/pad-support.md`, `skin-indicators.md`.
Bahan skin dari `SKIN-DTX-PREMIUM-ASHLEY-FIX-8.zip` hanya acuan format (`index.json`, `skins/*/skin.{json,svg}`, `preview.svg`); skin pad DTX tidak dipakai langsung.

## C. File karaoke DIPERTAHANKAN (tidak ditulis ulang)
| File | Status |
|---|---|
| `cpp/KaraokeEngine.{h,cpp}`, `native-lib.cpp`, `CMakeLists.txt` | utuh (Oboe/C++) |
| `AudioEngine.kt` (+ `pushAllSettingsToEngine`) | utuh; jadi target aksi dispatcher |
| `SettingsStore.kt` (`ReverbSettings`), `SettingsBus.kt` (+`MicStateBus`) | utuh; sumber state skin |
| `GlobalEqEngine.kt`, `GlobalEqService.kt` | utuh |
| `FloatingWindowService.kt`, `FloatingPrefs.kt`, `res/layout/floating_*.xml`, drawable | utuh (integrasi dicek di tahap 6: tidak ada perubahan perlu) |
| `MusicMeter.kt` | utuh; sumber `meter.output` |
| `KnobControl.kt` | dipakai di UI klasik tahap 1 |
| `MainActivity.kt` (28 KB) | DIUBAH kecil: landscape, tema, host skin; `KaraokeControls`, `KaraokeFloatingBody`, `LedBar` tetap |
| `AndroidManifest.xml` | ubah `screenOrientation` portrait → `sensorLandscape` |

## D. Pemetaan fungsi katalog → kode karaoke (acuan tahap 3)
Rentang skin 0..1 = rentang `ReverbSettings` untuk: `mic.volume`(micVol, ×2 saat push), `mic.gate`, `reverb.wet/dry/width/damp/room(roomSize)/cess`, `echo.delay/feedback/mix`, `eqmic.low/mid/high`, `eqaudio.gain/sub/low/mid/high`(gEq*).
Toggle: `mic.toggle`→`toggleEngine()` + `MicStateBus`; `reverb.toggle`→reverbEnabled; `echo.toggle`→echoEnabled; `eqmic.toggle`→eqEnabled; `eqaudio.toggle`→gEqEnabled (`GlobalEqService`).
Meter: `meter.mic`→`nativeGetMicMeanSquare()`, `meter.output`→`nativeGetOutputMeanSquare()` (lewat `levelFromAmp`).
Tombol: `floating.toggle`→`startFloatingService/stopFloatingService`; `theme.open`→ColorPickerDialog; `skin.pick`→SkinPickerDialog. `display.status`→template `{mic|reverb|echo|eqmic|eqaudio:ONOFF}`.
Tambahan perlu (tidak diubah di tahap 0): `reverb.cess` sudah ada di `ReverbSettings`; `nativeSetPreDelayMs`/`ApplyNyesPreset` tidak ada di katalog → tidak diakses skin.

## E. Perbedaan & risiko
- Toolchain: karaoke AGP 8.5.0 / Kotlin 1.9.24 / `composeOptions 1.5.14` / compileSdk 34; DTX AGP 9.1 / Kotlin 2.2.10 / plugin compose. **Keputusan: pakai toolchain karaoke** (sudah build hijau), kode DTX di-port ke API Kotlin 1.9.
- Kode skin DTX belum pernah dikompilasi penuh (menurut CHECKPOINT DTX) → perbaikan kecil di tahap 2 & 6 (mis. `catch (_)` tidak valid di Kotlin 1.9).
- Slider/toggle/meter tidak ada di engine skin DTX (hanya pad/button/rotary/display): ditambahkan di tahap 2–3 (selesai).
- `FloatingWindowService` memakai layout XML sendiri; tidak ikut skin.

## Catatan Tahap 1
ThemeState + ColorPickerDialog sudah diport (tanpa Background Pad & Visual Sentuhan). Layar utama landscape 3 kolom: [Mic, Level, Volume] [EQ Mic, EQ Audio] [Reverb, Echo].

## Catatan Tahap 2
Skin Engine ditulis ulang ringkas di `com.kendang.realpads.skin` (lihat docs/skin-engine.md), bukan salinan 1:1 DTX. Dibuang: PAD, zona pad/panel, indicators, PadModes, SkinToggleTab, IS_PREMIUM. Ditambah: TOGGLE, SLIDER, METER.
