# Skin Engine Mixer (tahap 2–6)

Paket `com.kendang.realpads.skin`. Alur: `skin.json + skin.svg` → SkinLoader → SkinParser → SkinValidator → SkinManager.state → SkinHost ⇄ SkinState ⇄ SkinBinding/SkinActionDispatcher (→ engine karaoke).

| File | Tugas |
|---|---|
| SkinModel / SvgDom / SkinParser | model + DOM + baca skin.json & SVG (tipe button, toggle, rotary, slider, meter, display) |
| FunctionRegistry / SkinValidator | katalog resmi (PROMPT_SKIN_MIXER §5) + aturan §3,4,6 |
| SkinLoader | assets (default) atau folder (unduhan/cache SkinRepository), batas 4 MB |
| SkinRenderer / SkinRenderCache | AndroidSVG → layer bitmap: base, handle knob/slider, toggle-on, button-active, segmen meter; cache PNG di cacheDir |
| SkinLayout / HitTester | stretch penuh (skala X≠Y), hit-test + perluasan 24dp |
| SkinState | jembatan nilai/toggle/meter/callback (diisi SkinBinding + SkinActionDispatcher) |
| SkinManager | state Loading/Classic/Active/Failed; fallback: pilihan → default → UI klasik |
| SkinHost | gambar + sentuhan multi-touch |

Konvensi SVG: handle knob digambar menunjuk jam 12 (sudut 0); engine memutar `min+(max-min)*nilai` di sekitar `data-cx/data-cy`. Hit knob harus mencakup handle. Slider: `(x1,y1)`=nilai 0, `(x2,y2)`=nilai 1, handle digambar di posisi nilai default dan harus berada di dalam hit area. Segmen meter = `<rect>` (atau grup berisi rect), `display="none"`. State `-on`/`-active` diberi `display="none"`.

Tools: `tools/gen_skin_01.py` (skin-01 = skin bawaan; `gen_skin_default.py` = versi awal), `gen_preview.py`, `validate.py <dir>`, `index.py <repo>`, `check_all.py` (validasi akhir).

## Tahap 5 — SkinRepository & pemilih skin
- `skin/SkinRepository.kt`: `REPO_BASE`/`INDEX_URL` (satu tempat) -> `fetchIndex` (cache `filesDir/skin_repo/index.json`, offline pakai cache),
  `status` (NOT_INSTALLED/INSTALLED/UPDATE dari sha256 file terpasang vs index), `install`, `previewText`, `cachedSkinDir`.
- `install`: unduh `skin.json`+`skin.svg` (wajib) dan `preview.svg` (opsional) ke `skin_repo/tmp-<id>` -> cek size + sha256 per file ->
  `SkinManager.loadFrom` (SkinLoader + SkinValidator) -> rename atomik ke `skin_repo/skins/<id>` (versi lama jadi cadangan, dihapus bila sukses).
  Gagal di langkah mana pun: tmp dibersihkan, skin lama/pilihan lama tidak berubah.
- Pengaman: hanya https; id `[A-Za-z0-9_-]{1,64}` dan bukan `classic`/`default`; `path` index harus `skins/<id>/`; nama file hanya
  `skin.json|skin.svg|preview.svg`; batas 512 KB (index) / 4 MB (file); URL file dibangun dari id tervalidasi, bukan dari index.
- `skin/SkinPickerDialog.kt`: daftar UI Klasik, skin bawaan, skin repo (thumbnail preview.svg via AndroidSVG), tombol PAKAI/UNDUH/PERBARUI,
  SEGARKAN, TUTUP. Dibuka dari tombol `skin.pick` (skin) dan tombol SKIN (UI klasik). Setelah unduh sukses skin langsung dipakai.
- `SkinManager.resolveDownloadedDir` -> `SkinRepository.cachedSkinDir`. Fallback berantai tetap: pilihan -> default (assets) -> UI klasik.
- Izin `INTERNET` ditambahkan di AndroidManifest.

## Tahap 6 — review build, integrasi floating & EQ global
**Review statis** (tanpa kompiler di sandbox): semua simbol karaoke yang dipakai skin dicocokkan dgn definisinya (AudioEngine.native*, MusicMeter,
SettingsBus/MicStateBus, GlobalEqEngine/Service, startGlobalEq/stopGlobalEq, eqKnobToDb, pushAllSettingsToEngine) -> semuanya ada & publik;
file karaoke asli yang diubah hanya MainActivity.kt, AndroidManifest.xml, build.gradle.kts (+ThemeState/ColorPickerDialog baru); C++/AudioEngine tidak disentuh.
- **Bug build ditemukan & diperbaiki:** `catch (_: Exception)` (SkinRenderCache, SvgDom, SkinParser) tidak valid di **Kotlin 1.9.24** (proyek ini) —
  baru didukung Kotlin 2.x (DTX memakai 2.2.10). Diganti `catch (e: Exception)`.
- **Aturan porting dari DTX (Kotlin 2.2) ke mixer (Kotlin 1.9.24):** hindari `catch (_: …)`, `break/continue` di dalam lambda inline,
  guard `if` di `when`, `$$`-interpolasi, dan API JDK21 (`List.removeLast`). `continue`/`break` langsung di dalam `for` + `when` aman.
- **Floating:** jalur skin identik dgn UI klasik (izin RECORD_AUDIO -> POST_NOTIFICATIONS -> overlay ada di MainActivity.requestFloatingMode;
  tombol skin `floating.toggle` memanggilnya). Sinkron dua arah lewat SettingsBus/MicStateBus; meter berhenti saat layar tidak RESUMED.
- **EQ global:** `eqaudio.toggle` = simpan dulu lalu start/stopGlobalEq (service membaca status dari SettingsStore); bila service gagal ia
  mematikan setting & mem-publish -> toggle skin kembali OFF. Saat skin tampil dan setting tersimpan ON tapi service mati -> dinyalakan lagi.
- **Landscape penuh:** manifest `sensorLandscape` + `MainActivity.applyImmersive()` (bar sistem disembunyikan, muncul sementara bila digeser).
- **Fallback render:** `SkinManager.reportRenderFailure` -> skin terunduh gagal = pakai skin bawaan; bawaan gagal = UI klasik.
- **Build:** `app/proguard-rules.pro` (JNI AudioEngine, AndroidSVG) dirujuk di buildType release (minify tetap off); workflow mengunggah
  `build-reports` bila gagal (`if: failure()`).
