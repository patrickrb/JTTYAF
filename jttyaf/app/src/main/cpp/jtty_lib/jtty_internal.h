// jtty_internal.h — declarations shared between the jtty_lib translation
// units and the host test harness. Nothing here is part of the public API
// (jtty.h); the JNI layer must not include it.

#ifndef JTTYAF_JTTY_INTERNAL_H
#define JTTYAF_JTTY_INTERNAL_H

#include "jtty.h"

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ---------------------------------------------------------------------------
// Tables (jtty_spec.c)
// ---------------------------------------------------------------------------

// The 64-character TEXT5 alphabet, index 0 = '0', 36 = ' ', 63 = ';'.
extern const char JTTY_ALPHABET[65];

// ARRL/RAC section table, 0-based here; CLASS_SECTION's section7 is 1-based.
#define JTTY_NUM_SECTIONS 86
extern const char* const JTTY_ARRL_SECTIONS[JTTY_NUM_SECTIONS];

// MISC/CONTROL phrases 0..17.
#define JTTY_NUM_PHRASES 18
extern const char* const JTTY_CONTROL_PHRASES[JTTY_NUM_PHRASES];

// Two-letter US state / Canadian province tokens the RTTY Roundup profile
// packs as EXCH_LOC STATE_PROVINCE.
extern const char* const JTTY_STATE_PROVINCE[];
extern const int JTTY_NUM_STATE_PROVINCE;

// Alphabet lookups: index of an (already upper-cased) character, -1 if the
// character is not in the alphabet.
int  jtty_alphabet_index(char c);
char jtty_alphabet_char(int index);

// ---------------------------------------------------------------------------
// Channel-layer conventions (jtty_fec.c / jtty_crc.c)
// ---------------------------------------------------------------------------
//
// The bit-level conventions docs/jtty-protocol.md marked VERIFY were settled
// empirically against the official WSJT-X recording (SOURCES.md) and are
// fixed in the code:
//   * CRC-12: augmented division (M(x)*x^12 mod G) over exactly the 34 payload
//     bits, no zero padding.
//   * TBCC generators 1167/1545 are delay-indexed — bit j of the octal literal
//     taps the bit j symbols old, so the literal's LSB multiplies the newest bit.
//   * 1167's output is the first bit of each coded pair (Gray-mapped MSB).
//   * Information bit 1 (the MSB of the 46-bit word) enters the encoder first.

// Encode 46 information bits given in transmission order (u[0] first).
void jtty_tbcc_encode_bits(const uint8_t* u46, uint8_t* data_tones);

// ---------------------------------------------------------------------------
// Viterbi workspace (jtty_fec.c)
// ---------------------------------------------------------------------------

#define JTTY_VIT_STATES 512
#define JTTY_VIT_LIST   4        // list width per state
#define JTTY_VIT_PASSES 2        // circular (wrap-around) passes
#define JTTY_VIT_CANDS  8        // global hypotheses checked by CRC+grammar

typedef struct {
    float   metric[2][JTTY_VIT_STATES][JTTY_VIT_LIST];
    // Per step/state/rank: (predecessor high bit << 2) | predecessor rank.
    uint8_t pred[JTTY_DATA_SYMBOLS][JTTY_VIT_STATES][JTTY_VIT_LIST];
} jtty_viterbi_ws_t;

// Same as jtty_decode_symbols but with a caller-owned workspace (the receiver
// keeps one so the hot path never allocates).
bool jtty_decode_symbols_ws(jtty_viterbi_ws_t* ws,
                            const float llr[JTTY_DATA_SYMBOLS][JTTY_NUM_TONES],
                            uint64_t* payload34, int* list_rank);

// ---------------------------------------------------------------------------
// GFSK synthesis (jtty_modulate.c)
// ---------------------------------------------------------------------------

// Synthesise n_sym tones at f0 into re[] (and im[] when non-NULL, giving the
// analytic signal cos/sin). Guard-symbol extension at both ends, raised-cosine
// ramp over nsps/8 samples only when `ramp` is set. Returns samples written
// (n_sym * nsps) or a JTTY_ERR_*. pulse_ws must hold 3 * nsps floats.
int jtty_gfsk_synth(const uint8_t* tones, int n_sym, float f0_hz,
                    int sample_rate, float amplitude, bool ramp,
                    float* pulse_ws, float* re, float* im, int max_out);

// Samples per symbol at the given rate (0 when degenerate).
int jtty_samples_per_symbol(int sample_rate);

// ---------------------------------------------------------------------------
// Source coding helpers (jtty_source.c)
// ---------------------------------------------------------------------------

// Render the 32-bit source word (payload34 >> 2). Returns length or
// JTTY_ERR_INVALID. kind_out (optional) receives 1 for TEXT5, 0 otherwise.
int jtty_render_word(uint32_t word, char* out, int out_len, int* is_text5);

#ifdef __cplusplus
}
#endif

#endif // JTTYAF_JTTY_INTERNAL_H
