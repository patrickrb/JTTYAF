// jtty.h — public API of the JTTYAF clean-room JTTY codec.
//
// JTTY (WSJT-X 3.2) is an asynchronous 4-GFSK keyboard mode: 1.888 s frames of
// 13 sync + 46 data symbols at 31.25 baud, each carrying a 34-bit payload
// (32-bit source word + reserved bit + EOM) protected by CRC-12 and a K=10
// tail-biting convolutional code. See docs/jtty-protocol.md for the protocol
// description this library is written from.
//
// The library is plain C11 with no Android dependency so the same sources
// build for the host test harness (jttyaf_glue/run_jtty_host_tests.*) and for
// libjttyaf.so. Nothing in here is thread-safe; the JNI/Kotlin layer serializes
// access to each receiver handle.

#ifndef JTTYAF_JTTY_H
#define JTTYAF_JTTY_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ---------------------------------------------------------------------------
// Protocol constants
// ---------------------------------------------------------------------------

#define JTTY_SAMPLE_RATE      12000   // reference rate for the receiver
#define JTTY_NSPS             384     // samples per symbol at 12 kHz (31.25 baud)
#define JTTY_BAUD             31.25f
#define JTTY_TONE_SPACING_HZ  31.25f
#define JTTY_SYMBOL_PERIOD_S  0.032f
#define JTTY_SYMBOL_BT        2.0f
#define JTTY_NUM_TONES        4
#define JTTY_SYNC_SYMBOLS     13
#define JTTY_DATA_SYMBOLS     46
#define JTTY_FRAME_SYMBOLS    59      // sync + data
#define JTTY_FRAME_SAMPLES    (JTTY_FRAME_SYMBOLS * JTTY_NSPS)  // 22656 @ 12 kHz
#define JTTY_FRAME_SECONDS    1.888f
#define JTTY_PAYLOAD_BITS     34
#define JTTY_CRC_BITS         12
#define JTTY_INFO_BITS        46
#define JTTY_CODED_BITS       92
#define JTTY_MAX_FRAMES       16      // frames per message
#define JTTY_MAX_TEXT         80      // characters per message (rejected beyond)
#define JTTY_MAX_RENDER       96      // bytes needed to render one message (+NUL)
#define JTTY_ATOM_RENDER      32      // bytes needed to render one atom (+NUL)

// The 13-symbol sync sequence, transmitted first in every frame.
extern const uint8_t JTTY_SYNC[JTTY_SYNC_SYMBOLS];

// Packing profiles: steer how ambiguous contest text is tokenised.
typedef enum {
    JTTY_PROFILE_GENERIC = 0,     // no contest assumptions
    JTTY_PROFILE_RTTY_ROUNDUP = 1,// "599 <n>" -> SERIAL (>=3 digits), state/province tokens
    JTTY_PROFILE_FIELD_DAY = 2,   // "<count><class> <section>" exchanges
} jtty_profile_t;

// Error codes (negative returns).
enum {
    JTTY_OK = 0,
    JTTY_ERR_BAD_ARGS = -1,
    JTTY_ERR_TOO_LONG = -2,       // text > JTTY_MAX_TEXT characters
    JTTY_ERR_INVALID = -3,        // payload fails the grammar / validity rules
    JTTY_ERR_NOMEM = -4,
    JTTY_ERR_EMPTY = -5,          // nothing to send
};

// ---------------------------------------------------------------------------
// Source coding — text <-> 34-bit payloads
// ---------------------------------------------------------------------------

// Pack message text into 34-bit payloads (one per frame, LSB-aligned in the
// uint64_t: bit 34 (EOM) is bit 0 of the value, bit 1 (MSB of the source word)
// is bit 33). EOM is set only on the last payload. Returns the number of
// frames (1..JTTY_MAX_FRAMES), JTTY_ERR_EMPTY for blank text, JTTY_ERR_TOO_LONG
// for >80 characters, JTTY_ERR_BAD_ARGS when max_frames < JTTY_MAX_FRAMES.
int jtty_pack_text(const char* text, jtty_profile_t profile,
                   uint64_t* payloads, int max_frames);

// Render one payload's atom as text (e.g. "CQ K1ABC CQ", "599 001", "HELLO").
// Trailing TEXT5 padding spaces are kept so consecutive TEXT5 atoms concatenate
// correctly; callers trim the assembled message. Writes at most out_len bytes
// (NUL-terminated). Returns the rendered length, or JTTY_ERR_INVALID when the
// payload breaks any validity rule (reserved bit, unassigned code, range).
int jtty_unpack_payload(uint64_t payload34, char* out, int out_len, bool* eom);

// Validity check only (reserved bit, grammar, ranges, non-zero word).
bool jtty_payload_valid(uint64_t payload34);

// Assemble the text of a complete message from its frames' rendered atoms,
// joining structured atoms with single spaces and concatenating TEXT5 runs,
// then trimming. Returns the length written.
int jtty_render_message(const uint64_t* payloads, int n_frames,
                        char* out, int out_len);

// Pack / unpack a standard callsign as the FT8 call28 code. Returns false for
// non-standard calls.
bool jtty_pack_call28(const char* callsign, uint32_t* call28);
bool jtty_unpack_call28(uint32_t call28, char* out, int out_len);

// ---------------------------------------------------------------------------
// Channel coding — payload <-> symbols
// ---------------------------------------------------------------------------

// CRC-12 (poly 0x80F, init 0) over the 34 payload bits -> 12-bit remainder.
uint16_t jtty_crc12(uint64_t payload34);

// Append the CRC: 34 payload bits -> 46 information bits (LSB-aligned).
uint64_t jtty_add_crc(uint64_t payload34);

// Tail-biting convolutional encode: 46 info bits -> 92 coded bits, written as
// 46 Gray-mapped tones (0..3) into data_tones[JTTY_DATA_SYMBOLS].
void jtty_tbcc_encode(uint64_t info46, uint8_t* data_tones);

// Full frame: sync + encoded data, 59 tones into tones[JTTY_FRAME_SYMBOLS].
void jtty_encode_frame(uint64_t payload34, uint8_t* tones);

// Soft-decision decode of one frame's 46 data symbols. llr[i][k] is the
// log-likelihood (or correlation magnitude) of tone k at data symbol i.
// Runs the list Viterbi + wrap-around passes, checks CRC-12 and the grammar,
// and writes the accepted payload. Returns true on success.
bool jtty_decode_symbols(const float llr[JTTY_DATA_SYMBOLS][JTTY_NUM_TONES],
                         uint64_t* payload34, int* list_rank);

// ---------------------------------------------------------------------------
// Modulation — tones -> audio
// ---------------------------------------------------------------------------

// Samples produced by jtty_modulate for n_frames at sample_rate
// (n_frames * 59 * round(sample_rate / 31.25)); 0 for degenerate input.
int jtty_waveform_samples(int n_frames, int sample_rate);

// Synthesise the GFSK waveform of n_frames consecutive frames (59 tones each,
// back-to-back, continuous phase, amplitude ramp only at the ends) with the
// lowest tone at f0_hz. Peak amplitude `amplitude` (0..1). Writes
// jtty_waveform_samples() floats into out (must hold at least that many).
// Returns the number of samples written or a JTTY_ERR_*.
int jtty_modulate(const uint8_t* tones, int n_frames, float f0_hz,
                  int sample_rate, float amplitude, float* out, int max_out);

// Convenience: text -> payloads -> frames -> audio in one call.
// Returns samples written, or a JTTY_ERR_* (JTTY_ERR_TOO_LONG etc.).
int jtty_encode_text(const char* text, jtty_profile_t profile, float f0_hz,
                     int sample_rate, float amplitude, float* out, int max_out,
                     int* n_frames_out);

// ---------------------------------------------------------------------------
// Receiver — streaming, push model
// ---------------------------------------------------------------------------

typedef struct jtty_rx jtty_rx_t;

typedef struct {
    uint64_t payload34;      // accepted payload (valid grammar, CRC ok)
    bool     eom;            // end-of-message flag
    char     text[JTTY_ATOM_RENDER]; // rendered atom
    float    freq_hz;        // lowest-tone frequency estimate
    int64_t  start_sample;   // frame start, in samples since jtty_rx_create/reset
    float    dt_s;           // sub-sample timing refinement (seconds, +/-)
    float    snr_db;         // S/N in 2500 Hz reference bandwidth
    float    sync_score;     // sync correlation quality (0..1)
    int      list_rank;      // which list hypothesis was accepted (0 = best)
    int      pass;           // subtraction pass that found it (0 = first)
} jtty_decode_t;

// Create a receiver for 12 kHz mono float audio. Returns NULL on bad rate/OOM.
jtty_rx_t* jtty_rx_create(int sample_rate);
void       jtty_rx_destroy(jtty_rx_t* rx);

// Forget all buffered audio and candidates (keeps the configuration).
void jtty_rx_reset(jtty_rx_t* rx);

// Lowest-tone search band for the wide-band ("everyone in the passband")
// search. Default 300..2700 Hz.
void jtty_rx_set_search_band(jtty_rx_t* rx, float f_lo_hz, float f_hi_hz);

// Priority channel: the operator's RX offset. Candidates within +/-tol_hz of
// it are searched first and with the looser WSJT-X "channel 0" gates. tol_hz
// <= 0 disables the priority channel.
void jtty_rx_set_rx_frequency(jtty_rx_t* rx, float f_hz, float tol_hz);

// Cap the number of candidate decode attempts per analysis step so a slow
// phone keeps up with real time (0 = library default).
void jtty_rx_set_decode_budget(jtty_rx_t* rx, int max_candidates_per_step);

// Enable/disable signal subtraction passes (default on).
void jtty_rx_set_subtraction(jtty_rx_t* rx, bool enabled);

// Feed audio (any chunk size). Returns the number of newly accepted frames now
// waiting in the output queue (>= 0), or JTTY_ERR_BAD_ARGS.
int jtty_rx_push(jtty_rx_t* rx, const float* samples, int n);

// Process whatever is buffered at end of stream (tests / file decode).
int jtty_rx_flush(jtty_rx_t* rx);

// Pop one accepted frame; false when the queue is empty. Frames are delivered
// in time order of their start_sample.
bool jtty_rx_pop(jtty_rx_t* rx, jtty_decode_t* out);

// Total samples consumed so far (for mapping start_sample to wall-clock time).
int64_t jtty_rx_samples_consumed(const jtty_rx_t* rx);

#ifdef __cplusplus
}
#endif

#endif // JTTYAF_JTTY_H
