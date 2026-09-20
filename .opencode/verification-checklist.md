# Protokol Sapu 100% — Order Grab (v2, matang)

Tujuan: tidak ada transisi pill yang luput dari bukti sejak order mulai sampai selesai.
Prinsip: capture kontinu tanpa jeda + cek berkala + semua keputusan tercatat di log
(tanpa tergantung toggle user).

## Fase 0 — Pra-order (begitu user kabar "aku order")
- [ ] 0.1 Verifikasi build: `dumpsys package com.d4viddf.hyperbridge | versionCode`
      cocok dengan HEAD master. Bila tidak: install + `force-stop`.
- [ ] 0.2 Verifikasi listener: `pidof` hidup + Proxy bound di `dumpsys notification`.
- [ ] 0.3 `logcat -c`, start capture background `-s HyperBridgeDebug:V HyperBridgeTest:V`,
      snapshot shade awal (`dumpsys --noredact` → parse): catat notif Grab aktif.
- [ ] 0.4 Konfirmasi: tidak ada pill zombie (bridge tanpa ori aktif). Bila ada: swipe
      manual 1x (cek tidak balik = SUPPRESSED bekerja).

## Fase 1 — Order berjalan (tiap stage Grab: searching → kitchen → onway → here)
Untuk TIAP stage, dalam ≤60 detik setelah Grab update:
- [ ] 1.1 Log ada `REAL ... type=DELIVERY` + `POSTING` (bukti pipeline lihat stage).
- [ ] 1.2 Pill muncul ≤5 detik. Hitung pill Grab di shade = 1 (bukan 0, bukan 2).
      Bila 2: tunggu update berikut (konvergensi) atau catat `CONVERGE`.
- [ ] 1.3 Isi pill benar (cek `miui.focus.param` bila perlu): stage/dots, ETA kanan
      (`Nmnt`), resto di big island, motor hijau Grab.
- [ ] 1.4 Update RV-only tanpa ganti extras TETAP memicu log (rvHash) — bukan diam.

## Fase 2 — Uji swipe (sekali, di stage tengah, mis. ONWAY)
- [ ] 2.1 Swipe pill → catat jam.
- [ ] 2.2 Screen event / sync berikut: TIDAK ada `POSTING` ulang konten sama,
      ada `SUPPRESSED-SWIPE skip`.
- [ ] 2.3 Stage berikutnya (konten beda): pill MUNCUL lagi (suppression tidak bunuh baru).

## Fase 3 — Order selesai (Feedback/rating masuk)
- [ ] 3.1 Log `DELIVERY-DISMISS` dipicu sinyal tuntas (apapun wordingnya — bila wording
      baru lolos, tambahkan keyword + test, seperti insiden Verdict).
- [ ] 3.2 Semua pill Grab hilang dari shade. Tidak ada bridge yatim
      (cek `dumpsys`: bridge HyperBridge tanpa ori aktif = 0).

## Anti-luput (jaminan proses)
- [ ] A.1 Capture TIDAK PERNAH jeda: cek task background tiap ronde; bila mati, restart
      segera (buffer 2MiB muter dalam hitungan menit di HP ini).
- [ ] A.2 Tiap ronde cek `adb devices` (scrcpy drop = buta total; minta user buka scrcpy).
- [ ] A.3 Setiap anomali (double/kosong/beku) langsung ambil: `dumpsys --noredact` +
      `focus.param` JSON + potongan log — sebelum buffer muter.
- [ ] A.4 Log keputusan bersifat unconditional (tanpa gate toggle) — sudah aktif sejak
      `5dad2b1`. Bila ronde sunyi total padahal stage berubah → eskalasi sebagai bug.

## Batas jujur (tidak dijanji)
- Stage Grab yang nol-perubahan (teks + RV identik persis) tidak terdeteksi — wajar,
  karena memang tidak ada yang baru untuk ditampilkan.
- Persen murni-visual di RV (tanpa teks/angka) tidak terbaca murah.
