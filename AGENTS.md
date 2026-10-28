# JTTYAF Project Instructions

> **This repo is JTTYAF** — a JTTY (WSJT-X 3.2 keyboard mode) app built from the FT8AF
> codebase. The FT8 engine is gone, replaced by the clean-room JTTY codec in
> `cpp/jtty_lib/` and the Kotlin engine in `kotlin/radio/ks3ckc/jttyaf/jtty/`
> (Operate/Waterfall/Log/Settings tabs). The Android module lives under `jttyaf/`.
> Two heritage names are kept deliberately because they are tied to JNI symbol
> resolution and must not change: the `com.k1af.ft8af` Java package namespace
> (JNI symbols for the waterfall FFT, resampler, USB audio and hamlib are bound to
> it) and the `cpp/ft8af_glue/` directory holding that glue. The native library
> itself is `libjttyaf.so` (`System.loadLibrary("jttyaf")`).

> **Placeholders.** This file is written for any contributor's machine.
> Substitute your own values wherever you see `<…>`. The recurring ones:
> `<windows-checkout>` / `<mac-checkout>` — your local clone of this repo;
> `<jdk17-home>` — the home directory of a JDK 17 install;
> `<phone-serial>` — your phone's serial from `adb devices`;
> `<android-sdk>` — your Android SDK root.
> Machine-specific notes (your paths, device serials, rig quirks) belong in an
> untracked `CLAUDE.local.md`, not here.

## Development environments

Contributors work from two kinds of checkout; the commands differ, so figure
out which one you're on first (`uname` / the primary working directory) and
follow the matching path throughout:

- **Windows** (`<windows-checkout>`, possibly driven from WSL) — uses the
  `cmd.exe /c "gradlew.bat …"` wrapper, which picks up the Android Studio JBR.
- **macOS** (`<mac-checkout>`) — builds and runs unit tests natively
  (`./gradlew …`, needs JDK 17).

Either kind of machine can build, test, install, and drive a real device when
an Android phone is attached over USB debugging (an emulator is often attached
alongside it).

## Workflow

**Every work item requires a pull request — no direct commits to `dev` or
`main`.** Even a one-line docs or config change goes through a feature branch and
a PR. **All PRs target the `dev` branch** (`gh pr create --base dev`).

**Use a git worktree for every separate line of work:**

```
git worktree add ../<checkout-dir-name>-<short-task-name> -b feat/<task>
```

The `jttyaf/app/src/main/cpp/` native sources (`jtty_lib`, `jttyaf_glue`,
`ft8af_glue`, `libusb`, `kissfft`, `hamlib/vendor`) are **tracked**, so a fresh
worktree builds with no manual copying — except `hamlib/prebuilt/<abi>/libhamlib.a`,
which is gitignored and built once (see `cpp/CMakeLists.txt`; on Windows build it
under WSL, or copy the four `.a` files from another checkout).

## Testing

**Every new code path requires a new test.** Any branch, helper, or behavior
you add or change must be covered by a unit test in the same PR.

Compose `@Composable` and `DrawScope` code can't be unit-tested directly, so
extract the decision logic into a plain top-level `internal` function and test
that (e.g. `txHint`, `nextSerial`, `JttyMessageAssembler`).

JVM tests live in `jttyaf/app/src/test/` (Kotlin under `.../kotlin`, Java under
`.../java`), use JUnit4 + Truth, and add `@RunWith(RobolectricTestRunner::class)`
when the code touches Android types. Run from the `jttyaf` dir:

```
cmd.exe /c "gradlew.bat testDebugUnitTest"            # Windows
JAVA_HOME=<jdk17-home> ./gradlew testDebugUnitTest    # macOS
```

The **codec host tests** (pure C11, seconds to run) are the authority on the JTTY
protocol and run in CI on every PR:

```
jttyaf/app/src/main/cpp/jttyaf_glue/run_jtty_host_tests.ps1   # Windows: needs a desktop LLVM clang
jttyaf/app/src/main/cpp/jttyaf_glue/run_jtty_host_tests.sh    # Linux/macOS/WSL: gcc or clang
```

`run_jtty_host_tests.ps1 -Sample <wav>` additionally decodes a WSJT-X recording
(the official sample is `sourceforge.net/projects/wsjt/files/samples/JTTY/260807_134110.wav`).

**Interop against WSJT-X itself** (the only test that proves TX, not just RX):
WSJT-X 3.2+ ships `rjtty.exe`/`sjtty.exe`, command-line JTTY decoder/encoder.
`run_jtty_interop.ps1` encodes a message set with each side and decodes it with
the other, failing on any mismatch; it SKIPs when no WSJT-X is installed
(releases: `github.com/WSJTX/wsjtx/releases`, install side by side under
`C:/WSJT/wsjtx-<version>/`). Pass `-TxWav <files>` to decode dumps from the app's
debug `tx_dump/` (below) with both decoders. WSJT-X is GPL: these binaries are
only ever *executed* as a black-box oracle, never read.

## Build & Deploy

After making code changes, build and install on the connected device when one is attached:

```
cd jttyaf && cmd.exe /c "gradlew.bat installDebug"     # Windows
cd jttyaf && JAVA_HOME=<jdk17-home> ./gradlew installDebug   # macOS
```

With several devices attached, target the phone with `adb -s <phone-serial>`.
Screenshots: `adb -s <phone-serial> exec-out screencap -p > shot.png`.

## Debug logs

The app writes a structured event log to
`/sdcard/Android/data/radio.ks3ckc.jttyaf/files/debug.log` via `fileLog()` — CAT
serial sends/recvs, USB attach events, autoConnect attempts, band changes, every
`JTTY RX:` decode and `JTTY TX:` over. Pull it with:

```
adb -s <phone-serial> pull /sdcard/Android/data/radio.ks3ckc.jttyaf/files/debug.log /tmp/
```

The receive/transmit text history is `jtty_all.txt` in the same directory.
Debug builds also write every JTTY over's audio as a 12 kHz 16-bit WAV into
`tx_dump/` in that directory (`TxWavDumper`, newest 20 kept) — the exact buffer
handed to the audio sink, for `run_jtty_interop.ps1 -TxWav`. Release builds never do.
The `applicationId` is `radio.ks3ckc.jttyaf`; pid-filter logcat with
`adb logcat --pid=$(adb shell pidof radio.ks3ckc.jttyaf)`. Useful logcat tags:
`NativeJttyCodec`, `MicRecorder`, `UsbAudioDevice`, `CableConnector`,
`CableSerialPort`, `UsbHostManager`.

## JTTY engine

- `docs/jtty-protocol.md` is the protocol reference the codec was written from.
  **Never consult or copy GPL JTTY code** (WSJT-X Fortran, libjtty, mfsk-core):
  this project is MIT and the codec is clean-room. Resolve ambiguities
  empirically against WSJT-X-generated audio.
- RX: `JttySignalListener` taps the 12 kHz recorder fan-out, pushes audio into the
  native streaming receiver (`jtty_rx_*`), chains frames into messages with
  `JttyMessageAssembler` (§4 of the protocol doc), and publishes `JttyMessage` rows.
- TX: `JttyTransmitter` encodes text → GFSK at `GeneralVariables.audioSampleRate`
  with the lowest tone at the RX/TX offset, then `PttController.keyDown` → settle →
  `TransmitAudioSink.play` → `keyUp` (always, in a finally). JTTY has no T/R slot,
  so there is **no late-start clipping anywhere** — the whole buffer plays.

## TX audio pipeline

Two hard-won FT8AF-era lessons apply verbatim; re-introducing either makes TX
silently broken (rig keys, audio audible, nobody decodes):

**1. Never clip the leading audio.** The 13-symbol sync a receiver needs lives at
the start of every frame. Drop samples from the tail if ever needed, never the head.

**2. `libusb_set_iso_packet_lengths` must use the audio rate, not `wMaxPacketSize`.**
`(sampleRate * channels * bytesPerSample) / 1000` bytes per USB FS frame; the
endpoint max clocks samples ~4 % fast and shifts every tone. Fixed in
`cpp/usb_audio_capture.cpp` (still the live code path). The USB-direct path must
also claim the UAC AudioControl interface (`UsbAudioDevice.detachKernelAudioDriver`).
