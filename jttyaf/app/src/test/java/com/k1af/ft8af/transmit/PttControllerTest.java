package com.k1af.ft8af.transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for the rig keying sequence extracted from the FT8 engine:
 * PTT-on → settle delay → play → PTT-off, with the un-key guaranteed even on
 * failure/cancel/exception, the CAT/RTS/DTR-vs-VOX dispatch, the SCO
 * pause/resume ordering and snapshot ({@code TxScoLatch}), and the unkey debt
 * ({@code PttSafetyLatch}) that survives a dead port.
 */
public class PttControllerTest {

    /** Records the exact order of keyer/SCO/play events. */
    private static class Harness {
        final List<String> events = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        boolean rigPresent = true;
        boolean rigConnected = true;
        boolean pttWriteOk = true;
        boolean needSco = false;
        boolean scoUp = false;
        String scoAddress = null;
        int controlMode = ControlMode.CAT;

        final PttController controller = new PttController(
                new PttController.Keyer() {
                    @Override
                    public boolean hasRig() {
                        return rigPresent;
                    }

                    @Override
                    public void setPtt(boolean on) {
                        events.add(on ? "ptt-on" : "ptt-off");
                    }

                    @Override
                    public boolean lastPttWriteReachedRig() {
                        return pttWriteOk;
                    }

                    @Override
                    public boolean isRigConnected() {
                        return rigConnected;
                    }
                },
                new PttController.ScoControl() {
                    @Override
                    public boolean needControlSco() {
                        return needSco;
                    }

                    @Override
                    public void stopSco() {
                        events.add("sco-stop");
                        scoUp = false; // the tracker drops the link as soon as asked
                    }

                    @Override
                    public void startSco() {
                        events.add("sco-start");
                    }

                    @Override
                    public boolean isScoLinkUpOrPending() {
                        events.add("sco-query");
                        return scoUp;
                    }

                    @Override
                    public String routedScoInputAddress() {
                        return scoAddress;
                    }
                },
                new PttController.ControlModeSource() {
                    @Override
                    public int controlMode() {
                        return controlMode;
                    }
                },
                new PttController.Sleeper() {
                    @Override
                    public void sleepMs(long ms) {
                        events.add("sleep-" + ms);
                    }
                },
                new PttController.Logger() {
                    @Override
                    public void log(String line) {
                        logs.add(line);
                    }
                });
    }

    // ---- control-mode dispatch -------------------------------------------------

    @Test
    public void controlsPtt_catRtsDtrYes_voxNo() {
        assertThat(PttController.controlsPtt(ControlMode.CAT)).isTrue();
        assertThat(PttController.controlsPtt(ControlMode.RTS)).isTrue();
        assertThat(PttController.controlsPtt(ControlMode.DTR)).isTrue();
        assertThat(PttController.controlsPtt(ControlMode.VOX)).isFalse();
    }

    @Test
    public void keyDown_voxMode_doesNotKey() {
        Harness h = new Harness();
        h.controlMode = ControlMode.VOX;
        h.controller.keyDown();
        h.controller.keyUp();
        assertThat(h.events).doesNotContain("ptt-on");
        assertThat(h.events).doesNotContain("ptt-off");
        assertThat(h.events).doesNotContain("sco-stop");
    }

    @Test
    public void keyDown_noRig_doesNotKey() {
        Harness h = new Harness();
        h.rigPresent = false;
        h.controller.keyDown();
        h.controller.keyUp();
        assertThat(h.events).doesNotContain("ptt-on");
        assertThat(h.events).doesNotContain("ptt-off");
    }

    // ---- SCO ordering + snapshot --------------------------------------------------

    @Test
    public void scoIsPausedBeforeKeyDownAndResumedAfterKeyUp() {
        Harness h = new Harness();
        h.needSco = true;
        h.controller.keyDown();
        h.controller.keyUp();
        assertThat(h.events)
                .containsExactly("sco-query", "sco-stop", "ptt-on", "ptt-off", "sco-start")
                .inOrder();
    }

    @Test
    public void scoUntouchedWhenNotNeeded() {
        Harness h = new Harness();
        h.controller.keyDown();
        h.controller.keyUp();
        assertThat(h.events).containsExactly("ptt-on", "ptt-off").inOrder();
    }

    @Test
    public void scoSnapshot_takenBeforeStop_heldForWholeTransmission() {
        Harness h = new Harness();
        h.needSco = true;
        h.scoUp = true;
        h.scoAddress = "AA:BB:CC:DD:EE:FF";

        h.controller.keyDown();

        // stopSco() has already dropped the live link, but the snapshot says
        // "held" for the whole transmission — that is the point of the latch.
        assertThat(h.scoUp).isFalse();
        assertThat(h.controller.isScoHeldForTx()).isTrue();
        assertThat(h.controller.scoAddressForTx()).isEqualTo("AA:BB:CC:DD:EE:FF");
        // Same answers through the sink-facing view.
        assertThat(h.controller.heldForTx()).isTrue();
        assertThat(h.controller.address()).isEqualTo("AA:BB:CC:DD:EE:FF");

        h.controller.keyUp();
        assertThat(h.controller.isScoHeldForTx()).isFalse();
        assertThat(h.controller.scoAddressForTx()).isNull();
    }

    @Test
    public void scoSnapshot_bluetoothRigOnVox_latchesWithoutStoppingSco() {
        Harness h = new Harness();
        h.controlMode = ControlMode.VOX;
        h.needSco = true;
        h.scoUp = true;

        h.controller.keyDown();

        // VOX keeps SCO up and lets the audio key the rig, but its media stream
        // is still on the SCO route — so it needs the steering just as much.
        assertThat(h.events).doesNotContain("sco-stop");
        assertThat(h.controller.isScoHeldForTx()).isTrue();
    }

    @Test
    public void scoSnapshot_notABluetoothRig_neverHeld() {
        Harness h = new Harness();
        h.needSco = false;
        h.scoUp = true; // e.g. a Bluetooth headset mic on a USB rig
        h.controller.keyDown();
        assertThat(h.controller.isScoHeldForTx()).isFalse();
        assertThat(h.controller.scoAddressForTx()).isNull();
    }

    // ---- transmit sequencing --------------------------------------------------------

    @Test
    public void transmit_keysThenSettlesThenPlaysThenUnkeys() {
        Harness h = new Harness();
        boolean ok = h.controller.transmit(100, () -> {
            h.events.add("play");
            return true;
        });
        assertThat(ok).isTrue();
        assertThat(h.events)
                .containsExactly("ptt-on", "sleep-100", "play", "ptt-off")
                .inOrder();
    }

    @Test
    public void transmit_zeroSettleDelaySkipsSleep() {
        Harness h = new Harness();
        h.controller.transmit(0, () -> {
            h.events.add("play");
            return true;
        });
        assertThat(h.events).containsExactly("ptt-on", "play", "ptt-off").inOrder();
    }

    @Test
    public void transmit_playFailureStillUnkeys() {
        Harness h = new Harness();
        boolean ok = h.controller.transmit(0, () -> {
            h.events.add("play-failed");
            return false;// cancelled / write error
        });
        assertThat(ok).isFalse();
        assertThat(h.events)
                .containsExactly("ptt-on", "play-failed", "ptt-off")
                .inOrder();
    }

    @Test
    public void transmit_playThrowing_unkeysAndReturnsFalse() {
        Harness h = new Harness();
        boolean ok = h.controller.transmit(0, () -> {
            throw new IllegalStateException("audio device vanished");
        });
        assertThat(ok).isFalse();
        assertThat(h.events).containsExactly("ptt-on", "ptt-off").inOrder();
    }

    @Test
    public void transmit_interruptedDuringSettle_unkeysAndSetsInterruptFlag() {
        List<String> events = new ArrayList<>();
        PttController controller = new PttController(
                new PttController.Keyer() {
                    @Override
                    public boolean hasRig() {
                        return true;
                    }

                    @Override
                    public void setPtt(boolean on) {
                        events.add(on ? "ptt-on" : "ptt-off");
                    }

                    @Override
                    public boolean lastPttWriteReachedRig() {
                        return true;
                    }

                    @Override
                    public boolean isRigConnected() {
                        return true;
                    }
                },
                new PttController.ScoControl() {
                    @Override
                    public boolean needControlSco() {
                        return false;
                    }

                    @Override
                    public void stopSco() {
                    }

                    @Override
                    public void startSco() {
                    }

                    @Override
                    public boolean isScoLinkUpOrPending() {
                        return false;
                    }

                    @Override
                    public String routedScoInputAddress() {
                        return null;
                    }
                },
                () -> ControlMode.CAT,
                ms -> {
                    throw new InterruptedException("stop");
                },
                line -> { });

        boolean ok = controller.transmit(100, () -> {
            events.add("play");
            return true;
        });

        assertThat(ok).isFalse();
        assertThat(events).containsExactly("ptt-on", "ptt-off").inOrder();// play never ran
        assertThat(Thread.interrupted()).isTrue();// interrupt status restored (and cleared here)
    }

    @Test
    public void transmit_voxMode_playsWithoutKeying() {
        Harness h = new Harness();
        h.controlMode = ControlMode.VOX;
        boolean ok = h.controller.transmit(0, () -> {
            h.events.add("play");
            return true;
        });
        assertThat(ok).isTrue();
        assertThat(h.events).containsExactly("play");// audio itself keys a VOX rig
    }

    // ---- unkey debt (PttSafetyLatch) --------------------------------------------------

    @Test
    public void unkeyDebt_clearedByConfirmedPttOff() {
        Harness h = new Harness();
        h.controller.keyDown();
        assertThat(h.controller.needsUnkey()).isTrue();
        h.controller.keyUp();
        assertThat(h.controller.needsUnkey()).isFalse();
        assertThat(h.controller.retryPendingUnkey()).isFalse();// nothing owed
    }

    @Test
    public void unkeyDebt_survivesAFailedPttOffAndIsSettledOnRetry() {
        Harness h = new Harness();
        h.controller.keyDown();
        // The port died mid-over: the PTT-off write did not reach the rig.
        h.pttWriteOk = false;
        h.rigConnected = false;
        h.controller.keyUp();
        assertThat(h.controller.needsUnkey()).isTrue();

        // Link still down: nothing to do, debt stays.
        assertThat(h.controller.retryPendingUnkey()).isFalse();
        assertThat(h.controller.needsUnkey()).isTrue();
        assertThat(h.events).containsExactly("ptt-on", "ptt-off").inOrder();

        // Port is back: the retry re-sends PTT-off and the confirmed write clears it.
        h.rigConnected = true;
        h.pttWriteOk = true;
        assertThat(h.controller.retryPendingUnkey()).isTrue();
        assertThat(h.controller.needsUnkey()).isFalse();
        assertThat(h.events).containsExactly("ptt-on", "ptt-off", "ptt-off").inOrder();
        assertThat(h.logs).hasSize(2);
        assertThat(h.logs.get(1)).contains("delivered");
    }

    @Test
    public void unkeyDebt_retryThatFailsKeepsTheDebt() {
        Harness h = new Harness();
        h.controller.keyDown();
        h.pttWriteOk = false;
        h.controller.keyUp();

        assertThat(h.controller.retryPendingUnkey()).isFalse();// write still failing
        assertThat(h.controller.needsUnkey()).isTrue();
        assertThat(h.logs.get(h.logs.size() - 1)).contains("still owed");
    }

    @Test
    public void unkeyDebt_armedBeforeTheWrite_soAThrowingKeyStillOwesAnUnkey() {
        PttController controller = new PttController(
                new PttController.Keyer() {
                    @Override
                    public boolean hasRig() {
                        return true;
                    }

                    @Override
                    public void setPtt(boolean on) {
                        if (on) throw new IllegalStateException("port vanished mid-key");
                    }

                    @Override
                    public boolean lastPttWriteReachedRig() {
                        return true;
                    }

                    @Override
                    public boolean isRigConnected() {
                        return true;
                    }
                },
                new PttController.ScoControl() {
                    @Override
                    public boolean needControlSco() {
                        return false;
                    }

                    @Override
                    public void stopSco() {
                    }

                    @Override
                    public void startSco() {
                    }

                    @Override
                    public boolean isScoLinkUpOrPending() {
                        return false;
                    }

                    @Override
                    public String routedScoInputAddress() {
                        return null;
                    }
                },
                () -> ControlMode.CAT,
                ms -> { },
                line -> { });

        try {
            controller.keyDown();
        } catch (IllegalStateException expected) {
            // the rig may well be keyed even though the call blew up
        }
        assertThat(controller.needsUnkey()).isTrue();
    }

    @Test
    public void resetUnkeyDebt_dropsTheDebtWithoutWriting() {
        Harness h = new Harness();
        h.controller.keyDown();
        h.pttWriteOk = false;
        h.controller.keyUp();
        assertThat(h.controller.needsUnkey()).isTrue();

        h.controller.resetUnkeyDebt();// rig object going away

        assertThat(h.controller.needsUnkey()).isFalse();
        assertThat(h.controller.retryPendingUnkey()).isFalse();
        assertThat(h.events).containsExactly("ptt-on", "ptt-off").inOrder();
    }
}
