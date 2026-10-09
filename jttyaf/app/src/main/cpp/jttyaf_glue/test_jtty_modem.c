// test_jtty_modem.c — end to end: encode -> GFSK -> channel (noise, frequency
// and time offsets) -> jtty_rx. Checks decoded text, frequency and timing,
// multi-frame messages, simultaneous signals, the sensitivity threshold and
// the real-time budget of the wide-band search.

#include "jtty_test_util.h"

#define FS 12000

// Build a test stream: `lead` seconds of silence, the message at f0, `tail`
// seconds of silence. Returns malloc'd samples; *n_out its length, *start_out
// the sample index of the first frame.
static float* make_stream(const char* text, jtty_profile_t profile, float f0, double lead_s,
                          double tail_s, float amp, int* n_out, int* start_out, int* nf_out)
{
    int start = (int)lround(lead_s * FS);
    int cap = start + 16 * JTTY_FRAME_SAMPLES + (int)lround(tail_s * FS) + 1;
    float* buf = (float*)calloc((size_t)cap, sizeof(float));
    int nf = 0;
    int n = jtty_encode_text(text, profile, f0, FS, amp, buf + start, cap - start, &nf);
    if (n < 0) {
        free(buf);
        return NULL;
    }
    *n_out = start + n + (int)lround(tail_s * FS);
    *start_out = start;
    *nf_out = nf;
    return buf;
}

static int drain(jtty_rx_t* rx, jtty_decode_t* out, int max)
{
    int n = 0;
    jtty_decode_t d;
    while (jtty_rx_pop(rx, &d)) {
        if (n < max) out[n] = d;
        ++n;
    }
    return n < max ? n : max;
}

static void test_modulator(void)
{
    printf("modulator\n");
    check(jtty_waveform_samples(1, 12000) == 22656, "1 frame @ 12 kHz = 22656 samples");
    check(jtty_waveform_samples(3, 48000) == 3 * 59 * 1536, "3 frames @ 48 kHz");
    check(jtty_waveform_samples(0, 12000) == 0, "0 frames -> 0");
    check(jtty_waveform_samples(1, 0) == 0, "rate 0 -> 0");
    check(jtty_waveform_samples(17, 12000) == 0, "17 frames -> 0");

    uint8_t tones[59];
    jtty_encode_frame(0x026F78D41ull, tones);
    float* w = (float*)malloc(sizeof(float) * 22656);
    int n = jtty_modulate(tones, 1, 1500.0f, 12000, 0.9f, w, 22656);
    check(n == 22656, "modulate returns the sample count");
    check(jtty_modulate(tones, 1, 1500.0f, 12000, 0.9f, w, 22000) == JTTY_ERR_BAD_ARGS, "short output rejected");
    check(jtty_modulate(NULL, 1, 1500.0f, 12000, 0.9f, w, 22656) == JTTY_ERR_BAD_ARGS, "NULL tones rejected");
    float peak = 0.0f;
    for (int i = 0; i < n; ++i) if (fabsf(w[i]) > peak) peak = fabsf(w[i]);
    check_near(peak, 0.9, 0.01, "peak amplitude");
    check(fabsf(w[0]) < 1e-6f && fabsf(w[n - 1]) < 0.05f, "raised-cosine ramp at the ends");
    // Sync symbol 0 is tone 0: measure the frequency of samples 2..9 of the
    // first symbol (ramp over) by zero-crossing count.
    int crossings = 0;
    double first = -1, last = -1;
    for (int i = 60; i < 380; ++i) {
        if (w[i] <= 0.0f && w[i + 1] > 0.0f) {
            double t = i + (double)(-w[i]) / (double)(w[i + 1] - w[i]);
            if (first < 0) first = t;
            else { last = t; ++crossings; }
        }
    }
    double f = crossings * (double)FS / (last - first);
    check_near(f, 1500.0, 3.0, "sync tone 0 sits at f0");
    free(w);

    // 24 and 48 kHz produce the expected lengths through jtty_encode_text.
    float* w48 = (float*)malloc(sizeof(float) * 59 * 1536 * 2);
    int nf = 0;
    int n48 = jtty_encode_text("CQ K1ABC CQ", JTTY_PROFILE_GENERIC, 1500.0f, 48000, 0.5f, w48, 59 * 1536 * 2, &nf);
    check(n48 == 59 * 1536 && nf == 1, "encode_text @ 48 kHz");
    check(jtty_encode_text("", JTTY_PROFILE_GENERIC, 1500.0f, 12000, 0.5f, w48, 59 * 1536, &nf) == JTTY_ERR_EMPTY, "encode_text empty");
    free(w48);
}

static void test_clean_decode(void)
{
    printf("clean single frame\n");
    int n, start, nf;
    float* s = make_stream("CQ K1ABC CQ", JTTY_PROFILE_GENERIC, 1500.0f, 1.0113, 0.8, 0.5f, &n, &start, &nf);
    jtty_rx_t* rx = jtty_rx_create(FS);
    check(rx != NULL, "rx create");
    check(jtty_rx_create(48000) == NULL, "rx create rejects non-12 kHz");
    test_push_all(rx, s, n, 1024);
    jtty_rx_flush(rx);
    jtty_decode_t d[8];
    int nd = drain(rx, d, 8);
    check(nd == 1, "exactly one frame");
    if (nd >= 1) {
        check_eq_str(d[0].text, "CQ K1ABC CQ", "text");
        check(d[0].eom, "eom");
        check_near(d[0].freq_hz, 1500.0, 0.5, "frequency");
        check(llabs(d[0].start_sample - start) <= 2, "start sample within 2 samples");
        printf("  info: start %lld (true %d), dt %.1f us, snr %.1f dB, sync %.2f, rank %d\n",
               (long long)d[0].start_sample, start, d[0].dt_s * 1e6, d[0].snr_db, d[0].sync_score, d[0].list_rank);
        // The off-tone "noise" of a clean signal is GFSK transition leakage,
        // so the estimate saturates around +16 dB (as FT8's does near +20).
        check(d[0].snr_db > 12.0f, "clean signal reports high S/N (>= 12 dB, self-interference cap)");
    }
    jtty_rx_destroy(rx);
    free(s);

    // Reset clears everything and restarts the sample clock.
    rx = jtty_rx_create(FS);
    s = make_stream("K1ABC TU", JTTY_PROFILE_GENERIC, 800.0f, 0.3, 0.6, 0.5f, &n, &start, &nf);
    test_push_all(rx, s, n, 4096);
    jtty_rx_reset(rx);
    check(jtty_rx_samples_consumed(rx) == 0, "reset zeroes consumed");
    test_push_all(rx, s, n, 333);
    jtty_rx_flush(rx);
    nd = drain(rx, d, 8);
    check(nd == 1 && strcmp(d[0].text, "K1ABC TU") == 0, "decode after reset (odd chunk size)");
    check(nd == 1 && llabs(d[0].start_sample - start) <= 2, "start sample after reset");
    jtty_rx_destroy(rx);
    free(s);
}

static void test_noisy_offsets(void)
{
    printf("noise + frequency/time offsets\n");
    static const float offs[] = { -2.0f, -0.7f, 1.3f, 2.0f };
    for (size_t i = 0; i < sizeof offs / sizeof offs[0]; ++i) {
        float f0 = 1234.0f + offs[i];
        int n, start, nf;
        float* s = make_stream("599 CA", JTTY_PROFILE_RTTY_ROUNDUP, f0, 0.5 + 0.0371 * i, 0.7, 0.5f, &n, &start, &nf);
        test_add_awgn(s, n, test_noise_sigma(-10.0, 0.5));
        jtty_rx_t* rx = jtty_rx_create(FS);
        test_push_all(rx, s, n, 2048);
        jtty_rx_flush(rx);
        jtty_decode_t d[8];
        int nd = drain(rx, d, 8);
        char label[96];
        snprintf(label, sizeof label, "-10 dB, f0 %.1f Hz: decoded", f0);
        check(nd == 1 && strcmp(d[0].text, "599 CA") == 0, label);
        if (nd == 1) {
            check_near(d[0].freq_hz, f0, 0.6, "  frequency within 0.6 Hz");
            printf("  info: start error %lld samples\n", (long long)(d[0].start_sample - start));
            check(llabs(d[0].start_sample - start) <= 12, "  start within 1 ms");
            printf("  info: snr %.1f dB (true -10), rank %d\n", d[0].snr_db, d[0].list_rank);
            check(fabsf(d[0].snr_db + 10.0f) < 3.0f, "  S/N estimate within 3 dB");
        }
        jtty_rx_destroy(rx);
        free(s);
    }
}

static void test_multiframe(void)
{
    printf("multi-frame message\n");
    const char* text = "K1ABC W1AW 599 001 001 TU";
    int n, start, nf;
    float* s = make_stream(text, JTTY_PROFILE_RTTY_ROUNDUP, 1650.0f, 0.4, 0.9, 0.5f, &n, &start, &nf);
    check(nf == 5, "message packs to 5 frames");
    test_add_awgn(s, n, test_noise_sigma(-8.0, 0.5));
    jtty_rx_t* rx = jtty_rx_create(FS);
    test_push_all(rx, s, n, 1024);
    jtty_rx_flush(rx);
    jtty_decode_t d[16];
    int nd = drain(rx, d, 16);
    check(nd == nf, "all frames decoded, no duplicates");
    if (nd == nf) {
        bool order = true, spacing = true, eom = true;
        uint64_t p[16];
        for (int i = 0; i < nd; ++i) {
            p[i] = d[i].payload34;
            if (i > 0 && d[i].start_sample <= d[i - 1].start_sample) order = false;
            if (llabs(d[i].start_sample - (start + (int64_t)i * JTTY_FRAME_SAMPLES)) > 12) spacing = false;
            if (d[i].eom != (i == nd - 1)) eom = false;
        }
        check(order, "delivered in time order");
        check(spacing, "frame starts one frame period apart (within 1 ms)");
        check(eom, "EOM only on the last frame");
        char msg[JTTY_MAX_RENDER];
        jtty_render_message(p, nd, msg, sizeof msg);
        check_eq_str(msg, text, "rendered message");
    }
    jtty_rx_destroy(rx);
    free(s);
}

static void test_two_signals(void)
{
    printf("two simultaneous signals\n");
    int n1, s1, nf1, n2, s2, nf2;
    float* a = make_stream("CQ K1ABC CQ", JTTY_PROFILE_GENERIC, 1000.0f, 0.5, 0.6, 0.4f, &n1, &s1, &nf1);
    float* b = make_stream("CQ W1AW CQ", JTTY_PROFILE_GENERIC, 1800.0f, 0.9, 0.2, 0.2f, &n2, &s2, &nf2);
    int n = n1 > n2 ? n1 : n2;
    float* mix = (float*)calloc((size_t)n, sizeof(float));
    for (int i = 0; i < n1; ++i) mix[i] += a[i];
    for (int i = 0; i < n2; ++i) mix[i] += b[i];
    test_add_awgn(mix, n, test_noise_sigma(-6.0, 0.2));
    jtty_rx_t* rx = jtty_rx_create(FS);
    test_push_all(rx, mix, n, 4096);
    jtty_rx_flush(rx);
    jtty_decode_t d[8];
    int nd = drain(rx, d, 8);
    bool got_a = false, got_b = false;
    for (int i = 0; i < nd; ++i) {
        if (strcmp(d[i].text, "CQ K1ABC CQ") == 0 && fabsf(d[i].freq_hz - 1000.0f) < 1.0f) got_a = true;
        if (strcmp(d[i].text, "CQ W1AW CQ") == 0 && fabsf(d[i].freq_hz - 1800.0f) < 1.0f) got_b = true;
    }
    check(nd == 2 && got_a && got_b, "both overlapping-in-time signals decoded (1000 / 1800 Hz)");
    jtty_rx_destroy(rx);
    free(mix);

    // Spectrally overlapping: 60 Hz apart, 12 dB level difference. The weak
    // one needs the subtraction pass. Informational unless subtraction is on.
    for (int i = 0; i < n1; ++i) a[i] = 0.0f;
    for (int i = 0; i < n2; ++i) b[i] = 0.0f;
    free(a);
    free(b);
    a = make_stream("CQ K1ABC CQ", JTTY_PROFILE_GENERIC, 1500.0f, 0.5, 0.6, 0.5f, &n1, &s1, &nf1);
    b = make_stream("CQ W1AW CQ", JTTY_PROFILE_GENERIC, 1560.0f, 0.5 + 0.02, 0.6, 0.125f, &n2, &s2, &nf2);
    n = n1 > n2 ? n1 : n2;
    mix = (float*)calloc((size_t)n, sizeof(float));
    for (int i = 0; i < n1; ++i) mix[i] += a[i];
    for (int i = 0; i < n2; ++i) mix[i] += b[i];
    test_add_awgn(mix, n, test_noise_sigma(0.0, 0.125));
    for (int sub = 0; sub < 2; ++sub) {
        rx = jtty_rx_create(FS);
        jtty_rx_set_subtraction(rx, sub != 0);
        test_push_all(rx, mix, n, 4096);
        jtty_rx_flush(rx);
        nd = drain(rx, d, 8);
        got_a = got_b = false;
        for (int i = 0; i < nd; ++i) {
            if (strcmp(d[i].text, "CQ K1ABC CQ") == 0) got_a = true;
            if (strcmp(d[i].text, "CQ W1AW CQ") == 0) { got_b = true; printf("  info: weak signal found in pass %d\n", d[i].pass); }
        }
        printf("  info: 60 Hz apart, 12 dB weaker, subtraction %s: strong %s, weak %s\n", sub ? "on" : "off",
               got_a ? "yes" : "no", got_b ? "yes" : "no");
        if (sub) check(got_a && got_b, "overlapping pair decoded with subtraction");
        else check(got_a, "strong signal decoded without subtraction");
        jtty_rx_destroy(rx);
    }
    free(mix);
    free(a);
    free(b);
}

// Sensitivity: one stream per S/N holding `per` single-frame transmissions
// at random frequencies, separated by silence. Returns decoded count.
static int sweep_one(double snr_db, int per, uint64_t seed, double* audio_s, double* cpu_s, int* wrong)
{
    test_srand(seed);
    static const char* texts[] = { "CQ K1ABC CQ", "599 001", "W1AW TU", "FN42", "HELLO", "QSL TU", "05 NWT", "1D EMA" };
    const int gap = FS * 3 / 5;  // 0.6 s
    int cap = per * (JTTY_FRAME_SAMPLES + gap) + FS;
    float* s = (float*)calloc((size_t)cap, sizeof(float));
    char expect[16][16];
    int pos = FS / 2;
    for (int i = 0; i < per; ++i) {
        const char* t = texts[test_rand64() % (sizeof texts / sizeof texts[0])];
        strcpy(expect[i], t);
        float f0 = 400.0f + (float)(test_rand01() * 2200.0);
        int nf = 0;
        jtty_encode_text(t, JTTY_PROFILE_RTTY_ROUNDUP, f0, FS, 0.5f, s + pos, cap - pos, &nf);
        pos += JTTY_FRAME_SAMPLES + gap + (int)(test_rand64() % 97);
    }
    int n = pos;
    test_add_awgn(s, n, test_noise_sigma(snr_db, 0.5));

    jtty_rx_t* rx = jtty_rx_create(FS);
    double t0 = test_now();
    test_push_all(rx, s, n, 2048);
    jtty_rx_flush(rx);
    *cpu_s += test_now() - t0;
    *audio_s += (double)n / FS;

    int ok = 0;
    jtty_decode_t d;
    bool used[16] = { false };
    while (jtty_rx_pop(rx, &d)) {
        bool matched = false;
        for (int i = 0; i < per; ++i) {
            if (!used[i] && strcmp(d.text, expect[i]) == 0) {
                used[i] = true;
                matched = true;
                ++ok;
                break;
            }
        }
        if (!matched) ++*wrong;
    }
    jtty_rx_destroy(rx);
    free(s);
    return ok;
}

static void test_sensitivity_and_budget(void)
{
    printf("sensitivity sweep (S/N in 2500 Hz, wide-band 300-2700 search)\n");
    static const double snrs[] = { -6.0, -8.0, -10.0, -12.0, -13.0, -14.0, -15.0, -16.0 };
    const int per = 12;
    double audio = 0.0, cpu = 0.0;
    int wrong = 0;
    int ok_at_10 = 0;
    double threshold = 0.0;
    bool have_thr = false;
    for (size_t i = 0; i < sizeof snrs / sizeof snrs[0]; ++i) {
        int ok = sweep_one(snrs[i], per, 777 + (uint64_t)i, &audio, &cpu, &wrong);
        printf("  S/N %6.1f dB: %2d/%d frames\n", snrs[i], ok, per);
        if (snrs[i] == -10.0) ok_at_10 = ok;
        if (!have_thr && ok * 2 < per) {
            threshold = snrs[i];
            have_thr = true;
        }
    }
    if (have_thr) printf("  info: 50%% threshold near %.0f dB; %d wrong/extra decodes\n", threshold, wrong);
    else printf("  info: 50%% threshold below %.0f dB; %d wrong/extra decodes\n", snrs[sizeof snrs / sizeof snrs[0] - 1], wrong);
    printf("  info: real-time factor %.3f (%.1f s cpu for %.1f s audio)\n", cpu / audio, cpu, audio);
    check(ok_at_10 >= per - 1, ">= 11/12 frames at -10 dB");
    check(wrong == 0, "no wrong decodes");
    check(cpu / audio < 0.5, "real-time factor < 0.5 on the host");

    // Pure noise: no decodes in 40 s.
    printf("noise only\n");
    test_srand(4242);
    int n = FS * 40;
    float* s = (float*)calloc((size_t)n, sizeof(float));
    test_add_awgn(s, n, 0.3);
    jtty_rx_t* rx = jtty_rx_create(FS);
    test_push_all(rx, s, n, 4096);
    jtty_rx_flush(rx);
    jtty_decode_t d;
    int nd = 0;
    while (jtty_rx_pop(rx, &d)) ++nd;
    check(nd == 0, "no decodes from 40 s of noise");
    jtty_rx_destroy(rx);
    free(s);
}

static void test_priority_channel(void)
{
    printf("priority channel / narrow band\n");
    int n, start, nf;
    float* s = make_stream("GRID?", JTTY_PROFILE_GENERIC, 1512.0f, 0.6, 0.6, 0.5f, &n, &start, &nf);
    test_add_awgn(s, n, test_noise_sigma(-13.0, 0.5));
    jtty_rx_t* rx = jtty_rx_create(FS);
    jtty_rx_set_search_band(rx, 1400.0f, 1600.0f);
    jtty_rx_set_rx_frequency(rx, 1500.0f, 50.0f);
    jtty_rx_set_decode_budget(rx, 4);
    test_push_all(rx, s, n, 1200);
    jtty_rx_flush(rx);
    jtty_decode_t d[4];
    int nd = drain(rx, d, 4);
    check(nd == 1 && strcmp(d[0].text, "GRID?") == 0, "-13 dB frame on the RX channel decoded");
    jtty_rx_destroy(rx);

    // Null safety.
    check(jtty_rx_push(NULL, s, 1) == JTTY_ERR_BAD_ARGS, "push NULL rx");
    free(s);
    jtty_rx_t* r2 = jtty_rx_create(FS);
    check(jtty_rx_push(r2, NULL, 1) == JTTY_ERR_BAD_ARGS, "push NULL samples");
    check(jtty_rx_push(r2, NULL, 0) == 0, "push zero samples ok");
    check(!jtty_rx_pop(r2, NULL), "pop NULL out");
    jtty_rx_set_search_band(NULL, 0, 0);
    jtty_rx_destroy(NULL);
    jtty_rx_destroy(r2);
}

int main(void)
{
    test_srand(99);
    test_modulator();
    test_clean_decode();
    test_noisy_offsets();
    test_multiframe();
    test_two_signals();
    test_priority_channel();
    test_sensitivity_and_budget();
    printf("%s: %d failure(s)\n", "test_jtty_modem", g_failures);
    return g_failures ? 1 : 0;
}
