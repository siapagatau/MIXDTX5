# Porting DTX -> Mixer (v2.1)

Prinsip: pakai kode DTX apa adanya, hanya ganti isi "pad sampler" dengan engine mixer.

| File DTX | Di mixer | Catatan |
|---|---|---|
| ThemeState.kt | ThemeState.kt | identik (prefs `mixer_theme`) |
| ColorPickerDialog.kt | ColorPickerDialog.kt | identik: warna, background gambar, transparansi, visual sentuhan (ukuran, kedip) |
| BackgroundState.kt | BackgroundState.kt | identik (prefs `mixer_background`) |
| TouchFx.kt | TouchFx.kt | identik; overlay + `dispatchTouchEvent` di MainActivity (global, juga di atas skin) |
| MainActivity: pemilih gambar, muat disk | MainActivity.kt | sama dengan DTX |
| skin/ (Skin Engine) | skin/ | sudah diport sebelumnya + tipe baru toggle/slider/meter |
| config/skin-repo.json + SkinRepository | sama | skin hanya dari repo; cache offline setelah diunduh |

Beda yang disengaja:
- Transparansi Pad -> **Transparansi Panel** (kartu UI klasik), karena mixer tidak punya pad.
- Bila user memilih background gambar saat skin aktif, `layer-bg` skin dibuang saat render sehingga gambar tampil di belakang
  kontrol (cache render dibedakan lewat flag `transparentBg`). Tanpa background gambar, skin tampil utuh seperti biasa.
- Tidak ada skin bawaan. Pemakaian pertama: UI klasik tampil, skin pertama repo diunduh otomatis lalu dipakai
  (offline -> dicoba lagi di peluncuran berikutnya). Pilihan lama `default` otomatis dibersihkan.
- URL repo mencoba `main`, lalu `master` bila gagal.

## v2.2 - tampilan utama ala DTX
- Layar klasik tidak lagi kartu yang di-scroll. Susunan sama dengan DTX: panel kiri (tab MIC / EQ MIC / REVERB / ECHO / AUDIO,
  tab aktif = warna tema), area tengah (knob membesar/mengecil mengikuti ruang, tanpa scroll), strip tombol bawah
  (MIC ON/OFF, FLOATING, TEMA, SKIN), panel kanan (meter LED vertikal Mic & Musik).
- Warna panel/tombol memakai palet DTX (UiColors.kt); Transparansi Panel berlaku di semua panel saat ada background gambar.
- Dialog Pilih Skin memakai gaya dialog DTX (AlertDialog, baris CtrlPanelColor, ikon unduh/centang warna tema).
- Panel floating tetap memakai kartu (KaraokeControls tanpa parameter `dtx`).
