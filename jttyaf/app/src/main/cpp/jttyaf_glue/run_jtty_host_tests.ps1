<#
.SYNOPSIS
  Build and run the native JTTY codec tests on the host (no device needed).

.DESCRIPTION
  Compiles the jttyaf_glue/test_jtty_*.c suites against the clean-room codec
  in cpp/jtty_lib with a host clang and runs them. Exit code 0 == all suites
  pass. Companion to run_jtty_host_tests.sh (POSIX); modelled on SSTVAF's
  run_sstv_host_tests.ps1.

  Suites:
    test_jtty_source   — §3 golden vectors, alphabet, call28, validity, tokenizer
    test_jtty_channel  — CRC-12, TBCC/Gray, list Viterbi, sensitivity curve
    test_jtty_modem    — encode->GFSK->noise->rx, multi-frame, overlap, budget
    test_jtty_sample   — the official WSJT-X recording (SKIPs when absent)

  Also builds jtty_cli.exe next to the test binaries.

.PARAMETER Clang
  Path to a host clang.exe. Defaults to a search of common install locations.
  The Android NDK clang does NOT work here — use a desktop LLVM
  (winget install LLVM.LLVM) or MSVC clang.

.PARAMETER Sample
  Path to 260807_134110.wav, passed to test_jtty_sample (optional; the suite
  also honours $env:JTTY_SAMPLE_WAV and SKIPs when the file is absent).

.EXAMPLE
  ./run_jtty_host_tests.ps1
.EXAMPLE
  ./run_jtty_host_tests.ps1 -Sample C:\samples\260807_134110.wav
#>
param(
    [string]$Clang = "",
    [string]$Sample = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Definition
$cpp  = Split-Path -Parent $here            # .../cpp
$lib  = Join-Path $cpp "jtty_lib"

if (-not $Clang) {
    $probe = @(
        "C:\Program Files\LLVM\bin\clang.exe",
        "${env:ProgramFiles(x86)}\LLVM\bin\clang.exe",
        (Get-Command clang -ErrorAction SilentlyContinue).Source
    )
    $candidates = @($probe | Where-Object { $_ -and (Test-Path $_) })
    if ($candidates.Count -eq 0) {
        Write-Error "No host clang found. Install LLVM (winget install LLVM.LLVM) or pass -Clang <path>."
    }
    $Clang = $candidates[0]
}
Write-Host "Using clang: $Clang"

$libSrcs = @(
    "jtty_spec.c","jtty_crc.c","jtty_fec.c","jtty_call28.c",
    "jtty_source.c","jtty_modulate.c","jtty_rx.c"
) | ForEach-Object { Join-Path $lib $_ }

$common = @("-std=c11","-O2","-D_CRT_SECURE_NO_WARNINGS",
            "-Wall","-Wextra","-Wno-unused-function",
            "-I", $lib)

$outDir = Join-Path $env:TEMP "jtty_host_tests"
New-Item -ItemType Directory -Force $outDir | Out-Null

$suites = @(
    "test_jtty_source",
    "test_jtty_channel",
    "test_jtty_modem",
    "test_jtty_sample"
)

# The CLI is built (not run) so a build break there is caught too.
$cliOut = Join-Path $outDir "jtty_cli.exe"
& $Clang @common (Join-Path $here "jtty_cli.c") @libSrcs -o $cliOut
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (jtty_cli)." }

$failed = 0
foreach ($suite in $suites) {
    $src = Join-Path $here "$suite.c"
    $out = Join-Path $outDir "$suite.exe"
    & $Clang @common $src @libSrcs -o $out
    if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed ($suite)." }
    $args = @()
    if ($suite -eq "test_jtty_sample" -and $Sample) { $args += $Sample }
    & $out @args
    if ($LASTEXITCODE -ne 0) {
        Write-Host "SUITE FAILED: $suite" -ForegroundColor Red
        $failed = 1
    }
}

if ($failed) { exit 1 }
Write-Host "all JTTY host test suites passed (binaries in $outDir)"
exit 0
