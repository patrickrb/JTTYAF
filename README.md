# JTTYAF

**JTTY on Android — RTTY-style keyboard QSOs with a coded waveform.**

JTTY is the new keyboard-to-keyboard digital mode introduced in WSJT-X 3.2: a 4-tone GFSK
signal (~127 Hz wide) carrying 1.888 s frames of structured contest exchanges or free text,
protected by a CRC and a tail-biting convolutional code, with no clock synchronisation —
transmissions start whenever you press Send. JTTYAF brings it to your phone: live-print
receive of every station in the passband, F-key macros for run and S&P, a serial-number
exchange, a logbook, and USB/Bluetooth/network CAT control of your radio.

---

## Features

### Receive
- **Live print** — every decoded JTTY message in the passband scrolls up as it arrives,
  with time, audio frequency and S/N; your own transmissions are echoed like a teleprinter
- **Whole-passband decode** (300–2700 Hz) with signal subtraction so overlapping stations
  print too, or RX-offset-only decode to save battery
- **Waterfall** with the RX/TX offset marker; tap to retune the offset
- **Background receive** — a foreground service keeps JTTYAF listening with the screen off
- Everything received and sent is appended to `jtty_all.txt` (WSJT-X `ALL.TXT` style)

### Transmit
- **Keyboard TX** — type up to 80 characters and Send; the hint shows how many frames it
  takes (five arbitrary characters per frame, more for contest formats)
- **F1–F8 macros** with `%M` (my call), `%H` (DX call), `%E` (599 + serial); defaults map
  to the one-frame JTTY atoms (`CQ <call> CQ`, `TU <call> CQ`, `<call> TU`, `AGN?` …);
  long-press to edit
- **Contest profiles** — General, RTTY Roundup (`599 001` in one frame), Field Day
- Tap any received line to pick the DX callsign; **Log QSO** writes it to the logbook and
  bumps the serial

### Radio control
- USB CAT control for the same rig set as FT8AF (Icom, Yaesu, Kenwood, Elecraft, Xiegu,
  FlexRadio, Guohe, truSDX, hamlib-driven rigs, and more), USB audio, Bluetooth, and
  network rigs
- Band picker with the preliminary JTTY frequencies, per-band TX level, tune carrier,
  ALC auto-volume and SWR protection

### Logbook
- QSOs logged with `MODE=JTTY`, ADIF export, Cloudlog / Wavelog / QRZ logbook / World
  Radio League upload, stats and award tracking

---

## Install

Grab the latest APK from the Releases page, or build it yourself:

```bash
cd jttyaf
./gradlew installDebug
```

Windows: `cd jttyaf && gradlew.bat installDebug`. Builds need JDK 17
(AGP 8.9.1 / Gradle 8.11.1), SDK 36 and NDK 25.1.8937393.
In a fresh Amp orb run `.agents/setup`, then use a new login shell for
`.agents/check` (native JTTY, shared-core and app JVM tests). Use
`.agents/check --host-only` for SDK-free native and tooling checks.
`assembleDebug` builds without a connected device; `installDebug` requires one.
See [AGENTS.md](AGENTS.md) for setup, commands and internal-testing safeguards.

---

## Native code

The JTTY codec in `jttyaf/app/src/main/cpp/jtty_lib/` is a **clean-room implementation**
written for this project from the published protocol description
([`docs/jtty-protocol.md`](docs/jtty-protocol.md): the WSJT-X design notes and source-coding
specification, golden vectors, and the official sample recording) — no GPL JTTY source was
consulted or copied. The codec and its JNI glue (`cpp/jttyaf_glue/`) are built from source
by the NDK/CMake toolchain into `libjttyaf.so`, so a fresh clone builds with no manual
steps. Host-side C tests (source-coding golden vectors, channel-coding round trips, modem
sensitivity) run in CI on every PR; the interoperability suite additionally decodes WSJT-X's
official sample recording when you point it at the file (the bit-level conventions were
settled against it — see `jtty_lib/SOURCES.md`):

```
jttyaf/app/src/main/cpp/jttyaf_glue/run_jtty_host_tests.sh [260807_134110.wav]   # Linux/macOS/WSL
jttyaf/app/src/main/cpp/jttyaf_glue/run_jtty_host_tests.ps1 -Sample <wav>       # Windows (host clang)
```

`jtty_cli encode "<text>" out.wav` / `jtty_cli decode in.wav` exercise the codec from the
command line.

---

## Heritage

JTTYAF is built from [FT8AF](https://github.com/patrickrb/FT8AF), which is in turn a fork of
[FT8CN](https://github.com/N0BOY/FT8CN) by **BG7YOZ** (hosted by **N0BOY**). The rig CAT
control, USB serial/audio stack, theming, and logbook all carry over from that lineage; the
FT8 engine was replaced by the clean-room JTTY codec. Massive thanks to the FT8CN authors —
none of this exists without their work — and to the WSJT-X team (K1JT, K9AN, G4KLA, N9ADG,
DL3WDG, W3SZ, KJ5HST, KD0BTO) for designing and documenting JTTY.

## License

MIT. See [LICENSE](LICENSE).

## Privacy

No ads, no analytics, no telemetry. See [privacy.md](privacy.md).

---

## About

Built by:
- **Patrick Burns — [K1AF](https://www.qrz.com/db/K1AF)**
- **Reid — [N0RC](https://www.qrz.com/db/N0RC)** (co-pilot, road-trip debugger, all-around enabler)

73.
