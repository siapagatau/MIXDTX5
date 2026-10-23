# CHECKPOINT  (v3.0 - DASAR = APK DTX)
PERUBAHAN ARAH (v3.0): proyek ini sekarang BERDASAR PROYEK APK DTX (DTX-VISUAL-TOUCH-FIX-MSC), bukan proyek mixer terpisah.
 - Dari DTX dipakai apa adanya: build system (AGP 9.1 / Kotlin 2.2.10 + plugin compose / Gradle 9.3.1 / SDK 36 / NDK 28 / 16 KB page size),
   workflow CI + verifikasi 16 KB, package `com.kendang.realpads`, tema window (Theme.RealPads), ThemeState/ColorPickerDialog/BackgroundState/TouchFx,
   Skin Engine + SkinRepository + SkinPickerDialog.
 - Yang diganti: isi "pad sampler" (PadEngine, pad/preset/rekam/MIDI/musik/export/load) -> engine MIXER (Oboe/C++ KaraokeEngine: mic, reverb, echo,
   EQ mic, gate, EQ audio global, floating, meter). Skin = mixer (rotary/slider/toggle/meter/display) di dalam SATU kotak skin.
 - Tidak ada lagi flavor free/premium (semua fitur terbuka). applicationId `com.karaoke.reverbmic`, nama app "DTX Mixer".
 - Native: lib `karaokeengine`, JNI `Java_com_kendang_realpads_AudioEngine_*` (17 fungsi, cocok dengan 17 `external fun`).
Catatan di bawah (v2.x) adalah riwayat; path `com/karaoke/reverbmic` di sana kini `com/kendang/realpads`.

# CHECKPOINT
Tujuan Utama: ubah APK mic karaoke (com.kendang.realpads) jadi APK mixer landscape ala DTX:
1) landscape penuh + tema (ThemeState/ColorPickerDialog DTX); 2) Skin Engine dari skin/ DTX (SVG+skin.json+hit area);
3) skin diunduh dari github.com/siapagatau/SKIN-MIXER-HP (index.json, sha256, cache offline, fallback default);
4) engine audio Oboe/C++ (reverb, echo, EQ, gate, EQ global, floating, meter LED) tetap, tidak ditulis ulang;
5) aturan skin: PROMPT_SKIN_MIXER.md.
Tahap selesai: 0-7 + v2.3 (fix build KnobControl `size` + tema menyeluruh) + v2.1 (perbaikan: tema/background/ketukan DTX, tanpa skin offline)   Tahap berikutnya: build & uji perangkat.
v2.1: ThemeState/ColorPickerDialog/BackgroundState/TouchFx diport apa adanya dari DTX (docs/dtx-port.md); assets/skins/default dihapus, skin hanya dari repo (unduh otomatis skin pertama).
File penting: README.md (final); docs/{peta,skin-engine,mapping-table}.md; skin/*.kt; skins-repo/ (isi repo SKIN-MIXER-HP);
  tools/{gen_skin_01,gen_preview,index,validate,check_all}.py; assets/config/skin-repo.json (tanpa skin bawaan); app/proguard-rules.pro; versi 2.2 (code 8).
Keputusan: Kotlin 1.9.24 + Compose compiler 1.5.14 dipertahankan (kode port DTX disesuaikan); jalur floating/EQ global skin = jalur UI klasik;
  fallback skin: terunduh -> bawaan -> UI klasik; katalog fungsi 30 buah identik di PROMPT §5, FunctionRegistry.kt, validate.py (dicek check_all.py).
Validasi tahap 7: `python3 tools/check_all.py PROMPT_SKIN_MIXER.md` -> SEMUA LOLOS (validate skin-01 & default, index sha256/size, default==skin-01,
  skins-repo tanpa kode APK, katalog konsisten, SVG bebas elemen terlarang).
Masalah/belum dibuild: BELUM PERNAH dikompilasi atau dijalankan di perangkat (sandbox tanpa Android SDK/Kotlin/jaringan) -> hanya review statis.
  Kemungkinan error kompilasi kecil tersisa; render AndroidSVG, preview.svg, unduh nyata, floating & EQ global di HP belum teruji;
  repo SKIN-MIXER-HP belum di-push (isi = skins-repo/ atau SKIN-MIXER-HP.zip).
Next Update (setelah tahap 7, di luar rencana awal):
 1. Push proyek -> jalankan Actions -> bila gagal kirim log/artefak build-reports; perbaiki hanya file yang disebut.
 2. Push skins-repo/ ke github.com/siapagatau/SKIN-MIXER-HP (branch main, index.json di root).
 3. Uji di HP: skin tampil & stretch, knob/slider/toggle, meter, mic ON, floating, EQ global, SKIN -> UNDUH -> PAKAI -> restart offline.
 4. Skin tambahan: 1 gambar = 1 skin = 1 sesi (turunan gen_skin_01.py), lalu check_all.py.
Perintah untuk AI berikutnya: "Baca CHECKPOINT.md. Perbaiki error build/uji dari log berikut: <tempel>."

v2.3: (1) FIX build: RotaryKnob(size: Dp) menimpa `size` DrawScope/PointerInputScope -> diganti `knobSize`.
 (2) Tema menyeluruh: ThemeState.palette menurunkan semua permukaan (backdrop/panel/pad/ctrl/line/text/dim) dari hue warna tema;
     UiColors.kt kini getter dinamis; aksen tunggal (ThemeState.uiAccent, otomatis dicerahkan bila warna terlalu gelap).
     Tidak ada lagi hijau/oranye/biru acak: knob, switch, tab, tombol, header, meter (zona aman), mic ON, dialog skin, bubble & panel floating ikut tema.
     Meter kuning/merah tetap sebagai indikator peringatan clip.

v2.4 (tampilan = susunan DTX): layar utama kini = SATU kotak skin berisi semua fungsi (knob, slider, toggle, meter, LCD) + strip tombol
 pendukung di luar kotak (FLOATING / TEMA / SKIN saja). Tanpa kartu-kartu terpisah. Rasio kotak mengikuti viewBox skin (tidak gepeng).
 - Skin bawaan "Mixer DTX" (assets/skins/default == skins-repo/skins/skin-01), tampil langsung tanpa unduhan/internet. Fallback: terpilih -> bawaan -> UI klasik.
 - Warna skin ikut TEMA: token @ACCENT@ @ONACCENT@ @BACKDROP@ @CTRL@ @LINE@ @PANEL@ @PAD@ @TEXT@ @DIM@ di skin.svg diganti palet tema
   (skin/SkinTheme.kt, SkinDefinition.themed). Skin repo lain tanpa token tetap tampil apa adanya.
 - Generator: tools/gen_skin_dtx.py <dir> [--preview] (menggantikan gen_skin_01/gen_preview). Validasi: python3 tools/check_all.py -> SEMUA LOLOS.
 - Tombol FLOATING/TEMA/SKIN dipindah dari dalam skin ke strip luar kotak; fungsi button di katalog tetap didukung untuk skin repo lain.
 - Tetap BELUM dikompilasi di sandbox (tanpa Android SDK).
