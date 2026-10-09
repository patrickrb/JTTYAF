// jtty_test_util.h — shared helpers for the jtty_lib host tests.
//
// Same shape as sstvaf_glue/sstv_test_util.h: each test_jtty_*.c has its own
// main(), a check() helper, and exits nonzero on any failure. Included once
// per test binary, so it defines the failure counter itself.

#ifndef JTTYAF_GLUE_JTTY_TEST_UTIL_H
#define JTTYAF_GLUE_JTTY_TEST_UTIL_H

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "jtty.h"
#include "jtty_internal.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static int g_failures = 0;

static void check(int cond, const char* label)
{
    if (cond) {
        printf("  ok:   %s\n", label);
    } else {
        printf("  FAIL: %s\n", label);
        ++g_failures;
    }
}

static void check_eq_str(const char* got, const char* want, const char* label)
{
    if (strcmp(got, want) == 0) {
        printf("  ok:   %s (\"%s\")\n", label, got);
    } else {
        printf("  FAIL: %s (got \"%s\", want \"%s\")\n", label, got, want);
        ++g_failures;
    }
}

static void check_near(double got, double want, double tol, const char* label)
{
    if (fabs(got - want) <= tol) {
        printf("  ok:   %s (got %.3f, want %.3f)\n", label, got, want);
    } else {
        printf("  FAIL: %s (got %.3f, want %.3f, tol %.3f)\n", label, got, want, tol);
        ++g_failures;
    }
}

// ---------------------------------------------------------------------------
// Deterministic RNG (xorshift64) + Box-Muller gaussian.
// ---------------------------------------------------------------------------
static uint64_t g_rng_state = 0x243F6A8885A308D3ull;

static void test_srand(uint64_t seed) { g_rng_state = seed ? seed : 1; }

static uint64_t test_rand64(void)
{
    uint64_t x = g_rng_state;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    g_rng_state = x;
    return x;
}

static double test_rand01(void) { return (double)(test_rand64() >> 11) / 9007199254740992.0; }

static double test_gauss(void)
{
    double u1 = test_rand01(), u2 = test_rand01();
    if (u1 < 1e-300) u1 = 1e-300;
    return sqrt(-2.0 * log(u1)) * cos(2.0 * M_PI * u2);
}

// Add white gaussian noise so that a unit-amplitude sine (power 1/2) has the
// requested S/N in the 2500 Hz reference bandwidth at 12 kHz:
//   noise power in 2500 Hz = 0.5 / 10^(snr/10) ; sigma^2 = that * 6000/2500.
static double test_noise_sigma(double snr_db_2500, double amplitude)
{
    double sig_power = 0.5 * amplitude * amplitude;
    double n2500 = sig_power / pow(10.0, snr_db_2500 / 10.0);
    return sqrt(n2500 * (6000.0 / 2500.0));
}

static void test_add_awgn(float* buf, int n, double sigma)
{
    for (int i = 0; i < n; ++i) buf[i] += (float)(sigma * test_gauss());
}

// ---------------------------------------------------------------------------
// Push a buffer through the receiver in audio-callback sized chunks.
// ---------------------------------------------------------------------------
static void test_push_all(jtty_rx_t* rx, const float* buf, int n, int chunk)
{
    for (int off = 0; off < n; off += chunk) {
        int c = (n - off < chunk) ? (n - off) : chunk;
        jtty_rx_push(rx, buf + off, c);
    }
}

// ---------------------------------------------------------------------------
// Minimal 16-bit PCM WAV I/O (mono; stereo is averaged on read).
// ---------------------------------------------------------------------------
static uint32_t rd_u32(const uint8_t* p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
static uint16_t rd_u16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }

// Returns malloc'd float samples (scaled to +/-1), or NULL. *rate_out gets the
// sample rate.
static float* test_wav_read(const char* path, int* n_out, int* rate_out)
{
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size < 44) { fclose(f); return NULL; }
    uint8_t* d = (uint8_t*)malloc((size_t)size);
    if (!d || fread(d, 1, (size_t)size, f) != (size_t)size) { free(d); fclose(f); return NULL; }
    fclose(f);
    if (memcmp(d, "RIFF", 4) != 0 || memcmp(d + 8, "WAVE", 4) != 0) { free(d); return NULL; }

    int channels = 0, rate = 0, bits = 0, fmt = 0;
    const uint8_t* data = NULL;
    uint32_t data_len = 0;
    long pos = 12;
    while (pos + 8 <= size) {
        uint32_t clen = rd_u32(d + pos + 4);
        const uint8_t* body = d + pos + 8;
        if ((long)clen > size - pos - 8) clen = (uint32_t)(size - pos - 8);
        if (memcmp(d + pos, "fmt ", 4) == 0 && clen >= 16) {
            fmt = rd_u16(body);
            channels = rd_u16(body + 2);
            rate = (int)rd_u32(body + 4);
            bits = rd_u16(body + 14);
        } else if (memcmp(d + pos, "data", 4) == 0) {
            data = body;
            data_len = clen;
        }
        pos += 8 + (long)clen + (clen & 1);
    }
    if (!data || fmt != 1 || bits != 16 || channels < 1 || rate <= 0) { free(d); return NULL; }
    int frames = (int)(data_len / (uint32_t)(2 * channels));
    float* out = (float*)malloc(sizeof(float) * (size_t)(frames > 0 ? frames : 1));
    if (!out) { free(d); return NULL; }
    for (int i = 0; i < frames; ++i) {
        double acc = 0.0;
        for (int c = 0; c < channels; ++c)
            acc += (int16_t)rd_u16(data + (size_t)(i * channels + c) * 2);
        out[i] = (float)(acc / channels / 32768.0);
    }
    free(d);
    *n_out = frames;
    *rate_out = rate;
    return out;
}

static void wr_u32(FILE* f, uint32_t v) { uint8_t b[4] = { (uint8_t)v, (uint8_t)(v >> 8), (uint8_t)(v >> 16), (uint8_t)(v >> 24) }; fwrite(b, 1, 4, f); }
static void wr_u16(FILE* f, uint16_t v) { uint8_t b[2] = { (uint8_t)v, (uint8_t)(v >> 8) }; fwrite(b, 1, 2, f); }

static int test_wav_write(const char* path, const float* x, int n, int rate)
{
    FILE* f = fopen(path, "wb");
    if (!f) return -1;
    fwrite("RIFF", 1, 4, f);
    wr_u32(f, 36u + 2u * (uint32_t)n);
    fwrite("WAVEfmt ", 1, 8, f);
    wr_u32(f, 16);
    wr_u16(f, 1);
    wr_u16(f, 1);
    wr_u32(f, (uint32_t)rate);
    wr_u32(f, (uint32_t)rate * 2u);
    wr_u16(f, 2);
    wr_u16(f, 16);
    fwrite("data", 1, 4, f);
    wr_u32(f, 2u * (uint32_t)n);
    for (int i = 0; i < n; ++i) {
        double v = x[i];
        if (v > 1.0) v = 1.0;
        if (v < -1.0) v = -1.0;
        wr_u16(f, (uint16_t)(int16_t)lrint(v * 32767.0));
    }
    fclose(f);
    return 0;
}

// Wall-clock seconds for the real-time budget measurements.
static double test_now(void)
{
    return (double)clock() / (double)CLOCKS_PER_SEC;
}

#endif // JTTYAF_GLUE_JTTY_TEST_UTIL_H
