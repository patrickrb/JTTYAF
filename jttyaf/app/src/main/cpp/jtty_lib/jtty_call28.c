// jtty_call28.c — standard-callsign <-> call28 (docs/jtty-protocol.md §3.1).
//
// JTTY reuses the FT8/FT4 77-bit protocol's 28-bit callsign code for
// *standard* callsigns only: no DE/QRZ/CQ tokens, no 22-bit hashes, no /P or
// /R suffixes. The standard-call arithmetic below follows the published
// FT8 protocol description (and kgoba/ft8_lib's pack28/unpack28, MIT):
//
//   pad the call to 6 chars (" " + call when the 3rd char is not a digit)
//   c1 in " 0-9A-Z" (37)  c2 in "0-9A-Z" (36)  c3 in "0-9" (10)
//   c4..c6 in " A-Z" (27)
//   n = ((((c1*36 + c2)*10 + c3)*27 + c4)*27 + c5)*27 + c6
//   call28 = n + 2063592 + 4194304
//
// 37*36*10*27^3 = 262177560 and 2063592 + 4194304 = 6257896, so every 28-bit
// value >= 6257896 denotes a standard-call character pattern; validity then
// depends only on that pattern being a plausible callsign.

#include "jtty_internal.h"

#include <string.h>

#define NTOKENS 2063592u
#define MAX22   4194304u
#define CALL28_BASE (NTOKENS + MAX22)

static const char* const TAB_ALNUM_SPACE = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
static const char* const TAB_ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
static const char* const TAB_NUMERIC = "0123456789";
static const char* const TAB_LETTERS_SPACE = " ABCDEFGHIJKLMNOPQRSTUVWXYZ";

static int table_index(const char* table, char c)
{
    if (c == '\0') return -1;
    const char* p = strchr(table, c);
    return p ? (int)(p - table) : -1;
}

static bool is_digit(char c) { return c >= '0' && c <= '9'; }
static bool is_upper(char c) { return c >= 'A' && c <= 'Z'; }

// Standard callsign: 1-2 character prefix (alphanumeric, at least one letter,
// not starting with Q), one digit, 1-3 letter suffix. This is what fits the
// 6-character pattern above and what the JTTY grammar accepts as a call atom.
static bool is_standard_call(const char* s, int len)
{
    if (len < 3 || len > 6) return false;
    // Find the area digit: position 3 when it is a digit (two-character
    // prefix, which may itself end in a digit, e.g. A41AB), else position 2.
    int digit_pos;
    if (is_digit(s[2])) digit_pos = 2;
    else if (is_digit(s[1])) digit_pos = 1;
    else return false;

    // Prefix: 1-2 alphanumerics with at least one letter, no leading Q.
    bool has_letter = false;
    for (int i = 0; i < digit_pos; ++i) {
        if (!(is_upper(s[i]) || is_digit(s[i]))) return false;
        if (is_upper(s[i])) has_letter = true;
    }
    if (!has_letter) return false;
    if (s[0] == 'Q') return false;

    // Suffix: 1-3 letters.
    int suffix = len - digit_pos - 1;
    if (suffix < 1 || suffix > 3) return false;
    for (int i = digit_pos + 1; i < len; ++i)
        if (!is_upper(s[i])) return false;
    return true;
}

bool jtty_pack_call28(const char* callsign, uint32_t* call28)
{
    if (callsign == NULL || call28 == NULL) return false;
    int len = (int)strlen(callsign);
    if (!is_standard_call(callsign, len)) return false;

    char c6[6];
    memset(c6, ' ', sizeof c6);
    // Right-align the area digit on position 3 (index 2).
    int shift = (len >= 3 && is_digit(callsign[2])) ? 0 : 1;
    if (len + shift > 6) return false;
    memcpy(c6 + shift, callsign, (size_t)len);

    int i0 = table_index(TAB_ALNUM_SPACE, c6[0]);
    int i1 = table_index(TAB_ALNUM, c6[1]);
    int i2 = table_index(TAB_NUMERIC, c6[2]);
    int i3 = table_index(TAB_LETTERS_SPACE, c6[3]);
    int i4 = table_index(TAB_LETTERS_SPACE, c6[4]);
    int i5 = table_index(TAB_LETTERS_SPACE, c6[5]);
    if (i0 < 0 || i1 < 0 || i2 < 0 || i3 < 0 || i4 < 0 || i5 < 0) return false;

    uint32_t n = (uint32_t)i0;
    n = n * 36 + (uint32_t)i1;
    n = n * 10 + (uint32_t)i2;
    n = n * 27 + (uint32_t)i3;
    n = n * 27 + (uint32_t)i4;
    n = n * 27 + (uint32_t)i5;
    *call28 = n + CALL28_BASE;
    return true;
}

bool jtty_unpack_call28(uint32_t call28, char* out, int out_len)
{
    if (out == NULL || out_len < 7) return false;
    out[0] = '\0';
    if (call28 < CALL28_BASE || call28 > 0x0FFFFFFFu) return false;
    uint32_t n = call28 - CALL28_BASE;

    char c6[7];
    c6[6] = '\0';
    c6[5] = TAB_LETTERS_SPACE[n % 27]; n /= 27;
    c6[4] = TAB_LETTERS_SPACE[n % 27]; n /= 27;
    c6[3] = TAB_LETTERS_SPACE[n % 27]; n /= 27;
    c6[2] = TAB_NUMERIC[n % 10];       n /= 10;
    c6[1] = TAB_ALNUM[n % 36];         n /= 36;
    c6[0] = TAB_ALNUM_SPACE[n % 37];

    // Strip the leading pad space and trailing pad spaces; anything that
    // leaves an interior space or an implausible pattern is not a callsign.
    const char* s = c6;
    if (*s == ' ') ++s;
    int len = (int)strlen(s);
    while (len > 0 && s[len - 1] == ' ') --len;
    if (len <= 0) return false;
    for (int i = 0; i < len; ++i)
        if (s[i] == ' ') return false;
    if (!is_standard_call(s, len)) return false;

    // Round trip guards the few patterns the digit tables accept but the
    // packer would place differently (e.g. a digit in position 2 and 3).
    char tmp[7];
    memcpy(tmp, s, (size_t)len);
    tmp[len] = '\0';
    uint32_t again;
    if (!jtty_pack_call28(tmp, &again) || again != call28) return false;

    memcpy(out, tmp, (size_t)len + 1);
    return true;
}
