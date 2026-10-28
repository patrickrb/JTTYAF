<#
.SYNOPSIS
  Cross-decode JTTY audio between the clean-room codec and WSJT-X, headless.

.DESCRIPTION
  WSJT-X 3.2+ ships command-line JTTY tools: sjtty.exe (encoder, writes a 12 kHz
  WAV) and rjtty.exe (decoder). This script uses them as a black-box reference
  oracle for the codec in cpp/jtty_lib:

    1. WSJT-X -> JTTYAF : every message is encoded by sjtty and decoded by
       jtty_cli; the decoded text must match.
    2. JTTYAF -> WSJT-X : every message is encoded by jtty_cli and decoded by
       rjtty; the decoded text must match.
    3. -TxWav           : WAVs dumped by the app's debug TxWavDumper
       (adb pull .../files/tx_dump/) are decoded by BOTH decoders, proving the
       Android TX path (sample rate, head not clipped, level) end to end.

  WSJT-X is GPL. It is only ever *executed* here, never read: the codec stays
  clean-room (see AGENTS.md). When no WSJT-X with rjtty/sjtty is found the
  script SKIPs with exit 0, so it is safe in any environment; a decode mismatch
  exits 1.

  Needs a host clang (same as run_jtty_host_tests.ps1) to build jtty_cli.

.PARAMETER WsjtxBin
  WSJT-X bin directory holding rjtty.exe and sjtty.exe. Defaults to the newest
  C:\WSJT\wsjtx*\bin that has them.

.PARAMETER Clang
  Host clang.exe; defaults to the usual LLVM install locations.

.PARAMETER Message
  Messages to round-trip. Defaults to a mixed set (calls, grid, report,
  punctuation, digits, a 16-frame-long one).

.PARAMETER F0
  Lowest-tone audio frequency for the generated signals (Hz). Default 1500.

.PARAMETER TxWav
  App TX dump(s) to decode with both decoders. Each is copied to a short
  temp name first: rjtty's Fortran path buffer truncates long paths.

.PARAMETER Expect
  Text the -TxWav dumps are expected to decode to (optional). Without it the
  dumps only have to decode to *something* identical in both decoders.

.EXAMPLE
  ./run_jtty_interop.ps1
.EXAMPLE
  adb pull /sdcard/Android/data/radio.ks3ckc.jttyaf/files/tx_dump/ .
  ./run_jtty_interop.ps1 -TxWav tx_dump\*.wav -Expect "CQ KS3CKC EM28"
#>
param(
    [string]$WsjtxBin = "",
    [string]$Clang = "",
    [string[]]$Message = @(),
    [double]$F0 = 1500,
    [string[]]$TxWav = @(),
    [string]$Expect = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Definition
$cpp  = Split-Path -Parent $here
$lib  = Join-Path $cpp "jtty_lib"

# ---- locate WSJT-X ---------------------------------------------------------
if (-not $WsjtxBin) {
    $cands = @(Get-ChildItem "C:\WSJT\wsjtx*\bin\rjtty.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | ForEach-Object { $_.DirectoryName })
    if ($cands.Count -gt 0) { $WsjtxBin = $cands[0] }
}
$rjtty = if ($WsjtxBin) { Join-Path $WsjtxBin "rjtty.exe" } else { "" }
$sjtty = if ($WsjtxBin) { Join-Path $WsjtxBin "sjtty.exe" } else { "" }
if (-not ($rjtty -and (Test-Path $rjtty) -and (Test-Path $sjtty))) {
    Write-Host "SKIP: no WSJT-X with rjtty.exe/sjtty.exe found (install WSJT-X 3.2+ or pass -WsjtxBin)."
    exit 0
}
Write-Host "Using WSJT-X tools in: $WsjtxBin"

# ---- build jtty_cli --------------------------------------------------------
if (-not $Clang) {
    $probe = @(
        "C:\Program Files\LLVM\bin\clang.exe",
        "${env:ProgramFiles(x86)}\LLVM\bin\clang.exe",
        (Get-Command clang -ErrorAction SilentlyContinue).Source
    )
    $found = @($probe | Where-Object { $_ -and (Test-Path $_) })
    if ($found.Count -eq 0) {
        Write-Error "No host clang found. Install LLVM (winget install LLVM.LLVM) or pass -Clang <path>."
    }
    $Clang = $found[0]
}
$libSrcs = @(
    "jtty_spec.c","jtty_crc.c","jtty_fec.c","jtty_call28.c",
    "jtty_source.c","jtty_modulate.c","jtty_rx.c"
) | ForEach-Object { Join-Path $lib $_ }
$common = @("-std=c11","-O2","-D_CRT_SECURE_NO_WARNINGS","-Wall","-Wextra","-Wno-unused-function","-I",$lib)

# Short work dir: rjtty cannot open long paths.
$work = Join-Path $env:TEMP "jtty_interop"
if (Test-Path $work) { Get-ChildItem $work -File | ForEach-Object { $_.Delete() } }
New-Item -ItemType Directory -Force $work | Out-Null
$cli = Join-Path $work "jtty_cli.exe"
& $Clang @common (Join-Path $here "jtty_cli.c") @libSrcs -o $cli
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (jtty_cli)." }

# ---- helpers ---------------------------------------------------------------
function Normalize([string]$s) {
    if ($null -eq $s) { return "" }
    return (($s.Trim() -replace '\s+', ' ')).ToUpperInvariant()
}

# jtty_cli decode prints a final  message: "<text>"  line.
function Decode-Ours([string]$wavPath) {
    $out = & $cli decode $wavPath 2>&1 | Out-String
    $m = [regex]::Match($out, 'message: "(.*)"')
    if ($m.Success) { return $m.Groups[1].Value } else { return "" }
}

# rjtty prints the cumulative message after every frame; the last line is the whole over.
function Decode-Wsjtx([string]$wavName, [double]$f0) {
    Push-Location $work
    try {
        $lines = @(& $rjtty 4.6 0 384 $f0 50 $wavName 2>&1 | ForEach-Object { "$_" } |
            Where-Object { $_ -match '^\s*\d+\s+\S' })
    } finally { Pop-Location }
    if ($lines.Count -eq 0) { return "" }
    return ($lines[-1] -replace '^\s*\d+\s+', '')
}

# sjtty writes 000000_000001.wav into the cwd; run it in a scratch subdir and move the file.
function Encode-Wsjtx([string]$text, [double]$f0, [string]$destName) {
    $sub = Join-Path $work "sjtty_tmp"
    New-Item -ItemType Directory -Force $sub | Out-Null
    Push-Location $sub
    try {
        & $sjtty "$text" $f0 0.0 0.0 1 384 1 99 2>&1 | Out-Null
    } finally { Pop-Location }
    $src = Join-Path $sub "000000_000001.wav"
    if (-not (Test-Path $src)) { throw "sjtty produced no WAV for '$text'" }
    Move-Item $src (Join-Path $work $destName) -Force
}

function Encode-Ours([string]$text, [double]$f0, [string]$destName) {
    & $cli encode "$text" (Join-Path $work $destName) $f0 12000 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "jtty_cli could not encode '$text'" }
}

# ---- the message set -------------------------------------------------------
if ($Message.Count -eq 0) {
    $Message = @(
        "CQ KS3CKC EM28",
        "KS3CKC DE W1AW R-15 73 TNX!",
        "W1AW DE KS3CKC UR 599 IN KS. HW?",
        "TESTING 1 2 3 ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789",
        "RAN ALL NIGHT ON BAND NOISE - NO FALSE DECODES!",
        "THIS IS A LONG MESSAGE TO PUSH THE FRAME COUNT UP A BIT MORE, OK? 73 DE KS3CKC"
    )
}

$failures = 0
$i = 0
foreach ($msg in $Message) {
    $i++
    $want = Normalize $msg

    Encode-Wsjtx $msg $F0 "w$i.wav"
    $got1 = Normalize (Decode-Ours (Join-Path $work "w$i.wav"))
    $ok1 = ($got1 -eq $want)

    Encode-Ours $msg $F0 "o$i.wav"
    $got2 = Normalize (Decode-Wsjtx "o$i.wav" $F0)
    $ok2 = ($got2 -eq $want)

    $tag1 = if ($ok1) { "ok  " } else { "FAIL" }
    $tag2 = if ($ok2) { "ok  " } else { "FAIL" }
    Write-Host ("  {0} WSJT-X->JTTYAF  {1}" -f $tag1, $msg)
    if (-not $ok1) { Write-Host ("        got: {0}" -f $got1) -ForegroundColor Red }
    Write-Host ("  {0} JTTYAF->WSJT-X  {1}" -f $tag2, $msg)
    if (-not $ok2) { Write-Host ("        got: {0}" -f $got2) -ForegroundColor Red }
    if (-not ($ok1 -and $ok2)) { $failures++ }
}

# ---- app TX dumps ----------------------------------------------------------
$k = 0
foreach ($pattern in $TxWav) {
    foreach ($f in @(Get-ChildItem $pattern -File -ErrorAction SilentlyContinue)) {
        $k++
        $name = "tx$k.wav"
        Copy-Item $f.FullName (Join-Path $work $name) -Force
        $ours  = Normalize (Decode-Ours (Join-Path $work $name))
        $theirs = Normalize (Decode-Wsjtx $name $F0)
        $ok = ($ours -ne "") -and ($ours -eq $theirs)
        if ($Expect) { $ok = $ok -and ($theirs -eq (Normalize $Expect)) }
        $tag = if ($ok) { "ok  " } else { "FAIL" }
        Write-Host ("  {0} TX dump {1}" -f $tag, $f.Name)
        Write-Host ("        JTTYAF decode: {0}" -f $ours)
        Write-Host ("        WSJT-X decode: {0}" -f $theirs)
        if (-not $ok) { $failures++ }
    }
}
if ($TxWav.Count -gt 0 -and $k -eq 0) {
    Write-Host "  FAIL no TX dump files matched -TxWav" -ForegroundColor Red
    $failures++
}

if ($failures -gt 0) {
    Write-Host "$failures interop check(s) FAILED (work dir $work)" -ForegroundColor Red
    exit 1
}
Write-Host "all JTTY interop checks passed against WSJT-X (work dir $work)"
exit 0
