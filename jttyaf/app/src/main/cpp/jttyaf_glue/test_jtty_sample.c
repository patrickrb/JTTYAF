// test_jtty_sample.c — decode the official WSJT-X JTTY sample recording
// (samples/JTTY/260807_134110.wav from the WSJT-X SourceForge files) and check
// it yields exactly the message WSJT-X decodes from it.
//
// This is the interoperability test: the recording was made by WSJT-X, so a
// decode proves the sync, the CRC-12, the tail-biting code's bit conventions,
// the source grammar and the TEXT5 alphabet all agree with the reference
// implementation (the conventions were settled with this file — SOURCES.md).
//
// The recording is not checked into the repo. Its path comes from argv[1], else
// the JTTY_SAMPLE_WAV environment variable, else samples/260807_134110.wav
// relative to the working directory. When it is missing the suite SKIPS
// (exit 0) with a clear note, so the host test run stays green without it.

#include "jtty_test_util.h"

#include <stdlib.h>

#define DEFAULT_PATH "samples/260807_134110.wav"

// What WSJT-X shows for the recording: ten TEXT5 frames, EOM on the last.
#define EXPECTED_TEXT   "RAN ALL NIGHT ON BAND NOISE - NO FALSE DECODES!"
#define EXPECTED_FRAMES 10

int main(int argc, char** argv)
{
    const char* path = argc > 1 ? argv[1] : getenv("JTTY_SAMPLE_WAV");
    if (path == NULL || path[0] == '\0') path = DEFAULT_PATH;

    int n = 0, rate = 0;
    float* x = test_wav_read(path, &n, &rate);
    if (x == NULL || n < JTTY_FRAME_SAMPLES) {
        printf("SKIP: sample recording not available or not a 16-bit PCM WAV: %s\n", path);
        free(x);
        return 0;
    }
    if (rate != JTTY_SAMPLE_RATE) {
        printf("SKIP: sample is %d Hz, this harness expects 12000 Hz: %s\n", rate, path);
        free(x);
        return 0;
    }
    printf("sample: %s (%.1f s)\n", path, (double)n / rate);

    jtty_rx_t* rx = jtty_rx_create(JTTY_SAMPLE_RATE);
    check(rx != NULL, "receiver created");
    if (rx == NULL) {
        free(x);
        return 1;
    }
    jtty_rx_set_search_band(rx, 200.0f, 3000.0f);
    jtty_rx_set_decode_budget(rx, 48);
    test_push_all(rx, x, n, 4096);
    jtty_rx_flush(rx);

    // Frames pop in time order; TEXT5 atoms concatenate directly, so the
    // message is simply the joined atom texts (the last one is space padded).
    char text[JTTY_MAX_FRAMES * 8 + 1] = { 0 };
    int count = 0, eom_frames = 0, eom_last = 0;
    float f0 = 0.0f, max_freq_dev = 0.0f;
    long prev_start = -1;
    float max_period_err_s = 0.0f;
    jtty_decode_t d;
    while (jtty_rx_pop(rx, &d)) {
        printf("  %7.3f s  %7.1f Hz  snr %5.1f dB  sync %.2f  rank %d  pass %d  %s%s\n",
               (double)d.start_sample / JTTY_SAMPLE_RATE, d.freq_hz, d.snr_db, d.sync_score,
               d.list_rank, d.pass, d.text, d.eom ? "  <EOM>" : "");
        if (count < JTTY_MAX_FRAMES) strncat(text, d.text, 7);
        if (count == 0) f0 = d.freq_hz;
        else if (fabsf(d.freq_hz - f0) > max_freq_dev) max_freq_dev = fabsf(d.freq_hz - f0);
        if (prev_start >= 0) {
            float err = fabsf((float)(d.start_sample - prev_start) - (float)JTTY_FRAME_SAMPLES)
                        / JTTY_SAMPLE_RATE;
            if (err > max_period_err_s) max_period_err_s = err;
        }
        prev_start = d.start_sample;
        if (d.eom) ++eom_frames;
        eom_last = d.eom;
        ++count;
    }
    jtty_rx_destroy(rx);
    free(x);

    // Trim the TEXT5 padding of the final atom.
    size_t len = strlen(text);
    while (len > 0 && text[len - 1] == ' ') text[--len] = '\0';

    printf("  %d frame(s): \"%s\"\n", count, text);
    check(count == EXPECTED_FRAMES, "exactly the ten frames WSJT-X decodes");
    check(strcmp(text, EXPECTED_TEXT) == 0, "message text matches WSJT-X");
    check(eom_frames == 1 && eom_last, "EOM on the last frame only");
    check(max_freq_dev < 3.0f, "all frames on one audio frequency (< 3 Hz spread)");
    check(max_period_err_s < 0.02f, "frames exactly one period apart (< 20 ms jitter)");

    printf("%s: %d failure(s)\n", "test_jtty_sample", g_failures);
    return g_failures ? 1 : 0;
}
