package com.k1af.ft8af.wave;

import android.content.Context;

import com.k1af.ft8af.BuildConfig;
import com.k1af.ft8af.GeneralVariables;

import radio.ks3ckc.jttyaf.jtty.JttyTransmitter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Debug-only TX audio dump: the mirror image of {@link WavInjector}. Every JTTY over's
 * audio — the exact float buffer handed to TransmitAudioSink — is written as a 16-bit PCM
 * WAV into {@code <external files dir>/tx_dump/}, so the app's real transmit output can be
 * pulled with adb and fed to WSJT-X's {@code rjtty} (and to {@code jtty_cli decode}) to
 * prove interoperability without a second radio. See
 * {@code jttyaf_glue/run_jtty_interop.ps1 -TxWav}.
 *
 * <p>Enabled only in a {@link BuildConfig#DEBUG} build; a release build never installs the
 * tap. The newest {@value #KEEP_FILES} dumps are kept, older ones are deleted.
 *
 * <p>Pull with {@code adb pull /sdcard/Android/data/radio.ks3ckc.jttyaf/files/tx_dump/ .}
 *
 * <p>The WAV serialisation, sample conversion, file naming and pruning are pure static
 * functions with unit tests; only {@link #tap} touches Android and only {@link #write}
 * touches the disk.
 */
public final class TxWavDumper {
    private TxWavDumper() {}

    static final String DIR_NAME = "tx_dump";
    static final String FILE_PREFIX = "jtty_tx_";
    /** Dumps kept per device, newest first; the rest are pruned after every write. */
    static final int KEEP_FILES = 20;
    /** Refuse to serialise more than ten minutes of 12 kHz audio (a runaway buffer). */
    static final int MAX_SAMPLES = 12_000 * 600;
    static final int HEADER_BYTES = 44;

    /** The single gate: a debug build AND somewhere to write. */
    static boolean shouldDump(boolean debugBuild, File dir) {
        return debugBuild && dir != null;
    }

    /**
     * The tap to install on the transmitter, or {@code null} when dumping must not run
     * (release build, no context, no external files dir).
     */
    public static JttyTransmitter.TxTap tap(Context ctx) {
        if (ctx == null) return null;
        File ext = ctx.getExternalFilesDir(null);
        if (!shouldDump(BuildConfig.DEBUG, ext)) return null;
        final File dir = new File(ext, DIR_NAME);
        return (buffer, sampleRate) -> write(dir, buffer, sampleRate, System.currentTimeMillis());
    }

    /** {@code jtty_tx_yyyyMMdd_HHmmss_SSS.wav}: sorts chronologically by name. */
    static String fileName(long epochMillis, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
        f.setTimeZone(tz);
        return FILE_PREFIX + f.format(new Date(epochMillis)) + ".wav";
    }

    /** Float in [-1, 1] to a 16-bit sample; out-of-range input clips instead of wrapping. */
    static short floatToPcm16(float v) {
        float s = v * 32768f;
        if (s >= 32767f) return 32767;
        if (s <= -32768f) return -32768;
        return (short) Math.round(s);
    }

    /**
     * Serialise mono samples as a canonical 44-byte-header PCM WAV (the layout
     * {@link WavInjector#parseHeader} and WSJT-X both read). Returns {@code null} for an
     * unreasonable request (no samples, bad rate, absurd length) rather than writing a
     * broken file.
     */
    static byte[] toWav(float[] samples, int sampleRate) {
        if (samples == null || samples.length == 0 || samples.length > MAX_SAMPLES || sampleRate <= 0) {
            return null;
        }
        int dataBytes = samples.length * 2;
        byte[] b = new byte[HEADER_BYTES + dataBytes];
        putTag(b, 0, "RIFF");
        putLe32(b, 4, 36 + dataBytes);
        putTag(b, 8, "WAVE");
        putTag(b, 12, "fmt ");
        putLe32(b, 16, 16);             // fmt chunk size
        putLe16(b, 20, 1);              // PCM
        putLe16(b, 22, 1);              // mono
        putLe32(b, 24, sampleRate);
        putLe32(b, 28, sampleRate * 2); // byte rate
        putLe16(b, 32, 2);              // block align
        putLe16(b, 34, 16);             // bits per sample
        putTag(b, 36, "data");
        putLe32(b, 40, dataBytes);
        int pos = HEADER_BYTES;
        for (float v : samples) {
            short s = floatToPcm16(v);
            b[pos++] = (byte) s;
            b[pos++] = (byte) (s >> 8);
        }
        return b;
    }

    /**
     * Which of {@code existing} to delete so that at most {@code keep} dumps remain:
     * the oldest first, by name (the name embeds the timestamp). Files that are not our
     * dumps are never touched — the dir is shared storage we did not create alone.
     */
    static List<File> filesToPrune(File[] existing, int keep) {
        List<File> ours = new ArrayList<>();
        if (existing != null) {
            for (File f : existing) {
                String n = f.getName();
                if (n.startsWith(FILE_PREFIX) && n.endsWith(".wav")) ours.add(f);
            }
        }
        Collections.sort(ours, (a, b) -> a.getName().compareTo(b.getName()));
        int excess = ours.size() - Math.max(0, keep);
        return excess > 0 ? new ArrayList<>(ours.subList(0, excess)) : new ArrayList<>();
    }

    /**
     * Write one dump named for {@code nowMillis} and prune to {@link #KEEP_FILES}. Never
     * throws: a dump must never spoil an over, so every failure is logged and swallowed.
     */
    static void write(File dir, float[] samples, int sampleRate, long nowMillis) {
        try {
            byte[] wav = toWav(samples, sampleRate);
            if (wav == null) {
                GeneralVariables.fileLog("TxWavDumper: refusing to dump "
                        + (samples == null ? 0 : samples.length) + " samples at " + sampleRate + " Hz");
                return;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                GeneralVariables.fileLog("TxWavDumper: cannot create " + dir);
                return;
            }
            File out = new File(dir, fileName(nowMillis, TimeZone.getDefault()));
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(wav);
            }
            for (File old : filesToPrune(dir.listFiles(), KEEP_FILES)) {
                if (!old.delete()) {
                    GeneralVariables.fileLog("TxWavDumper: could not prune " + old.getName());
                }
            }
            GeneralVariables.fileLog(String.format(Locale.US,
                    "TxWavDumper: wrote %s (%d samples, %.2f s at %d Hz)",
                    out.getName(), samples.length, samples.length / (double) sampleRate, sampleRate));
        } catch (IOException | RuntimeException e) {
            GeneralVariables.fileLog("TxWavDumper: dump failed — " + e);
        }
    }

    private static void putTag(byte[] b, int off, String s) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }
}
