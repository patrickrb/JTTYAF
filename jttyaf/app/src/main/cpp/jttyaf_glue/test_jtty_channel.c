// test_jtty_channel.c — channel coding: CRC-12, tail-biting convolutional
// encoder (independent re-implementation vs jtty_fec.c), Gray mapping, hard
// and soft-decision list Viterbi decoding, tail-biting rotation property,
// decoder sensitivity curve under symbol-level AWGN and a noise-only false
// accept count.

#include "jtty_test_util.h"

// Independent, straightforward shift-register encoder for the verified
// convention (literal bit j taps the bit j symbols old, 1167 output first,
// bit 1 first — jtty_internal.h). Used to cross-check the table-driven encoder.
static void reference_encode(uint64_t info46, uint8_t* tones)
{
    const uint32_t ga = 01167u, gb = 01545u;
    uint8_t u[46];
    for (int i = 0; i < 46; ++i)
        u[i] = (uint8_t)((info46 >> (45 - i)) & 1u);
    for (int i = 0; i < 46; ++i) {
        int a = 0, b = 0;
        for (int j = 0; j < 10; ++j) {
            int bit = u[(i - j + 46) % 46];
            a ^= ((ga >> j) & 1) & bit;
            b ^= ((gb >> j) & 1) & bit;
        }
        static const uint8_t gray[4] = { 0, 1, 3, 2 };  // (b0 b1) = 00,01,10,11 -> 0,1,3,2
        tones[i] = gray[(a << 1) | b];
    }
}

static uint64_t random_valid_payload(bool eom)
{
    for (;;) {
        uint64_t word = test_rand64() & 0xFFFFFFFFull;
        uint64_t p = (word << 2) | (eom ? 1u : 0u);
        if (jtty_payload_valid(p)) return p;
    }
}

static void test_crc(void)
{
    printf("CRC-12\n");
    check(jtty_crc12(0) == 0, "crc(0) == 0");
    uint16_t c1 = jtty_crc12(0x026F78D41ull), c2 = jtty_crc12(0x026F78D40ull);
    check(c1 != c2, "crc differs when EOM flips");
    check((jtty_add_crc(0x026F78D41ull) >> 12) == 0x026F78D41ull, "add_crc keeps payload in top 34 bits");
    check((jtty_add_crc(0x026F78D41ull) & 0xFFF) == c1, "add_crc appends the 12-bit crc");
    int misses = 0;
    for (int t = 0; t < 500; ++t) {
        uint64_t p = test_rand64() & ((1ull << 34) - 1);
        int bit = (int)(test_rand64() % 34);
        if (jtty_crc12(p) == jtty_crc12(p ^ (1ull << bit))) ++misses;
    }
    check(misses == 0, "single-bit errors always change the crc");
    // Multi-bit: a random 2-bit error pattern must be caught too (minimum
    // distance of a CRC generator with x+1 factor is >= 4 over short blocks).
    misses = 0;
    for (int t = 0; t < 500; ++t) {
        uint64_t p = test_rand64() & ((1ull << 34) - 1);
        int b1 = (int)(test_rand64() % 34), b2 = (int)(test_rand64() % 34);
        if (b1 == b2) continue;
        if (jtty_crc12(p) == jtty_crc12(p ^ (1ull << b1) ^ (1ull << b2))) ++misses;
    }
    check(misses == 0, "double-bit errors always change the crc");
}

static void test_encoder(void)
{
    printf("TBCC encoder\n");
    int mism = 0;
    for (int t = 0; t < 200; ++t) {
        uint64_t info = test_rand64() & ((1ull << 46) - 1);
        uint8_t a[46], b[46];
        jtty_tbcc_encode(info, a);
        reference_encode(info, b);
        if (memcmp(a, b, 46) != 0) ++mism;
    }
    check(mism == 0, "table encoder == reference shift-register encoder (200 random)");

    uint8_t z[46];
    jtty_tbcc_encode(0, z);
    bool all0 = true;
    for (int i = 0; i < 46; ++i) all0 = all0 && z[i] == 0;
    check(all0, "all-zero info -> all tone 0 (Gray 00 -> 0)");

    // Gray map: every window value maps to the tone 2*b0 + (b0^b1). Probe the
    // encoder with single-bit info words: output pairs of the K=10 code for a
    // weight-1 input are the generator taps, so the tone sequence shows both
    // generators and the mapping.
    uint8_t one[46];
    jtty_tbcc_encode(1ull << 45, one);  // bit 1 (first transmitted) set
    int ok = 1;
    for (int i = 0; i < 46; ++i) {
        int j = i;  // delay of the single '1' at transmit index 0
        int ca = 0, cb = 0;
        if (j < 10) {
            ca = (01167u >> j) & 1;
            cb = (01545u >> j) & 1;
        }
        int want = 2 * ca + (ca ^ cb);
        if (one[i] != want) ok = 0;
    }
    check(ok, "impulse response shows generator taps through the Gray map");

    // Tail-biting: rotating the information bits rotates the tones.
    uint8_t u[46], ur[46], ta[46], tb[46];
    for (int i = 0; i < 46; ++i) u[i] = (uint8_t)(test_rand64() & 1);
    int rot_ok = 1;
    for (int r = 1; r < 46; r += 5) {
        for (int i = 0; i < 46; ++i) ur[i] = u[(i + r) % 46];
        jtty_tbcc_encode_bits(u, ta);
        jtty_tbcc_encode_bits(ur, tb);
        for (int i = 0; i < 46; ++i)
            if (tb[i] != ta[(i + r) % 46]) rot_ok = 0;
    }
    check(rot_ok, "tail-biting: rotated input -> rotated codeword (any start state)");

    uint8_t frame[59];
    jtty_encode_frame(0x026F78D41ull, frame);
    check(memcmp(frame, JTTY_SYNC, 13) == 0, "frame starts with the 13-symbol sync");
}

// Perfect (hard) metrics from tones.
static void hard_metrics(const uint8_t* tones, float m[46][4])
{
    for (int i = 0; i < 46; ++i)
        for (int k = 0; k < 4; ++k) m[i][k] = (tones[i] == k) ? 1.0f : 0.0f;
}

static void test_hard_decode(void)
{
    printf("hard-decision decode\n");
    int ok = 0, rank0 = 0;
    for (int t = 0; t < 300; ++t) {
        uint64_t p = random_valid_payload(test_rand64() & 1);
        uint8_t tones[59];
        jtty_encode_frame(p, tones);
        float m[46][4];
        hard_metrics(tones + 13, m);
        uint64_t got = 0;
        int rank = -1;
        if (jtty_decode_symbols(m, &got, &rank) && got == p) {
            ++ok;
            if (rank == 0) ++rank0;
        }
    }
    check(ok == 300, "300/300 random valid payloads decode exactly");
    check(rank0 == 300, "all at list rank 0");

    // A few hard symbol errors are corrected (free distance of the K=10 r=1/2
    // code is 12 coded bits; 4 random tone errors are well inside).
    int corrected = 0;
    for (int t = 0; t < 200; ++t) {
        uint64_t p = random_valid_payload(true);
        uint8_t tones[59];
        jtty_encode_frame(p, tones);
        for (int e = 0; e < 3; ++e) {
            int i = 13 + (int)(test_rand64() % 46);
            tones[i] = (uint8_t)((tones[i] + 1 + test_rand64() % 3) & 3);
        }
        float m[46][4];
        hard_metrics(tones + 13, m);
        uint64_t got = 0;
        if (jtty_decode_symbols(m, &got, NULL) && got == p) ++corrected;
    }
    printf("  info: 3 hard tone errors corrected in %d/200\n", corrected);
    check(corrected >= 190, "3 hard tone errors corrected in >= 95%");
}

// Symbol-level AWGN model: the complex correlation of the sent tone has
// magnitude 1, every tone gets complex gaussian noise of variance 2 sigma^2.
// Es/N0 = 1 / (2 sigma^2); S/N in 2500 Hz = Es/N0 - 10 log10(2500 / 31.25).
static void noisy_metrics(const uint8_t* tones, double sigma, float m[46][4])
{
    for (int i = 0; i < 46; ++i) {
        for (int k = 0; k < 4; ++k) {
            double re = (tones[i] == k ? 1.0 : 0.0) + sigma * test_gauss();
            double im = sigma * test_gauss();
            m[i][k] = (float)(re * re + im * im);
        }
    }
}

static void test_soft_decode(void)
{
    printf("soft-decision decode, sensitivity curve (symbol-level AWGN)\n");
    static const double esn0_db[] = { 12.0, 10.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0 };
    const int trials = 200;
    int false_accepts = 0;
    int ok_at_10 = 0;
    double us_per_decode = 0.0;
    int n_timed = 0;
    for (size_t s = 0; s < sizeof esn0_db / sizeof esn0_db[0]; ++s) {
        double sigma = sqrt(0.5 / pow(10.0, esn0_db[s] / 10.0));
        int ok = 0, ranks = 0;
        for (int t = 0; t < trials; ++t) {
            uint64_t p = random_valid_payload(test_rand64() & 1);
            uint8_t tones[59];
            jtty_encode_frame(p, tones);
            float m[46][4];
            noisy_metrics(tones + 13, sigma, m);
            uint64_t got = 0;
            int rank = 0;
            double t0 = test_now();
            bool dec = jtty_decode_symbols(m, &got, &rank);
            us_per_decode += (test_now() - t0) * 1e6;
            ++n_timed;
            if (dec) {
                if (got == p) {
                    ++ok;
                    ranks += rank;
                } else {
                    ++false_accepts;
                }
            }
        }
        printf("  Es/N0 %5.1f dB (S/N2500 %6.1f dB): %3d/%d decoded, mean rank %.2f\n", esn0_db[s],
               esn0_db[s] - 10.0 * log10(2500.0 / 31.25), ok, trials, ok ? (double)ranks / ok : 0.0);
        if (esn0_db[s] == 10.0) ok_at_10 = ok;
    }
    printf("  info: %.1f us per list decode (%d decodes)\n", us_per_decode / n_timed, n_timed);
    check(ok_at_10 >= (int)(0.95 * trials), ">= 95% decoded at Es/N0 = 10 dB (S/N2500 ~ -9 dB)");
    check(false_accepts == 0, "no wrong payload accepted over the sweep");
}

static void test_noise_only(void)
{
    printf("noise-only false accepts\n");
    int accepted = 0;
    const int trials = 3000;
    for (int t = 0; t < trials; ++t) {
        float m[46][4];
        for (int i = 0; i < 46; ++i)
            for (int k = 0; k < 4; ++k) {
                double re = test_gauss(), im = test_gauss();
                m[i][k] = (float)(re * re + im * im);
            }
        uint64_t got;
        if (jtty_decode_symbols(m, &got, NULL)) ++accepted;
    }
    printf("  info: %d/%d noise frames accepted\n", accepted, trials);
    check(accepted <= 1, "<= 1 false accept in 3000 pure-noise frames");
}

int main(void)
{
    test_srand(12345);
    test_crc();
    test_encoder();
    test_hard_decode();
    test_soft_decode();
    test_noise_only();
    printf("%s: %d failure(s)\n", "test_jtty_channel", g_failures);
    return g_failures ? 1 : 0;
}
