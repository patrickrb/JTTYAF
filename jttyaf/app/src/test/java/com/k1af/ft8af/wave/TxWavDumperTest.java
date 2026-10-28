package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.TimeZone;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The debug TX dump: gate, file naming, sample conversion, WAV layout (round-tripped
 * through the injector's reader), pruning policy and the on-disk write. Robolectric only
 * because {@code write} logs through GeneralVariables.fileLog (android.util.Log).
 */
@RunWith(RobolectricTestRunner.class)
public class TxWavDumperTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void gateNeedsDebugBuildAndADirectory() {
        File dir = new File("x");
        assertThat(TxWavDumper.shouldDump(true, dir)).isTrue();
        assertThat(TxWavDumper.shouldDump(false, dir)).isFalse();
        assertThat(TxWavDumper.shouldDump(true, null)).isFalse();
        assertThat(TxWavDumper.shouldDump(false, null)).isFalse();
    }

    @Test
    public void fileNameEmbedsTimestampAndSortsChronologically() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        assertThat(TxWavDumper.fileName(0L, utc)).isEqualTo("jtty_tx_19700101_000000_000.wav");
        String earlier = TxWavDumper.fileName(1_700_000_000_123L, utc);
        String later = TxWavDumper.fileName(1_700_000_001_000L, utc);
        assertThat(earlier).isEqualTo("jtty_tx_20231114_221320_123.wav");
        assertThat(earlier.compareTo(later)).isLessThan(0);
    }

    @Test
    public void floatToPcm16ScalesAndClips() {
        assertThat(TxWavDumper.floatToPcm16(0f)).isEqualTo((short) 0);
        assertThat(TxWavDumper.floatToPcm16(0.5f)).isEqualTo((short) 16384);
        assertThat(TxWavDumper.floatToPcm16(-0.5f)).isEqualTo((short) -16384);
        assertThat(TxWavDumper.floatToPcm16(1f)).isEqualTo((short) 32767);
        assertThat(TxWavDumper.floatToPcm16(-1f)).isEqualTo((short) -32768);
        // Over-range input clips rather than wrapping to the opposite sign.
        assertThat(TxWavDumper.floatToPcm16(2f)).isEqualTo((short) 32767);
        assertThat(TxWavDumper.floatToPcm16(-2f)).isEqualTo((short) -32768);
    }

    @Test
    public void toWavRoundTripsThroughTheInjectorReader() {
        float[] samples = new float[] {0f, 0.25f, -0.25f, 0.8f, -0.8f, 1f, -1f};
        byte[] wav = TxWavDumper.toWav(samples, 12_000);

        assertThat(wav).hasLength(TxWavDumper.HEADER_BYTES + samples.length * 2);
        WavInjector.WavInfo info = WavInjector.parseHeader(wav);
        assertThat(info).isNotNull();
        assertThat(info.sampleRate).isEqualTo(12_000);
        assertThat(info.channels).isEqualTo(1);
        assertThat(info.bitsPerSample).isEqualTo(16);
        assertThat(info.dataOffset).isEqualTo(TxWavDumper.HEADER_BYTES);
        assertThat(info.dataLength).isEqualTo(samples.length * 2);

        float[] back = new float[samples.length];
        int n = WavInjector.pcm16ToMonoFloat(wav, info.dataOffset, info.dataLength, info.channels, back);
        assertThat(n).isEqualTo(samples.length);
        for (int i = 0; i < samples.length; i++) {
            assertThat(back[i]).isWithin(1f / 32768f).of(samples[i]);
        }
    }

    @Test
    public void toWavRefusesNonsense() {
        assertThat(TxWavDumper.toWav(null, 12_000)).isNull();
        assertThat(TxWavDumper.toWav(new float[0], 12_000)).isNull();
        assertThat(TxWavDumper.toWav(new float[] {0.1f}, 0)).isNull();
        assertThat(TxWavDumper.toWav(new float[TxWavDumper.MAX_SAMPLES + 1], 12_000)).isNull();
        assertThat(TxWavDumper.toWav(new float[] {0.1f}, 12_000)).isNotNull();
    }

    @Test
    public void filesToPruneDropsOldestOfOursOnly() {
        File[] existing = new File[] {
                new File("jtty_tx_20260101_000003_000.wav"),
                new File("inject.wav"),
                new File("jtty_tx_20260101_000001_000.wav"),
                new File("notes.txt"),
                new File("jtty_tx_20260101_000002_000.wav"),
                new File("jtty_tx_20260101_000004_000.wav"),
        };
        List<File> prune = TxWavDumper.filesToPrune(existing, 2);
        assertThat(prune).containsExactly(
                new File("jtty_tx_20260101_000001_000.wav"),
                new File("jtty_tx_20260101_000002_000.wav")).inOrder();

        assertThat(TxWavDumper.filesToPrune(existing, 4)).isEmpty();
        assertThat(TxWavDumper.filesToPrune(existing, 10)).isEmpty();
        assertThat(TxWavDumper.filesToPrune(existing, 0)).hasSize(4);
        assertThat(TxWavDumper.filesToPrune(null, 2)).isEmpty();
    }

    @Test
    public void writeCreatesDirWritesReadableWavAndPrunes() throws IOException {
        File dir = new File(tmp.getRoot(), "tx_dump");
        // Pre-existing dumps up to the cap, all older than the one we are about to write,
        // plus a stranger that must survive.
        dir.mkdirs();
        for (int i = 0; i < TxWavDumper.KEEP_FILES; i++) {
            File f = new File(dir, String.format("jtty_tx_20200101_0000%02d_000.wav", i));
            Files.write(f.toPath(), new byte[] {1});
        }
        File stranger = new File(dir, "inject.wav");
        Files.write(stranger.toPath(), new byte[] {1});

        float[] samples = new float[] {0f, 0.5f, -0.5f};
        TxWavDumper.write(dir, samples, 12_000, 1_700_000_000_000L);

        File[] dumps = dir.listFiles((d, name) -> name.startsWith("jtty_tx_"));
        assertThat(dumps).isNotNull();
        assertThat(dumps).hasLength(TxWavDumper.KEEP_FILES);
        assertThat(new File(dir, "jtty_tx_20200101_000000_000.wav").exists()).isFalse();
        assertThat(new File(dir, "jtty_tx_20200101_000001_000.wav").exists()).isTrue();
        assertThat(stranger.exists()).isTrue();

        File written = new File(dir, TxWavDumper.fileName(1_700_000_000_000L, TimeZone.getDefault()));
        assertThat(written.exists()).isTrue();
        byte[] bytes = Files.readAllBytes(written.toPath());
        WavInjector.WavInfo info = WavInjector.parseHeader(bytes);
        assertThat(info).isNotNull();
        assertThat(info.sampleRate).isEqualTo(12_000);
        assertThat(info.dataLength).isEqualTo(samples.length * 2);
    }

    @Test
    public void writeSwallowsAnUnwritableTarget() {
        // A regular file where the directory should be: mkdirs fails, write must not throw.
        File notADir = new File(tmp.getRoot(), "file");
        try {
            Files.write(notADir.toPath(), new byte[] {1});
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        TxWavDumper.write(notADir, new float[] {0.1f}, 12_000, 0L);
        assertThat(notADir.isFile()).isTrue();
    }
}
