// jtty_spec.c — protocol constants and tables (docs/jtty-protocol.md §1, §3).

#include "jtty_internal.h"

#include <string.h>

// §1: the 13-symbol sync sequence (peak autocorrelation sidelobe 2/13).
const uint8_t JTTY_SYNC[JTTY_SYNC_SYMBOLS] = { 0, 2, 2, 3, 0, 0, 3, 2, 1, 3, 1, 2, 0 };

// §3.3: index 0 = '0', 10 = 'A', 36 = ' ', 63 = ';'.
const char JTTY_ALPHABET[65] =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ +-./?!\"#$%,&*()_'=[]{}<>|:;";

// §3.2: the 86-entry ARRL/RAC section list (the FT8 Field Day order).
const char* const JTTY_ARRL_SECTIONS[JTTY_NUM_SECTIONS] = {
    "AB", "AK", "AL", "AR", "AZ", "BC", "CO", "CT", "DE", "EB",
    "EMA", "ENY", "EPA", "EWA", "GA", "GH", "IA", "ID", "IL", "IN",
    "KS", "KY", "LA", "LAX", "NS", "MB", "MDC", "ME", "MI", "MN",
    "MO", "MS", "MT", "NC", "ND", "NE", "NFL", "NH", "NL", "NLI",
    "NM", "NNJ", "NNY", "TER", "NTX", "NV", "OH", "OK", "ONE", "ONN",
    "ONS", "OR", "ORG", "PAC", "PR", "QC", "RI", "SB", "SC", "SCV",
    "SD", "SDG", "SF", "SFL", "SJV", "SK", "SNJ", "STX", "SV", "TN",
    "UT", "VA", "VI", "VT", "WCF", "WI", "WMA", "WNY", "WPA", "WTX",
    "WV", "WWA", "WY", "DX", "PE", "NB",
};

// §3.2 MISC/CONTROL phrases 0..17.
const char* const JTTY_CONTROL_PHRASES[JTTY_NUM_PHRASES] = {
    "AGN?", "CALL?", "AGN CALL", "NR?", "AGN NR", "EXCH?", "STATE?", "SECTION?",
    "ZONE?", "GRID?", "RPRT?", "QSL TU", "TU", "QRZ?", "QSO B4", "WAIT",
    "NIL?", "OK?",
};

// US states + DC and the Canadian provinces/territories, as exchanged in the
// ARRL RTTY Roundup. Only the Roundup profile consults this list; the atom
// itself (EXCH_LOC kind 0) carries any 2/3-character token.
const char* const JTTY_STATE_PROVINCE[] = {
    "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID",
    "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS",
    "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK",
    "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV",
    "WI", "WY", "DC",
    "NB", "NS", "QC", "ON", "MB", "SK", "AB", "BC", "NT", "NU", "YT", "PE",
    "NL",
};
const int JTTY_NUM_STATE_PROVINCE =
    (int)(sizeof(JTTY_STATE_PROVINCE) / sizeof(JTTY_STATE_PROVINCE[0]));

int jtty_alphabet_index(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'Z') return 10 + (c - 'A');
    const char* p = strchr(JTTY_ALPHABET + 36, c);
    if (p == NULL || c == '\0') return -1;
    return (int)(p - JTTY_ALPHABET);
}

char jtty_alphabet_char(int index)
{
    if (index < 0 || index > 63) return '#';
    return JTTY_ALPHABET[index];
}
