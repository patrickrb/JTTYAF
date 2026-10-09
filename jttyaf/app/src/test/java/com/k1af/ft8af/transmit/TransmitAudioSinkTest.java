package com.k1af.ft8af.transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.wave.AudioChannelSelect;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for the chunked playback loop extracted from the FT8 engine.
 * The AudioTrack is abstracted behind {@link TransmitAudioSink.PcmOutput} so
 * chunking, live volume application, the stereo layout, the QP-7C end pad, the
 * drain wait, the rig-route priority and cancellation are all exercised without
 * an Android runtime.
 */
public class TransmitAudioSinkTest {

    private static final int STEREO_SINK = 2;
    private static final TxChannelLayout MONO =
            TxChannelLayout.resolve(AudioChannelSelect.BOTH, true, STEREO_SINK);
    private static final TxChannelLayout LEFT_ONLY =
            TxChannelLayout.resolve(AudioChannelSelect.LEFT, true, STEREO_SINK);

    /** A fake PCM output that records every write. */
    private static class FakeOutput implements TransmitAudioSink.PcmOutput {
        final List<float[]> floatWrites = new ArrayList<>();
        final List<short[]> shortWrites = new ArrayList<>();
        int channels = 1;
        int framesWritten = 0;
        int opens = 0;
        boolean released = false;
        boolean pausedAndFlushed = false;
        int failAfterWrites = -1; // when >= 0, writes past this index fail

        private float[] slice(float[] data, int length) {
            float[] out = new float[length];
            System.arraycopy(data, 0, out, 0, length);
            return out;
        }

        @Override
        public int writeFloats(float[] data, int length) {
            if (failAfterWrites >= 0 && floatWrites.size() >= failAfterWrites) {
                return -1;
            }
            floatWrites.add(slice(data, length));
            framesWritten += length / channels;
            return length;
        }

        @Override
        public int writeShorts(short[] data, int length) {
            if (failAfterWrites >= 0 && shortWrites.size() >= failAfterWrites) {
                return -1;
            }
            short[] out = new short[length];
            System.arraycopy(data, 0, out, 0, length);
            shortWrites.add(out);
            framesWritten += length / channels;
            return length;
        }

        @Override
        public int playbackHeadPosition() {
            return framesWritten; // pretend playback keeps up instantly
        }

        @Override
        public void pauseAndFlush() {
            pausedAndFlushed = true;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    private static TransmitAudioSink sinkFor(FakeOutput out) {
        return new TransmitAudioSink((rate, f32, layout, device) -> {
            out.opens++;
            out.channels = layout.channels;
            return out;
        }, ms -> { /* no real sleeping */ });
    }

    private static float[] ramp(int n) {
        float[] b = new float[n];
        for (int i = 0; i < n; i++) b[i] = (i % 100) / 100f;
        return b;
    }

    // ---- chunking -----------------------------------------------------------

    @Test
    public void play_writesWholeBufferInChunks_noLeadingTrim() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        // 1000 samples at 1000 Hz -> chunk = 1000/20 = 50 samples -> 20 chunks.
        float[] buffer = ramp(1000);

        TransmitAudioSink.PlayResult result =
                sink.playViaPcmOutput(buffer, 1000, true, () -> 1.0f, MONO, null);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.COMPLETED);
        assertThat(out.floatWrites).hasSize(20);
        int total = 0;
        for (float[] w : out.floatWrites) total += w.length;
        assertThat(total).isEqualTo(1000);
        // The very first sample of the very first chunk is buffer[0]: the whole
        // buffer plays — no lateStartSkipMs-style leading clip survives here.
        assertThat(out.floatWrites.get(0)[0]).isEqualTo(buffer[0]);
        assertThat(out.released).isTrue();
    }

    @Test
    public void play_lastChunkIsPartial() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        // 130 samples with 50-sample chunks -> 50 + 50 + 30.
        TransmitAudioSink.PlayResult result =
                sink.playViaPcmOutput(ramp(130), 1000, true, () -> 1.0f, MONO, null);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.COMPLETED);
        assertThat(out.floatWrites).hasSize(3);
        assertThat(out.floatWrites.get(2)).hasLength(30);
    }

    @Test
    public void play_emptyBufferIsAnError() {
        TransmitAudioSink sink = sinkFor(new FakeOutput());
        assertThat(sink.play(new float[0], 1000, true, () -> 1.0f))
                .isEqualTo(TransmitAudioSink.PlayResult.ERROR);
        assertThat(sink.play(null, 1000, true, () -> 1.0f))
                .isEqualTo(TransmitAudioSink.PlayResult.ERROR);
    }

    // ---- volume application -------------------------------------------------

    @Test
    public void play_readsVolumeFreshPerChunk() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        float[] buffer = new float[100];
        java.util.Arrays.fill(buffer, 1.0f);
        // Volume halves after the first chunk (50 samples).
        AtomicInteger calls = new AtomicInteger();
        TransmitAudioSink.VolumeSource volume =
                () -> calls.getAndIncrement() == 0 ? 1.0f : 0.5f;

        sink.playViaPcmOutput(buffer, 1000, true, volume, MONO, null);

        assertThat(out.floatWrites).hasSize(2);
        assertThat(out.floatWrites.get(0)[0]).isEqualTo(1.0f);
        assertThat(out.floatWrites.get(1)[0]).isEqualTo(0.5f);
    }

    @Test
    public void applyVolume_scalesSliceByGain() {
        float[] src = {0.2f, 0.4f, 0.8f, 1.0f};
        float[] out = TransmitAudioSink.applyVolume(src, 1, 2, 0.5f);
        assertThat(out).hasLength(2);
        assertThat(out[0]).isWithin(1e-6f).of(0.2f);
        assertThat(out[1]).isWithin(1e-6f).of(0.4f);
    }

    @Test
    public void applyVolume_zeroGainYieldsDigitalSilence() {
        float[] out = TransmitAudioSink.applyVolume(new float[]{1f, -1f}, 0, 2, 0f);
        assertThat(out[0]).isWithin(0f).of(0f);
        assertThat(out[1]).isWithin(0f).of(0f);// -1f * 0f == -0.0f, still silence
    }

    @Test
    public void floatToInt16NoPad_clipsAndScalesWithoutPadding() {
        short[] out = TransmitAudioSink.floatToInt16NoPad(new float[]{2.0f, -2.0f, 0f}, 3);
        assertThat(out).hasLength(3);// no trailing pad per chunk
        assertThat(out[0]).isEqualTo((short) 32767);
        assertThat(out[1]).isEqualTo((short) -32767);
        assertThat(out[2]).isEqualTo((short) 0);
    }

    // ---- int16 path + QP-7C end pad ------------------------------------------

    @Test
    public void play_int16Path_appendsEightSampleZeroPadOnceAtEnd() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        sink.playViaPcmOutput(ramp(100), 1000, false, () -> 1.0f, MONO, null);

        // 2 audio chunks of 50 + one 8-sample pad write.
        assertThat(out.shortWrites).hasSize(3);
        short[] pad = out.shortWrites.get(2);
        assertThat(pad).hasLength(8);
        for (short s : pad) assertThat(s).isEqualTo((short) 0);
    }

    @Test
    public void play_float32Path_hasNoEndPad() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        sink.playViaPcmOutput(ramp(100), 1000, true, () -> 1.0f, MONO, null);
        assertThat(out.floatWrites).hasSize(2);
        assertThat(out.shortWrites).isEmpty();
    }

    // ---- stereo layout ----------------------------------------------------------

    @Test
    public void play_stereoLayout_interleavesAndSilencesTheExcludedSide() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        float[] buffer = {0.1f, 0.2f, 0.3f};

        TransmitAudioSink.PlayResult result =
                sink.playViaPcmOutput(buffer, 20, true, () -> 1.0f, LEFT_ONLY, null);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.COMPLETED);
        assertThat(out.channels).isEqualTo(2);// the track was opened stereo
        assertThat(out.floatWrites).hasSize(3);// 20 Hz -> 1-sample chunks
        // Left carries the waveform, right is digital silence, 2 samples per frame.
        assertThat(out.floatWrites.get(0)).usingExactEquality().containsExactly(0.1f, 0f).inOrder();
        assertThat(out.floatWrites.get(2)).usingExactEquality().containsExactly(0.3f, 0f).inOrder();
    }

    @Test
    public void play_stereoInt16Path_padIsEightFramesSixteenSamples() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        sink.playViaPcmOutput(ramp(100), 1000, false, () -> 1.0f, LEFT_ONLY, null);

        // 2 chunks of 50 frames (100 samples each) + the pad.
        assertThat(out.shortWrites).hasSize(3);
        assertThat(out.shortWrites.get(0)).hasLength(100);
        assertThat(out.shortWrites.get(2)).hasLength(16);
    }

    @Test
    public void play_stereoDrainWait_comparesFramesNotSamples() {
        // The fake's head position counts frames (samples / channels), like the
        // real track. If the sink compared samples written against frames played
        // the drain loop would spin until cancelled; with the no-op sleeper that
        // would hang, so completing at all proves the conversion.
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        TransmitAudioSink.PlayResult result =
                sink.playViaPcmOutput(ramp(100), 1000, true, () -> 1.0f, LEFT_ONLY, null);
        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.COMPLETED);
        assertThat(out.framesWritten).isEqualTo(100);
    }

    // ---- errors & cancellation ------------------------------------------------

    @Test
    public void play_writeErrorReturnsErrorAndReleases() {
        FakeOutput out = new FakeOutput();
        out.failAfterWrites = 1;// second write fails
        TransmitAudioSink sink = sinkFor(out);

        TransmitAudioSink.PlayResult result =
                sink.playViaPcmOutput(ramp(1000), 1000, true, () -> 1.0f, MONO, null);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.ERROR);
        assertThat(out.floatWrites).hasSize(1);
        assertThat(out.released).isTrue();
    }

    @Test
    public void play_openFailureReturnsError() {
        TransmitAudioSink sink = new TransmitAudioSink((rate, f32, layout, device) -> {
            throw new IllegalStateException("AudioTrack init failed");
        }, ms -> { });
        TransmitAudioSink.PlayResult result =
                sink.playViaPcmOutput(ramp(100), 1000, true, () -> 1.0f, MONO, null);
        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.ERROR);
    }

    @Test
    public void cancel_stopsMidBuffer_pausesAndFlushes_workerReleases() throws Exception {
        CountDownLatch firstWrite = new CountDownLatch(1);
        CountDownLatch mayContinue = new CountDownLatch(1);
        FakeOutput out = new FakeOutput() {
            @Override
            public int writeFloats(float[] data, int length) {
                firstWrite.countDown();
                try {
                    mayContinue.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return super.writeFloats(data, length);
            }
        };
        TransmitAudioSink sink = sinkFor(out);

        final TransmitAudioSink.PlayResult[] result = new TransmitAudioSink.PlayResult[1];
        Thread worker = new Thread(() ->
                result[0] = sink.playViaPcmOutput(ramp(1000), 1000, true, () -> 1.0f, MONO, null));
        worker.start();

        assertThat(firstWrite.await(5, TimeUnit.SECONDS)).isTrue();
        sink.cancel();// UI-thread side: flags + pause/flush, never releases
        assertThat(out.pausedAndFlushed).isTrue();
        assertThat(out.released).isFalse();

        mayContinue.countDown();
        worker.join(5000);

        assertThat(result[0]).isEqualTo(TransmitAudioSink.PlayResult.CANCELLED);
        assertThat(out.released).isTrue();// worker-owned teardown
        // Cancellation after the first chunk: nowhere near the full 20 chunks.
        assertThat(out.floatWrites.size()).isLessThan(20);
        // A cancelled int16-less run must not have appended the end pad.
        assertThat(out.shortWrites).isEmpty();
    }

    @Test
    public void cancel_beforeAnyOutputIsOpen_isHarmless() {
        TransmitAudioSink sink = sinkFor(new FakeOutput());
        sink.cancel();// nothing active: must not throw
    }

    // ---- rig wave route priority ------------------------------------------------

    @Test
    public void play_rigRouteAvailable_bypassesLocalOutputAndHoldsForDuration() {
        FakeOutput out = new FakeOutput();
        List<Long> sleeps = new ArrayList<>();
        TransmitAudioSink sink = new TransmitAudioSink((rate, f32, layout, device) -> {
            out.opens++;
            return out;
        }, sleeps::add);
        List<String> sent = new ArrayList<>();
        sink.setRigWaveRoute(new TransmitAudioSink.RigWaveRoute() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean sendWave(float[] buffer, int sampleRate) {
                sent.add(buffer.length + "@" + sampleRate);
                return true;
            }
        });

        // 10 samples at 1000 Hz = 10 ms; the hold loop sleeps in 20 ms steps
        // until the wall clock passes it, so with a no-op sleeper it spins a
        // few times and exits on its own.
        TransmitAudioSink.PlayResult result =
                sink.play(new float[10], 1000, true, () -> 1.0f);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.COMPLETED);
        assertThat(sent).containsExactly("10@1000");
        assertThat(out.opens).isEqualTo(0);// local sink never touched
    }

    @Test
    public void play_rigRouteUnavailable_fallsThroughToLocalOutput() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        AtomicInteger sends = new AtomicInteger();
        sink.setRigWaveRoute(new TransmitAudioSink.RigWaveRoute() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public boolean sendWave(float[] buffer, int sampleRate) {
                sends.incrementAndGet();
                return true;
            }
        });

        // The route is checked first; this exercises the fall-through into the
        // chunked loop with the test's explicit layout.
        TransmitAudioSink.PlayResult result =
                sink.playViaPcmOutput(ramp(100), 1000, true, () -> 1.0f, MONO, null);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.COMPLETED);
        assertThat(sends.get()).isEqualTo(0);
        assertThat(out.floatWrites).hasSize(2);
    }

    @Test
    public void play_rigRouteSendFailure_isAnError() {
        TransmitAudioSink sink = sinkFor(new FakeOutput());
        sink.setRigWaveRoute(new TransmitAudioSink.RigWaveRoute() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean sendWave(float[] buffer, int sampleRate) {
                return false;
            }
        });
        assertThat(sink.play(new float[10], 1000, true, () -> 1.0f))
                .isEqualTo(TransmitAudioSink.PlayResult.ERROR);
    }

    // ---- streaming (Tune) ------------------------------------------------------

    @Test
    public void playStream_drainsChunkSourceUntilExhausted() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        AtomicInteger remaining = new AtomicInteger(3);
        TransmitAudioSink.ChunkSource source = (buf, maxLen) -> {
            if (remaining.getAndDecrement() <= 0) return 0;
            java.util.Arrays.fill(buf, 0, maxLen, 0.25f);
            return maxLen;
        };

        TransmitAudioSink.PlayResult result = sink.playStream(source, 1000, true, MONO, null);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.COMPLETED);
        assertThat(out.floatWrites).hasSize(3);
        assertThat(out.released).isTrue();
    }

    @Test
    public void playStream_stereoLayout_writesInterleavedFrames() {
        FakeOutput out = new FakeOutput();
        TransmitAudioSink sink = sinkFor(out);
        AtomicInteger remaining = new AtomicInteger(1);
        TransmitAudioSink.ChunkSource source = (buf, maxLen) -> {
            if (remaining.getAndDecrement() <= 0) return 0;
            buf[0] = 0.5f;
            return 1;// a short final chunk
        };

        sink.playStream(source, 1000, true, LEFT_ONLY, null);

        assertThat(out.floatWrites).hasSize(1);
        assertThat(out.floatWrites.get(0)).usingExactEquality().containsExactly(0.5f, 0f).inOrder();
    }

    @Test
    public void playStream_writeErrorReturnsError() {
        FakeOutput out = new FakeOutput();
        out.failAfterWrites = 0;
        TransmitAudioSink sink = sinkFor(out);
        TransmitAudioSink.PlayResult result =
                sink.playStream((buf, maxLen) -> maxLen, 1000, true, MONO, null);
        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.ERROR);
        assertThat(out.released).isTrue();
    }

    @Test
    public void playStream_cancelEndsTheStream() throws Exception {
        CountDownLatch firstWrite = new CountDownLatch(1);
        CountDownLatch mayContinue = new CountDownLatch(1);
        FakeOutput out = new FakeOutput() {
            @Override
            public int writeFloats(float[] data, int length) {
                firstWrite.countDown();
                try {
                    mayContinue.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return super.writeFloats(data, length);
            }
        };
        TransmitAudioSink sink = sinkFor(out);
        final TransmitAudioSink.PlayResult[] result = new TransmitAudioSink.PlayResult[1];
        Thread worker = new Thread(() ->
                result[0] = sink.playStream((buf, maxLen) -> maxLen, 1000, true, MONO, null));
        worker.start();

        assertThat(firstWrite.await(5, TimeUnit.SECONDS)).isTrue();
        sink.cancel();
        mayContinue.countDown();
        worker.join(5000);

        assertThat(result[0]).isEqualTo(TransmitAudioSink.PlayResult.CANCELLED);
        assertThat(out.pausedAndFlushed).isTrue();
        assertThat(out.released).isTrue();
    }

    // ---- route helpers ----------------------------------------------------------

    @Test
    public void isUsbDirectOutput_requiresMinusOneDeviceAndVid() {
        assertThat(TransmitAudioSink.isUsbDirectOutput(-1, 0x0D8C)).isTrue();
        assertThat(TransmitAudioSink.isUsbDirectOutput(-1, 0)).isFalse();
        assertThat(TransmitAudioSink.isUsbDirectOutput(0, 0x0D8C)).isFalse();
        assertThat(TransmitAudioSink.isUsbDirectOutput(3, 0x0D8C)).isFalse();
    }

    @Test
    public void shouldWarnTxDropped_onlyForRealFailures() {
        // A dropped USB TX write is invisible at the rig (it keys and transmits
        // dead air), so the operator gets a warning — but not for their own stop.
        assertThat(TransmitAudioSink.shouldWarnTxDropped(false, false)).isTrue();
        assertThat(TransmitAudioSink.shouldWarnTxDropped(false, true)).isFalse();// user stop
        assertThat(TransmitAudioSink.shouldWarnTxDropped(true, false)).isFalse();
        assertThat(TransmitAudioSink.shouldWarnTxDropped(true, true)).isFalse();
    }

    @Test
    public void buildWriteAudioResultLog_spellsOutTheDrop() {
        assertThat(TransmitAudioSink.buildWriteAudioResultLog(true))
                .isEqualTo("playViaUsbAudio: writeAudio returned OK");
        String log = TransmitAudioSink.buildWriteAudioResultLog(false);
        assertThat(log).contains("FAILED");
        assertThat(log).contains("TX DROPPED");
        // Must convey that nothing was actually transmitted.
        assertThat(log).contains("no audio sent");
    }

    @Test
    public void outputMaxChannels_nullDeviceIsUnknown() {
        assertThat(TransmitAudioSink.outputMaxChannels(null))
                .isEqualTo(com.k1af.ft8af.wave.AudioChannelCapability.UNKNOWN);
    }
}
