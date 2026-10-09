package com.k1af.ft8af.wave;

import android.content.Context;

import com.k1af.ft8af.BuildConfig;
import com.k1af.ft8af.GeneralVariables;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Debug-only audio injection: plays a WAV file into the recorder fan-out in place of the
 * microphone, so the whole RX chain (waterfall, JTTY decoder, alerts) can be exercised on an
 * emulator or a phone with no radio attached — e.g. to record the Play Console foreground-
 * service demo or to shoot store screenshots from a known recording.
 *
 * <p>Enabled only when BOTH hold: this is a {@link BuildConfig#DEBUG} build, and a file named
 * {@value #FILE_NAME} exists in the app's internal files dir (push with
 * {@code adb shell run-as radio.ks3ckc.jttyaf cp /data/local/tmp/x.wav files/inject.wav}) or in
 * its external files dir. A release build never looks for the file.
 *
 * <p>The file must be 16-bit PCM at the recorder's 12 kHz rate; stereo is folded to mono. It is
 * loaded once, then looped forever and paced to wall-clock time so decodes appear at the same
 * cadence as live audio. The header walk, sample conversion and pacing arithmetic are pure
 * static functions with unit tests; only {@link #find} and {@link #startThread} touch Android
 * or threads.
 */
public final class WavInjector {
    private WavInjector() {}

    static final String FILE_NAME = "inject.wav";
    /** Samples per delivery: 100 ms at 12 kHz, close to a typical AudioRecord buffer. */
    static final int CHUNK_SAMPLES = 1200;
    /** Refuse absurdly large files rather than OOM on a mis-pushed recording. */
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    /** Parsed WAV header. {@code dataOffset}/{@code dataLength} locate the PCM payload. */
    static final class WavInfo {
        final int sampleRate;
        final int channels;
        final int bitsPerSample;
        final int dataOffset;
        final int dataLength;

        WavInfo(int sampleRate, int channels, int bitsPerSample, int dataOffset, int dataLength) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.dataOffset = dataOffset;
            this.dataLength = dataLength;
        }
    }

    /** The single gate: a debug build AND the inject file is present. */
    static boolean shouldInject(boolean debugBuild, boolean fileExists) {
        return debugBuild && fileExists;
    }

    /**
     * The inject file for this build, or {@code null} when injection must not run (release
     * build, no context, or no file). Internal files dir is checked first because it is
     * writable via {@code run-as} on any debuggable install; the external dir is the fallback
     * for devices where {@code adb push} into {@code Android/data} works.
     */
    public static File find(Context ctx) {
        if (ctx == null) return null;
        File internal = new File(ctx.getFilesDir(), FILE_NAME);
        if (shouldInject(BuildConfig.DEBUG, internal.isFile())) return internal;
        File extDir = ctx.getExternalFilesDir(null);
        if (extDir != null) {
            File external = new File(extDir, FILE_NAME);
            if (shouldInject(BuildConfig.DEBUG, external.isFile())) return external;
        }
        return null;
    }

    /**
     * Walk a RIFF/WAVE header and locate the {@code fmt } and {@code data} chunks. Returns
     * {@code null} unless the file is PCM (format tag 1) with 16-bit samples; any other layout,
     * truncated header, or missing chunk is rejected rather than guessed at.
     */
    static WavInfo parseHeader(byte[] b) {
        if (b == null || b.length < 12) return null;
        if (!tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) return null;
        int pos = 12;
        int sampleRate = 0, channels = 0, bits = 0, formatTag = 0;
        boolean haveFmt = false;
        while (pos + 8 <= b.length) {
            int size = le32(b, pos + 4);
            int body = pos + 8;
            if (size < 0) return null;
            if (tag(b, pos, "fmt ")) {
                if (size < 16 || body + 16 > b.length) return null;
                formatTag = le16(b, body);
                channels = le16(b, body + 2);
                sampleRate = le32(b, body + 4);
                bits = le16(b, body + 14);
                haveFmt = true;
            } else if (tag(b, pos, "data")) {
                if (!haveFmt) return null;
                if (formatTag != 1 || bits != 16 || channels < 1 || sampleRate <= 0) return null;
                // A streaming writer may leave the data size as 0 or 0xFFFFFFFF; clamp to what
                // is actually in the buffer either way.
                int avail = b.length - body;
                int len = (size <= 0 || size > avail) ? avail : size;
                return new WavInfo(sampleRate, channels, bits, body, len);
            }
            // Chunks are word-aligned: an odd-sized chunk is followed by a pad byte.
            pos = body + size + (size & 1);
        }
        return null;
    }

    /**
     * Convert interleaved 16-bit little-endian PCM to mono floats in [-1, 1), averaging the
     * channels. Returns the number of mono samples written; {@code out} must hold
     * {@code nBytes / (2 * channels)} samples.
     */
    static int pcm16ToMonoFloat(byte[] src, int off, int nBytes, int channels, float[] out) {
        if (channels < 1) return 0;
        int frames = nBytes / (2 * channels);
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            int base = off + i * 2 * channels;
            for (int c = 0; c < channels; c++) {
                sum += (short) le16(src, base + 2 * c);
            }
            out[i] = (sum / (float) channels) / 32768f;
        }
        return frames;
    }

    /**
     * How long (ns) the producer should sleep before delivering the next chunk so that
     * {@code samplesDelivered} at {@code sampleRate} lines up with wall time since
     * {@code startNanos}. Never negative: a producer that has fallen behind just runs.
     */
    static long paceSleepNanos(long startNanos, long nowNanos, long samplesDelivered, int sampleRate) {
        long due = startNanos + samplesDelivered * 1_000_000_000L / sampleRate;
        return Math.max(0L, due - nowNanos);
    }

    /** Index of the chunk start after {@code pos} samples, wrapping to 0 at the end of the file. */
    static int nextPosition(int pos, int chunk, int total) {
        int next = pos + chunk;
        return next >= total ? 0 : next;
    }

    /**
     * Load and convert the whole file to mono floats. Returns {@code null} (after logging) when
     * the file is unreadable, not 16-bit PCM, or not at {@code expectedRate}.
     */
    static float[] load(File file, int expectedRate) {
        if (file.length() > MAX_FILE_BYTES) {
            GeneralVariables.fileLog("WavInjector: " + file + " is too large, ignoring");
            return null;
        }
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int got = 0;
            while (got < bytes.length) {
                int n = in.read(bytes, got, bytes.length - got);
                if (n < 0) break;
                got += n;
            }
        } catch (IOException e) {
            GeneralVariables.fileLog("WavInjector: cannot read " + file + ": " + e.getMessage());
            return null;
        }
        WavInfo info = parseHeader(bytes);
        if (info == null) {
            GeneralVariables.fileLog("WavInjector: " + file + " is not 16-bit PCM WAV, ignoring");
            return null;
        }
        if (info.sampleRate != expectedRate) {
            GeneralVariables.fileLog(String.format(
                    "WavInjector: %s is %d Hz, recorder wants %d Hz, ignoring",
                    file, info.sampleRate, expectedRate));
            return null;
        }
        float[] mono = new float[info.dataLength / (2 * info.channels)];
        int n = pcm16ToMonoFloat(bytes, info.dataOffset, info.dataLength, info.channels, mono);
        GeneralVariables.fileLog(String.format(
                "WavInjector: loaded %s: %d Hz, %d ch, %.1f s — looping into the RX fan-out",
                file.getName(), info.sampleRate, info.channels, n / (float) expectedRate));
        return mono;
    }

    /**
     * Start the producer thread. {@code listener} is read fresh for every chunk because
     * {@code HamRecorder} installs it after {@code MicRecorder.start()} returns. The thread exits
     * as soon as {@code keepRunning} turns false.
     */
    static Thread startThread(
            final float[] samples,
            final int sampleRate,
            final Supplier<MicRecorder.OnDataListener> listener,
            final BooleanSupplier keepRunning) {
        Thread t = new Thread(() -> {
            final long start = System.nanoTime();
            long delivered = 0;
            int pos = 0;
            float[] chunk = new float[CHUNK_SAMPLES];
            while (keepRunning.getAsBoolean()) {
                int n = Math.min(CHUNK_SAMPLES, samples.length - pos);
                System.arraycopy(samples, pos, chunk, 0, n);
                MicRecorder.OnDataListener l = listener.get();
                if (l != null) l.onDataReceived(chunk, n);
                delivered += n;
                pos = nextPosition(pos, n, samples.length);
                long sleep = paceSleepNanos(start, System.nanoTime(), delivered, sampleRate);
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "WavInjector");
        t.start();
        return t;
    }

    private static boolean tag(byte[] b, int off, String s) {
        if (off + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) {
            if (b[off + i] != (byte) s.charAt(i)) return false;
        }
        return true;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
