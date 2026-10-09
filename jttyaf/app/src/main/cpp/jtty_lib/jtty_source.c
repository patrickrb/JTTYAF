// jtty_source.c — the 32-bit JTTY source word (docs/jtty-protocol.md §3).
//
// Bit numbering follows the design notes: bit 1 is the MSB of the 32-bit word
// and the top-level type i2 sits in bits 31-32 (the two LSBs), so the word is
// [30-bit form][i2]. The 34-bit payload adds the reserved bit (33, must be 0)
// and EOM (34) below the word: payload34 = word << 2 | reserved << 1 | eom.
//
// Rendering and validation are one routine (jtty_render_word) so the receiver
// can never display something the grammar would not have accepted.
//
// Message text and atoms: a received message is rendered by joining
// structured atoms with single spaces, while a run of consecutive TEXT5
// atoms is concatenated (TEXT5 carries its own spaces, the last atom of a
// run being space-padded) and trimmed as one unit. The transmit tokenizer
// below produces exactly that structure, so render(pack(text)) == text for
// every normalised text.

#include "jtty_internal.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>

// ---------------------------------------------------------------------------
// Field layout helpers
// ---------------------------------------------------------------------------

enum { FAM_EXCH_NUM = 0, FAM_EXCH_LOC = 1, FAM_EXCH_PAIR = 2, FAM_EXCH_NUM_TIME = 3, FAM_MISC = 4 };
enum { NUM_SERIAL = 0, NUM_CQ_ZONE, NUM_ITU_ZONE, NUM_AGE, NUM_POWER, NUM_CHECK,
       NUM_FIRST_LICENSE_YEAR, NUM_GENERIC };
enum { LOC_STATE = 0, LOC_SECTION = 1, LOC_COUNTRY_PREFIX = 2, LOC_GENERIC_QTH = 3,
       LOC_LOCAL_ADMIN = 4 };
enum { PAIR_ZONE_LOC3 = 0, PAIR_CLASS_SECTION = 1 };
enum { MISC_CONTROL = 0, MISC_GRID4 = 1 };

#define VALUE17_MAX 131071u
#define SERIAL14_MAX 16383u
#define GRID4_MAX 32399u
#define TEXT5_SPACE 36

static uint32_t make_call_word(uint32_t call28, unsigned n2, unsigned i2)
{
    return (call28 << 4) | (n2 << 2) | i2;
}
static uint32_t make_struct_word(unsigned family, uint32_t body27)
{
    return (body27 << 5) | (family << 2) | 2u;
}
static uint32_t body_exch_num(unsigned role, unsigned kind, uint32_t value17)
{
    return (role << 26) | (kind << 22) | (value17 << 5);
}
static uint32_t body_exch_loc(unsigned role, unsigned kind, unsigned len1, uint32_t token16)
{
    return (role << 26) | (kind << 22) | (len1 << 21) | (token16 << 5);
}
static uint32_t body_exch_pair(unsigned schema, uint32_t pair23)
{
    return (schema << 24) | (pair23 << 1);
}
static uint32_t pair_zone_loc3(unsigned zone6, unsigned len1, uint32_t token16)
{
    return (zone6 << 17) | (len1 << 16) | token16;
}
static uint32_t pair_class_section(unsigned count6, unsigned class3, unsigned section7)
{
    return (count6 << 17) | (class3 << 14) | (section7 << 7);
}
static uint32_t body_num_time(unsigned role, unsigned serial14, unsigned minute11)
{
    return (role << 26) | (serial14 << 12) | (minute11 << 1);
}
static uint32_t body_misc(unsigned subtype, uint32_t data23)
{
    return (subtype << 23) | data23;
}
static uint32_t misc_control(unsigned phrase7) { return phrase7 << 16; }
static uint32_t misc_grid4(unsigned role, uint32_t code15) { return (role << 22) | (code15 << 7); }

// Base-36 location token (EXCH_LOC / ZONE_LOC3): 2 characters when len1 == 0
// (token < 36^2), 3 when len1 == 1 (36^2 <= token < 36^3).
static bool token_decode(unsigned len1, uint32_t token, char* out /* >= 4 */)
{
    int n = len1 ? 3 : 2;
    if (len1 == 0 && token >= 1296u) return false;
    if (len1 == 1 && (token < 1296u || token >= 46656u)) return false;
    for (int i = n - 1; i >= 0; --i) {
        out[i] = JTTY_ALPHABET[token % 36u];  // alphabet[0..35] is 0-9A-Z
        token /= 36u;
    }
    out[n] = '\0';
    return true;
}

static bool token_encode(const char* s, int len, unsigned* len1, uint32_t* token)
{
    if (len < 2 || len > 3) return false;
    uint32_t t = 0;
    for (int i = 0; i < len; ++i) {
        int idx = jtty_alphabet_index(s[i]);
        if (idx < 0 || idx > 35) return false;
        t = t * 36u + (uint32_t)idx;
    }
    *len1 = (len == 3) ? 1u : 0u;
    if (len == 3 && t < 1296u) return false;  // "0AB" would decode as 2 chars
    *token = t;
    return true;
}

// ---------------------------------------------------------------------------
// Rendering + validation
// ---------------------------------------------------------------------------

// snprintf-style append that tracks a hard failure on overflow.
static int put(char* out, int out_len, int pos, const char* s)
{
    int n = (int)strlen(s);
    if (pos < 0 || pos + n >= out_len) return -1;
    memcpy(out + pos, s, (size_t)n + 1);
    return pos + n;
}

static int render_struct(uint32_t word, char* out, int out_len)
{
    unsigned family = (word >> 2) & 7u;
    uint32_t body = word >> 5;  // 27 bits
    char tmp[JTTY_ATOM_RENDER];
    int pos = 0;
    out[0] = '\0';

    switch (family) {
    case FAM_EXCH_NUM: {
        unsigned role = (body >> 26) & 1u, kind = (body >> 22) & 15u;
        uint32_t value = (body >> 5) & VALUE17_MAX;
        if ((body & 31u) != 0 || kind > NUM_GENERIC) return JTTY_ERR_INVALID;
        if (kind == NUM_FIRST_LICENSE_YEAR && value > 9999u) return JTTY_ERR_INVALID;
        if (role) pos = put(out, out_len, pos, "599 ");
        snprintf(tmp, sizeof tmp, kind == NUM_SERIAL ? "%03u" : "%u", value);
        return put(out, out_len, pos, tmp);
    }
    case FAM_EXCH_LOC: {
        unsigned role = (body >> 26) & 1u, kind = (body >> 22) & 15u, len1 = (body >> 21) & 1u;
        uint32_t token = (body >> 5) & 0xFFFFu;
        if ((body & 31u) != 0 || kind > LOC_LOCAL_ADMIN) return JTTY_ERR_INVALID;
        if (!token_decode(len1, token, tmp)) return JTTY_ERR_INVALID;
        if (role) pos = put(out, out_len, pos, "599 ");
        return put(out, out_len, pos, tmp);
    }
    case FAM_EXCH_PAIR: {
        unsigned schema = (body >> 24) & 7u;
        uint32_t pair = (body >> 1) & 0x7FFFFFu;
        if ((body & 1u) != 0) return JTTY_ERR_INVALID;
        if (schema == PAIR_ZONE_LOC3) {
            unsigned zone = (pair >> 17) & 63u, len1 = (pair >> 16) & 1u;
            uint32_t token = pair & 0xFFFFu;
            char tok[4];
            if (zone < 1 || zone > 40) return JTTY_ERR_INVALID;
            if (!token_decode(len1, token, tok)) return JTTY_ERR_INVALID;
            snprintf(tmp, sizeof tmp, "%02u %s", zone, tok);
            return put(out, out_len, pos, tmp);
        }
        if (schema == PAIR_CLASS_SECTION) {
            unsigned count = (pair >> 17) & 63u, cls = (pair >> 14) & 7u, section = (pair >> 7) & 127u;
            if ((pair & 127u) != 0) return JTTY_ERR_INVALID;
            if (count < 1 || count > 32 || cls > 5) return JTTY_ERR_INVALID;
            if (section < 1 || section > JTTY_NUM_SECTIONS) return JTTY_ERR_INVALID;
            snprintf(tmp, sizeof tmp, "%u%c %s", count, (char)('A' + cls),
                     JTTY_ARRL_SECTIONS[section - 1]);
            return put(out, out_len, pos, tmp);
        }
        return JTTY_ERR_INVALID;
    }
    case FAM_EXCH_NUM_TIME: {
        unsigned role = (body >> 26) & 1u, serial = (body >> 12) & SERIAL14_MAX, minute = (body >> 1) & 0x7FFu;
        if ((body & 1u) != 0 || minute > 1439u) return JTTY_ERR_INVALID;
        if (role) pos = put(out, out_len, pos, "599 ");
        snprintf(tmp, sizeof tmp, "%03u %02u%02u", serial, minute / 60u, minute % 60u);
        return put(out, out_len, pos, tmp);
    }
    case FAM_MISC: {
        unsigned subtype = (body >> 23) & 15u;
        uint32_t data = body & 0x7FFFFFu;
        if (subtype == MISC_CONTROL) {
            unsigned phrase = (data >> 16) & 127u;
            if ((data & 0xFFFFu) != 0 || phrase >= JTTY_NUM_PHRASES) return JTTY_ERR_INVALID;
            return put(out, out_len, pos, JTTY_CONTROL_PHRASES[phrase]);
        }
        if (subtype == MISC_GRID4) {
            unsigned role = (data >> 22) & 1u;
            uint32_t code = (data >> 7) & 0x7FFFu;
            if ((data & 127u) != 0 || code > GRID4_MAX) return JTTY_ERR_INVALID;
            if (role) pos = put(out, out_len, pos, "599 ");
            tmp[0] = (char)('A' + code / 1800u);
            tmp[1] = (char)('A' + (code / 100u) % 18u);
            tmp[2] = (char)('0' + (code / 10u) % 10u);
            tmp[3] = (char)('0' + code % 10u);
            tmp[4] = '\0';
            return put(out, out_len, pos, tmp);
        }
        return JTTY_ERR_INVALID;
    }
    default:
        return JTTY_ERR_INVALID;
    }
}

int jtty_render_word(uint32_t word, char* out, int out_len, int* is_text5)
{
    if (out == NULL || out_len < JTTY_ATOM_RENDER) return JTTY_ERR_BAD_ARGS;
    out[0] = '\0';
    if (is_text5) *is_text5 = 0;
    if (word == 0) return JTTY_ERR_INVALID;  // the all-zero word is never valid

    unsigned i2 = word & 3u;
    if (i2 == 0 || i2 == 1) {
        unsigned n2 = (word >> 2) & 3u;
        uint32_t call28 = word >> 4;
        char call[8];
        if (!jtty_unpack_call28(call28, call, sizeof call)) return JTTY_ERR_INVALID;
        char tmp[JTTY_ATOM_RENDER];
        if (i2 == 0) {
            static const char* const fmt[4] = { "CQ %s CQ", "%s", "TU %s CQ", "%s TU" };
            snprintf(tmp, sizeof tmp, fmt[n2], call);
        } else {
            if (n2 == 0) snprintf(tmp, sizeof tmp, "%s AGN?", call);
            else if (n2 == 1) snprintf(tmp, sizeof tmp, "TU NOW %s", call);
            else return JTTY_ERR_INVALID;  // 1.2 / 1.3 reserved
        }
        return put(out, out_len, 0, tmp);
    }
    if (i2 == 2) return render_struct(word, out, out_len);

    // TEXT5: five 6-bit characters, first in bits 1-6.
    uint32_t form = word >> 2;
    for (int k = 0; k < 5; ++k)
        out[k] = jtty_alphabet_char((int)((form >> (24 - 6 * k)) & 63u));
    out[5] = '\0';
    if (is_text5) *is_text5 = 1;
    return 5;
}

int jtty_unpack_payload(uint64_t payload34, char* out, int out_len, bool* eom)
{
    if (out == NULL || out_len < JTTY_ATOM_RENDER) return JTTY_ERR_BAD_ARGS;
    out[0] = '\0';
    if (eom) *eom = (payload34 & 1u) != 0;
    if (payload34 >> JTTY_PAYLOAD_BITS) return JTTY_ERR_INVALID;
    if ((payload34 >> 1) & 1u) return JTTY_ERR_INVALID;  // reserved bit 33
    return jtty_render_word((uint32_t)(payload34 >> 2), out, out_len, NULL);
}

bool jtty_payload_valid(uint64_t payload34)
{
    char tmp[JTTY_ATOM_RENDER];
    return jtty_unpack_payload(payload34, tmp, sizeof tmp, NULL) >= 0;
}

int jtty_render_message(const uint64_t* payloads, int n_frames, char* out, int out_len)
{
    if (out == NULL || out_len < 1) return JTTY_ERR_BAD_ARGS;
    out[0] = '\0';
    if (payloads == NULL || n_frames <= 0) return 0;

    // Build the string, treating each TEXT5 run as one unit.
    char run[JTTY_MAX_RENDER + 8 * JTTY_MAX_FRAMES];
    int run_len = 0;
    int pos = 0;
    bool in_run = false;

    for (int f = 0; f <= n_frames; ++f) {
        char atom[JTTY_ATOM_RENDER];
        int is_text = 0, len = -1;
        if (f < n_frames) {
            if ((payloads[f] >> 1) & 1u) continue;  // reserved bit set: drop
            len = jtty_render_word((uint32_t)(payloads[f] >> 2), atom, sizeof atom, &is_text);
            if (len < 0) continue;  // invalid atoms are not displayed
        }
        if (f < n_frames && is_text) {
            if (run_len + len < (int)sizeof run) {
                memcpy(run + run_len, atom, (size_t)len);
                run_len += len;
            }
            in_run = true;
            continue;
        }
        // Flush a pending TEXT5 run (trimmed) as one unit.
        if (in_run) {
            int a = 0, b = run_len;
            while (a < b && run[a] == ' ') ++a;
            while (b > a && run[b - 1] == ' ') --b;
            if (b > a) {
                if (pos > 0 && pos + 1 < out_len) out[pos++] = ' ';
                int n = b - a;
                if (pos + n >= out_len) n = out_len - 1 - pos;
                if (n > 0) { memcpy(out + pos, run + a, (size_t)n); pos += n; }
            }
            run_len = 0;
            in_run = false;
        }
        if (f < n_frames) {
            if (pos > 0 && pos + 1 < out_len) out[pos++] = ' ';
            int n = len;
            if (pos + n >= out_len) n = out_len - 1 - pos;
            if (n > 0) { memcpy(out + pos, atom, (size_t)n); pos += n; }
        }
    }
    out[pos] = '\0';
    // Final trim (a structured atom never has outer spaces, but be safe).
    int a = 0;
    while (a < pos && out[a] == ' ') ++a;
    while (pos > a && out[pos - 1] == ' ') --pos;
    if (a > 0) memmove(out, out + a, (size_t)(pos - a));
    pos -= a;
    out[pos] = '\0';
    return pos;
}

// ---------------------------------------------------------------------------
// Text -> atoms (§3.5)
// ---------------------------------------------------------------------------

#define MAX_TOKENS (JTTY_MAX_TEXT / 2 + 1)
#define MAX_CANDS 24
#define COST_INF 1000

typedef struct {
    const char* s;
    int len;
} tok_t;

// Normalise: upper-case, whitespace -> single spaces, unsupported -> '#',
// trimmed. Returns the length, JTTY_ERR_TOO_LONG, or JTTY_ERR_EMPTY.
static int normalise(const char* text, char* out, int cap)
{
    int n = 0;
    bool pending_space = false;
    for (const char* p = text; *p; ++p) {
        unsigned char uc = (unsigned char)*p;
        char c;
        if (uc == ' ' || uc == '\t' || uc == '\n' || uc == '\r') {
            if (n > 0) pending_space = true;
            continue;
        }
        if (uc >= 0x80) c = '#';
        else {
            c = (char)toupper(uc);
            if (jtty_alphabet_index(c) < 0) c = '#';
        }
        if (pending_space) {
            if (n + 1 >= cap) return JTTY_ERR_TOO_LONG;
            out[n++] = ' ';
            pending_space = false;
        }
        if (n + 1 >= cap) return JTTY_ERR_TOO_LONG;
        out[n++] = c;
    }
    out[n] = '\0';
    if (n == 0) return JTTY_ERR_EMPTY;
    return n;
}

static bool tok_eq(const tok_t* t, const char* lit)
{
    return (int)strlen(lit) == t->len && memcmp(t->s, lit, (size_t)t->len) == 0;
}

static bool parse_uint(const tok_t* t, uint32_t* v)
{
    if (t->len < 1 || t->len > 9) return false;
    uint32_t x = 0;
    for (int i = 0; i < t->len; ++i) {
        if (t->s[i] < '0' || t->s[i] > '9') return false;
        x = x * 10u + (uint32_t)(t->s[i] - '0');
    }
    *v = x;
    return true;
}

static bool tok_call28(const tok_t* t, uint32_t* call28)
{
    if (t->len < 3 || t->len > 6) return false;
    char tmp[8];
    memcpy(tmp, t->s, (size_t)t->len);
    tmp[t->len] = '\0';
    return jtty_pack_call28(tmp, call28);
}

static bool is_alnum_tok(const tok_t* t)
{
    for (int i = 0; i < t->len; ++i) {
        int idx = jtty_alphabet_index(t->s[i]);
        if (idx < 0 || idx > 35) return false;
    }
    return t->len > 0;
}

static int section_index(const tok_t* t)
{
    for (int i = 0; i < JTTY_NUM_SECTIONS; ++i)
        if (tok_eq(t, JTTY_ARRL_SECTIONS[i])) return i;
    return -1;
}

static bool is_state_province(const tok_t* t)
{
    for (int i = 0; i < JTTY_NUM_STATE_PROVINCE; ++i)
        if (tok_eq(t, JTTY_STATE_PROVINCE[i])) return true;
    return false;
}

static bool tok_grid4(const tok_t* t, uint32_t* code)
{
    if (t->len != 4) return false;
    const char* s = t->s;
    if (s[0] < 'A' || s[0] > 'R' || s[1] < 'A' || s[1] > 'R') return false;
    if (s[2] < '0' || s[2] > '9' || s[3] < '0' || s[3] > '9') return false;
    *code = (((uint32_t)(s[0] - 'A') * 18u + (uint32_t)(s[1] - 'A')) * 10u
             + (uint32_t)(s[2] - '0')) * 10u + (uint32_t)(s[3] - '0');
    return true;
}

typedef struct {
    uint32_t words[MAX_CANDS];
    int n;
} cand_list_t;

static void add_cand(cand_list_t* c, uint32_t w)
{
    if (c->n < MAX_CANDS) c->words[c->n++] = w;
}

// EXCH_LOC candidates for a 2/3-character token, kind chosen by profile.
static void add_loc_cands(cand_list_t* c, const tok_t* t, unsigned role, jtty_profile_t profile)
{
    unsigned len1;
    uint32_t token;
    if (!is_alnum_tok(t) || !token_encode(t->s, t->len, &len1, &token)) return;
    if (profile == JTTY_PROFILE_RTTY_ROUNDUP && is_state_province(t))
        add_cand(c, make_struct_word(FAM_EXCH_LOC, body_exch_loc(role, LOC_STATE, len1, token)));
    if (profile == JTTY_PROFILE_FIELD_DAY && section_index(t) >= 0)
        add_cand(c, make_struct_word(FAM_EXCH_LOC, body_exch_loc(role, LOC_SECTION, len1, token)));
    add_cand(c, make_struct_word(FAM_EXCH_LOC, body_exch_loc(role, LOC_GENERIC_QTH, len1, token)));
}

static void add_num_cands(cand_list_t* c, const tok_t* t, unsigned role, jtty_profile_t profile)
{
    uint32_t v;
    if (!parse_uint(t, &v) || v > VALUE17_MAX) return;
    if (profile == JTTY_PROFILE_RTTY_ROUNDUP && t->len >= 3)
        add_cand(c, make_struct_word(FAM_EXCH_NUM, body_exch_num(role, NUM_SERIAL, v)));
    add_cand(c, make_struct_word(FAM_EXCH_NUM, body_exch_num(role, NUM_GENERIC, v)));
}

static void add_num_time_cand(cand_list_t* c, const tok_t* ser, const tok_t* hhmm, unsigned role)
{
    uint32_t s, hm;
    if (!parse_uint(ser, &s) || ser->len < 3 || s > SERIAL14_MAX) return;
    if (!parse_uint(hhmm, &hm) || hhmm->len != 4) return;
    unsigned hh = hm / 100u, mm = hm % 100u;
    if (hh > 23 || mm > 59) return;
    add_cand(c, make_struct_word(FAM_EXCH_NUM_TIME, body_num_time(role, s, hh * 60u + mm)));
}

static void add_grid_cand(cand_list_t* c, const tok_t* t, unsigned role)
{
    uint32_t code;
    if (tok_grid4(t, &code))
        add_cand(c, make_struct_word(FAM_MISC, body_misc(MISC_GRID4, misc_grid4(role, code))));
}

// Candidate structured atoms for the token span t[0..n-1] whose exact text is
// span/span_len. Generation order doubles as the tie-break order.
static void gen_candidates(const tok_t* t, int n, const char* span, int span_len,
                           jtty_profile_t profile, cand_list_t* out)
{
    out->n = 0;
    uint32_t call;

    // 1. Callsign actions.
    if (n == 3) {
        if (tok_eq(&t[0], "CQ") && tok_eq(&t[2], "CQ") && tok_call28(&t[1], &call))
            add_cand(out, make_call_word(call, 0, 0));
        if (tok_eq(&t[0], "TU") && tok_eq(&t[2], "CQ") && tok_call28(&t[1], &call))
            add_cand(out, make_call_word(call, 2, 0));
        if (tok_eq(&t[0], "TU") && tok_eq(&t[1], "NOW") && tok_call28(&t[2], &call))
            add_cand(out, make_call_word(call, 1, 1));
    } else if (n == 2) {
        if (tok_eq(&t[1], "TU") && tok_call28(&t[0], &call))
            add_cand(out, make_call_word(call, 3, 0));
        if (tok_eq(&t[1], "AGN?") && tok_call28(&t[0], &call))
            add_cand(out, make_call_word(call, 0, 1));
    } else if (n == 1) {
        if (tok_call28(&t[0], &call))
            add_cand(out, make_call_word(call, 1, 0));
    }

    // 2. CONTROL phrases (one or two words).
    for (int p = 0; p < JTTY_NUM_PHRASES; ++p) {
        const char* ph = JTTY_CONTROL_PHRASES[p];
        if ((int)strlen(ph) == span_len && memcmp(ph, span, (size_t)span_len) == 0)
            add_cand(out, make_struct_word(FAM_MISC, body_misc(MISC_CONTROL, misc_control((unsigned)p))));
    }

    bool rst = (n >= 2) && tok_eq(&t[0], "599");

    // 3. EXCH_NUM.
    if (n == 1) add_num_cands(out, &t[0], 0, profile);
    if (n == 2 && rst) add_num_cands(out, &t[1], 1, profile);

    // 4. EXCH_LOC.
    if (n == 1) add_loc_cands(out, &t[0], 0, profile);
    if (n == 2 && rst) add_loc_cands(out, &t[1], 1, profile);

    // 5. EXCH_PAIR.
    if (n == 2) {
        uint32_t zone;
        unsigned len1;
        uint32_t token;
        if (t[0].len == 2 && parse_uint(&t[0], &zone) && zone >= 1 && zone <= 40 &&
            is_alnum_tok(&t[1]) && token_encode(t[1].s, t[1].len, &len1, &token))
            add_cand(out, make_struct_word(FAM_EXCH_PAIR,
                     body_exch_pair(PAIR_ZONE_LOC3, pair_zone_loc3(zone, len1, token))));

        int sec = section_index(&t[1]);
        if (sec >= 0 && t[0].len >= 2 && t[0].len <= 3) {
            char cls = t[0].s[t[0].len - 1];
            tok_t cnt = { t[0].s, t[0].len - 1 };
            uint32_t count;
            if (cls >= 'A' && cls <= 'F' && parse_uint(&cnt, &count) && count >= 1 && count <= 32)
                add_cand(out, make_struct_word(FAM_EXCH_PAIR,
                         body_exch_pair(PAIR_CLASS_SECTION,
                                        pair_class_section(count, (unsigned)(cls - 'A'),
                                                           (unsigned)sec + 1))));
        }
    }

    // 6. EXCH_NUM_TIME (serial + UTC time), RTTY Roundup profile.
    if (profile == JTTY_PROFILE_RTTY_ROUNDUP) {
        if (n == 2) add_num_time_cand(out, &t[0], &t[1], 0);
        if (n == 3 && rst) add_num_time_cand(out, &t[1], &t[2], 1);
    }

    // 7. GRID4.
    if (n == 1) add_grid_cand(out, &t[0], 0);
    if (n == 2 && rst) add_grid_cand(out, &t[1], 1);

    // Keep only candidates that render back to exactly the span.
    int kept = 0;
    for (int i = 0; i < out->n; ++i) {
        char r[JTTY_ATOM_RENDER];
        int len = jtty_render_word(out->words[i], r, sizeof r, NULL);
        if (len == span_len && memcmp(r, span, (size_t)span_len) == 0)
            out->words[kept++] = out->words[i];
    }
    out->n = kept;
}

int jtty_pack_text(const char* text, jtty_profile_t profile, uint64_t* payloads, int max_frames)
{
    if (text == NULL || payloads == NULL || max_frames < JTTY_MAX_FRAMES) return JTTY_ERR_BAD_ARGS;
    if (profile < JTTY_PROFILE_GENERIC || profile > JTTY_PROFILE_FIELD_DAY) return JTTY_ERR_BAD_ARGS;

    char norm[JTTY_MAX_TEXT + 1];
    int len = normalise(text, norm, sizeof norm);
    if (len < 0) return len;

    // Tokenise (single spaces after normalisation).
    tok_t tok[MAX_TOKENS];
    int nt = 0;
    for (int i = 0; i < len && nt < MAX_TOKENS;) {
        int j = i;
        while (j < len && norm[j] != ' ') ++j;
        tok[nt].s = norm + i;
        tok[nt].len = j - i;
        ++nt;
        i = j + 1;
    }

    // Dynamic programme over token boundaries, back to front.
    //   costS[k]   : min frames for tokens k.. when the first atom is structured
    //   costAll[k] : min frames for tokens k.. (first atom structured or TEXT5 run)
    // A TEXT5 run must be followed by a structured atom (or the end), since
    // two adjacent runs would merge into one on the receive side.
    int costS[MAX_TOKENS + 1], costAll[MAX_TOKENS + 1];
    int chS_j[MAX_TOKENS + 1];
    uint32_t chS_word[MAX_TOKENS + 1];
    int chAll_text_j[MAX_TOKENS + 1];  // -1: use the structured choice
    costS[nt] = costAll[nt] = 0;

    for (int k = nt - 1; k >= 0; --k) {
        costS[k] = COST_INF;
        chS_j[k] = -1;
        for (int j = k; j < nt && j <= k + 2; ++j) {
            if (costAll[j + 1] >= COST_INF) continue;
            cand_list_t cl;
            const char* span = tok[k].s;
            int span_len = (int)(tok[j].s + tok[j].len - tok[k].s);
            gen_candidates(&tok[k], j - k + 1, span, span_len, profile, &cl);
            for (int c = 0; c < cl.n; ++c) {
                int cost = 1 + costAll[j + 1];
                // Ties: longer span wins; same span keeps the first generated.
                if (cost < costS[k] || (cost == costS[k] && j > chS_j[k])) {
                    costS[k] = cost;
                    chS_j[k] = j;
                    chS_word[k] = cl.words[c];
                }
            }
        }
        costAll[k] = costS[k];
        chAll_text_j[k] = -1;
        for (int j = k; j < nt; ++j) {
            if (costS[j + 1] >= COST_INF) continue;
            int span_len = (int)(tok[j].s + tok[j].len - tok[k].s);
            int cost = (span_len + 4) / 5 + costS[j + 1];
            // Structured wins ties; among text runs the longest wins.
            if (cost < costAll[k] || (cost == costAll[k] && chAll_text_j[k] >= 0 && j > chAll_text_j[k])) {
                costAll[k] = cost;
                chAll_text_j[k] = j;
            }
        }
    }

    // Emit atoms.
    int nf = 0;
    for (int k = 0; k < nt;) {
        if (chAll_text_j[k] < 0) {
            if (nf >= JTTY_MAX_FRAMES) return JTTY_ERR_TOO_LONG;
            payloads[nf++] = (uint64_t)chS_word[k] << 2;
            k = chS_j[k] + 1;
        } else {
            int j = chAll_text_j[k];
            const char* s = tok[k].s;
            int span_len = (int)(tok[j].s + tok[j].len - tok[k].s);
            for (int off = 0; off < span_len; off += 5) {
                if (nf >= JTTY_MAX_FRAMES) return JTTY_ERR_TOO_LONG;
                uint32_t form = 0;
                for (int q = 0; q < 5; ++q) {
                    int idx = (off + q < span_len) ? jtty_alphabet_index(s[off + q]) : TEXT5_SPACE;
                    if (idx < 0) idx = jtty_alphabet_index('#');
                    form = (form << 6) | (uint32_t)idx;
                }
                payloads[nf++] = (uint64_t)((form << 2) | 3u) << 2;
            }
            k = j + 1;
        }
    }
    if (nf == 0) return JTTY_ERR_EMPTY;
    payloads[nf - 1] |= 1u;  // EOM on the last frame only
    return nf;
}
