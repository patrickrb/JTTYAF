package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** Pure-logic tests for the debug WAV injector: header walk, PCM conversion, pacing, looping. */
public class WavInjectorTest {

    /** Build a canonical 44-byte-header WAV, optionally with an extra chunk before `data`. */
    private static byte[] wav(int rate, int channels, int bits, int formatTag, short[] pcm, boolean extraChunk) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer fmt = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        fmt.put("fmt ".getBytes()).putInt(16)
                .putShort((short) formatTag).putShort((short) channels).putInt(rate)
                .putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8))
                .putShort((short) bits);
        ByteBuffer data = ByteBuffer.allocate(8 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        data.put("data".getBytes()).putInt(pcm.length * 2);
        for (short s : pcm) data.putShort(s);
        // An odd-sized LIST chunk exercises the word-alignment pad.
        byte[] list = extraChunk
                ? ByteBuffer.allocate(8 + 3 + 1).order(ByteOrder.LITTLE_ENDIAN)
                        .put("LIST".getBytes()).putInt(3).put(new byte[] {1, 2, 3, 0}).array()
                : new byte[0];
        int riffSize = 4 + fmt.capacity() + list.length + data.capacity();
        ByteBuffer riff = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        riff.put("RIFF".getBytes()).putInt(riffSize).put("WAVE".getBytes());
        out.write(riff.array(), 0, 12);
        out.write(fmt.array(), 0, fmt.capacity());
        out.write(list, 0, list.length);
        out.write(data.array(), 0, data.capacity());
        return out.toByteArray();
    }

    @Test
    public void gateRequiresDebugBuildAndFile() {
        assertThat(WavInjector.shouldInject(true, true)).isTrue();
        assertThat(WavInjector.shouldInject(true, false)).isFalse();
        assertThat(WavInjector.shouldInject(false, true)).isFalse();
        assertThat(WavInjector.shouldInject(false, false)).isFalse();
    }

    @Test
    public void parsesCanonicalMono16BitHeader() {
        byte[] b = wav(12000, 1, 16, 1, new short[] {1, 2, 3}, false);
        WavInjector.WavInfo info = WavInjector.parseHeader(b);
        assertThat(info).isNotNull();
        assertThat(info.sampleRate).isEqualTo(12000);
        assertThat(info.channels).isEqualTo(1);
        assertThat(info.bitsPerSample).isEqualTo(16);
        assertThat(info.dataOffset).isEqualTo(44);
        assertThat(info.dataLength).isEqualTo(6);
    }

    @Test
    public void skipsUnknownChunksAndHonoursPadByte() {
        byte[] b = wav(12000, 2, 16, 1, new short[] {1, 2, 3, 4}, true);
        WavInjector.WavInfo info = WavInjector.parseHeader(b);
        assertThat(info).isNotNull();
        assertThat(info.channels).isEqualTo(2);
        // 12 (RIFF) + 24 (fmt) + 12 (LIST incl. pad) + 8 (data header)
        assertThat(info.dataOffset).isEqualTo(56);
        assertThat(info.dataLength).isEqualTo(8);
    }

    @Test
    public void rejectsNonPcmAndNon16Bit() {
        assertThat(WavInjector.parseHeader(wav(12000, 1, 8, 1, new short[] {1}, false))).isNull();
        assertThat(WavInjector.parseHeader(wav(12000, 1, 16, 3, new short[] {1}, false))).isNull();
    }

    @Test
    public void rejectsGarbageAndTruncatedInput() {
        assertThat(WavInjector.parseHeader(null)).isNull();
        assertThat(WavInjector.parseHeader(new byte[] {1, 2, 3})).isNull();
        assertThat(WavInjector.parseHeader("RIFF....WAVE".getBytes())).isNull();
        byte[] noData = wav(12000, 1, 16, 1, new short[0], false);
        byte[] cut = new byte[noData.length - 8]; // drop the data chunk header entirely
        System.arraycopy(noData, 0, cut, 0, cut.length);
        assertThat(WavInjector.parseHeader(cut)).isNull();
    }

    @Test
    public void clampsOversizedDataLengthToBuffer() {
        byte[] b = wav(12000, 1, 16, 1, new short[] {1, 2}, false);
        // Lie in the data chunk size: claim 1 MB.
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 1 << 20);
        WavInjector.WavInfo info = WavInjector.parseHeader(b);
        assertThat(info).isNotNull();
        assertThat(info.dataLength).isEqualTo(4);
    }

    @Test
    public void pcm16MonoScalesToUnitRange() {
        byte[] b = wav(12000, 1, 16, 1, new short[] {32767, -32768, 0}, false);
        float[] out = new float[3];
        int n = WavInjector.pcm16ToMonoFloat(b, 44, 6, 1, out);
        assertThat(n).isEqualTo(3);
        assertThat(out[0]).isWithin(1e-4f).of(0.99997f);
        assertThat(out[1]).isWithin(1e-6f).of(-1f);
        assertThat(out[2]).isWithin(1e-6f).of(0f);
    }

    @Test
    public void pcm16StereoIsAveragedToMono() {
        byte[] b = wav(12000, 2, 16, 1, new short[] {16384, -16384, 8192, 8192}, false);
        float[] out = new float[2];
        int n = WavInjector.pcm16ToMonoFloat(b, 44, 8, 2, out);
        assertThat(n).isEqualTo(2);
        assertThat(out[0]).isWithin(1e-6f).of(0f);
        assertThat(out[1]).isWithin(1e-6f).of(0.25f);
    }

    @Test
    public void pacingSleepsWhenAheadAndNeverNegative() {
        long start = 1_000_000_000L;
        // 1200 samples at 12 kHz = 100 ms. Only 40 ms elapsed → sleep 60 ms.
        assertThat(WavInjector.paceSleepNanos(start, start + 40_000_000L, 1200, 12000))
                .isEqualTo(60_000_000L);
        // 150 ms elapsed → behind schedule → no sleep.
        assertThat(WavInjector.paceSleepNanos(start, start + 150_000_000L, 1200, 12000))
                .isEqualTo(0L);
    }

    @Test
    public void positionWrapsAtEndOfFile() {
        assertThat(WavInjector.nextPosition(0, 1200, 36000)).isEqualTo(1200);
        assertThat(WavInjector.nextPosition(34800, 1200, 36000)).isEqualTo(0);
        // Short final chunk still wraps to the start.
        assertThat(WavInjector.nextPosition(35000, 1000, 36000)).isEqualTo(0);
    }

    @Test
    public void samplesElapsedTracksTheClockWithoutOverflow() {
        assertThat(WavInjector.samplesElapsed(0, 12000)).isEqualTo(0);
        assertThat(WavInjector.samplesElapsed(-5, 12000)).isEqualTo(0);
        assertThat(WavInjector.samplesElapsed(1_000_000_000L, 12000)).isEqualTo(12000);
        assertThat(WavInjector.samplesElapsed(1_500_000_000L, 12000)).isEqualTo(18000);
        // ~10 days: a naive elapsed*rate would overflow a long.
        long tenDays = 10L * 24 * 3600 * 1_000_000_000L;
        assertThat(WavInjector.samplesElapsed(tenDays, 12000)).isEqualTo(10L * 24 * 3600 * 12000);
    }

    @Test
    public void threadDeliversChunksAndStopsWhenAskedTo() throws Exception {
        float[] samples = new float[WavInjector.CHUNK_SAMPLES * 3];
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch some = new CountDownLatch(2);
        MicRecorder.OnDataListener listener = (data, len) -> {
            delivered.addAndGet(len);
            some.countDown();
        };
        Thread t = WavInjector.startThread(samples, 12000, () -> listener, running::get);
        assertThat(some.await(5, TimeUnit.SECONDS)).isTrue();
        running.set(false);
        t.join(5000);
        assertThat(t.isAlive()).isFalse();
        assertThat(delivered.get() % WavInjector.CHUNK_SAMPLES).isEqualTo(0);
        assertThat(delivered.get()).isAtLeast(2 * WavInjector.CHUNK_SAMPLES);
    }
}
