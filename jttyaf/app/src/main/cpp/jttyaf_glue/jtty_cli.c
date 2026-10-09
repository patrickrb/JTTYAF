// jtty_cli.c — small command-line front end for jtty_lib (host only).
//
//   jtty_cli encode "<text>" out.wav [f0] [rate]   GFSK audio of the message
//   jtty_cli decode in.wav [flo fhi]               print every accepted frame
//   jtty_cli tones "<text>"                        payload hex + 59 tones/frame

#include "jtty_test_util.h"

static int usage(void)
{
    fprintf(stderr,
            "usage:\n"
            "  jtty_cli encode \"<text>\" out.wav [f0_hz=1500] [rate=12000] [profile=0]\n"
            "  jtty_cli decode in.wav [f_lo f_hi]\n"
            "  jtty_cli tones \"<text>\" [profile=0]\n");
    return 2;
}

static int cmd_encode(int argc, char** argv)
{
    if (argc < 4) return usage();
    float f0 = argc > 4 ? (float)atof(argv[4]) : 1500.0f;
    int rate = argc > 5 ? atoi(argv[5]) : 12000;
    int profile = argc > 6 ? atoi(argv[6]) : 0;
    int cap = jtty_waveform_samples(JTTY_MAX_FRAMES, rate);
    if (cap <= 0) {
        fprintf(stderr, "bad sample rate %d\n", rate);
        return 1;
    }
    float* buf = (float*)calloc((size_t)cap, sizeof(float));
    int nf = 0;
    int n = jtty_encode_text(argv[2], (jtty_profile_t)profile, f0, rate, 0.8f, buf, cap, &nf);
    if (n < 0) {
        fprintf(stderr, "encode failed (%d)\n", n);
        free(buf);
        return 1;
    }
    if (test_wav_write(argv[3], buf, n, rate) != 0) {
        fprintf(stderr, "cannot write %s\n", argv[3]);
        free(buf);
        return 1;
    }
    printf("%d frame(s), %d samples (%.3f s) at %d Hz, f0 %.1f Hz -> %s\n", nf, n, (double)n / rate, rate, f0, argv[3]);
    free(buf);
    return 0;
}

static int cmd_decode(int argc, char** argv)
{
    if (argc < 3) return usage();
    int n = 0, rate = 0;
    float* x = test_wav_read(argv[2], &n, &rate);
    if (!x) {
        fprintf(stderr, "cannot read %s (16-bit PCM WAV expected)\n", argv[2]);
        return 1;
    }
    if (rate != JTTY_SAMPLE_RATE) {
        fprintf(stderr, "sample rate %d Hz: the receiver wants 12000 Hz\n", rate);
        free(x);
        return 1;
    }
    jtty_rx_t* rx = jtty_rx_create(rate);
    if (argc >= 5) jtty_rx_set_search_band(rx, (float)atof(argv[3]), (float)atof(argv[4]));
    double t0 = test_now();
    test_push_all(rx, x, n, 4096);
    jtty_rx_flush(rx);
    double cpu = test_now() - t0;
    jtty_decode_t d;
    int count = 0;
    while (jtty_rx_pop(rx, &d)) {
        ++count;
        printf("%8.3f s  %7.1f Hz  %5.1f dB  sync %.2f  rank %d  pass %d  0x%09llX  %s%s\n",
               (double)d.start_sample / rate, d.freq_hz, d.snr_db, d.sync_score, d.list_rank, d.pass,
               (unsigned long long)d.payload34, d.text, d.eom ? "  <EOM>" : "");
    }
    printf("%d frame(s); %.2f s audio decoded in %.2f s (x%.3f real time)\n", count, (double)n / rate, cpu,
           cpu / ((double)n / rate));
    jtty_rx_destroy(rx);
    free(x);
    return 0;
}

static int cmd_tones(int argc, char** argv)
{
    if (argc < 3) return usage();
    int profile = argc > 3 ? atoi(argv[3]) : 0;
    uint64_t p[JTTY_MAX_FRAMES];
    int nf = jtty_pack_text(argv[2], (jtty_profile_t)profile, p, JTTY_MAX_FRAMES);
    if (nf < 0) {
        fprintf(stderr, "pack failed (%d)\n", nf);
        return 1;
    }
    for (int f = 0; f < nf; ++f) {
        char text[JTTY_ATOM_RENDER];
        bool eom;
        jtty_unpack_payload(p[f], text, sizeof text, &eom);
        uint8_t tones[JTTY_FRAME_SYMBOLS];
        jtty_encode_frame(p[f], tones);
        printf("frame %d: payload 0x%09llX crc 0x%03X %-16s%s\n  ", f + 1, (unsigned long long)p[f],
               jtty_crc12(p[f]), text, eom ? " <EOM>" : "");
        for (int i = 0; i < JTTY_FRAME_SYMBOLS; ++i) printf("%d%s", tones[i], i == 12 ? " | " : (i + 1 < JTTY_FRAME_SYMBOLS ? " " : ""));
        printf("\n");
    }
    return 0;
}

int main(int argc, char** argv)
{
    if (argc < 2) return usage();
    if (strcmp(argv[1], "encode") == 0) return cmd_encode(argc, argv);
    if (strcmp(argv[1], "decode") == 0) return cmd_decode(argc, argv);
    if (strcmp(argv[1], "tones") == 0) return cmd_tones(argc, argv);
    return usage();
}
