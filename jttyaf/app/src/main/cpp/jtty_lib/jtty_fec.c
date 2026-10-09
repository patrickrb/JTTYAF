// jtty_fec.c — tail-biting convolutional code (docs/jtty-protocol.md §2.3/§2.4)
// and its soft-decision list Viterbi decoder.
//
// Encoder: rate 1/2, K = 10 (512 states), generators 1167 and 1545 (octal).
// Tail-biting means the shift register is pre-loaded with the last nine
// information bits, so the trellis starts and ends in the same (unknown)
// state and no flush bits are spent: 46 information bits -> 46 coded pairs ->
// 46 Gray-mapped 4-ary symbols, one data symbol per information bit.
//
// Decoder: 512-state Viterbi with a per-state list of JTTY_VIT_LIST survivors
// (parallel list Viterbi) run circularly over the 46 symbols for
// JTTY_VIT_PASSES passes (the wrap-around, "WAVA", treatment of the unknown
// start state: the end-state metrics of one pass seed the next). After the
// last pass the globally best hypotheses are traced back and accepted only
// when they are tail-biting consistent (start state == end state), the CRC-12
// matches and the source word passes the §3 grammar.

#include "jtty_internal.h"

#include <math.h>
#include <string.h>

// ---------------------------------------------------------------------------
// Code definition
// ---------------------------------------------------------------------------
//
// The generator literals are delay-indexed: bit j of the octal literal is the
// tap on the information bit that is j symbols old (bit 0 = the newest bit),
// i.e. the LSB of the literal — not the MSB — multiplies the newest bit. That
// orientation, 1167's output as the first bit of each coded pair, and
// information bit 1 (the MSB of the 46-bit word) entering the encoder first
// are the conventions verified against the WSJT-X sample recording: they are
// the only combination of the §2.2/§2.3 VERIFY alternatives under which the
// recording decodes (SOURCES.md).

#define GEN_A 01167u   // 1001110111 — first bit of each coded pair
#define GEN_B 01545u   // 1101100101 — second bit

static uint8_t g_tone_tab[1024];       // 10-bit encoder window -> tone
static bool    g_tone_tab_valid = false;

static int parity32(uint32_t v)
{
    v ^= v >> 16;
    v ^= v >> 8;
    v ^= v >> 4;
    v ^= v >> 2;
    v ^= v >> 1;
    return (int)(v & 1u);
}

// The encoder window w has bit j = information bit (i - j): bit 0 is the
// newest bit, bit 9 the oldest. A generator mask in the same delay-indexed
// form makes the coded bit parity(w & mask).
static void build_tone_table(void)
{
    for (uint32_t w = 0; w < 1024; ++w) {
        int a = parity32(w & GEN_A);
        int b = parity32(w & GEN_B);
        // §2.4 Gray map: 00->0, 01->1, 11->2, 10->3.
        g_tone_tab[w] = (uint8_t)(2 * a + (a ^ b));
    }
    g_tone_tab_valid = true;
}

static const uint8_t* tone_table(void)
{
    if (!g_tone_tab_valid) build_tone_table();
    return g_tone_tab;
}

// ---------------------------------------------------------------------------
// Encoder
// ---------------------------------------------------------------------------

void jtty_tbcc_encode_bits(const uint8_t* u, uint8_t* data_tones)
{
    if (u == NULL || data_tones == NULL) return;
    const uint8_t* tt = tone_table();
    for (int i = 0; i < JTTY_INFO_BITS; ++i) {
        uint32_t w = 0;
        for (int j = 0; j < 10; ++j) {
            int idx = i - j;
            if (idx < 0) idx += JTTY_INFO_BITS;  // tail-biting pre-load
            w |= (uint32_t)(u[idx] & 1u) << j;
        }
        data_tones[i] = tt[w];
    }
}

void jtty_tbcc_encode(uint64_t info46, uint8_t* data_tones)
{
    if (data_tones == NULL) return;
    uint8_t u[JTTY_INFO_BITS];
    // Bit 1 (the MSB) is transmitted first.
    for (int i = 0; i < JTTY_INFO_BITS; ++i)
        u[i] = (uint8_t)((info46 >> (JTTY_INFO_BITS - 1 - i)) & 1u);
    jtty_tbcc_encode_bits(u, data_tones);
}

void jtty_encode_frame(uint64_t payload34, uint8_t* tones)
{
    if (tones == NULL) return;
    memcpy(tones, JTTY_SYNC, JTTY_SYNC_SYMBOLS);
    jtty_tbcc_encode(jtty_add_crc(payload34), tones + JTTY_SYNC_SYMBOLS);
}

// ---------------------------------------------------------------------------
// List Viterbi decoder
// ---------------------------------------------------------------------------

#define NEG_INF (-1e30f)

// Merge two descending candidate lists (a[] + ba, b[] + bb) into the best L.
// pred codes (hi << 2) | rank, where hi says the predecessor state had its
// top bit set.
static inline void merge_lists(const float* a, float ba, const float* b, float bb,
                               float* out, uint8_t* pred)
{
    int ia = 0, ib = 0;
    for (int r = 0; r < JTTY_VIT_LIST; ++r) {
        float va = a[ia] + ba;
        float vb = b[ib] + bb;
        if (va >= vb) {
            out[r] = va;
            pred[r] = (uint8_t)ia;
            ++ia;
        } else {
            out[r] = vb;
            pred[r] = (uint8_t)(4 | ib);
            ++ib;
        }
    }
}

static bool crc_and_grammar(uint64_t info46, uint64_t* payload_out)
{
    uint64_t payload = (info46 >> JTTY_CRC_BITS) & ((1ull << JTTY_PAYLOAD_BITS) - 1u);
    uint16_t crc = (uint16_t)(info46 & 0xFFFu);
    if (jtty_crc12(payload) != crc) return false;
    if (!jtty_payload_valid(payload)) return false;
    *payload_out = payload;
    return true;
}

bool jtty_decode_symbols_ws(jtty_viterbi_ws_t* ws,
                            const float llr[JTTY_DATA_SYMBOLS][JTTY_NUM_TONES],
                            uint64_t* payload34, int* list_rank)
{
    if (ws == NULL || llr == NULL || payload34 == NULL) return false;
    const uint8_t* tt = tone_table();
    const int L = JTTY_VIT_LIST;

    float (*cur)[JTTY_VIT_LIST] = ws->metric[0];
    float (*nxt)[JTTY_VIT_LIST] = ws->metric[1];

    // Every start state is equally likely in the first pass.
    for (int s = 0; s < JTTY_VIT_STATES; ++s) {
        cur[s][0] = 0.0f;
        for (int r = 1; r < L; ++r) cur[s][r] = NEG_INF;
    }

    for (int pass = 0; pass < JTTY_VIT_PASSES; ++pass) {
        for (int i = 0; i < JTTY_DATA_SYMBOLS; ++i) {
            const float* bm = llr[i];
            uint8_t (*pred)[JTTY_VIT_LIST] = ws->pred[i];
            for (int ns = 0; ns < JTTY_VIT_STATES; ++ns) {
                // New state ns = (old state << 1 | u) & 0x1FF, so the two
                // predecessors are ns >> 1 and (ns >> 1) | 0x100, and the
                // 10-bit encoder window is simply ns (old top bit 0) or
                // ns | 0x200 (old top bit 1).
                int p0 = ns >> 1;
                int p1 = p0 | 0x100;
                float b0 = bm[tt[ns]];
                float b1 = bm[tt[ns | 0x200]];
                merge_lists(cur[p0], b0, cur[p1], b1, nxt[ns], pred[ns]);
            }
            float (*t)[JTTY_VIT_LIST] = cur;
            cur = nxt;
            nxt = t;
        }
        if (pass + 1 < JTTY_VIT_PASSES) {
            // Wrap around: the best metric reached at each end state seeds
            // the same start state of the next pass. Normalise so the
            // metrics stay well inside float range; only rank 0 is carried
            // because the next pass's own list builds the alternatives.
            float best = NEG_INF;
            for (int s = 0; s < JTTY_VIT_STATES; ++s)
                if (cur[s][0] > best) best = cur[s][0];
            for (int s = 0; s < JTTY_VIT_STATES; ++s) {
                cur[s][0] -= best;
                for (int r = 1; r < L; ++r) cur[s][r] = NEG_INF;
            }
        }
    }

    // Collect the globally best (state, rank) hypotheses of the final pass.
    int   cand_s[JTTY_VIT_CANDS], cand_r[JTTY_VIT_CANDS];
    float cand_m[JTTY_VIT_CANDS];
    int   ncand = 0;
    for (int s = 0; s < JTTY_VIT_STATES; ++s) {
        for (int r = 0; r < L; ++r) {
            float m = cur[s][r];
            if (m <= NEG_INF / 2) break;
            if (ncand == JTTY_VIT_CANDS && m <= cand_m[ncand - 1]) break;
            int k = ncand < JTTY_VIT_CANDS ? ncand : JTTY_VIT_CANDS - 1;
            while (k > 0 && cand_m[k - 1] < m) {
                cand_m[k] = cand_m[k - 1];
                cand_s[k] = cand_s[k - 1];
                cand_r[k] = cand_r[k - 1];
                --k;
            }
            cand_m[k] = m;
            cand_s[k] = s;
            cand_r[k] = r;
            if (ncand < JTTY_VIT_CANDS) ++ncand;
        }
    }

    for (int c = 0; c < ncand; ++c) {
        int state = cand_s[c];
        int rank = cand_r[c];
        uint8_t u[JTTY_INFO_BITS];
        for (int i = JTTY_DATA_SYMBOLS - 1; i >= 0; --i) {
            u[i] = (uint8_t)(state & 1);
            uint8_t p = ws->pred[i][state][rank];
            rank = p & 3;
            state = (state >> 1) | ((p & 4) ? 0x100 : 0);
        }
        // Tail-biting: the path must start where it ended.
        if (state != cand_s[c]) continue;

        uint64_t info = 0;
        for (int i = 0; i < JTTY_INFO_BITS; ++i)
            if (u[i]) info |= 1ull << (JTTY_INFO_BITS - 1 - i);
        uint64_t payload;
        if (crc_and_grammar(info, &payload)) {
            *payload34 = payload;
            if (list_rank) *list_rank = c;
            return true;
        }
    }
    return false;
}

bool jtty_decode_symbols(const float llr[JTTY_DATA_SYMBOLS][JTTY_NUM_TONES],
                         uint64_t* payload34, int* list_rank)
{
    // The public entry point has no context object; like everything else in
    // the library it is not thread-safe, so one static workspace suffices.
    static jtty_viterbi_ws_t ws;
    return jtty_decode_symbols_ws(&ws, llr, payload34, list_rank);
}
