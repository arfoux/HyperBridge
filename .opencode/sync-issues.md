# Sync Issues

## Open
- **LIVE-1**: Verifikasi swipe-suppression + stage awal + range-ETA + update-RV butuh
  order Grab sungguhan. Blocker: user (tunggu kabar order). Instrumen siap:
  debug logging ON, tombol GRAB 1:1 di Test screen, capture logcat via adb scrcpy.
- **LIVE-2**: Penyebab delay ~10 menit pill awal order (06:42 → 06:52) belum pasti —
  kandidat: suppression grace dari swipe sebelumnya / missed `onNotificationPosted` /
  render tunda MIUI. Butuh capture kontinu saat order mulai.

## Resolved
- Pill tidak muncul (Feedback basi): `72124aa`, verified live 2026-09-19.
- Repost loop (swipe): `4fd7b71` + `bef5f99`, menunggu LIVE-1 untuk bukti akhir.
- Update beku sampai toggle: `f4ec859` + `81f1be7`, menunggu LIVE-1.
- Desain pill/big island: `a091927`, verified via `miui.focus.param` JSON.
- CI merah 2x (`isRealClone` scope, duplikat `pctLabel`): fixed, CI hijau.
- Random respawn (Verdict wording lolos keyword): `4de5661` + ungate log + retry
  (`5dad2b1`) — bukti 2026-09-21: `DELIVERY-FINISHED-SIBLING dismiss kitchen
  by=Verdict`, pill zombie hilang dari shade, log keputusan tercatat tanpa toggle.
- Sync rebind balapan daftar kosong: `788baa7` (retry 1d+4d), CI hijau.
- Double pill tanpa konvergensi: `200c3a8` + `dac4919` (konvergensi dini), CI hijau.
- Pelajaran 2026-09-20: (a) cek flag `debug_logging`/`save_real` DULU saat log sunyi
  (user mematikan keduanya; history ikut ke-clear); (b) toggle layar via adb void
  bila scrcpy stay-awake — klaim verifikasi via toggle dicabut, hanya log + JSON yang sah.
