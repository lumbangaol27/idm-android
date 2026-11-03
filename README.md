# IDM Android

Aplikasi download manager untuk Android, terinspirasi Internet Download Manager (IDM).

## Fitur

- Tempel satu atau banyak link, lalu unduh otomatis
- **Multi-segmen**: file dipecah menjadi 4 bagian yang diunduh paralel (jika server mendukung HTTP Range)
- Jeda dan lanjut, termasuk setelah aplikasi ditutup (jika server mendukung Range)
- Antrean otomatis (2 unduhan berjalan bersamaan)
- Berjalan di latar belakang dengan notifikasi progres dan tombol "Jeda semua"
- Terima link lewat menu **Bagikan** dari browser
- File selesai tersimpan di `Download/IDM` dan bisa dibuka dari aplikasi

## Catatan

- Mendukung link langsung ke file (misalnya `.mp4`, `.zip`, `.apk`, `.pdf`).
- Tidak mengunduh dari situs streaming (YouTube, TikTok, dan sejenisnya).
- Membutuhkan Android 10 (API 29) atau lebih baru.

## Cara mendapatkan APK (tanpa komputer)

1. Buka repo ini di GitHub, lalu tab **Actions**.
2. Pilih workflow **Build APK**. Build berjalan otomatis setiap ada push, atau tekan **Run workflow**.
3. Setelah hijau, buka run terbaru dan unduh artifact **idm-android-debug** (file zip berisi `app-debug.apk`).
4. Ekstrak zip, lalu install APK. Izinkan "Install dari sumber tidak dikenal" jika diminta.

## Struktur kode

- `DownloadEngine.kt`: antrean, pemecahan segmen, jeda/lanjut, penyimpanan ke folder Download
- `DownloadService.kt`: service latar depan dan notifikasi
- `DownloadStore.kt`: penyimpanan daftar unduhan
- `Ui.kt` dan `MainActivity.kt`: tampilan (Jetpack Compose)
