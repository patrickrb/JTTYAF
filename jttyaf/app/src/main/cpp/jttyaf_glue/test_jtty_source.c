// test_jtty_source.c — source coding: golden vectors (docs/jtty-protocol.md
// §3.4), alphabet, call28, validity rejections, tokenizer (§3.5).

#include "jtty_test_util.h"

typedef struct {
    const char* text;
    jtty_profile_t profile;
    uint64_t payload;
    bool eom;
    int frames;  // frames jtty_pack_text should produce for `text`
} golden_t;

static const golden_t k_golden[] = {
    { "CQ K1ABC CQ",  JTTY_PROFILE_GENERIC,      0x026F78D41ull, true,  1 },
    { "599 123",      JTTY_PROFILE_RTTY_ROUNDUP, 0x20007B008ull, false, 1 },  // more frames follow
    { "599 123",      JTTY_PROFILE_RTTY_ROUNDUP, 0x20007B009ull, true,  1 },
    { "599 CA",       JTTY_PROFILE_RTTY_ROUNDUP, 0x2001BA019ull, true,  1 },
    { "05 NWT",       JTTY_PROFILE_GENERIC,      0x00B790D29ull, true,  1 },
    { "1D EMA",       JTTY_PROFILE_GENERIC,      0x082C58029ull, true,  1 },
    { "599 156 1749", JTTY_PROFILE_RTTY_ROUNDUP, 0x204E42D39ull, true,  1 },
    { "AGN?",         JTTY_PROFILE_GENERIC,      0x000000049ull, true,  1 },
    { "FN42",         JTTY_PROFILE_GENERIC,      0x04A198049ull, true,  1 },
    { "HELLO",        JTTY_PROFILE_GENERIC,      0x11395558Dull, true,  1 },
};

static void test_golden(void)
{
    printf("golden vectors (unpack/render)\n");
    for (size_t i = 0; i < sizeof k_golden / sizeof k_golden[0]; ++i) {
        const golden_t* g = &k_golden[i];
        char text[JTTY_ATOM_RENDER];
        bool eom = false;
        int len = jtty_unpack_payload(g->payload, text, sizeof text, &eom);
        char label[96];
        snprintf(label, sizeof label, "unpack 0x%09llX", (unsigned long long)g->payload);
        check(len > 0, label);
        check_eq_str(text, g->text, "  text");
        check(eom == g->eom, "  eom flag");
        check(jtty_payload_valid(g->payload), "  valid");
    }

    printf("golden vectors (pack)\n");
    for (size_t i = 0; i < sizeof k_golden / sizeof k_golden[0]; ++i) {
        const golden_t* g = &k_golden[i];
        uint64_t p[JTTY_MAX_FRAMES];
        int nf = jtty_pack_text(g->text, g->profile, p, JTTY_MAX_FRAMES);
        char label[96];
        snprintf(label, sizeof label, "pack \"%s\" -> %d frame(s)", g->text, g->frames);
        check(nf == g->frames, label);
        if (nf == 1) {
            // The packer always sets EOM on a single frame; compare the word.
            uint64_t want = g->payload | 1u;
            snprintf(label, sizeof label, "  payload 0x%09llX", (unsigned long long)want);
            check(p[0] == want, label);
        }
    }
}

static void test_alphabet(void)
{
    printf("alphabet\n");
    check(strlen(JTTY_ALPHABET) == 64, "64 characters");
    check(jtty_alphabet_index('0') == 0, "'0' -> 0");
    check(jtty_alphabet_index('A') == 10, "'A' -> 10");
    check(jtty_alphabet_index(' ') == 36, "' ' -> 36");
    check(jtty_alphabet_index(';') == 63, "';' -> 63");
    check(jtty_alphabet_index('a') < 0, "lowercase not in alphabet (normalised earlier)");
    check(jtty_alphabet_index('~') < 0, "'~' unsupported");
    bool all_roundtrip = true;
    for (int i = 0; i < 64; ++i)
        if (jtty_alphabet_index(jtty_alphabet_char(i)) != i) all_roundtrip = false;
    check(all_roundtrip, "index/char round trip for all 64");

    // Every TEXT5 word is valid and renders its five characters.
    uint64_t p[JTTY_MAX_FRAMES];
    int nf = jtty_pack_text("hello world", JTTY_PROFILE_GENERIC, p, JTTY_MAX_FRAMES);
    check(nf == 3, "\"hello world\" -> 3 TEXT5 frames (upper-cased)");
    char msg[JTTY_MAX_RENDER];
    jtty_render_message(p, nf, msg, sizeof msg);
    check_eq_str(msg, "HELLO WORLD", "render \"hello world\"");

    nf = jtty_pack_text("a~b", JTTY_PROFILE_GENERIC, p, JTTY_MAX_FRAMES);
    jtty_render_message(p, nf, msg, sizeof msg);
    check_eq_str(msg, "A#B", "unsupported char -> '#'");
}

static void test_call28(void)
{
    printf("call28\n");
    uint32_t c = 0;
    check(jtty_pack_call28("K1ABC", &c) && c == 10214965u, "K1ABC -> 10214965");
    char out[8];
    check(jtty_unpack_call28(10214965u, out, sizeof out) && strcmp(out, "K1ABC") == 0, "10214965 -> K1ABC");

    static const char* good[] = { "W1AW", "VE3ABC", "A41AB", "4X1AB", "DL1X", "JA1ABC", "9A1A", "K1A" };
    for (size_t i = 0; i < sizeof good / sizeof good[0]; ++i) {
        uint32_t v;
        bool ok = jtty_pack_call28(good[i], &v) && jtty_unpack_call28(v, out, sizeof out) &&
                  strcmp(out, good[i]) == 0;
        char label[64];
        snprintf(label, sizeof label, "round trip %s", good[i]);
        check(ok, label);
    }
    static const char* bad[] = { "CQ", "DE", "QRZ", "K1ABC/P", "3DA0XX", "KA1ABCD", "12ABC",
                                 "Q1AB", "K1", "K1ABCD", "", "k1abc", "KH6/K1ABC" };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; ++i) {
        uint32_t v;
        char label[64];
        snprintf(label, sizeof label, "reject \"%s\"", bad[i]);
        check(!jtty_pack_call28(bad[i], &v), label);
    }
    check(!jtty_unpack_call28(0, out, sizeof out), "call28 0 (DE token) invalid");
    check(!jtty_unpack_call28(2u, out, sizeof out), "call28 2 (CQ token) invalid");
    check(!jtty_unpack_call28(6257895u, out, sizeof out), "last hashed value invalid");
    check(!jtty_unpack_call28(6257896u, out, sizeof out), "all-space pattern invalid");
}

static void test_validity(void)
{
    printf("validity rejections\n");
    char t[JTTY_ATOM_RENDER];
    check(jtty_unpack_payload(0x026F78D41ull | 2u, t, sizeof t, NULL) == JTTY_ERR_INVALID, "reserved bit set");
    check(!jtty_payload_valid(0), "all-zero payload");
    check(!jtty_payload_valid(1), "all-zero word with EOM");
    // i2 = 1, n2 = 2 and 3 are reserved.
    uint64_t call_word = (uint64_t)10214965u << 4;
    check(jtty_payload_valid(((call_word | (0u << 2) | 1u) << 2) | 1u), "1.0 AGN? valid");
    check(!jtty_payload_valid(((call_word | (2u << 2) | 1u) << 2) | 1u), "1.2 reserved");
    check(!jtty_payload_valid(((call_word | (3u << 2) | 1u) << 2) | 1u), "1.3 reserved");
    // Non-standard call28 (a hash value).
    check(!jtty_payload_valid((((uint64_t)12345u << 4) << 2) | 1u), "hashed call28 invalid");

    // STRUCT30 helpers: word = body27<<5 | family<<2 | 2 ; payload = word<<2 | eom
#define STRUCT(fam, body) (((((uint64_t)(body) << 5) | ((fam) << 2) | 2u) << 2) | 1u)
    check(!jtty_payload_valid(STRUCT(5u, 0u)), "family 5 invalid");
    check(!jtty_payload_valid(STRUCT(7u, 0u)), "family 7 invalid");
    // EXCH_NUM: role1 kind4 value17 zero5
    check(jtty_payload_valid(STRUCT(0u, (7u << 22) | (99u << 5))), "GENERIC_NUMERIC 99 valid");
    check(!jtty_payload_valid(STRUCT(0u, (8u << 22) | (99u << 5))), "EXCH_NUM kind 8 invalid");
    check(!jtty_payload_valid(STRUCT(0u, (7u << 22) | (99u << 5) | 1u)), "EXCH_NUM zero5 != 0");
    check(jtty_payload_valid(STRUCT(0u, (6u << 22) | (9999u << 5))), "FIRST_LICENSE_YEAR 9999 valid");
    check(!jtty_payload_valid(STRUCT(0u, (6u << 22) | (10000u << 5))), "FIRST_LICENSE_YEAR 10000 invalid");
    // EXCH_LOC: role1 kind4 len1 token16 zero5
    check(!jtty_payload_valid(STRUCT(1u, (5u << 22) | (442u << 5))), "EXCH_LOC kind 5 invalid");
    check(!jtty_payload_valid(STRUCT(1u, (0u << 22) | (1296u << 5))), "2-char token >= 36^2 invalid");
    check(!jtty_payload_valid(STRUCT(1u, (0u << 22) | (1u << 21) | (1295u << 5))), "3-char token < 36^2 invalid");
    check(!jtty_payload_valid(STRUCT(1u, (0u << 22) | (1u << 21) | (46656u << 5))), "3-char token >= 36^3 invalid");
    check(jtty_payload_valid(STRUCT(1u, (3u << 22) | (1u << 21) | (30989u << 5))), "GENERIC_QTH NWT valid");
    // EXCH_PAIR: schema3 pair23 zero1
    check(!jtty_payload_valid(STRUCT(2u, (2u << 24))), "EXCH_PAIR schema 2 invalid");
    check(!jtty_payload_valid(STRUCT(2u, (0u << 24) | ((0u << 17) | 442u) << 1)), "ZONE_LOC3 zone 0 invalid");
    check(!jtty_payload_valid(STRUCT(2u, (0u << 24) | ((41u << 17) | 442u) << 1)), "ZONE_LOC3 zone 41 invalid");
    check(jtty_payload_valid(STRUCT(2u, (0u << 24) | ((40u << 17) | 442u) << 1)), "ZONE_LOC3 zone 40 valid");
    check(!jtty_payload_valid(STRUCT(2u, (0u << 24) | (((5u << 17) | 442u) << 1) | 1u)), "EXCH_PAIR zero1 != 0");
    check(!jtty_payload_valid(STRUCT(2u, (1u << 24) | ((1u << 17) | (6u << 14) | (11u << 7)) << 1)), "CLASS_SECTION class 6 invalid");
    check(!jtty_payload_valid(STRUCT(2u, (1u << 24) | ((0u << 17) | (3u << 14) | (11u << 7)) << 1)), "CLASS_SECTION count 0 invalid");
    check(!jtty_payload_valid(STRUCT(2u, (1u << 24) | ((33u << 17) | (3u << 14) | (11u << 7)) << 1)), "CLASS_SECTION count 33 invalid");
    check(!jtty_payload_valid(STRUCT(2u, (1u << 24) | ((1u << 17) | (3u << 14) | (0u << 7)) << 1)), "CLASS_SECTION section 0 invalid");
    check(!jtty_payload_valid(STRUCT(2u, (1u << 24) | ((1u << 17) | (3u << 14) | (87u << 7)) << 1)), "CLASS_SECTION section 87 invalid");
    check(jtty_payload_valid(STRUCT(2u, (1u << 24) | ((1u << 17) | (3u << 14) | (86u << 7)) << 1)), "CLASS_SECTION section 86 (NB) valid");
    check(!jtty_payload_valid(STRUCT(2u, (1u << 24) | ((1u << 17) | (3u << 14) | (11u << 7) | 1u) << 1)), "CLASS_SECTION zero7 != 0");
    // EXCH_NUM_TIME: role1 serial14 minute11 zero1
    check(!jtty_payload_valid(STRUCT(3u, (1u << 26) | (156u << 12) | (1440u << 1))), "minute 1440 invalid");
    check(jtty_payload_valid(STRUCT(3u, (1u << 26) | (156u << 12) | (1439u << 1))), "minute 1439 valid");
    check(!jtty_payload_valid(STRUCT(3u, (1u << 26) | (156u << 12) | (1069u << 1) | 1u)), "NUM_TIME zero1 != 0");
    // MISC: subtype4 data23
    check(!jtty_payload_valid(STRUCT(4u, (2u << 23))), "MISC subtype 2 invalid");
    check(!jtty_payload_valid(STRUCT(4u, (0u << 23) | (18u << 16))), "CONTROL phrase 18 invalid");
    check(jtty_payload_valid(STRUCT(4u, (0u << 23) | (17u << 16))), "CONTROL phrase 17 (OK?) valid");
    check(!jtty_payload_valid(STRUCT(4u, (0u << 23) | (17u << 16) | 1u)), "CONTROL zero16 != 0");
    check(!jtty_payload_valid(STRUCT(4u, (1u << 23) | (32400u << 7))), "GRID4 code 32400 invalid");
    check(jtty_payload_valid(STRUCT(4u, (1u << 23) | (32399u << 7))), "GRID4 code 32399 (RR99) valid");
    check(!jtty_payload_valid(STRUCT(4u, (1u << 23) | (10342u << 7) | 1u)), "GRID4 zero7 != 0");
#undef STRUCT
    check(!jtty_payload_valid(1ull << 34), "payload wider than 34 bits");

    // Rendering spot checks.
    bool eom;
    jtty_unpack_payload(0x20007B009ull, t, sizeof t, &eom);
    check_eq_str(t, "599 123", "render SERIAL 599 123");
    uint64_t w = ((((uint64_t)((0u << 26) | (0u << 22) | (7u << 5)) << 5) | (0u << 2) | 2u) << 2) | 1u;
    jtty_unpack_payload(w, t, sizeof t, &eom);
    check_eq_str(t, "007", "SERIAL 7 renders with three digits");
    w = ((((uint64_t)((0u << 26) | (7u << 22) | (7u << 5)) << 5) | (0u << 2) | 2u) << 2) | 1u;
    jtty_unpack_payload(w, t, sizeof t, &eom);
    check_eq_str(t, "7", "GENERIC_NUMERIC 7 renders plain");
    w = ((((uint64_t)((1u << 23) | (1u << 22) | (10342u << 7)) << 5) | (4u << 2) | 2u) << 2) | 1u;
    jtty_unpack_payload(w, t, sizeof t, &eom);
    check_eq_str(t, "599 FN42", "GRID4 with role 1");
    w = ((((uint64_t)((1u << 23) | (0u << 22) | (0u << 7)) << 5) | (4u << 2) | 2u) << 2) | 1u;
    jtty_unpack_payload(w, t, sizeof t, &eom);
    check_eq_str(t, "AA00", "GRID4 code 0 -> AA00");
    w = ((uint64_t)((10214965u << 4) | (1u << 2) | 1u) << 2) | 1u;
    jtty_unpack_payload(w, t, sizeof t, &eom);
    check_eq_str(t, "TU NOW K1ABC", "call action 1.1");
}

typedef struct {
    const char* text;
    jtty_profile_t profile;
    int frames;
} tok_case_t;

static void test_tokenizer(void)
{
    printf("tokenizer\n");
    static const tok_case_t cases[] = {
        { "CQ K1ABC CQ", JTTY_PROFILE_GENERIC, 1 },
        { "599 001", JTTY_PROFILE_RTTY_ROUNDUP, 1 },
        { "599 001", JTTY_PROFILE_GENERIC, 2 },
        { "K1ABC 599 001", JTTY_PROFILE_RTTY_ROUNDUP, 2 },
        { "K1ABC", JTTY_PROFILE_GENERIC, 1 },
        { "TU K1ABC CQ", JTTY_PROFILE_GENERIC, 1 },
        { "K1ABC TU", JTTY_PROFILE_GENERIC, 1 },
        { "K1ABC AGN?", JTTY_PROFILE_GENERIC, 1 },
        { "TU NOW K1ABC", JTTY_PROFILE_GENERIC, 1 },
        { "QSL TU", JTTY_PROFILE_GENERIC, 1 },
        { "AGN CALL", JTTY_PROFILE_GENERIC, 1 },
        { "TU", JTTY_PROFILE_GENERIC, 1 },
        { "1D EMA", JTTY_PROFILE_GENERIC, 1 },
        { "1D EMA", JTTY_PROFILE_FIELD_DAY, 1 },
        { "05 NWT", JTTY_PROFILE_GENERIC, 1 },
        { "FN42", JTTY_PROFILE_GENERIC, 1 },
        { "599 FN42", JTTY_PROFILE_GENERIC, 1 },
        { "599 CA", JTTY_PROFILE_RTTY_ROUNDUP, 1 },
        { "599 156 1749", JTTY_PROFILE_RTTY_ROUNDUP, 1 },
        { "123", JTTY_PROFILE_GENERIC, 1 },
        { "599 123", JTTY_PROFILE_GENERIC, 1 },     // GENERIC_NUMERIC with role 1
        { "HELLO", JTTY_PROFILE_GENERIC, 1 },
        { "HELLO WORLD", JTTY_PROFILE_GENERIC, 3 }, // 11 chars incl. the space
        { "HELLO WORLD K1ABC 599 001", JTTY_PROFILE_RTTY_ROUNDUP, 5 },
        { "CQ K1ABC CQ CQ K1ABC CQ", JTTY_PROFILE_GENERIC, 2 },
        { "K1ABC W1AW 599 001 001 TU", JTTY_PROFILE_RTTY_ROUNDUP, 5 },
        { "  cq   k1abc   cq  ", JTTY_PROFILE_GENERIC, 1 },
        { "THIS IS A LONGER FREE TEXT MESSAGE 12345", JTTY_PROFILE_GENERIC, 8 },
    };
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; ++i) {
        uint64_t p[JTTY_MAX_FRAMES];
        int nf = jtty_pack_text(cases[i].text, cases[i].profile, p, JTTY_MAX_FRAMES);
        char label[128];
        snprintf(label, sizeof label, "\"%s\" (profile %d) -> %d frames (got %d)", cases[i].text,
                 (int)cases[i].profile, cases[i].frames, nf);
        check(nf == cases[i].frames, label);
        if (nf > 0) {
            // EOM only on the last frame; reserved bit clear everywhere.
            bool eom_ok = true, res_ok = true, valid_ok = true;
            for (int f = 0; f < nf; ++f) {
                if (((p[f] & 1u) != 0) != (f == nf - 1)) eom_ok = false;
                if ((p[f] >> 1) & 1u) res_ok = false;
                if (!jtty_payload_valid(p[f])) valid_ok = false;
            }
            check(eom_ok && res_ok && valid_ok, "  EOM/reserved/validity flags");
            // Round trip through the renderer (normalised text).
            char msg[JTTY_MAX_RENDER];
            jtty_render_message(p, nf, msg, sizeof msg);
            char want[JTTY_MAX_TEXT + 1];
            // Normalise the expectation the same way the packer does.
            int n = 0;
            bool sp = false;
            for (const char* s = cases[i].text; *s; ++s) {
                if (*s == ' ') { if (n) sp = true; continue; }
                if (sp) want[n++] = ' ';
                sp = false;
                want[n++] = (char)((*s >= 'a' && *s <= 'z') ? *s - 32 : *s);
            }
            want[n] = '\0';
            check_eq_str(msg, want, "  render round trip");
        }
    }

    uint64_t p[JTTY_MAX_FRAMES];
    char long80[81], long81[82];
    memset(long80, 'A', 80); long80[80] = '\0';
    memset(long81, 'A', 81); long81[81] = '\0';
    check(jtty_pack_text(long80, JTTY_PROFILE_GENERIC, p, JTTY_MAX_FRAMES) == 16, "80 chars -> 16 frames (cap)");
    check(jtty_pack_text(long81, JTTY_PROFILE_GENERIC, p, JTTY_MAX_FRAMES) == JTTY_ERR_TOO_LONG, "81 chars rejected");
    check(jtty_pack_text("", JTTY_PROFILE_GENERIC, p, JTTY_MAX_FRAMES) == JTTY_ERR_EMPTY, "empty rejected");
    check(jtty_pack_text("   ", JTTY_PROFILE_GENERIC, p, JTTY_MAX_FRAMES) == JTTY_ERR_EMPTY, "blank rejected");
    check(jtty_pack_text("HI", JTTY_PROFILE_GENERIC, p, 4) == JTTY_ERR_BAD_ARGS, "max_frames < 16 rejected");
    check(jtty_pack_text(NULL, JTTY_PROFILE_GENERIC, p, JTTY_MAX_FRAMES) == JTTY_ERR_BAD_ARGS, "NULL text");
    char t[JTTY_ATOM_RENDER];
    check(jtty_unpack_payload(0x026F78D41ull, t, 8, NULL) == JTTY_ERR_BAD_ARGS, "short render buffer rejected");

    // A message of 16 mixed frames renders and never exceeds the cap.
    int nf = jtty_pack_text("CQ K1ABC CQ THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 599 001 FN42 TU",
                            JTTY_PROFILE_RTTY_ROUNDUP, p, JTTY_MAX_FRAMES);
    check(nf > 0 && nf <= JTTY_MAX_FRAMES, "mixed long message within 16 frames");
    char msg[JTTY_MAX_RENDER];
    jtty_render_message(p, nf, msg, sizeof msg);
    check_eq_str(msg, "CQ K1ABC CQ THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 599 001 FN42 TU", "mixed message round trip");
}

int main(void)
{
    test_golden();
    test_alphabet();
    test_call28();
    test_validity();
    test_tokenizer();
    printf("%s: %d failure(s)\n", "test_jtty_source", g_failures);
    return g_failures ? 1 : 0;
}
