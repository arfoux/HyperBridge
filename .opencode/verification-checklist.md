# Verification Checklist — Order Berikutnya

Jalankan saat user kabar order Grab baru dimulai. Semua via adb scrcpy
(`adb-QSFUPN6X9TF64DCA-RAUOZ0`).

- [ ] 1. `adb logcat -c`, lalu capture `adb logcat -s HyperBridgeDebug:V HyperBridgeTest:V`
- [ ] 2. Stage awal (`mencari driver`/`Kitchen`): pill muncul ≤5 detik, log `REAL ... type=DELIVERY`
- [ ] 3. Swipe pill → kunci/buka layar → pill TIDAK balik, log `SUPPRESSED-SWIPE skip`
- [ ] 4. Stage berikutnya (teks beda): pill muncul lagi, 1 pill (cek `DELIVERY-DEDUP`)
- [ ] 5. Range waktu di notif: pill kanan `Nmnt` (durasi), big island `Tiba ...` (mentah,
      cek `dumpsys notification --noredact` → `miui.focus.param`)
- [ ] 6. Update tanpa toggle: tiap stage Grab, pill ikut ≤5 detik tanpa sentuh layar
- [ ] 7. Order selesai (Feedback/rating): semua pill Grab hilang, log `DELIVERY-DISMISS`
- [ ] 8. `dumpsys` akhir: tidak ada pill zombie (bridge tanpa ori aktif)
