# JTTY protocol notes (clean-room reference for `jtty_lib`)

JTTY is the keyboard-to-keyboard, RTTY-replacement digital mode introduced in
WSJT-X 3.2.0-rc1 (announced 2026-09-23). This document collects everything the
JTTYAF codec needs, gathered from the WSJT-X **design documents**
(`lib/jtty/jtty_design.{txt,md}`, `lib/jtty/jtty_source_encoding.txt`), the
WSJT-X 3.2 User Guide, and independent published write-ups of the mode. No GPL
source code was copied; `jtty_lib/` is written from this description and
verified empirically against the official sample recording
(`sourceforge.net/projects/wsjt/files/samples/JTTY/260807_134110.wav`).

Where a convention is not pinned down by the documents (marked **VERIFY**), the
implementation must be checked against that recording: with a 12-bit CRC plus
the grammar validity rules, a wrong convention produces essentially no valid
frames, while the right one yields a coherent, readable QSO.

The licensing reasoning is the same as SSTVAF's clean-room SSTV codec: FT8AF /
JTTYAF are MIT, WSJT-X is GPL-3, so the codec is re-implemented from the
protocol description, not ported.

---

## 1. Physical layer

| Parameter | Value |
|---|---|
| Sample rate (reference) | 12 000 Hz |
| Samples per symbol `nsps` | 384 → **31.25 baud** (symbol = 32 ms) |
| Modulation | 4-GFSK, continuous phase |
| Tone spacing | 31.25 Hz (= baud rate, modulation index h = 1) |
| Gaussian BT | 2.0, frequency pulse spans 3 symbols (same pulse as FT8) |
| Amplitude ramp | raised-cosine over `nsps/8` samples at the very start and end of a *transmission* (not per frame) |
| Frame | **59 symbols** = 13 sync + 46 data, in that order → **1.888 s** (22 656 samples) |
| Occupied bandwidth | ≈127 Hz (99 % power); 125 Hz half-power |
| Timing | **asynchronous** — no UTC slots; a transmission may start any time |

Tone `k` (0..3) sits at `f0 + k·31.25 Hz` where `f0` is the lowest tone. The
"frequency" WSJT-X displays for a decode is the lowest tone.

**Sync sequence** (13 symbols, transmitted first in every frame):

```
0 2 2 3 0 0 3 2 1 3 1 2 0
```

Peak autocorrelation sidelobe is 2/13.

Multi-frame messages are sent back-to-back with **continuous phase across
frames**; the first/last symbol of the transmission is extended into the
Gaussian guard region exactly as the FT8 GFSK synthesiser does (see
`ft8af_glue/gfsk.c`, MIT, which can be reused for TX: `symbol_period =
0.032`, `symbol_bt = 2.0`, tones 0..3).

The GFSK pulse (identical to FT8's):

```
pulse(t) = ( erf(K·BT·(t + 0.5)) − erf(K·BT·(t − 0.5)) ) / 2,  K = π·sqrt(2/ln 2),  t ∈ [−1.5, 1.5) symbols
```

## 2. Channel coding (per frame)

```
34-bit payload ─► CRC-12 ─► 46 bits ─► TBCC r=1/2 K=10 ─► 92 bits ─► 46 Gray symbols ─► [13 sync | 46 data]
```

### 2.1 Payload (34 bits, MSB first; "bit 1" is the MSB)

| Bits | Meaning |
|---|---|
| 1–32 | source word (grammar, §3) |
| 33 | reserved — **must be 0** |
| 34 | **EOM** — 1 only on the last frame of a message |

### 2.2 CRC-12

* Polynomial `0x80F` with the implicit leading term: x¹² + x¹¹ + x³ + x² + x + 1.
* Initial register 0, no reflection, no final XOR.
* Computed over the 34 payload bits taken MSB first; the 12 CRC bits are
  appended after bit 34, MSB first, giving 46 information bits.
* **Verified** (official sample recording, see `jtty_lib/SOURCES.md`): the
  division runs over exactly the 34 bits with **no zero padding** (unlike FT8's
  CRC-14, which pads its 77 bits to 82). It is the ordinary augmented CRC,
  M(x)·x¹² mod G(x); the 2/6/14-bit padding variants and the non-augmented
  remainder all fail to validate the sample.

### 2.3 Tail-biting convolutional code

* Rate 1/2, constraint length **K = 10** (512 states), **tail-biting**: the
  encoder register is pre-loaded with the *last* 9 information bits (CRC bits
  included) so the trellis starts and ends in the same state; no flush bits.
* Generators, octal: **1167** and **1545**
  (binary `1001110111` and `1101100101`).
* 46 information bits → 92 coded bits. Each information bit produces one output
  bit from each generator, i.e. one **coded bit pair** per information bit, and
  that pair becomes **one 4-ary symbol**; so data symbol *i* (0..45) carries
  information bit *i*.
* **Verified** (official sample recording, see `jtty_lib/SOURCES.md`):
  (a) the generator literals are *delay-indexed* — bit *j* of `1167`/`1545`
  (bit 0 = LSB) is the tap on the information bit *j* symbols old, so the
  **LSB** of each literal multiplies the newest bit (the opposite, MSB-newest
  reading yields nothing); (b) **1167's output is the first bit** of each pair
  (the Gray-map MSB) and 1545's the second; (c) information bit 1 — the MSB of
  the 46-bit word — enters the encoder first. Only this one combination of the
  alternatives decodes the recording.

### 2.4 Gray mapping (bit pair → tone)

```
00 → 0,  01 → 1,  11 → 2,  10 → 3        (tone = 2·b0 + (b0 xor b1))
```

### 2.5 Validity after decoding

A frame is accepted only if: the Viterbi/list decoder yields a codeword whose
CRC-12 matches **and** bit 33 = 0 **and** the source word passes every grammar
rule in §3 (no unassigned type/family/kind/enum, every field in range, not the
all-zero word). Anything else is dropped before display, EOM handling, message
assembly, or signal subtraction.

## 3. Source coding (the 32-bit word)

Bits are numbered 1..32 from the MSB. The top-level type is `i2` =
**bits 31–32** (so the word layout is `[30-bit form][i2]`).

### 3.1 `i2 = 0` and `i2 = 1` — callsign actions

| Bits | Field |
|---|---|
| 1–28 | `call28` — the standard-callsign code from the FT8/FT4 77-bit protocol (`pack28`) |
| 29–30 | `n2` |
| 31–32 | `i2` |

| i2.n2 | Rendered text | |
|---|---|---|
| 0.0 | `CQ <call> CQ` | |
| 0.1 | `<call>` | |
| 0.2 | `TU <call> CQ` | |
| 0.3 | `<call> TU` | |
| 1.0 | `<call> AGN?` | |
| 1.1 | `TU NOW <call>` | |
| 1.2, 1.3 | **invalid** (reserved) | |

Only **standard** callsigns are valid call atoms: those that round-trip through
`pack28`/`unpack28` (1–2 character prefix with at least one letter, a digit, up
to 3 letter suffix; no `/`, no hashed/non-standard forms, no leading `Q`).
`pack28` for standard calls (identical to FT8):

```
pad the call to 6 chars (" " + call when the 3rd char is not a digit)
c1 ∈ " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ" (37)   c2 ∈ "0-9A-Z" (36)
c3 ∈ "0-9" (10)   c4,c5,c6 ∈ " A-Z" (27)
n = ((((c1·36 + c2)·10 + c3)·27 + c4)·27 + c5)·27 + c6
call28 = n + 2063592 + 4194304
```

Golden: `K1ABC` → call28 = `10214965` (0x9BDE35).

### 3.2 `i2 = 2` — STRUCT30 (structured contest exchanges)

```
bits 1–27 : body27        bits 28–30 : family3        bits 31–32 : 10
```

Body fields are listed **in bit order from bit 1**:

| family3 | Name | body27 layout |
|---|---|---|
| 000 | EXCH_NUM | `role1, kind4, value17, zero5` |
| 001 | EXCH_LOC | `role1, kind4, length1, token16, zero5` |
| 010 | EXCH_PAIR | `schema3, pair_data23, zero1` |
| 011 | EXCH_NUM_TIME | `role1, serial14, minute11, zero1` |
| 100 | MISC | `subtype4, subtype_data23` |
| 101–111 | **invalid** in version 1 |

`role1`: 0 = field only (render just the field), 1 = full exchange (prefix the
rendered field with `599 `). Every `zeroN` field must be zero.

**EXCH_NUM** kinds (`kind4`): 0 SERIAL, 1 CQ_ZONE, 2 ITU_ZONE, 3 AGE, 4 POWER,
5 CHECK, 6 FIRST_LICENSE_YEAR (value ≤ 9999), 7 GENERIC_NUMERIC; 8–15 invalid.
`value17` ∈ 0..131071. SERIAL renders with at least three digits (`001`).

**EXCH_LOC** kinds: 0 STATE_PROVINCE, 1 ARRL_RAC_SECTION, 2 COUNTRY_PREFIX,
3 GENERIC_QTH, 4 LOCAL_ADMINISTRATIVE_CODE; 5–15 invalid. `length1` = 0 for a
2-character token, 1 for 3 characters. `token16` is base-36 over `0-9A-Z`,
accumulated left to right (`ABC` = ((10·36)+11)·36+12). For length 0 the value
must be < 36², for length 1 it must be ≥ 36² and < 36³.

**EXCH_PAIR** schemas (`schema3`):
* 0 ZONE_LOC3: `cq_zone6 (1..40), length1, token16` (token rules as EXCH_LOC)
  — renders `<zone> <token>` e.g. `05 NWT`.
* 1 CLASS_SECTION: `count6 (1..32), class3 (0..5 → A..F; 6,7 invalid),
  section7 (1..86, 1-based index into the ARRL/RAC section table), zero7`
  — renders `<count><class> <section>` e.g. `1D EMA`.
* 2–7 invalid.

**EXCH_NUM_TIME**: `serial14` ∈ 0..16383, `minute11` ∈ 0..1439 (UTC
`HHMM` = minute/60, minute%60; 1440–2047 invalid). Renders `<serial> <HHMM>`.

**MISC** subtypes (`subtype4`):
* 0 CONTROL: `phrase7, zero16`. Phrases 0–17:
  `0 AGN?, 1 CALL?, 2 AGN CALL, 3 NR?, 4 AGN NR, 5 EXCH?, 6 STATE?, 7 SECTION?,
  8 ZONE?, 9 GRID?, 10 RPRT?, 11 QSL TU, 12 TU, 13 QRZ?, 14 QSO B4, 15 WAIT,
  16 NIL?, 17 OK?`; 18–127 invalid.
* 1 GRID4: `role1, grid4_code15, zero7` with
  `code = ((field1·18 + field2)·10 + digit1)·10 + digit2` (fields A..R → 0..17);
  valid range 0..32399, 32400–32767 invalid.
* 2–15 invalid.

The 86-entry ARRL/RAC section table (1-based for `section7`) is the FT8 Field
Day list, already in the tree as `FT8Package.ARRL_SECTIONS` /
`ft8_lib/ft8/message.c ARRL_SECTIONS` (0-based there: `EMA` is index 10 →
`section7 = 11`):

```
AB AK AL AR AZ BC CO CT DE EB EMA ENY EPA EWA GA GH IA ID IL IN
KS KY LA LAX NS MB MDC ME MI MN MO MS MT NC ND NE NFL NH NL NLI
NM NNJ NNY TER NTX NV OH OK ONE ONN ONS OR ORG PAC PR QC RI SB SC SCV
SD SDG SF SFL SJV SK SNJ STX SV TN UT VA VI VT WCF WI WMA WNY WPA WTX
WV WWA WY DX PE NB
```

### 3.3 `i2 = 3` — TEXT5 (free text)

Five 6-bit characters (bits 1–30, first character in bits 1–6) indexed into the
64-character alphabet:

```
0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ +-./?!"#$%,&*()_'=[]{}<>|:;
```

(index 0 = `0`, 10 = `A`, 36 = space, 63 = `;`). Lowercase is upper-cased before
packing; unsupported characters are replaced by `#`.

### 3.4 Golden vectors (34-bit payload incl. reserved + EOM)

| Meaning | Bits 1–34 | Hex |
|---|---|---|
| `CQ K1ABC CQ` (EOM) | `0000100110111101111000110101000001` | `0x026F78D41` |
| EXCH_NUM SERIAL 123, 599, more frames follow | `1000000000000001111011000000001000` | `0x20007B008` |
| EXCH_NUM SERIAL 123, 599, EOM | `1000000000000001111011000000001001` | `0x20007B009` |
| EXCH_LOC STATE `CA`, 599, EOM | `1000000000000110111010000000011001` | `0x2001BA019` |
| ZONE_LOC3 `05 NWT` (EOM) | `0000001011011110010000110100101001` | `0x00B790D29` |
| CLASS_SECTION `1D EMA` (EOM) | `0010000010110001011000000000101001` | `0x082C58029` |
| EXCH_NUM_TIME `156 1749`, 599, EOM | `1000000100111001000010110100111001` | `0x204E42D39` |
| CONTROL `AGN?` (EOM) | `0000000000000000000000000001001001` | `0x000000049` |
| GRID4 `FN42` field-only (EOM) | `0001001010000110011000000001001001` | `0x04A198049` |
| TEXT5 `HELLO` (EOM) | `0100010011100101010101010110001101` | `0x11395558D` |

All ten have been re-derived by hand from the field layouts above and agree.

### 3.5 Text → atoms (transmit-side packing)

1. Normalise: upper-case, trim, collapse repeated spaces, replace unsupported
   characters with `#`. Text longer than **80 characters is rejected**, not
   truncated.
2. Generate candidate atoms at complete token boundaries: the six call forms,
   the 18 CONTROL phrases, GENERIC_NUMERIC, GENERIC_QTH, GRID4, CLASS_SECTION
   (and, under the RTTY Roundup profile, `599 <number>` → SERIAL with ≥3 digits
   and state/province candidates). A candidate is valid only if it packs,
   unpacks, and renders back to exactly its span of the text.
3. Dynamic programming over character offsets picks the **minimum-frame**
   sequence; interior TEXT5 atoms consume exactly 5 characters (the final one is
   space-padded). Ties: structured atom first, then longest span, then
   ascending kind/subtype/role.
4. Every atom gets bit 33 = 0; only the last gets EOM = 1. At most 16 frames
   result, because TEXT5 is always available as a fallback.

Examples: `CQ K1ABC CQ` → 1 frame; `599 001` → 1 frame under RTTY Roundup,
2 under the generic profile; `K1ABC 599 001` → 2 frames (RTTY Roundup).
Queued native messages need no spacing frame between them.

## 4. Messages (frame chaining on receive)

* A message is 1..16 frames. EOM terminates it.
* A new frame **continues** an open message when it arrives *n* frame periods
  (n = 1..3) after the previous frame, within ±0.1 s, and within
  `10 + 3·(n−1)` Hz of it. A skipped period (n > 1) inserts `...` in the text.
* Up to 30 messages may be open at once; a message with no continuation for 3
  frame periods is flushed as incomplete.
* Frames within 12 Hz and 50 ms of an already-reported frame are duplicates.
* Received and transmitted text is logged to `ALL.TXT`-style history.

## 5. Receiver (what WSJT-X does; a guide, not a requirement)

* Analysis window 1.25 frames (2.36 s), stepped by a quarter frame (0.472 s).
* Sync surface: correlate the 13-symbol sync waveform at 2 ms time steps across
  the candidate frequencies; smooth over frequency with a 1-2-3-2-1 kernel.
* Candidate channels: the operator's RX frequency ±tolerance (priority), plus
  the two "listening" channels centred on 1350 and 1650 Hz (±150 Hz). JTTYAF
  additionally wants a configurable wide-band search so the live-print view
  shows every station in the passband, budgeted so a phone keeps up.
* Peak-up: refine ±4 ms and ±2.5 Hz (0.5 Hz steps); fit a line to the unwrapped
  phase of the 13 sync phasors, accept if residual RMS < 1 rad.
* Gates: `nsync > 6` and S/N ≥ 4.6 dB on the priority channel; `nsync > 8`,
  S/N ≥ 5.0 dB elsewhere.
* Demod: coherent block lengths L = 1, 2, 4 on full-symbol correlations, then
  L = 1 on half-symbol energies; first rung with an accepted hypothesis wins.
* Decoder: 512-state list Viterbi (list width 4) with two wrap-around (WAVA)
  passes; top hypotheses checked by CRC-12 then grammar.
* Subtraction: re-encode the accepted frame, fit a complex gain, subtract from
  the buffer, and re-run so overlapping/weaker signals decode.
* The S/N reference bandwidth is 2500 Hz (same convention as FT8).

## 6. Operating conventions

* Suggested dial frequencies (preliminary): 1.838, 3.575, 7.090, **14.090**
  (main meeting place), and others per band.
* Macros: `%M` own call, `%H` other station's call, `%E` exchange
  (`599` + serial), `%Q` next queued call. F1–F8 cover run / S&P.
* Speed: ≈60 WPM for structured exchanges, ≈30 WPM for free text.
* N1MM Logger+ ≥ 1.0.11449 talks to WSJT-X via an MMTTY-compatible interface
  (out of scope for the first JTTYAF release).

## Sources

* WSJT-X 3.2.0-rc1 `lib/jtty/jtty_design.txt`, `jtty_design.md`,
  `jtty_source_encoding.txt` (design documents and golden vectors).
* WSJT-X 3.2.0-rc1 release notes; WSJT-X User Guide 3.2 (JTTY sections).
* DXZone, "JTTY Digital Mode: WSJT-X 3.2 Brings RTTY-Style Speed" (2026).
* jl1nie/mfsk-core `docs/notes/JTTY_UPSTREAM.md` and strijar/libjtty
  `PROTOCOL.md` — independent descriptions of the waveform parameters,
  consulted for the numeric parameters only.
* Official sample: `samples/JTTY/260807_134110.wav` (WSJT-X Help → Download
  samples).
