# JTTYAF shared-core integration

JTTYAF vendors **ham-radio-core 0.1.0** unchanged at `ham-radio-core/`.
Canonical source is `patrickrb/FT8AF/ham-radio-core`; its README documents the
contracts and update procedure, and `SHA256SUMS` pins all 13 payload files.
Supplied archive SHA-256:
`02e632964a4e619438bdc300abac7b45bb924cd2a7e1e3bc00695878c2c6dcba`.

The original readiness and core exports targeted the obsolete FT8AF import
`556c89427d0fcd1bfb8ac77ea83be290cefd298a`, still on `origin/main` at integration.
This integration instead preserves current JTTYAF from `origin/staging`
`a29f1e6430769d3b6e6b0fac40493bb175086fa8`, including the clean-room JTTY codec,
`radio.ks3ckc.jttyaf` application ID, API 24 minimum, JNI heritage namespace and
`libjttyaf.so`. It does not rename/rebrand the app or restore the removed FT8 engine.

Provenance (exports downloaded with Amp file-transfer tools):

- Readiness: thread `T-01a18ae1-84b0-75c9-b823-79f2e5ba0c74`, patch SHA-256
  `a46bb939aa9cd939191b3fc108df53b3e9f04e9bf5e76e9d3ef9f068f3f87539`.
- Core coordinator/report: thread `T-01a18ae1-a5b1-70a5-a3cc-9ed9ecf1667c`,
  original JTTYAF patch SHA-256
  `5f5214a6336004eed80ec3f97f0498d595ce69564148f3da641ff123d77cafa9`.
- Core source worker: thread `T-01a18ae3-44f0-7436-854e-d2354d114f71`.

`jttyaf/settings.gradle` maps `:ham-radio-core` to the root module and the app
uses a project dependency, not a published artifact. USB capture imports core
`FirDecimator`, `RationalResampler` and `UacAudioFormats`; TX upsampling uses
the same rational kernel. CAT watchdog consumers import core `CatLiveness`.
All four current implementations and duplicate test classes match the original
export baseline. Their algorithms/APIs are unchanged; tests move with the core.
Unique CAT lifecycle/polling and TX upsampling tests remain in the app.
Native/JNI code, USB ownership, PTT, mode codec and timing stay app-owned.

Readiness uses current JTTY paths and native codec suites, preserves executable
modes on `.agents/setup`, `.agents/check` and `jttyaf/gradlew`, and pins NDK
25.1.8937393 to match existing CI. Both `.agents/check` and Android CI explicitly
run `:ham-radio-core:test` as well as app tests. Core-only changes are included
in CI detection/release scope; release triggers, lanes and source gates are
not bypassed or weakened.

## Combined validation

From the repository root, after `.agents/setup`, in a new login shell:

```sh
.agents/check
./jttyaf/gradlew -p jttyaf --no-daemon --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' \
  :ham-radio-core:test testDebugUnitTest assembleDebug
(cd ham-radio-core && sha256sum -c SHA256SUMS)
```

The single worker, in-process Kotlin compiler and 1536 MiB Gradle heap avoid
the prior isolated port's two-worker OOM. Historical source-thread results
are not proof of this combined tree; use its own test reports and build logs.

## Internal-testing boundary

No production release/promotion/rollout is authorized until the user tests all
five apps and subsequently explicitly approves production. Do not use main,
release tags or a production workflow dispatch to deliver this candidate.

Current JTTYAF no longer collides with FT8AF's app ID. However debug has no
application-ID suffix, so it cannot coexist with a Play/release installation
of **JTTYAF**. A different certificate rejects an update, and local versionCode
1 may be below an installed build. Back up logs/settings before considering
uninstallation; uninstalling can erase app data. No branding workaround is added.

Host tests and APK inspection do not establish installation, Android UI,
real-device USB/audio/CAT/PTT or RF behavior. The optional official WSJT-X sample
and black-box interop checks need their external fixtures/tools; a skipped suite
is not an interoperability pass. Broad static analysis remains a separate check.
