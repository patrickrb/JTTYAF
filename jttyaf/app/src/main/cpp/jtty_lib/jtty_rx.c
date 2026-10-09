// jtty_rx.c — streaming JTTY receiver (docs/jtty-protocol.md §5, as a guide).
//
// Push model: audio is appended to a linear buffer; every quarter frame
// (0.472 s) an analysis window of 1.25 frames is searched for frames that
// *start* inside that quarter. Each window:
//
//  1. Spectrogram. Symbol-length (384-sample) blocks every 48 samples
//     (1/8 symbol), zero padded to a 1536-point FFT so bins are 7.8125 Hz and
//     the 31.25 Hz tone spacing is exactly 4 bins. Rows are cached across
//     windows and only recomputed where a subtracted signal changed the
//     audio, so the steady-state cost is 118 new rows per step (59 FFTs with
//     the two-real-signals-in-one-complex-FFT trick).
//  2. Sync surface. Non-coherent 13-symbol sync correlation
//     S(t,f) = sum_i P[t + 8i][f + 4*sync_i], normalised by the window's
//     noise floor and smoothed 1-2-3-2-1 across frequency. Local maxima
//     above a threshold are candidates, the operator's RX channel first.
//  3. Refinement. Direct complex correlation of the 13 sync symbols at the
//     candidate; time is walked in ever finer steps (12 -> 3 -> 1 samples)
//     and frequency is refined by rotating the 13 phasors (the phase-slope
//     fit of the design notes, done as a small grid search so it works at
//     low S/N where unwrapping fails). Gates: nsync (sync tone is the
//     strongest of four) and a coherence score.
//  4. Demodulation. With h = 1 the carrier phase at every symbol boundary is
//     the same (mod 2 pi) regardless of the tones sent, so per-symbol
//     complex tone correlations can be summed coherently over blocks of
//     L = 1, 2, 4 symbols; each rung yields per-tone metrics for the list
//     Viterbi decoder and the first rung that produces a CRC+grammar valid
//     payload wins.
//  5. Acceptance. Duplicates (12 Hz / 50 ms of a reported frame) are
//     dropped, S/N in 2500 Hz is estimated from the tone correlations, the
//     frame is queued, and (optionally) its GFSK waveform is re-synthesised,
//     fitted with a complex gain, subtracted from the buffer and the window
//     is searched again so overlapping weaker signals surface.
//
// Frame-to-message chaining is deliberately not done here (the Kotlin layer
// owns it); this file delivers frames.

#include "jtty_internal.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>
#ifdef JTTY_RX_DEBUG
#include <stdio.h>
#endif

// ---------------------------------------------------------------------------
// Geometry (12 kHz only)
// ---------------------------------------------------------------------------

#define FS            12000
#define NSPS          JTTY_NSPS                 // 384
#define FRAME         JTTY_FRAME_SAMPLES        // 22656
#define HOP           48                        // coarse time grid, 1/8 symbol
#define HOPS_PER_SYM  (NSPS / HOP)              // 8
#define NFFT          1536                      // 4x zero-padded symbol
#define BIN_HZ        ((float)FS / (float)NFFT) // 7.8125 Hz
#define NBINS         (NFFT / 2 + 1)            // 769 (0..6000 Hz)
#define BINS_PER_TONE 4
#define STEP_HOPS     118                       // quarter frame = 5664 samples
#define STEP          (STEP_HOPS * HOP)
#define PRE           96                        // slack before the window start
#define POST          96
#define WINDOW        (PRE + STEP + FRAME + POST)                 // 28512
#define ROWS          (PRE / HOP + STEP_HOPS + HOPS_PER_SYM * 12 + 1) // 217
#define BUF_CAP       (WINDOW + FS)
#define MAX_CANDS     64
#define MAX_PASSES    3
#define QUEUE_CAP     64
#define RECENT_CAP    64
#define DEFAULT_BUDGET 24
#define NOISE_SAMPLES 4096

// Candidate gates.
// Noise-only sync sums normalise to mean 1 with sigma ~0.25 after the
// 1-2-3-2-1 smoothing; the maximum over a window's ~40k (t, f) cells sits
// near 2.3-2.5. A -15 dB signal scores ~3, so 2.1 keeps those while
// leaving most of the budget free for real candidates.
#define THR_WIDE       2.1f   // normalised sync-surface score, wide-band search
#define THR_PRIO       1.7f   // ... on the operator's RX channel (far fewer cells)
#define NSYNC_WIDE     9      // of 13 sync symbols won by the sync tone
#define NSYNC_PRIO     7
#define DUP_HZ         12.0f
#define DUP_SAMPLES    600    // 50 ms

typedef struct {
    float re, im;
} cpx_t;

typedef struct {
    float score;
    float f0;
    int   t0;     // buffer offset of the coarse frame start
    bool  prio;
} cand_t;

struct jtty_rx {
    // configuration
    float f_lo, f_hi;
    float f_rx, f_tol;
    int   budget;
    bool  subtract;

    // audio buffer: buf[i] is absolute sample buf_abs + i
    float*  buf;
    int     buf_len;
    int64_t buf_abs;
    int64_t consumed;
    int64_t next_pos;   // absolute start of the next analysis window's search region

    // spectrogram rows (row r <-> buffer offset r * HOP)
    float*  spec;        // ROWS * NBINS power values
    uint8_t row_valid[ROWS];

    // FFT
    cpx_t* tw;           // NFFT twiddles
    cpx_t* fft_in;
    cpx_t* fft_out;

    // sync surface
    float* ssurf;        // STEP_HOPS * NBINS
    cand_t cands[MAX_CANDS];
    float  noise_scratch[NOISE_SAMPLES];

    // demodulation scratch
    cpx_t  ek[JTTY_NUM_TONES][NSPS];   // tone templates for the current f0
    cpx_t  rot[JTTY_FRAME_SYMBOLS];    // per-symbol carrier rotation at f0
    cpx_t  ph[JTTY_FRAME_SYMBOLS][JTTY_NUM_TONES];
    float  llr[JTTY_DATA_SYMBOLS][JTTY_NUM_TONES];
    jtty_viterbi_ws_t vit;

    // subtraction scratch
    float* sub_re;
    float* sub_im;
    float  pulse_ws[3 * NSPS];

    // results
    jtty_decode_t queue[QUEUE_CAP];
    int           queue_n;
    struct { float f; int64_t t; } recent[RECENT_CAP];
    int recent_n, recent_pos;
};

// ---------------------------------------------------------------------------
// Small complex helpers
// ---------------------------------------------------------------------------

static inline cpx_t cmul(cpx_t a, cpx_t b)
{
    cpx_t r = { a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re };
    return r;
}
static inline float cabs2(cpx_t a) { return a.re * a.re + a.im * a.im; }

// ---------------------------------------------------------------------------
// Mixed-radix (2, 3) FFT, N = 1536 = 2^9 * 3. Recursive decimation in time
// is plenty fast for 118 transforms per 0.47 s of audio.
// ---------------------------------------------------------------------------

static void fft_rec(const cpx_t* tw, int N, const cpx_t* x, int stride, cpx_t* y, int n)
{
    if (n == 1) {
        y[0] = x[0];
        return;
    }
    int ts = N / n;  // twiddle stride for this level
    if ((n & 1) == 0) {
        int h = n / 2;
        fft_rec(tw, N, x, stride * 2, y, h);
        fft_rec(tw, N, x + stride, stride * 2, y + h, h);
        for (int k = 0; k < h; ++k) {
            cpx_t t = cmul(tw[k * ts], y[h + k]);
            y[h + k].re = y[k].re - t.re;
            y[h + k].im = y[k].im - t.im;
            y[k].re += t.re;
            y[k].im += t.im;
        }
    } else {
        // n divisible by 3 (the only odd factor we ever see)
        int h = n / 3;
        fft_rec(tw, N, x, stride * 3, y, h);
        fft_rec(tw, N, x + stride, stride * 3, y + h, h);
        fft_rec(tw, N, x + 2 * stride, stride * 3, y + 2 * h, h);
        const float c3 = -0.5f, s3 = -0.86602540378f;  // exp(-j 2pi/3)
        for (int k = 0; k < h; ++k) {
            cpx_t a = y[k];
            cpx_t b = cmul(tw[k * ts], y[h + k]);
            cpx_t c = cmul(tw[2 * k * ts], y[2 * h + k]);
            cpx_t sum = { b.re + c.re, b.im + c.im };
            cpx_t dif = { b.re - c.re, b.im - c.im };
            y[k].re = a.re + sum.re;
            y[k].im = a.im + sum.im;
            // a + c3*sum +/- j*s3*dif
            y[h + k].re = a.re + c3 * sum.re - s3 * dif.im;
            y[h + k].im = a.im + c3 * sum.im + s3 * dif.re;
            y[2 * h + k].re = a.re + c3 * sum.re + s3 * dif.im;
            y[2 * h + k].im = a.im + c3 * sum.im - s3 * dif.re;
        }
    }
}

// ---------------------------------------------------------------------------
// Spectrogram rows
// ---------------------------------------------------------------------------

// Compute rows ra and rb (rb may be -1) with one complex FFT: the block of
// row ra goes into the real part, row rb into the imaginary part, and the
// two spectra are separated by conjugate symmetry.
static void compute_row_pair(jtty_rx_t* rx, int ra, int rb)
{
    const float* xa = rx->buf + (size_t)ra * HOP;
    const float* xb = rb >= 0 ? rx->buf + (size_t)rb * HOP : NULL;
    cpx_t* in = rx->fft_in;
    for (int n = 0; n < NSPS; ++n) {
        in[n].re = xa[n];
        in[n].im = xb ? xb[n] : 0.0f;
    }
    memset(in + NSPS, 0, sizeof(cpx_t) * (size_t)(NFFT - NSPS));
    fft_rec(rx->tw, NFFT, in, 1, rx->fft_out, NFFT);

    const cpx_t* Z = rx->fft_out;
    float* pa = rx->spec + (size_t)ra * NBINS;
    float* pb = rb >= 0 ? rx->spec + (size_t)rb * NBINS : NULL;
    for (int k = 0; k < NBINS; ++k) {
        cpx_t z = Z[k];
        cpx_t zc = Z[(NFFT - k) % NFFT];
        // Xa = (Z[k] + conj(Z[N-k])) / 2 ; Xb = (Z[k] - conj(Z[N-k])) / 2j
        float ar = 0.5f * (z.re + zc.re), ai = 0.5f * (z.im - zc.im);
        float br = 0.5f * (z.im + zc.im), bi = -0.5f * (z.re - zc.re);
        pa[k] = ar * ar + ai * ai;
        if (pb) pb[k] = br * br + bi * bi;
    }
    rx->row_valid[ra] = 1;
    if (rb >= 0) rx->row_valid[rb] = 1;
}

static void ensure_rows(jtty_rx_t* rx)
{
    int pending = -1;
    for (int r = 0; r < ROWS; ++r) {
        if (rx->row_valid[r]) continue;
        if (pending < 0) {
            pending = r;
        } else {
            compute_row_pair(rx, pending, r);
            pending = -1;
        }
    }
    if (pending >= 0) compute_row_pair(rx, pending, -1);
}

static void invalidate_rows(jtty_rx_t* rx, int t_from, int t_to)
{
    for (int r = 0; r < ROWS; ++r) {
        int s = r * HOP;
        if (s < t_to && s + NSPS > t_from) rx->row_valid[r] = 0;
    }
}

// ---------------------------------------------------------------------------
// Noise floor: median bin power over the search band, converted to the mean
// of the (exponential) noise distribution so a noise-only sync sum
// normalises to ~1.
// ---------------------------------------------------------------------------

static float select_kth(float* a, int n, int k)
{
    int lo = 0, hi = n - 1;
    while (lo < hi) {
        float pivot = a[(lo + hi) / 2];
        int i = lo, j = hi;
        while (i <= j) {
            while (a[i] < pivot) ++i;
            while (a[j] > pivot) --j;
            if (i <= j) {
                float t = a[i];
                a[i] = a[j];
                a[j] = t;
                ++i;
                --j;
            }
        }
        if (k <= j) hi = j;
        else if (k >= i) lo = i;
        else break;
    }
    return a[k];
}

static void band_bins(const jtty_rx_t* rx, int* b_lo, int* b_hi)
{
    int lo = (int)floorf(rx->f_lo / BIN_HZ);
    int hi = (int)ceilf(rx->f_hi / BIN_HZ);
    // Need 3 tones (12 bins) plus the 2-bin smoothing kernel above f0.
    if (lo < 2) lo = 2;
    if (hi > NBINS - 1 - 3 * BINS_PER_TONE - 2) hi = NBINS - 1 - 3 * BINS_PER_TONE - 2;
    if (hi < lo) hi = lo;
    *b_lo = lo;
    *b_hi = hi;
}

static float noise_floor(jtty_rx_t* rx)
{
    int b_lo, b_hi;
    band_bins(rx, &b_lo, &b_hi);
    int nb = b_hi + 3 * BINS_PER_TONE - b_lo + 1;
    // Spread the samples over the whole window and band.
    int total = ROWS * nb;
    int stride = total / NOISE_SAMPLES;
    if (stride < 1) stride = 1;
    int n = 0;
    for (int idx = 0; idx < total && n < NOISE_SAMPLES; idx += stride) {
        int r = idx / nb, b = b_lo + idx % nb;
        rx->noise_scratch[n++] = rx->spec[(size_t)r * NBINS + b];
    }
    if (n == 0) return 1.0f;
    float med = select_kth(rx->noise_scratch, n, n / 2);
    float mean = med / 0.6931472f;  // exponential: median = mean * ln 2
    return mean > 1e-20f ? mean : 1e-20f;
}

// ---------------------------------------------------------------------------
// Sync surface and candidate selection
// ---------------------------------------------------------------------------

static int cmp_cand(const void* pa, const void* pb)
{
    const cand_t* a = (const cand_t*)pa;
    const cand_t* b = (const cand_t*)pb;
    if (a->prio != b->prio) return a->prio ? -1 : 1;
    if (a->score > b->score) return -1;
    if (a->score < b->score) return 1;
    return 0;
}

static int find_candidates(jtty_rx_t* rx, float floor_mean)
{
    int b_lo, b_hi;
    band_bins(rx, &b_lo, &b_hi);
    const float norm = 1.0f / (9.0f * 13.0f * floor_mean);
    const int k_first = PRE / HOP;

    // Raw sync sums into the surface, then smooth in place via a scratch row.
    for (int k = 0; k < STEP_HOPS; ++k) {
        float* S = rx->ssurf + (size_t)k * NBINS;
        int r0 = k_first + k;
        for (int b = b_lo - 2; b <= b_hi + 2; ++b) {
            float acc = 0.0f;
            for (int i = 0; i < JTTY_SYNC_SYMBOLS; ++i)
                acc += rx->spec[(size_t)(r0 + HOPS_PER_SYM * i) * NBINS + b + BINS_PER_TONE * JTTY_SYNC[i]];
            S[b] = acc;
        }
        float prev2 = S[b_lo - 2], prev1 = S[b_lo - 1];
        for (int b = b_lo; b <= b_hi; ++b) {
            float cur = S[b];
            float sm = (prev2 + 2.0f * prev1 + 3.0f * cur + 2.0f * S[b + 1] + S[b + 2]) * norm;
            prev2 = prev1;
            prev1 = cur;
            S[b] = sm;
        }
    }

    // Local maxima above threshold.
    int n = 0;
    bool have_prio = rx->f_tol > 0.0f;
    for (int k = 0; k < STEP_HOPS; ++k) {
        const float* S = rx->ssurf + (size_t)k * NBINS;
        for (int b = b_lo; b <= b_hi; ++b) {
            float v = S[b];
            float f0 = (float)b * BIN_HZ;
            bool prio = have_prio && fabsf(f0 - rx->f_rx) <= rx->f_tol;
            if (v < (prio ? THR_PRIO : THR_WIDE)) continue;
            bool is_max = true;
            for (int dk = -4; dk <= 4 && is_max; ++dk) {
                int kk = k + dk;
                if (kk < 0 || kk >= STEP_HOPS) continue;
                const float* S2 = rx->ssurf + (size_t)kk * NBINS;
                for (int db = -4; db <= 4; ++db) {
                    int bb = b + db;
                    if (bb < b_lo || bb > b_hi || (dk == 0 && db == 0)) continue;
                    float w = S2[bb];
                    // Strict on one side so plateaus yield one candidate.
                    if (w > v || (w == v && (dk < 0 || (dk == 0 && db < 0)))) {
                        is_max = false;
                        break;
                    }
                }
            }
            if (!is_max) continue;
            // Parabolic interpolation over the neighbouring bins gives a
            // first frequency estimate good to ~1 Hz, so the refinement's
            // +/-8 Hz search never starts a whole bin off.
            if (b > b_lo && b < b_hi) {
                float den = S[b - 1] - 2.0f * v + S[b + 1];
                if (den < 0.0f) {
                    float off = 0.5f * (S[b - 1] - S[b + 1]) / den;
                    if (off > 0.5f) off = 0.5f;
                    if (off < -0.5f) off = -0.5f;
                    f0 += off * BIN_HZ;
                }
            }
            cand_t c = { v, f0, PRE + k * HOP, prio };
            if (n < MAX_CANDS) {
                rx->cands[n++] = c;
            } else {
                // Replace the weakest non-priority entry if this one is better.
                int worst = -1;
                for (int i = 0; i < n; ++i)
                    if (!rx->cands[i].prio && (worst < 0 || rx->cands[i].score < rx->cands[worst].score))
                        worst = i;
                if (worst >= 0 && (c.prio || c.score > rx->cands[worst].score)) rx->cands[worst] = c;
            }
        }
    }
    qsort(rx->cands, (size_t)n, sizeof(cand_t), cmp_cand);
    return n;
}

// ---------------------------------------------------------------------------
// Tone correlations
// ---------------------------------------------------------------------------

// Templates for the lowest tone f0: ek[k][n] = exp(-j 2pi (f0 + k df) n / fs)
// over one symbol and rot[i] = exp(-j 2pi f0 * NSPS * i / fs). Because
// df * NSPS / fs = 1, the k-dependent part repeats every symbol, so the
// correlation of symbol i with tone k is rot[i] * sum_n x[i*NSPS+n] ek[k][n]
// and every symbol's phasor is referred to the same carrier phase.
static void make_templates(jtty_rx_t* rx, float f0)
{
    double w0 = 2.0 * M_PI * (double)f0 / FS;
    double wt = 2.0 * M_PI * (double)JTTY_TONE_SPACING_HZ / FS;
    for (int n = 0; n < NSPS; ++n) {
        for (int k = 0; k < JTTY_NUM_TONES; ++k) {
            double a = -(w0 + k * wt) * n;
            rx->ek[k][n].re = (float)cos(a);
            rx->ek[k][n].im = (float)sin(a);
        }
    }
    for (int i = 0; i < JTTY_FRAME_SYMBOLS; ++i) {
        double a = -w0 * (double)NSPS * i;
        rx->rot[i].re = (float)cos(a);
        rx->rot[i].im = (float)sin(a);
    }
}

static inline cpx_t corr_symbol(const float* x, const cpx_t* e)
{
    float re = 0.0f, im = 0.0f;
    for (int n = 0; n < NSPS; ++n) {
        re += x[n] * e[n].re;
        im += x[n] * e[n].im;
    }
    cpx_t c = { re, im };
    return c;
}

// Phasors of the 13 sync symbols (sync tone only) for a frame starting at
// buffer offset t.
static void sync_phasors(const jtty_rx_t* rx, int t, cpx_t out[JTTY_SYNC_SYMBOLS])
{
    for (int i = 0; i < JTTY_SYNC_SYMBOLS; ++i) {
        cpx_t c = corr_symbol(rx->buf + t + i * NSPS, rx->ek[JTTY_SYNC[i]]);
        out[i] = cmul(c, rx->rot[i]);
    }
}

// All four tone phasors for all 59 symbols.
static void frame_phasors(jtty_rx_t* rx, int t)
{
    for (int i = 0; i < JTTY_FRAME_SYMBOLS; ++i)
        for (int k = 0; k < JTTY_NUM_TONES; ++k)
            rx->ph[i][k] = cmul(corr_symbol(rx->buf + t + i * NSPS, rx->ek[k]), rx->rot[i]);
}

// Coherent sync power after removing a frequency offset df (Hz): the phasor
// of sync symbol i is rotated back by 2 pi df * i * T.
static float coherent_sync(const cpx_t c[JTTY_SYNC_SYMBOLS], float df)
{
    float w = -2.0f * (float)M_PI * df * ((float)NSPS / (float)FS);
    float sr = 0.0f, si = 0.0f;
    for (int i = 0; i < JTTY_SYNC_SYMBOLS; ++i) {
        float a = w * (float)i;
        float cr = cosf(a), ci = sinf(a);
        sr += c[i].re * cr - c[i].im * ci;
        si += c[i].re * ci + c[i].im * cr;
    }
    return sr * sr + si * si;
}

// One refinement stage: time shifts dt in [-t_span, t_span] step t_step and
// frequency offsets in [-f_span, f_span] step f_step around (t, f). Updates
// t and f in place; returns the best score and fills s3[] with the scores at
// the best dt - t_step, dt, dt + t_step (for the parabolic sub-step fit).
static float refine_stage(jtty_rx_t* rx, int* t, float* f, int t_span, int t_step,
                          float f_span, float f_step, float s3[3])
{
    make_templates(rx, *f);
    int n_dt = 2 * (t_span / t_step) + 1;
    float best = -1.0f, best_df = 0.0f;
    int best_dt = 0;
    float scores[64];  // per dt, at its own best df (n_dt <= 9 here)
    for (int a = 0; a < n_dt; ++a) {
        int dt = -t_span + a * t_step;
        int tt = *t + dt;
        if (tt < 0 || tt + FRAME > WINDOW) {
            scores[a] = -1.0f;
            continue;
        }
        cpx_t c[JTTY_SYNC_SYMBOLS];
        sync_phasors(rx, tt, c);
        float sbest = -1.0f, dfbest = 0.0f;
        for (float df = -f_span; df <= f_span + 1e-6f; df += f_step) {
            float s = coherent_sync(c, df);
            if (s > sbest) {
                sbest = s;
                dfbest = df;
            }
        }
        scores[a] = sbest;
        if (sbest > best) {
            best = sbest;
            best_dt = dt;
            best_df = dfbest;
        }
    }
    int ab = (best_dt + t_span) / t_step;
    s3[0] = ab > 0 ? scores[ab - 1] : -1.0f;
    s3[1] = scores[ab];
    s3[2] = ab + 1 < n_dt ? scores[ab + 1] : -1.0f;
    *t += best_dt;
    *f += best_df;
    return best;
}

// ---------------------------------------------------------------------------
// Metric rungs for the decoder
// ---------------------------------------------------------------------------

// L = 1: non-coherent per-symbol tone energies.
static void metrics_noncoherent(const cpx_t ph[JTTY_FRAME_SYMBOLS][JTTY_NUM_TONES],
                                float m[JTTY_DATA_SYMBOLS][JTTY_NUM_TONES])
{
    for (int i = 0; i < JTTY_DATA_SYMBOLS; ++i)
        for (int k = 0; k < JTTY_NUM_TONES; ++k)
            m[i][k] = cabs2(ph[JTTY_SYNC_SYMBOLS + i][k]);
}

// L = 2 or 4: for every block of L consecutive data symbols, every 4^L tone
// hypothesis is summed coherently; a symbol's metric for tone k is the best
// block energy among hypotheses that assign it tone k. The last block is
// shortened when 46 is not a multiple of L.
static void metrics_block(const cpx_t ph[JTTY_FRAME_SYMBOLS][JTTY_NUM_TONES], int L,
                          float m[JTTY_DATA_SYMBOLS][JTTY_NUM_TONES])
{
    for (int i = 0; i < JTTY_DATA_SYMBOLS; ++i)
        for (int k = 0; k < JTTY_NUM_TONES; ++k) m[i][k] = 0.0f;

    for (int b = 0; b < JTTY_DATA_SYMBOLS; b += L) {
        int len = (b + L <= JTTY_DATA_SYMBOLS) ? L : JTTY_DATA_SYMBOLS - b;
        int nh = 1 << (2 * len);
        for (int h = 0; h < nh; ++h) {
            float sr = 0.0f, si = 0.0f;
            for (int q = 0; q < len; ++q) {
                int k = (h >> (2 * q)) & 3;
                const cpx_t* c = &ph[JTTY_SYNC_SYMBOLS + b + q][k];
                sr += c->re;
                si += c->im;
            }
            float e = sr * sr + si * si;
            for (int q = 0; q < len; ++q) {
                int k = (h >> (2 * q)) & 3;
                if (e > m[b + q][k]) m[b + q][k] = e;
            }
        }
    }
}

// Data-aided refinement once the tones are known.
//
// Frequency: the 59 known-tone phasors are rotated back by a trial offset and
// the offset with the largest coherent sum wins (resolution ~1/(59 T); the
// result is good to a few hundredths of a Hz, which the full-frame matched
// filter below needs so its phase does not drift).
//
// Timing: the sync-only estimate jitters ~10 samples at -10 dB because a
// one-symbol tone correlation has a very flat peak. The coherent matched
// filter against the re-synthesised GFSK waveform of the whole frame uses the
// phase continuity through every tone transition and gets within a couple of
// samples (close to the Cramer-Rao bound for a ~125 Hz wide signal).
//
// Leaves the templates set for the refined frequency and rx->sub_re/sub_im
// holding the analytic reference at that frequency for the subtraction.
static void data_aided_refine(jtty_rx_t* rx, const uint8_t* tones, int* t, float* f, float* dt_sub)
{
    make_templates(rx, *f);
    cpx_t c[JTTY_FRAME_SYMBOLS];
    for (int i = 0; i < JTTY_FRAME_SYMBOLS; ++i)
        c[i] = cmul(corr_symbol(rx->buf + *t + i * NSPS, rx->ek[tones[i]]), rx->rot[i]);
    float w_sym = -2.0f * (float)M_PI * ((float)NSPS / (float)FS);
    float best = -1.0f, best_df = 0.0f;
    for (float df = -0.6f; df <= 0.6f + 1e-6f; df += 0.02f) {
        float sr = 0.0f, si = 0.0f;
        for (int i = 0; i < JTTY_FRAME_SYMBOLS; ++i) {
            float a = w_sym * df * (float)i;
            float cr = cosf(a), ci = sinf(a);
            sr += c[i].re * cr - c[i].im * ci;
            si += c[i].re * ci + c[i].im * cr;
        }
        float s = sr * sr + si * si;
        if (s > best) {
            best = s;
            best_df = df;
        }
    }
    *f += best_df;
    make_templates(rx, *f);

    *dt_sub = 0.0f;
    if (jtty_gfsk_synth(tones, JTTY_FRAME_SYMBOLS, *f, FS, 1.0f, false, rx->pulse_ws, rx->sub_re,
                        rx->sub_im, FRAME) != FRAME)
        return;
    const float* zr = rx->sub_re;
    const float* zi = rx->sub_im;
    enum { span = 16 };
    float scores[2 * span + 1];
    best = -1.0f;
    int best_dt = 0;
    for (int dt = -span; dt <= span; ++dt) {
        int tt = *t + dt;
        scores[dt + span] = -1.0f;
        if (tt < 0 || tt + FRAME > rx->buf_len) continue;
        const float* x = rx->buf + tt;
        float sr = 0.0f, si = 0.0f;
        for (int n = 0; n < FRAME; ++n) {
            sr += x[n] * zr[n];
            si -= x[n] * zi[n];
        }
        float s = sr * sr + si * si;
        scores[dt + span] = s;
        if (s > best) {
            best = s;
            best_dt = dt;
        }
    }
    *t += best_dt;
    int bi = best_dt + span;
    if (bi > 0 && bi < 2 * span && scores[bi - 1] >= 0.0f && scores[bi + 1] >= 0.0f) {
        float den = scores[bi - 1] - 2.0f * scores[bi] + scores[bi + 1];
        if (den < 0.0f) {
            float o = 0.5f * (scores[bi - 1] - scores[bi + 1]) / den;
            *dt_sub = o > 0.5f ? 0.5f : (o < -0.5f ? -0.5f : o);
        }
    }
}

// ---------------------------------------------------------------------------
// Acceptance helpers
// ---------------------------------------------------------------------------

static bool is_duplicate(const jtty_rx_t* rx, float f, int64_t t_abs)
{
    for (int i = 0; i < rx->recent_n; ++i) {
        if (fabsf(rx->recent[i].f - f) <= DUP_HZ &&
            llabs(rx->recent[i].t - t_abs) <= DUP_SAMPLES)
            return true;
    }
    return false;
}

static void remember(jtty_rx_t* rx, float f, int64_t t_abs)
{
    rx->recent[rx->recent_pos].f = f;
    rx->recent[rx->recent_pos].t = t_abs;
    rx->recent_pos = (rx->recent_pos + 1) % RECENT_CAP;
    if (rx->recent_n < RECENT_CAP) ++rx->recent_n;
}

static void enqueue(jtty_rx_t* rx, const jtty_decode_t* d)
{
    if (rx->queue_n == QUEUE_CAP) {
        // Consumer is not keeping up: drop the oldest.
        memmove(rx->queue, rx->queue + 1, sizeof(jtty_decode_t) * (QUEUE_CAP - 1));
        --rx->queue_n;
    }
    rx->queue[rx->queue_n++] = *d;
}

// S/N in 2500 Hz from the tone correlations, given the transmitted tones.
// For a rectangular one-symbol DFT of N samples, a tone of amplitude A gives
// |c|^2 = (A N / 2)^2 and white noise of variance s^2 gives E|c|^2 = N s^2;
// the noise power in 2500 Hz is s^2 * 2500 / (fs / 2).
static float estimate_snr(const cpx_t ph[JTTY_FRAME_SYMBOLS][JTTY_NUM_TONES], const uint8_t* tones)
{
    double sig = 0.0, noise = 0.0;
    for (int i = 0; i < JTTY_FRAME_SYMBOLS; ++i) {
        for (int k = 0; k < JTTY_NUM_TONES; ++k) {
            double p = cabs2(ph[i][k]);
            if (k == tones[i]) sig += p;
            else noise += p;
        }
    }
    sig /= JTTY_FRAME_SYMBOLS;
    noise /= JTTY_FRAME_SYMBOLS * (JTTY_NUM_TONES - 1);
    if (noise <= 0.0) noise = 1e-30;
    // The tone bin also contains noise, so subtract the off-tone average.
    double s = sig - noise;
    if (s < noise * 1e-3) s = noise * 1e-3;
    double a2_half = 2.0 * s / ((double)NSPS * (double)NSPS);  // A^2 / 2
    double sigma2 = noise / (double)NSPS;
    double n2500 = sigma2 * 2500.0 / (FS / 2.0);
    return (float)(10.0 * log10(a2_half / n2500));
}

// Fit a complex gain to the accepted frame's analytic reference (already in
// rx->sub_re/sub_im from data_aided_refine) by least squares, subtract it
// from the buffer and recompute the spectrogram rows it overlapped.
static void subtract_frame(jtty_rx_t* rx, int t)
{
    if (t < 0 || t + FRAME > rx->buf_len) return;
    float* x = rx->buf + t;
    const float* zr = rx->sub_re;
    const float* zi = rx->sub_im;
    // Model x[n] ~ gr*zr[n] - gi*zi[n] = Re((gr + j gi)(zr + j zi)).
    double srr = 0.0, sii = 0.0, sri = 0.0, sxr = 0.0, sxi = 0.0;
    for (int i = 0; i < FRAME; ++i) {
        srr += (double)zr[i] * zr[i];
        sii += (double)zi[i] * zi[i];
        sri += (double)zr[i] * zi[i];
        sxr += (double)x[i] * zr[i];
        sxi += (double)x[i] * zi[i];
    }
    // Normal equations: [srr -sri; -sri sii] [gr; gi] = [sxr; -sxi]
    double det = srr * sii - sri * sri;
    if (fabs(det) < 1e-12) return;
    double gr = (sxr * sii - (-sxi) * (-sri)) / det;
    double gi = (srr * (-sxi) - (-sri) * sxr) / det;
    for (int i = 0; i < FRAME; ++i)
        x[i] -= (float)(gr * zr[i] - gi * zi[i]);
    invalidate_rows(rx, t, t + FRAME);
    ensure_rows(rx);
}

// ---------------------------------------------------------------------------
// One candidate: refine, gate, demodulate, decode, accept.
// ---------------------------------------------------------------------------

static bool try_candidate(jtty_rx_t* rx, const cand_t* c, int pass)
{
    int t = c->t0;
    float f = c->f0;
    if (is_duplicate(rx, f, rx->buf_abs + t)) return false;

    // Coarse grid: 48 samples in time, 7.8 Hz in frequency. The first stage
    // covers +/-8 Hz so a sync-surface peak one bin off is still pulled in.
    float s3[3];
    refine_stage(rx, &t, &f, 36, 12, 8.0f, 0.5f, s3);
    refine_stage(rx, &t, &f, 9, 3, 0.6f, 0.1f, s3);
    refine_stage(rx, &t, &f, 2, 1, 0.1f, 0.025f, s3);
#ifdef JTTY_RX_DEBUG
    fprintf(stderr, "cand score %.2f coarse f %.1f t %lld -> f %.2f t %lld\n", c->score, c->f0,
            (long long)(rx->buf_abs + c->t0), f, (long long)(rx->buf_abs + t));
#endif
    if (f < 1.0f || f + 3.0f * JTTY_TONE_SPACING_HZ > FS / 2.0f - 1.0f) return false;
    if (t < 0 || t + FRAME > WINDOW) return false;

    float dt_sub = 0.0f;  // sub-sample timing, set by the data-aided pass
    int64_t t_abs = rx->buf_abs + t;
    if (is_duplicate(rx, f, t_abs)) return false;

    make_templates(rx, f);
    frame_phasors(rx, t);

    // Gates on the sync symbols.
    int nsync = 0;
    float coh_r = 0.0f, coh_i = 0.0f, inc = 0.0f;
    for (int i = 0; i < JTTY_SYNC_SYMBOLS; ++i) {
        int best = 0;
        float bp = -1.0f;
        for (int k = 0; k < JTTY_NUM_TONES; ++k) {
            float p = cabs2(rx->ph[i][k]);
            if (p > bp) {
                bp = p;
                best = k;
            }
        }
        if (best == JTTY_SYNC[i]) ++nsync;
        cpx_t s = rx->ph[i][JTTY_SYNC[i]];
        coh_r += s.re;
        coh_i += s.im;
        inc += cabs2(s);
    }
    float sync_score = inc > 0.0f ? (coh_r * coh_r + coh_i * coh_i) / (JTTY_SYNC_SYMBOLS * inc) : 0.0f;
    if (nsync < (c->prio ? NSYNC_PRIO : NSYNC_WIDE)) return false;

    // Decode rungs: L = 1, 2, 4 coherent block lengths.
    static const int rungs[3] = { 1, 2, 4 };
    uint64_t payload = 0;
    int rank = 0;
    bool ok = false;
    for (int r = 0; r < 3 && !ok; ++r) {
        if (rungs[r] == 1) metrics_noncoherent(rx->ph, rx->llr);
        else metrics_block(rx->ph, rungs[r], rx->llr);
        ok = jtty_decode_symbols_ws(&rx->vit, rx->llr, &payload, &rank);
    }
    if (!ok) return false;

    uint8_t tones[JTTY_FRAME_SYMBOLS];
    jtty_encode_frame(payload, tones);

    // Known tones: pin time and frequency down before reporting/subtracting.
    data_aided_refine(rx, tones, &t, &f, &dt_sub);
    t_abs = rx->buf_abs + t;
    if (is_duplicate(rx, f, t_abs)) return false;
    frame_phasors(rx, t);

    jtty_decode_t d;
    memset(&d, 0, sizeof d);
    d.payload34 = payload;
    jtty_unpack_payload(payload, d.text, sizeof d.text, &d.eom);
    d.freq_hz = f;
    d.start_sample = t_abs;
    d.dt_s = dt_sub / (float)FS;
    d.snr_db = estimate_snr(rx->ph, tones);
    d.sync_score = sync_score;
    d.list_rank = rank;
    d.pass = pass;
    enqueue(rx, &d);
    remember(rx, f, t_abs);

    if (rx->subtract) subtract_frame(rx, t);
    return true;
}

// ---------------------------------------------------------------------------
// Window analysis and buffer management
// ---------------------------------------------------------------------------

static void analyze_window(jtty_rx_t* rx)
{
    ensure_rows(rx);
    for (int pass = 0; pass < MAX_PASSES; ++pass) {
        float floor_mean = noise_floor(rx);
        int nc = find_candidates(rx, floor_mean);
        int budget = rx->budget > 0 ? rx->budget : DEFAULT_BUDGET;
        int accepted = 0;
        for (int i = 0; i < nc && i < budget; ++i)
            if (try_candidate(rx, &rx->cands[i], pass)) ++accepted;
        if (accepted == 0 || !rx->subtract) break;
    }
}

// Drop one analysis step from the front of the buffer and shift the cached
// spectrogram rows with it.
static void advance_window(jtty_rx_t* rx)
{
    memmove(rx->buf, rx->buf + STEP, sizeof(float) * (size_t)(rx->buf_len - STEP));
    rx->buf_len -= STEP;
    rx->buf_abs += STEP;
    rx->next_pos += STEP;
    memmove(rx->spec, rx->spec + (size_t)STEP_HOPS * NBINS,
            sizeof(float) * (size_t)(ROWS - STEP_HOPS) * NBINS);
    memmove(rx->row_valid, rx->row_valid + STEP_HOPS, (size_t)(ROWS - STEP_HOPS));
    memset(rx->row_valid + (ROWS - STEP_HOPS), 0, STEP_HOPS);
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

jtty_rx_t* jtty_rx_create(int sample_rate)
{
    if (sample_rate != FS) return NULL;
    jtty_rx_t* rx = (jtty_rx_t*)calloc(1, sizeof(jtty_rx_t));
    if (rx == NULL) return NULL;
    rx->buf = (float*)calloc(BUF_CAP, sizeof(float));
    rx->spec = (float*)calloc((size_t)ROWS * NBINS, sizeof(float));
    rx->tw = (cpx_t*)calloc(NFFT, sizeof(cpx_t));
    rx->fft_in = (cpx_t*)calloc(NFFT, sizeof(cpx_t));
    rx->fft_out = (cpx_t*)calloc(NFFT, sizeof(cpx_t));
    rx->ssurf = (float*)calloc((size_t)STEP_HOPS * NBINS, sizeof(float));
    rx->sub_re = (float*)calloc(FRAME, sizeof(float));
    rx->sub_im = (float*)calloc(FRAME, sizeof(float));
    if (!rx->buf || !rx->spec || !rx->tw || !rx->fft_in || !rx->fft_out || !rx->ssurf ||
        !rx->sub_re || !rx->sub_im) {
        jtty_rx_destroy(rx);
        return NULL;
    }
    for (int k = 0; k < NFFT; ++k) {
        double a = -2.0 * M_PI * (double)k / NFFT;
        rx->tw[k].re = (float)cos(a);
        rx->tw[k].im = (float)sin(a);
    }
    rx->f_lo = 300.0f;
    rx->f_hi = 2700.0f;
    rx->f_rx = 1500.0f;
    rx->f_tol = 0.0f;
    rx->budget = DEFAULT_BUDGET;
    rx->subtract = true;
    jtty_rx_reset(rx);
    return rx;
}

void jtty_rx_destroy(jtty_rx_t* rx)
{
    if (rx == NULL) return;
    free(rx->buf);
    free(rx->spec);
    free(rx->tw);
    free(rx->fft_in);
    free(rx->fft_out);
    free(rx->ssurf);
    free(rx->sub_re);
    free(rx->sub_im);
    free(rx);
}

void jtty_rx_reset(jtty_rx_t* rx)
{
    if (rx == NULL) return;
    // The first window starts PRE samples "before" the stream; those are
    // silence so refinement can look slightly left of sample 0.
    memset(rx->buf, 0, sizeof(float) * PRE);
    rx->buf_len = PRE;
    rx->buf_abs = -PRE;
    rx->consumed = 0;
    rx->next_pos = 0;
    memset(rx->row_valid, 0, sizeof rx->row_valid);
    rx->queue_n = 0;
    rx->recent_n = 0;
    rx->recent_pos = 0;
}

void jtty_rx_set_search_band(jtty_rx_t* rx, float f_lo_hz, float f_hi_hz)
{
    if (rx == NULL) return;
    if (!(f_lo_hz >= 0.0f)) f_lo_hz = 0.0f;
    if (!(f_hi_hz <= 5900.0f)) f_hi_hz = 5900.0f;
    if (f_hi_hz < f_lo_hz) f_hi_hz = f_lo_hz;
    rx->f_lo = f_lo_hz;
    rx->f_hi = f_hi_hz;
}

void jtty_rx_set_rx_frequency(jtty_rx_t* rx, float f_hz, float tol_hz)
{
    if (rx == NULL) return;
    rx->f_rx = f_hz;
    rx->f_tol = tol_hz > 0.0f ? tol_hz : 0.0f;
}

void jtty_rx_set_decode_budget(jtty_rx_t* rx, int max_candidates_per_step)
{
    if (rx == NULL) return;
    rx->budget = max_candidates_per_step > 0 ? max_candidates_per_step : DEFAULT_BUDGET;
}

void jtty_rx_set_subtraction(jtty_rx_t* rx, bool enabled)
{
    if (rx) rx->subtract = enabled;
}

int jtty_rx_push(jtty_rx_t* rx, const float* samples, int n)
{
    if (rx == NULL || (samples == NULL && n > 0) || n < 0) return JTTY_ERR_BAD_ARGS;
    while (n > 0) {
        int room = BUF_CAP - rx->buf_len;
        int take = n < room ? n : room;
        memcpy(rx->buf + rx->buf_len, samples, sizeof(float) * (size_t)take);
        rx->buf_len += take;
        rx->consumed += take;
        samples += take;
        n -= take;
        while (rx->buf_len >= WINDOW) {
            analyze_window(rx);
            advance_window(rx);
        }
    }
    return rx->queue_n;
}

int jtty_rx_flush(jtty_rx_t* rx)
{
    if (rx == NULL) return JTTY_ERR_BAD_ARGS;
    // Feed silence until every frame that could have completed inside the
    // real audio has had its window analysed. The zeros count as consumed.
    float zeros[STEP];
    memset(zeros, 0, sizeof zeros);
    int64_t real_end = rx->consumed;
    int guard = 0;
    while (rx->next_pos <= real_end - FRAME && guard++ < 16) {
        int r = jtty_rx_push(rx, zeros, STEP);
        if (r < 0) return r;
    }
    return rx->queue_n;
}

bool jtty_rx_pop(jtty_rx_t* rx, jtty_decode_t* out)
{
    if (rx == NULL || out == NULL || rx->queue_n == 0) return false;
    int best = 0;
    for (int i = 1; i < rx->queue_n; ++i)
        if (rx->queue[i].start_sample < rx->queue[best].start_sample) best = i;
    *out = rx->queue[best];
    memmove(rx->queue + best, rx->queue + best + 1,
            sizeof(jtty_decode_t) * (size_t)(rx->queue_n - best - 1));
    --rx->queue_n;
    return true;
}

int64_t jtty_rx_samples_consumed(const jtty_rx_t* rx)
{
    return rx ? rx->consumed : 0;
}
