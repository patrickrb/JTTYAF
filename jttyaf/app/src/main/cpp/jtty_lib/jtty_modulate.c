// jtty_modulate.c — 4-GFSK synthesis (docs/jtty-protocol.md §1).
//
// The waveform generator is the FT8 GFSK modulator from kgoba/ft8_lib
// (demo/gen_ft8.c, MIT; the same algorithm lives in ft8af_glue/gfsk.c) with
// JTTY's parameters: symbol period 32 ms, BT = 2.0, tones 0..3, modulation
// index h = 1 (tone spacing = baud rate). Differences from the FT8 glue:
//
//   * the per-sample phase increment is evaluated on the fly from the three
//     Gaussian pulses that overlap each sample (previous, current, next
//     symbol) instead of accumulating a dphi[] array the size of the whole
//     transmission — a 16-frame message at 48 kHz would otherwise need a
//     5.8 MB scratch buffer;
//   * the phase accumulates in double so a 30 s transmission stays exact;
//   * the raised-cosine amplitude ramp (nsps/8 samples) is applied at the two
//     ends of the whole multi-frame transmission only, never between frames,
//     and the guard-symbol extension of the first/last tone happens at those
//     ends as well, giving the continuous phase across frames that the
//     receiver's coherent symbol blocks rely on.

#include "jtty_internal.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

// pi * sqrt(2 / ln 2) — normalisation constant of the Gaussian frequency pulse.
#define GFSK_CONST_K 5.336446f

int jtty_samples_per_symbol(int sample_rate)
{
    if (sample_rate <= 0) return 0;
    return (int)(0.5f + (float)sample_rate * JTTY_SYMBOL_PERIOD_S);
}

int jtty_waveform_samples(int n_frames, int sample_rate)
{
    if (n_frames <= 0 || n_frames > JTTY_MAX_FRAMES) return 0;
    int nsps = jtty_samples_per_symbol(sample_rate);
    if (nsps <= 0) return 0;
    int64_t n = (int64_t)n_frames * JTTY_FRAME_SYMBOLS * nsps;
    if (n > INT32_MAX) return 0;
    return (int)n;
}

// GFSK smoothing pulse spanning 3 symbol periods, pulse[3 * nsps].
static void gfsk_pulse(int nsps, float bt, float* pulse)
{
    for (int i = 0; i < 3 * nsps; ++i) {
        float t = (float)i / (float)nsps - 1.5f;
        float a1 = GFSK_CONST_K * bt * (t + 0.5f);
        float a2 = GFSK_CONST_K * bt * (t - 0.5f);
        pulse[i] = (erff(a1) - erff(a2)) / 2.0f;
    }
}

int jtty_gfsk_synth(const uint8_t* tones, int n_sym, float f0_hz, int sample_rate,
                    float amplitude, bool ramp, float* pulse_ws, float* re, float* im,
                    int max_out)
{
    if (tones == NULL || re == NULL || pulse_ws == NULL || n_sym <= 0) return JTTY_ERR_BAD_ARGS;
    int nsps = jtty_samples_per_symbol(sample_rate);
    if (nsps <= 0) return JTTY_ERR_BAD_ARGS;
    int64_t n_wave64 = (int64_t)n_sym * nsps;
    if (n_wave64 > max_out || n_wave64 > INT32_MAX) return JTTY_ERR_BAD_ARGS;
    int n_wave = (int)n_wave64;
    for (int i = 0; i < n_sym; ++i)
        if (tones[i] >= JTTY_NUM_TONES) return JTTY_ERR_BAD_ARGS;

    gfsk_pulse(nsps, JTTY_SYMBOL_BT, pulse_ws);
    const float* pulse = pulse_ws;

    const double dphi_base = 2.0 * M_PI * (double)f0_hz / (double)sample_rate;
    const double dphi_peak = 2.0 * M_PI / (double)nsps;  // h = 1: one tone step = one cycle/symbol

    double phi = 0.0;
    for (int m = 0; m < n_sym; ++m) {
        // Guard extension: the virtual symbols before the first and after the
        // last repeat the end tones so the Gaussian tails see no step.
        float t_prev = (float)tones[m > 0 ? m - 1 : 0];
        float t_cur  = (float)tones[m];
        float t_next = (float)tones[m + 1 < n_sym ? m + 1 : n_sym - 1];
        float* ro = re + (size_t)m * nsps;
        float* io = im ? im + (size_t)m * nsps : NULL;
        for (int j = 0; j < nsps; ++j) {
            // Symbol m-1's pulse is at its third third here, m's at its
            // middle third, m+1's at its first third.
            double dev = (double)(t_prev * pulse[j + 2 * nsps] + t_cur * pulse[j + nsps] + t_next * pulse[j]);
            // re + j*im = -j * exp(j*phi): the analytic (positive-frequency)
            // companion of the real waveform sin(phi), for the receiver's
            // complex gain fit when it subtracts a decoded frame.
            ro[j] = amplitude * (float)sin(phi);
            if (io) io[j] = -amplitude * (float)cos(phi);
            phi += dphi_base + dphi_peak * dev;
            if (phi > 2.0 * M_PI) phi -= 2.0 * M_PI;
        }
    }

    if (ramp) {
        int n_ramp = nsps / 8;
        for (int i = 0; i < n_ramp; ++i) {
            float env = (1.0f - cosf(2.0f * (float)M_PI * (float)i / (float)(2 * n_ramp))) / 2.0f;
            re[i] *= env;
            re[n_wave - 1 - i] *= env;
            if (im) {
                im[i] *= env;
                im[n_wave - 1 - i] *= env;
            }
        }
    }
    return n_wave;
}

int jtty_modulate(const uint8_t* tones, int n_frames, float f0_hz, int sample_rate,
                  float amplitude, float* out, int max_out)
{
    if (tones == NULL || out == NULL) return JTTY_ERR_BAD_ARGS;
    int n_wave = jtty_waveform_samples(n_frames, sample_rate);
    if (n_wave <= 0 || n_wave > max_out) return JTTY_ERR_BAD_ARGS;
    if (!(f0_hz > 0.0f) || f0_hz + 3.0f * JTTY_TONE_SPACING_HZ >= (float)sample_rate / 2.0f)
        return JTTY_ERR_BAD_ARGS;
    if (amplitude < 0.0f) amplitude = 0.0f;
    if (amplitude > 1.0f) amplitude = 1.0f;

    int nsps = jtty_samples_per_symbol(sample_rate);
    float* pulse = (float*)malloc(sizeof(float) * (size_t)(3 * nsps));
    if (pulse == NULL) return JTTY_ERR_NOMEM;
    int n = jtty_gfsk_synth(tones, n_frames * JTTY_FRAME_SYMBOLS, f0_hz, sample_rate,
                            amplitude, true, pulse, out, NULL, max_out);
    free(pulse);
    return n;
}

int jtty_encode_text(const char* text, jtty_profile_t profile, float f0_hz, int sample_rate,
                     float amplitude, float* out, int max_out, int* n_frames_out)
{
    if (n_frames_out) *n_frames_out = 0;
    if (text == NULL || out == NULL) return JTTY_ERR_BAD_ARGS;

    uint64_t payloads[JTTY_MAX_FRAMES];
    int nf = jtty_pack_text(text, profile, payloads, JTTY_MAX_FRAMES);
    if (nf < 0) return nf;

    uint8_t tones[JTTY_MAX_FRAMES * JTTY_FRAME_SYMBOLS];
    for (int f = 0; f < nf; ++f)
        jtty_encode_frame(payloads[f], tones + f * JTTY_FRAME_SYMBOLS);

    int n = jtty_modulate(tones, nf, f0_hz, sample_rate, amplitude, out, max_out);
    if (n > 0 && n_frames_out) *n_frames_out = nf;
    return n;
}
