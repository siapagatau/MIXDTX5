# DTX Mixer (berdasar APK DTX)

Dasar proyek = APK DTX; fungsi pad diganti mixer (mic, reverb, echo, EQ, gate, EQ audio global, floating). Lihat CHECKPOINT.md.

# Karaoke Mixer (com.kendang.realpads)

APK **mixer karaoke landscape**: suara mic clip diproses real-time (reverb, echo, EQ, gate) lalu keluar ke
speaker/earphone HP, dengan tampilan berbasis **skin** (SVG) yang bisa diganti dan diunduh dari repo skin.
Engine audio (Oboe/C++) sama dengan versi mic karaoke sebelumnya; yang baru adalah layar mixer landscape, tema warna,
dan Skin Engine.

## Fitur
- **Landscape penuh** (bar sistem disembunyikan; geser dari tepi untuk memunculkannya sebentar).
- **Mixer**: Mic (ON/OFF, Volume, Gate) · Reverb (Wet, Dry, Width, Damp, Room, Cess) · Echo (Delay, Repeat, Mix) ·
  EQ Mic (Low/Mid/High) · EQ Audio global (Gain, Sub, Low, Mid, High) · meter LED Mic & Output · display status.
- **Skin**: tampilan = `skin.svg` + `skin.json` (+ area sentuh). Skin bawaan ada di dalam APK; skin lain diunduh dari
  [SKIN-MIXER-HP](https://github.com/siapagatau/SKIN-MIXER-HP) (verifikasi sha256, cache offline, fallback ke skin bawaan lalu UI klasik).
- **Tema**: tombol **TEMA** mengganti warna aksen (tersimpan).
- **Floating**: panel kecil (Mic ON/OFF + Volume) di atas app lain; bisa digeser, di-resize, dan di-minimize jadi bulatan.
- **EQ Audio global**: EQ untuk suara semua app (YouTube, TikTok, musik) lewat `DynamicsProcessing` + foreground service.

## Cara pakai
1. Colok mic clip (jack 3.5 mm atau adapter USB-C) sebagai mic default sistem, buka app, izinkan akses mic.
2. Nyalakan **MIC**, atur Volume/Gate, Reverb, Echo, EQ. Pakai earphone bila terdengar feedback (ngiiing).
3. **FLOAT** membuka panel floating (meminta izin mic → notifikasi → "tampil di atas app lain" bila belum ada).
4. **TEMA** mengganti warna. **SKIN** membuka pemilih: UI Klasik, skin bawaan, dan skin dari repo
   (UNDUH / PERBARUI / PAKAI; tanpa internet tetap bisa memakai skin yang sudah terunduh).
5. **EQ AUDIO** ON menyalakan service EQ global (notifikasi "EQ Audio aktif"). Sebagian HP/ROM mengabaikan efek di
   output mix; bila begitu toggle kembali OFF.

Kontrol skin: **knob** = geser vertikal, **slider** = ikuti jari sepanjang jalur, **toggle/tombol** = tap (multi-touch didukung).

## Mengapa low-latency
Oboe (AAudio LowLatency), full-duplex dalam satu callback (mic → proses → speaker, tanpa antrian), seluruh DSP di C++ tanpa alokasi
di audio thread, input `Unprocessed` (tanpa noise-suppression/AGC sistem). Output mode Shared agar app musik lain tetap bisa
keluar bersamaan. Volume mic dan Dry hanya perkalian per sample, tidak menambah delay.

## Build
Lewat GitHub Actions (workflow **Build Debug APK**, JDK 17, SDK 34, NDK 26.1, CMake 3.22.1, Gradle 8.7, AGP 8.5.0,
Kotlin 1.9.24, Compose compiler 1.5.14):
1. Push folder ini ke GitHub (branch `main`/`master`).
2. Tab **Actions** → jalan otomatis, atau **Run workflow**.
3. Unduh artefak **karaoke-mic-reverb-debug** → `app-debug.apk`. Bila gagal, unduh artefak **build-reports**.

> Catatan Kotlin: proyek ini memakai Kotlin 1.9.24. Jangan menyalin kode dari proyek DTX (Kotlin 2.2) apa adanya —
> hindari `catch (_: …)`, `break/continue` di lambda inline, guard `if` di `when`.

## Struktur
```
app/src/main/java/com/karaoke/reverbmic/
  MainActivity.kt            layar utama (landscape), UI klasik, host skin, dialog tema/skin, immersive
  ThemeState.kt, ColorPickerDialog.kt, BackgroundState.kt, TouchFx.kt, UiColors.kt
                             tema persis APK DTX: warna aksen, background gambar, transparansi, visual sentuhan
  AudioEngine.kt             jembatan JNI;  SettingsStore/SettingsBus  nilai & sinkron antar layar
  FloatingWindowService.kt, FloatingPrefs.kt   panel floating
  GlobalEqEngine.kt, GlobalEqService.kt, MusicMeter.kt   EQ global & meter
  skin/  SkinModel SvgDom SkinParser SkinValidator FunctionRegistry SkinLoader SkinRenderer SkinRenderCache
         SkinLayout HitTester SkinState SkinManager SkinHost SkinActionDispatcher SkinBinding
         SkinRepository SkinPickerDialog
app/src/main/assets/config/skin-repo.json   URL repo skin (+ 1 skin bawaan di assets/skins/default, sama dengan skins-repo/skin-01)
app/src/main/cpp/                    KaraokeEngine (Oboe, reverb Freeverb-style, echo, EQ, gate), native-lib
skins-repo/                          isi repo SKIN-MIXER-HP: index.json, skins/<id>/, tools/
tools/                               gen_skin_01.py gen_preview.py index.py validate.py check_all.py
docs/                                peta.md  skin-engine.md  mapping-table.md
```

## Membuat / mengubah skin
Aturan lengkap: `PROMPT_SKIN_MIXER.md` (katalog fungsi resmi: 30 function; fungsi di luar katalog = skin ditolak).
```
python3 tools/gen_skin_01.py skins-repo/skins/skin-01   # skin.svg + skin.json
python3 tools/gen_preview.py skins-repo/skins/skin-01   # preview.svg (overlay hit area)
python3 tools/validate.py skins-repo/skins/skin-01      # harus OK
python3 tools/index.py skins-repo                        # index.json (sha256+size, SETELAH file final)
python3 tools/check_all.py                               # validasi akhir semuanya
```
Skin baru: salin `gen_skin_01.py` jadi `gen_skin_XX.py`, ubah tampilan, jalankan alur di atas, push `skins-repo/` ke repo SKIN-MIXER-HP
(`index.json` di root repo, branch `main`). Unduhan ditolak APK bila sha256/size tidak cocok.

## Keterbatasan yang diketahui
- Build dan tampilan pada perangkat belum teruji oleh pengembang sebelumnya (disusun tanpa Android SDK); periksa hasil Actions.
- Thumbnail di pemilih skin memakai `preview.svg`, jadi tampil dengan overlay hit area.
- Panel floating memakai layout XML sendiri (tidak ikut skin).
