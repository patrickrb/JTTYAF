package com.k1af.ft8af.transmit;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.bluetooth.AudioOutputRoutingPolicy;
import com.k1af.ft8af.bluetooth.DefaultOutputRouting;
import com.k1af.ft8af.ui.ToastMessage;
import com.k1af.ft8af.wave.AudioChannelCapability;
import com.k1af.ft8af.wave.UsbAudioDevice;
import com.k1af.ft8af.wave.UsbAudioNative;

/**
 * The transmit audio playback sink, extracted from the retired FT8 transmit
 * engine ({@code FT8TransmitSignal.playFT8Signal/playViaAudioTrack/
 * playViaUsbAudio/playTuneTone}). Plays a mono float waveform through the
 * currently selected output route:
 *
 * <ol>
 *   <li><b>Rig wave route</b> (network rigs / truSDX audio-over-CAT) — the
 *       whole buffer is handed to the rig, which streams it itself.</li>
 *   <li><b>Direct USB audio</b> — libusb-backed {@link UsbAudioDevice#writeAudio};
 *       TX volume is applied live inside the native write loop.</li>
 *   <li><b>AudioTrack</b> (Android sink) — chunked MODE_STREAM playback with
 *       the TX volume re-read per ~50 ms chunk, so a slider move lands
 *       mid-transmission; honours the operator's TX channel selection
 *       ({@link TxChannelLayout}), holds exclusive audio focus
 *       ({@link TxAudioFocus}), and steers the "Default" output to the rig's
 *       A2DP endpoint when our own SCO link is what displaced the media route
 *       ({@link DefaultOutputRouting}).</li>
 * </ol>
 *
 * <p>Two hard-won behaviours are preserved here (see AGENTS.md, "TX audio
 * pipeline"):
 * <ul>
 *   <li>The buffer is played <b>in full</b> — no leading samples are ever
 *       clipped. (The old {@code lateStartSkipMs} trim was FT8 slot logic;
 *       JTTY is asynchronous and has no slot.)</li>
 *   <li>Cancellation never releases the AudioTrack from the UI thread: the
 *       worker owns teardown; {@link #cancel()} only flips the flag and
 *       pause+flushes for immediate silence.</li>
 * </ul>
 */
public class TransmitAudioSink {
    private static final String TAG = "TransmitAudioSink";

    /** Live TX volume source, read fresh per chunk. */
    public interface VolumeSource {
        /** Gain 0.0-1.0. */
        float volume();
    }

    /** Open-ended chunk producer for streamed tones (Tune). */
    public interface ChunkSource {
        /**
         * Fill {@code out[0..maxLen)} with the next (already level-scaled)
         * samples. Return the number written; {@code <= 0} ends the stream.
         */
        int nextChunk(float[] out, int maxLen);
    }

    /**
     * Optional rig-side wave route (network rig / truSDX over CAT). When
     * available it takes priority over local audio output.
     */
    public interface RigWaveRoute {
        boolean isAvailable();

        /** Send the whole waveform through the rig. True when it was handed off. */
        boolean sendWave(float[] buffer, int sampleRate);
    }

    /**
     * Whether <em>this app's</em> SCO link was up when the current transmission
     * was keyed, and on which device — the snapshot {@link PttController} takes
     * before it stops SCO. Feeds {@link DefaultOutputRouting} for the "Default"
     * output; without it the override never fires in exactly the CAT/RTS/DTR +
     * Bluetooth case it exists for (Copilot review on #790).
     */
    public interface ScoSnapshot {
        boolean heldForTx();

        String address();
    }

    /** Result of a playback call. */
    public enum PlayResult { COMPLETED, CANCELLED, ERROR }

    /**
     * Abstraction over the PCM output device so the chunked loop is
     * unit-testable without an Android AudioTrack. Write lengths are in
     * <em>samples</em> (interleaved for a stereo layout); the head position is
     * in <em>frames</em> — the same asymmetry {@code AudioTrack} has, which
     * {@link TxChannelLayout#framesFromSamples} reconciles.
     */
    public interface PcmOutput {
        /** Blocking write; returns samples written or a negative error. */
        int writeFloats(float[] data, int length);

        /** Blocking write; returns samples written or a negative error. */
        int writeShorts(short[] data, int length);

        /** Current playback head position in frames (for the drain wait). */
        int playbackHeadPosition();

        /** Immediate silence: pause + flush (never release — worker owns that). */
        void pauseAndFlush();

        /** Worker-thread teardown (track release, audio focus abandon). */
        void release();
    }

    /** Factory for the PCM output; the default opens a real AudioTrack. */
    public interface PcmOutputFactory {
        /**
         * @param layout          channel layout the track opens with
         * @param preferredDevice the explicitly chosen sink, or null for Android's Default
         */
        PcmOutput open(int sampleRate, boolean float32, TxChannelLayout layout,
                       AudioDeviceInfo preferredDevice) throws Exception;
    }

    /** Millisecond sleeper, injected for tests. */
    public interface Sleeper {
        void sleepMs(long ms) throws InterruptedException;
    }

    private final PcmOutputFactory outputFactory;
    private final Sleeper sleeper;
    private RigWaveRoute rigWaveRoute;
    private volatile ScoSnapshot scoSnapshot;

    // Set by cancel() to abort the chunked MODE_STREAM playback loop mid-buffer.
    // The worker thread owns the track's stop/release; the canceller only flips
    // this flag and pauses+flushes the track for immediate silence, avoiding a
    // release race against the worker blocked in write(). Reset at the start
    // of each playback.
    private volatile boolean cancelled = false;
    private volatile PcmOutput activeOutput = null;

    public TransmitAudioSink() {
        this.outputFactory = new AudioTrackOutputFactory();
        this.sleeper = new Sleeper() {
            @Override
            public void sleepMs(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }
        };
    }

    TransmitAudioSink(PcmOutputFactory outputFactory, Sleeper sleeper) {
        this.outputFactory = outputFactory;
        this.sleeper = sleeper;
    }

    public void setRigWaveRoute(RigWaveRoute route) {
        this.rigWaveRoute = route;
    }

    public void setScoSnapshot(ScoSnapshot snapshot) {
        this.scoSnapshot = snapshot;
    }

    /**
     * Abort an in-progress playback from any thread. The playback worker owns
     * the actual stop/release; this only signals it.
     *
     * <p>Stops the same writers a STOP always stopped: the native/fallback USB
     * write (which would otherwise drain the whole message) and the chunked
     * MODE_STREAM AudioTrack loop. For the track we must NOT release here: the
     * worker may be blocked in a WRITE_BLOCKING call and releasing underneath
     * it would race/crash. pause()+flush() gives immediate silence, and flush()
     * discards the queued buffer so a blocked write returns at once instead of
     * deadlocking on a full, paused buffer. The worker then breaks out and its
     * own teardown does the stop()/release().
     */
    public void cancel() {
        cancelled = true;
        UsbAudioNative.cancelWrite();
        PcmOutput out = activeOutput;
        if (out != null) {
            out.pauseAndFlush();
        }
    }

    /**
     * Play the whole waveform through the current route. Blocking; call from a
     * worker thread with PTT already keyed. The entire buffer is played — never
     * trimmed at the head.
     *
     * @param buffer     mono float waveform, full scale (volume applied here)
     * @param sampleRate waveform sample rate in Hz
     * @param float32    true to write PCM float, false to convert to 16-bit
     * @param volume     live volume source, re-read per chunk
     */
    public PlayResult play(float[] buffer, int sampleRate, boolean float32, VolumeSource volume) {
        cancelled = false;
        if (buffer == null || buffer.length == 0) {
            return PlayResult.ERROR;
        }

        // 1) Rig wave route (network rig / truSDX audio over CAT).
        RigWaveRoute route = rigWaveRoute;
        if (route != null && route.isAvailable()) {
            GeneralVariables.fileLog("TransmitAudioSink: using rig wave route (network/CAT)");
            return playViaRigRoute(route, buffer, sampleRate);
        }

        // Logged so a debug.log reader can see which branch was taken — the
        // difference between TX going out the USB radio (direct) vs. Android's
        // sink (AudioTrack) was the whole reason for #84, and nothing else in
        // the log tells them apart.
        GeneralVariables.fileLog(String.format(
                "TransmitAudioSink: TX path branch: audioOutputDeviceId=%d "
                        + "usbAudioOutputVidPid=%04X:%04X volume=%.0f%% autoVolume=%b",
                GeneralVariables.audioOutputDeviceId,
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId,
                volume.volume() * 100f,
                GeneralVariables.autoVolumeEnabled));

        // 2) Direct USB audio (libusb) when the user picked a USB device.
        if (useUsbDirectOutput()) {
            GeneralVariables.fileLog("TransmitAudioSink: using USB audio (direct) output");
            return playViaUsbAudio(buffer, sampleRate);
        }

        // 3) AudioTrack (Android sink). Resolve the preferred sink up front: the
        // TX channel selection needs its channel count before the track is
        // built, and the same AudioDeviceInfo is handed to setPreferredDevice.
        GeneralVariables.fileLog("TransmitAudioSink: using AudioTrack output (Android sink)");
        AudioDeviceInfo preferredOutputDevice = preferredOutputDevice();
        return playViaPcmOutput(buffer, sampleRate, float32, volume,
                resolveTxLayout(preferredOutputDevice), preferredOutputDevice);
    }

    /**
     * Stream an open-ended chunk source (the Tune carrier) through the local
     * AudioTrack sink. Blocking; runs until the source is exhausted, a write
     * error occurs, or {@link #cancel()} fires. The source is responsible for
     * level scaling and its own ramp-down. Same channel layout as a
     * transmission: on a splitter cable the carrier must key only the rig the
     * operator picked — a mono open here would have the framework duplicate the
     * carrier onto the other rig's mic input.
     */
    public PlayResult playStream(ChunkSource source, int sampleRate, boolean float32) {
        cancelled = false;
        AudioDeviceInfo preferredOutputDevice = preferredOutputDevice();
        return playStream(source, sampleRate, float32,
                resolveTxLayout(preferredOutputDevice), preferredOutputDevice);
    }

    /** Whether the user picked a direct-USB audio output device. */
    public static boolean isUsbDirectOutput(int audioOutputDeviceId, int usbAudioOutputVendorId) {
        return audioOutputDeviceId == -1 && usbAudioOutputVendorId != 0;
    }

    private boolean useUsbDirectOutput() {
        return isUsbDirectOutput(GeneralVariables.audioOutputDeviceId,
                GeneralVariables.usbAudioOutputVendorId);
    }

    // ---- rig wave route -------------------------------------------------------

    private PlayResult playViaRigRoute(RigWaveRoute route, float[] buffer, int sampleRate) {
        long startedAt = System.currentTimeMillis();
        long durationMs = (long) buffer.length * 1000L / sampleRate;
        boolean ok = route.sendWave(buffer, sampleRate);
        // Some rig transports hand the waveform off asynchronously (the network
        // connectors pace their packets out in real time on their own thread);
        // hold the caller — and thus PTT — for the audio duration so the rig
        // finishes streaming before key-up. A blocking transport (truSDX serial
        // writes) has already consumed the time, so this loop exits at once.
        while (ok && !cancelled
                && System.currentTimeMillis() - startedAt < durationMs) {
            try {
                sleeper.sleepMs(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (cancelled) return PlayResult.CANCELLED;
        return ok ? PlayResult.COMPLETED : PlayResult.ERROR;
    }

    // ---- AudioTrack route -------------------------------------------------------

    /**
     * Highest channel count the chosen output device reports, or
     * {@link AudioChannelCapability#UNKNOWN} when it reports nothing usable (or
     * there is no explicit device).
     */
    static int outputMaxChannels(AudioDeviceInfo device) {
        if (device == null) return AudioChannelCapability.UNKNOWN;
        return AudioChannelCapability.maxChannelCount(device.getChannelCounts());
    }

    /**
     * The channel layout this transmission's {@code AudioTrack} opens with, for
     * the operator's current TX selection and the sink they picked. See
     * {@link TxChannelLayout#resolve} for why the Default sink always gets the
     * mono layout.
     *
     * @param preferredOutputDevice the explicitly chosen sink, or null for
     *                              Android's Default
     */
    static TxChannelLayout resolveTxLayout(AudioDeviceInfo preferredOutputDevice) {
        return TxChannelLayout.resolve(
                GeneralVariables.txAudioChannel,
                GeneralVariables.audioOutputDeviceId > 0,
                outputMaxChannels(preferredOutputDevice));
    }

    /** The operator's explicitly chosen output device, or null for Android's Default. */
    private static AudioDeviceInfo preferredOutputDevice() {
        return GeneralVariables.audioOutputDeviceId > 0
                ? findAudioDeviceById(GeneralVariables.audioOutputDeviceId,
                        AudioManager.GET_DEVICES_OUTPUTS)
                : null;
    }

    /**
     * The chunked MODE_STREAM playback loop, extracted intact from the FT8
     * engine. Package-visible core so tests can drive it with a fake output
     * and an explicit layout.
     */
    PlayResult playViaPcmOutput(float[] buffer, int sampleRate, boolean float32,
                                VolumeSource volume, TxChannelLayout layout,
                                AudioDeviceInfo preferredDevice) {
        PcmOutput out;
        try {
            out = outputFactory.open(sampleRate, float32, layout, preferredDevice);
        } catch (Exception e) {
            // AudioTrack construction/play can throw on a bad route/rate or a
            // DEAD_OBJECT. Swallow-and-log: the caller's finally drops PTT, and
            // an escaping exception on the TX worker would crash the app with
            // the rig keyed.
            Log.e(TAG, "play: failed to open output: " + e);
            return PlayResult.ERROR;
        }
        activeOutput = out;
        try {
            // Stream the waveform in chunks, re-reading the volume each chunk so
            // a slider move ramps the on-air level within ~1 chunk + buffer depth.
            final int chunkSamples = Math.max(1, sampleRate / 20); // ~50ms
            // Stereo scratch, allocated once: the interleave runs every ~50ms
            // and a fresh array per chunk is churn for nothing. Unused on mono.
            final float[] stereoScratch =
                    layout.isStereo() ? new float[layout.samplesForFrames(chunkSamples)] : null;
            int framesWritten = 0;
            boolean writeError = false;
            int offset = 0;
            while (offset < buffer.length) {
                if (cancelled) break;
                int chunkLen = Math.min(chunkSamples, buffer.length - offset);
                // applyVolume reads the volume source fresh for this chunk (live gain).
                float[] chunk = applyVolume(buffer, offset, chunkLen, volume.volume());

                // On a stereo open the framework wants interleaved frames, with
                // the channel the operator excluded silenced. Mono hands the
                // chunk straight through — the original single-buffer write.
                float[] toWrite = layout.layOut(chunk, chunkLen, stereoScratch);
                int writeSamples = layout.samplesForFrames(chunkLen);

                int writeResult = float32
                        ? out.writeFloats(toWrite, writeSamples)
                        : out.writeShorts(floatToInt16NoPad(toWrite, writeSamples), writeSamples);
                if (writeResult < 0) {
                    Log.e(TAG, String.format("Playback error: %d", writeResult));
                    writeError = true;
                    break;
                }
                // write() counts samples, playbackHeadPosition() counts frames —
                // the drain wait below compares the two, so convert here.
                framesWritten += layout.framesFromSamples(writeResult);
                offset += chunkLen;
            }

            // Append the 8-frame zero pad once at the end (QP-7C RP2040 audio
            // detection compatibility), only for the int16 path. Skipped if we
            // errored or were cancelled.
            if (!writeError && !cancelled && !float32) {
                short[] pad = layout.zeroPad();// 8 frames, whatever the channel count
                int padResult = out.writeShorts(pad, pad.length);
                if (padResult > 0) framesWritten += layout.framesFromSamples(padResult);
            }

            // Blocking writes return once data is *buffered*, not played. Wait for
            // the tail (up to the buffer depth) to actually drain before releasing,
            // so the end of the message isn't truncated. A cancel skips the wait
            // (the canceller has already paused+flushed for immediate silence).
            if (!writeError && !cancelled) {
                while (!cancelled) {
                    if (out.playbackHeadPosition() >= framesWritten) break;
                    try {
                        sleeper.sleepMs(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (writeError) return PlayResult.ERROR;
            return cancelled ? PlayResult.CANCELLED : PlayResult.COMPLETED;
        } finally {
            // Worker-owned teardown, on both the normal exit and any thrown
            // output failure. The canceller never releases the streaming track —
            // it only flips `cancelled` and pauses/flushes — so there's no
            // release race against this write loop.
            activeOutput = null;
            out.release();
        }
    }

    /**
     * The open-ended streaming loop behind {@link #playStream(ChunkSource, int, boolean)}.
     * Package-visible so tests can drive it with a fake output and an explicit
     * layout.
     */
    PlayResult playStream(ChunkSource source, int sampleRate, boolean float32,
                          TxChannelLayout layout, AudioDeviceInfo preferredDevice) {
        PcmOutput out;
        try {
            out = outputFactory.open(sampleRate, float32, layout, preferredDevice);
        } catch (Exception e) {
            Log.e(TAG, "playStream: failed to open output: " + e);
            return PlayResult.ERROR;
        }
        activeOutput = out;
        try {
            final int chunkSamples = Math.max(1, sampleRate / 20); // ~50ms
            float[] chunk = new float[chunkSamples];
            final float[] stereoScratch =
                    layout.isStereo() ? new float[layout.samplesForFrames(chunkSamples)] : null;
            while (true) {
                if (cancelled) return PlayResult.CANCELLED;
                int written = source.nextChunk(chunk, chunkSamples);
                if (written <= 0) {
                    return PlayResult.COMPLETED;
                }
                float[] toWrite = layout.layOut(chunk, written, stereoScratch);
                int writeSamples = layout.samplesForFrames(written);
                int writeResult = float32
                        ? out.writeFloats(toWrite, writeSamples)
                        : out.writeShorts(floatToInt16NoPad(toWrite, writeSamples), writeSamples);
                if (writeResult < 0) {
                    Log.e(TAG, "playStream: write error " + writeResult);
                    return PlayResult.ERROR;
                }
            }
        } finally {
            activeOutput = null;
            out.release();
        }
    }

    // ---- direct USB route -------------------------------------------------------

    /**
     * Direct USB audio output, extracted intact from the FT8 engine. TX volume
     * is applied live inside the native write loop (UsbAudioNative.setTxVolume),
     * so the buffer is handed over at full scale. The iso-packet-length math in
     * cpp/usb_audio_capture.cpp is load-bearing (see AGENTS.md) and untouched.
     */
    private PlayResult playViaUsbAudio(float[] buffer, int sampleRate) {
        GeneralVariables.fileLog(String.format(
                "playViaUsbAudio: start, VID=%04X PID=%04X samples=%d rate=%d",
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId,
                buffer.length, sampleRate));

        Context context = GeneralVariables.getMainContext();
        if (context == null) {
            GeneralVariables.fileLog("playViaUsbAudio: ABORT no main context");
            return PlayResult.ERROR;
        }

        UsbDevice device = UsbAudioDevice.findDeviceByVidPid(context,
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId);
        if (device == null) {
            GeneralVariables.fileLog(String.format(
                    "playViaUsbAudio: ABORT USB audio output device not found "
                            + "by VID:PID %04X:%04X",
                    GeneralVariables.usbAudioOutputVendorId,
                    GeneralVariables.usbAudioOutputProductId));
            return PlayResult.ERROR;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            GeneralVariables.fileLog("playViaUsbAudio: ABORT UsbManager is null");
            return PlayResult.ERROR;
        }
        if (!usbManager.hasPermission(device)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT no USB permission for output device "
                            + "(re-pick (USB direct) in Settings to re-grant)");
            return PlayResult.ERROR;
        }

        UsbAudioDevice usbDev = new UsbAudioDevice();
        if (!usbDev.open(context, device)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT UsbAudioDevice.open() failed "
                            + "(descriptor parse or claimInterface failed)");
            return PlayResult.ERROR;
        }

        try {
            if (!usbDev.hasOutput()) {
                GeneralVariables.fileLog("playViaUsbAudio: ABORT device has no output endpoint");
                return PlayResult.ERROR;
            }

            if (!usbDev.activateOutput(48000)) {
                GeneralVariables.fileLog(
                        "playViaUsbAudio: ABORT activateOutput(48000) failed "
                                + "(alt-setting select or rate setup failed)");
                return PlayResult.ERROR;
            }
            GeneralVariables.fileLog("playViaUsbAudio: device opened, output activated at 48000 Hz");

            // writeAudio() clears the native cancel flag as it starts, so a
            // cancel that landed while we were opening the device would
            // otherwise be lost and the whole buffer would play.
            if (cancelled) return PlayResult.CANCELLED;

            // Pass the full-scale buffer; TX volume is applied live as it drains
            // (native scales each sample in its write loop, the UsbRequest
            // fallback scales per chunk), so the slider attenuates the
            // in-progress TX within tens of ms.
            GeneralVariables.fileLog(String.format(
                    "playViaUsbAudio: calling writeAudio playLength=%d rate=%d",
                    buffer.length, sampleRate));
            boolean success = usbDev.writeAudio(buffer, sampleRate);
            GeneralVariables.fileLog(buildWriteAudioResultLog(success));
            boolean wasCancelled = UsbAudioNative.writeCancelled || cancelled;
            // A drop is invisible at the rig (it keys and shows normal behavior
            // but transmits dead air), so tell the operator — unless the
            // "failure" is just the user stopping mid-message.
            if (shouldWarnTxDropped(success, wasCancelled)) {
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tx_audio_dropped));
            }
            if (success) return PlayResult.COMPLETED;
            return wasCancelled ? PlayResult.CANCELLED : PlayResult.ERROR;
        } finally {
            usbDev.close();
        }
    }

    /**
     * Whether a failed USB-audio write warrants the on-screen "TX dropped"
     * warning. A cancelled write is the operator's own stop, not a fault.
     */
    static boolean shouldWarnTxDropped(boolean success, boolean cancelled) {
        return !success && !cancelled;
    }

    /**
     * Builds the debug-log line for a {@code writeAudio} result. A failure here
     * means the USB write dropped the audio: the rig was keyed (PTT already
     * sent) but nothing went out, so it transmits dead air. That is invisible to
     * the operator (the rig keys normally), which is exactly why the dropped
     * case is spelled out rather than logged as a bare "FAILED".
     */
    static String buildWriteAudioResultLog(boolean success) {
        if (success) {
            return "playViaUsbAudio: writeAudio returned OK";
        }
        return "playViaUsbAudio: writeAudio returned FAILED — TX DROPPED, "
                + "no audio sent (rig keyed but silent)";
    }

    // ---- pure helpers -------------------------------------------------------------

    /**
     * Scale a slice of the waveform by the TX volume, returning a new buffer of
     * exactly {@code playLength} samples.
     *
     * <p>This is the single source of truth for TX level on the AudioTrack
     * path: {@code AudioTrack.setVolume()} is a no-op when the track is routed
     * to a USB Audio Class device, so the gain must be baked into the samples.
     * Pure function — unit-tested.
     *
     * @param source      full waveform
     * @param skipSamples offset of the slice start; clamped to 0
     * @param playLength  number of samples to emit
     * @param volume      gain 0.0-1.0; 0 yields digital silence
     */
    static float[] applyVolume(float[] source, int skipSamples, int playLength, float volume) {
        if (skipSamples < 0) skipSamples = 0;
        float[] out = new float[playLength];
        for (int i = 0; i < playLength; i++) {
            out[i] = source[skipSamples + i] * volume;
        }
        return out;
    }

    /**
     * Convert the first {@code length} float samples to 16-bit, with no
     * trailing zero padding: the chunked loop writes many chunks per message,
     * and the 8-frame QP-7C pad must be appended once at the very end, not
     * after every chunk. Pure function.
     */
    static short[] floatToInt16NoPad(float[] buffer, int length) {
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            float x = buffer[i];
            if (x > 1.0f) x = 1.0f;
            else if (x < -1.0f) x = -1.0f;
            out[i] = (short) (x * 32767.0);
        }
        return out;
    }

    // ---- the real AudioTrack output -------------------------------------------------

    /**
     * The real AudioTrack-backed output. MODE_STREAM (was MODE_STATIC) with a
     * deliberately SMALL buffer so TX volume can be applied live: the waveform
     * is fed out in ~50ms chunks and a blocking write only commits a chunk once
     * the device has room. The buffer depth (~200ms) bounds how much audio is
     * already queued at the old level, i.e. the worst-case latency from a
     * slider move to the level changing on air — instant enough to pull drive
     * down and protect the rig mid-over, which a one-shot MODE_STATIC write
     * could not do.
     *
     * <p>Also owns the per-playback audio focus: this branch shares Android's
     * mixer with every other app, so exclusive focus is claimed for the
     * transmission and abandoned in {@link PcmOutput#release()}. Each open gets
     * its own {@link TxAudioFocus} so a tune and a transmission can never
     * release each other's focus.
     */
    private class AudioTrackOutputFactory implements PcmOutputFactory {
        @Override
        public PcmOutput open(int sampleRate, boolean float32, TxChannelLayout layout,
                              AudioDeviceInfo preferredDevice) {
            // Denial is log-only: TX must still go out.
            final TxAudioFocus focus = new TxAudioFocus();
            boolean focusGranted = focus.acquire(GeneralVariables.getMainContext());
            GeneralVariables.fileLog("TransmitAudioSink: audio focus "
                    + (focusGranted ? "granted (exclusive)"
                    : "NOT granted — other-app audio may mix into TX"));

            Log.d(TAG, String.format(
                    "Preparing sound card playback... bit depth: %s, sample rate: %d, channels: %d",
                    float32 ? "Float32" : "Int16", sampleRate, layout.channels));
            if (layout.isStereo()) {
                GeneralVariables.fileLog(
                        "TransmitAudioSink: stereo TX open, channel select=" + layout.selection);
            }

            final AudioTrack track;
            try {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                int encoding = float32
                        ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT;
                AudioFormat format = new AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(layout.channelMask).build();
                int bytesPerSample = float32 ? 4 : 2;
                // ~200ms at whatever channel count the layout opened with.
                int targetBufBytes = layout.bufferBytes(sampleRate, bytesPerSample);
                int minBuf = AudioTrack.getMinBufferSize(sampleRate, layout.channelMask, encoding);
                int bufBytes = Math.max(targetBufBytes, minBuf > 0 ? minBuf : targetBufBytes);
                track = new AudioTrack(attributes, format, bufBytes, AudioTrack.MODE_STREAM, 0);

                if (preferredDevice != null) {
                    track.setPreferredDevice(preferredDevice);
                } else {
                    applyDefaultOutputRoutingOverride(track);
                }

                // Keep the track at unity: TX level is carried in the sample
                // values. On routes where AudioTrack.setVolume() works (phone
                // speaker) this avoids double-attenuation; on routes where it's a
                // no-op (USB Audio Class sound cards delegating level to device
                // hardware) the in-sample gain is what controls the rig's drive.
                track.play();
                track.setVolume(1.0f);
            } catch (RuntimeException e) {
                // The track never reached the caller, so nobody else will drop
                // the focus we just took.
                focus.release();
                throw e;
            }

            return new PcmOutput() {
                @Override
                public int writeFloats(float[] data, int length) {
                    return track.write(data, 0, length, AudioTrack.WRITE_BLOCKING);
                }

                @Override
                public int writeShorts(short[] data, int length) {
                    return track.write(data, 0, length, AudioTrack.WRITE_BLOCKING);
                }

                @Override
                public int playbackHeadPosition() {
                    try {
                        return track.getPlaybackHeadPosition();
                    } catch (IllegalStateException e) {
                        return Integer.MAX_VALUE; // released underneath us: stop waiting
                    }
                }

                @Override
                public void pauseAndFlush() {
                    try {
                        if (track.getState() != AudioTrack.STATE_UNINITIALIZED
                                && track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                            track.pause();
                            track.flush();
                        }
                    } catch (IllegalStateException ignored) {
                        // Worker already released the track between our read and here.
                    }
                }

                @Override
                public void release() {
                    try {
                        track.stop();
                    } catch (IllegalStateException ignored) {
                    }
                    track.release();
                    focus.release();
                }
            };
        }
    }

    /**
     * When the user picked "Default" output and <em>this app</em> is holding a
     * Bluetooth SCO link, pin the AudioTrack to the A2DP endpoint of the same
     * Bluetooth device. On Android 8.1 (issue #759 follow-up) the OS routes the
     * USAGE_MEDIA stream through SCO while the hands-free link is active,
     * leaving TX inaudible on a rig that only listens for the A2DP music
     * channel — the tester's exact "audio received, not transmitted" report,
     * cured by manually picking A2DP. Leaves routing to the OS whenever the
     * conditions aren't met; see {@link AudioOutputRoutingPolicy} for why the
     * enumerated device types alone are not enough to decide.
     *
     * <p>The "is our SCO link up" answer is the {@link ScoSnapshot} taken at
     * keying time, not the tracker's live state: by the time this runs — after
     * key-down and the PTT settle sleep — the posted {@code requestOff()} has
     * normally already flipped the tracker to DISCONNECTED, so a live query
     * said "no" in exactly the case the steering exists for. With no snapshot
     * wired, "no SCO session of ours" is the correct answer.
     */
    private void applyDefaultOutputRoutingOverride(final AudioTrack track) {
        Context context = GeneralVariables.getMainContext();
        if (context == null) return;
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        ScoSnapshot sco = scoSnapshot;
        boolean heldForTx = sco != null && sco.heldForTx();
        String scoAddress = sco == null ? null : sco.address();
        // The enumeration-to-policy-to-track wiring (and the address gating for
        // API < 28 / a denied BLUETOOTH_CONNECT) lives in DefaultOutputRouting so
        // it is covered by DefaultOutputRoutingTest; only the real track and the
        // debug log are supplied from here.
        DefaultOutputRouting.apply(outputs, heldForTx, scoAddress,
                new DefaultOutputRouting.Sink() {
                    @Override
                    public boolean setPreferredDevice(AudioDeviceInfo device) {
                        return track.setPreferredDevice(device);
                    }

                    @Override
                    public void log(String line) {
                        GeneralVariables.fileLog(line);
                    }
                });
    }

    /** Find an AudioDeviceInfo by device ID. */
    private static AudioDeviceInfo findAudioDeviceById(int deviceId, int deviceType) {
        Context context = GeneralVariables.getMainContext();
        if (context == null) return null;
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return null;
        AudioDeviceInfo[] devices = audioManager.getDevices(deviceType);
        for (AudioDeviceInfo device : devices) {
            if (device.getId() == deviceId) {
                return device;
            }
        }
        return null;
    }
}
