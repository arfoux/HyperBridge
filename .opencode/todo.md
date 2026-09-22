# HyperBridge — Mission TODO (pill Grab + semua keluhan)

## Selesai & terverifikasi CONFIG
<!-- app item: [x] = verified leaf, status completed = verified parent -->

- [x] (completed) Investigasi no-pill 0.5.45 — bukti logcat `DELIVERY-FINISHED-SIBLING dismiss`
- [x] (completed) Fix sibling tuntas berbasis postTime (`72124aa`, CI hijau)
- [x] (completed) Verifikasi live: 3 POSTING DELIVERY, nol SIBLING (`force-stop` + dumpsys)
- [x] (completed) Akar swipe-loop: swipe≠bunuh ori + sync repost untracked + DELIVERY bebas gate 2s
- [x] (completed) Fix suppression hash+postTime+grace (`4fd7b71`+`bef5f99`, CI hijau)
- [x] (completed) Grab REAL-clone 1:1 + `isGrabPipeline` (`0c7ddb9`, CI hijau)
- [x] (completed) Desain pill: kanan teks-ETA, big island info mentah, range→durasi,
      stage awal, stage ikut angka ori, rvHash anti-beku (`a091927`+`f4ec859`+`81f1be7`, CI hijau)
- [x] (completed) Verifikasi render via adb: `miui.focus.param` JSON (pin hilang,
      resto tampil, motor hijau, `100%`)
- [x] (completed) Review 7 commit + audit default DND/limit (tidak ada silent killer)
- [x] (completed) Unit test `RemoteViewsExtractorTest` (12 test, pure JVM, hijau lokal + CI)
- [x] (completed) Sync retry pasca-bind + konvergensi dini (`788baa7`, `200c3a8`, `dac4919`, CI hijau)
- [x] (completed) Bukti render final: ETA `15mnt` + `Tiba 13:55 - 14:10` + resto di JSON (adb)

## Menunggu order berikutnya (butuh live order, tidak bisa diuji tanpa itu)
- [x] (completed) Fase 0 order 2026-09-21: build 5dad2b1 terinstall, listener bound,
  capture jalan, baseline tercatat, 1 pill live (`202810785`, ETA `15mnt` via async RV,
  kitchen merge tanpa double)
- [ ] Uji swipe: Post KITCHEN → swipe → kunci/buka → pill tidak balik (`SUPPRESSED-SWIPE`)
- [ ] Uji konten baru: Post ONWAY → pill muncul lagi
- [ ] Uji FEEDBACK: semua pill Grab hilang (dismiss benar)
- [ ] Amati stage awal (`mencari driver`) muncul sejak awal order
- [ ] Amati range waktu tampil `15mnt` di pill + `Tiba 07.25 - 07.40` di big island
- [ ] Amati update RV-only ikut tanpa toggle layar

## Risiko sisa (jujur)
- Suppression map in-RAM: restart proses menghapus ingatan (rescan posting ulang).
- Suppression per-pkg 1 slot: 9 pill bersamaan, yang terakhir dismiss menang.
- Persen visual-RV murni (tanpa teks/ekstra angka) tidak terbaca murah — butuh inflate penuh.
- `parseRangeEnd` kini tak terpakai (warning kompilasi, bukan error).
