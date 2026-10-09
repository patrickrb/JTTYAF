# jtty_lib — specification sources and verification notes

This directory is a **clean-room** implementation of the JTTY digital mode
(WSJT-X 3.2's asynchronous 4-GFSK keyboard mode), written for the MIT-licensed
JTTYAF project. It was implemented from the protocol description in
`docs/jtty-protocol.md` — itself distilled from the WSJT-X *design documents*
and User Guide — and verified empirically. No GPL JTTY source (WSJT-X's
`lib/jtty/*.f90`, `genjtty`, `tbcc.f90`, strijar/libjtty, jl1nie/mfsk-core,
jtty-sidecar or any other implementation) was read, consulted or reproduced.

## Sources

1. **`docs/jtty-protocol.md`** (this repository). The only protocol source:
   physical layer (31.25 baud 4-GFSK, BT 2.0, h = 1, 13-symbol sync
   `0 2 2 3 0 0 3 2 1 3 1 2 0`, 59-symbol frame), channel coding (34-bit
   payload, CRC-12 poly 0x80F, K = 10 rate-1/2 tail-biting convolutional code
   with generators 1167/1545 octal, Gray map 00/01/11/10 -> 0/1/2/3), the
   32-bit source grammar with its ten golden vectors, the text-to-atoms
   rules, and the receiver design guide.

2. **kgoba/ft8_lib (MIT)** — two pieces of FT8 code were reused with
   attribution:
   * the GFSK synthesiser algorithm (`ft8af_glue/gfsk.c`, originally
     `demo/gen_ft8.c`): Gaussian frequency pulse, guard-symbol extension and
     raised-cosine ramp, re-parameterised for JTTY in `jtty_modulate.c`
     (phase increments evaluated per sample instead of a whole-transmission
     `dphi[]` array, double-precision phase, ramp only at the ends of a
     multi-frame transmission);
   * the standard-callsign branch of `pack28` / `unpack28`
     (`ft8/message.c`): JTTY's `call28` is exactly FT8's 28-bit standard
     callsign code (`jtty_call28.c`). Tokens, hashed calls and `/P`, `/R`
     forms are deliberately not supported because the JTTY grammar rejects
     them.

3. Public textbook material for everything else: Viterbi / parallel list
   Viterbi decoding, the wrap-around (WAVA) treatment of tail-biting codes,
   mixed-radix Cooley-Tukey FFT, the "two real FFTs in one complex FFT"
   trick, least-squares complex gain fitting, and the Cramér-Rao argument for
   matched-filter timing. All of `jtty_rx.c` is original signal-processing
   code written for this project.

## Design notes that are not in the protocol document

* **Coherent symbol blocks.** With h = 1 the carrier phase at every symbol
  boundary is the same modulo 2 pi whatever tones were sent (up to a small
  GFSK-tail term, <= 0.5 rad for a 3-tone jump at BT 2.0). The receiver
  therefore refers every per-symbol tone correlation to one carrier phase and
  sums them coherently over blocks of L = 1, 2, 4 data symbols (the "rungs"
  of §5); each rung yields metrics for the list Viterbi decoder and the first
  CRC+grammar-valid result wins.
* **Message rendering rule.** Structured atoms are joined with single spaces;
  a run of consecutive TEXT5 atoms is concatenated (TEXT5 carries its own
  spaces, the last atom of a run being space-padded) and trimmed as one unit.
  The tokenizer's dynamic programme works on token boundaries and only emits
  TEXT5 runs that are followed by a structured atom or the end of the
  message, so `render(pack(text)) == normalise(text)` for every input.
* **S/N estimate.** From the one-symbol DFT bins: tone-bin power minus the
  off-tone average, converted to A^2/2 over the noise in 2500 Hz. For clean
  signals the off-tone "noise" is GFSK transition leakage, so the estimate
  saturates near +16 dB.
* **Timing.** After a decode the frame is re-synthesised and a full-frame
  coherent matched filter refines the start to ~2 samples (sync-only
  refinement jitters ~10 samples at -10 dB). The same reference is then used
  for the complex-gain subtraction.

## Channel-layer conventions (docs/jtty-protocol.md "VERIFY" items)

The protocol document left four bit-level conventions open. They were settled
on 2026-10-08 by decoding the official WSJT-X recording
`samples/JTTY/260807_134110.wav` (16-bit 12 kHz mono, 30.2 s) with every one
of the 40 combinations of the alternatives (a temporary switch in the
library, since removed). With the CRC-12 and the grammar as filters, 39
combinations yielded **zero** frames and exactly one yielded the complete
message:

| Convention | Alternatives tried | Verified |
|---|---|---|
| CRC-12 zero padding before division | 0, 2, 6, 14 bits; non-augmented remainder | **0** — augmented CRC over exactly the 34 bits |
| Generator orientation | MSB of octal literal taps newest bit / LSB taps newest | **LSB taps newest** (literal bit *j* = tap at delay *j*) |
| First bit of each coded pair | 1167 / 1545 | **1167** |
| Information bit order | bit 1 (MSB) first / LSB first | **bit 1 first** |

The recording decodes as ten TEXT5 frames at 1506.9 Hz, one frame period
apart (3.135 s … 20.127 s), S/N −11.5 … −1.0 dB, every one at list rank 0 in
the first pass, EOM on the last:

```
RAN A|LL NI|GHT O|N BAN|D NOI|SE - |NO FA|LSE D|ECODE|S!
→ "RAN ALL NIGHT ON BAND NOISE - NO FALSE DECODES!"
```

`jttyaf_glue/test_jtty_sample.c` now asserts exactly that (frame count, text,
EOM placement, frequency and period consistency) whenever the recording is
available, so a regression in any convention fails CI.
