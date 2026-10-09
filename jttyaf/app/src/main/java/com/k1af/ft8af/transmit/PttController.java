package com.k1af.ft8af.transmit;

import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.PttSafetyLatch;
import com.k1af.ft8af.bluetooth.TxScoLatch;
import com.k1af.ft8af.database.ControlMode;

/**
 * Rig keying sequence around a transmission, extracted from the retired FT8
 * transmit engine ({@code MainViewModel.beginKeying()/endKeying()/
 * retryPendingUnkey()} plus the PTT bracket in {@code FT8TransmitSignal}):
 *
 * <pre>  key PTT down → settle delay → play audio → key PTT up (always)</pre>
 *
 * <p>PTT is asserted through the configured control path (CAT, RTS, or DTR —
 * {@code BaseRig.setPTT} does the per-mode dispatch); with no control path
 * (VOX) keying is a no-op and the audio itself keys the rig.
 *
 * <p>Two latches ride along with every keying and live here so the ordering
 * they depend on is a single tested call rather than statements in the view
 * model that could drift apart:
 * <ul>
 *   <li>{@link TxScoLatch} — snapshots whether <em>this app's</em> Bluetooth
 *       SCO link was up at keying time, <em>before</em> keying pauses it. The
 *       audio sink reads the snapshot (through {@link TransmitAudioSink.ScoSnapshot})
 *       after the PTT settle delay, when the live tracker has already been told
 *       to drop the link, to decide whether Default output must be steered to
 *       the rig's A2DP endpoint.</li>
 *   <li>{@link PttSafetyLatch} — remembers that we owe the rig an unkey until a
 *       PTT-off write is <em>confirmed</em> delivered. A USB brown-out mid-over
 *       kills the port, the unkey fails, and the rig stays keyed; the port is
 *       usually back within seconds, so {@link #retryPendingUnkey()} settles
 *       the debt the moment a link exists again.</li>
 * </ul>
 *
 * <p>The un-key in {@link #transmit} lives in a {@code finally}: a play failure,
 * cancellation, or exception must never leave the rig keyed.
 */
public class PttController implements TransmitAudioSink.ScoSnapshot {
    private static final String TAG = "PttController";

    /** The rig-side keying surface (implemented over BaseRig in MainViewModel). */
    public interface Keyer {
        /** Whether a rig object is present and PTT can be commanded. */
        boolean hasRig();

        /** Assert or release PTT on the rig (CAT/RTS/DTR dispatch inside). */
        void setPtt(boolean on);

        /**
         * Whether the rig's connector believes the most recent PTT write was
         * delivered ({@code BaseRigConnector.isLastPttWriteOk()}). No connector
         * at all means nothing was written: answer false so the unkey debt stays
         * armed rather than silently forgiving a lost unkey.
         */
        boolean lastPttWriteReachedRig();

        /** {@code BaseRig.isConnected()}; false with no rig. */
        boolean isRigConnected();
    }

    /** Bluetooth SCO pause/resume hooks and link state (no-ops when SCO isn't in play). */
    public interface ScoControl {
        /**
         * Is this a Bluetooth <em>rig</em> whose TX audio must not ride SCO
         * ({@code ScoPolicy.needControlSco()})? True for Bluetooth + VOX too:
         * VOX leaves SCO up, so its media stream sits on the SCO route and needs
         * the A2DP steering just as much. A USB/network rig with a Bluetooth
         * headset picked as its mic also holds a SCO link of ours, but its audio
         * belongs on the rig: false.
         */
        boolean needControlSco();

        /** Take SCO down before PTT; posts to the main looper. */
        void stopSco();

        /** Bring SCO back up after PTT-off. */
        void startSco();

        /** {@code ScoLinkCoordinator.isLinkUpOrPending()} — read before {@link #stopSco()}. */
        boolean isScoLinkUpOrPending();

        /**
         * Bluetooth address of the device the mic is currently captured from
         * over SCO, or null when unknown; lets the routing policy pick
         * <em>that</em> device's A2DP endpoint.
         */
        String routedScoInputAddress();
    }

    /** Current control mode supplier (GeneralVariables.controlMode). */
    public interface ControlModeSource {
        int controlMode();
    }

    /** Millisecond sleeper, injected for tests. */
    public interface Sleeper {
        void sleepMs(long ms) throws InterruptedException;
    }

    /** debug.log line sink, injected for tests. */
    public interface Logger {
        void log(String line);
    }

    /** The audio action run between key-down and key-up. */
    public interface PlayAction {
        /** @return true if the audio played to completion. */
        boolean play();
    }

    private final Keyer keyer;
    private final ScoControl sco;
    private final ControlModeSource controlModeSource;
    private final Sleeper sleeper;
    private final Logger logger;

    private final PttSafetyLatch pttSafetyLatch = new PttSafetyLatch();
    private final TxScoLatch txScoLatch = new TxScoLatch();

    public PttController(Keyer keyer, ScoControl sco, ControlModeSource controlModeSource) {
        this(keyer, sco, controlModeSource, new Sleeper() {
            @Override
            public void sleepMs(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }
        }, new Logger() {
            @Override
            public void log(String line) {
                GeneralVariables.fileLog(line);
            }
        });
    }

    PttController(Keyer keyer, ScoControl sco, ControlModeSource controlModeSource,
                  Sleeper sleeper, Logger logger) {
        this.keyer = keyer;
        this.sco = sco;
        this.controlModeSource = controlModeSource;
        this.sleeper = sleeper;
        this.logger = logger;
    }

    /**
     * Whether the given control mode commands PTT explicitly (CAT/RTS/DTR).
     * VOX (and anything unknown) does not — the audio keys the rig.
     */
    static boolean controlsPtt(int controlMode) {
        return controlMode == ControlMode.CAT
                || controlMode == ControlMode.RTS
                || controlMode == ControlMode.DTR;
    }

    /**
     * Assert PTT (+ pause SCO) through the configured control path. No-op for
     * VOX or when no rig is connected — except for the SCO snapshot, which a
     * Bluetooth rig on VOX still needs (see {@link ScoControl#needControlSco()}).
     *
     * <p>Two different questions are answered here (Copilot review on #790):
     * whether this TX goes to a Bluetooth rig (decides the A2DP steering) and
     * whether this keying path actually pauses SCO around PTT (only a
     * control-path keying with a rig does). {@link TxScoLatch#keyDown} takes
     * the snapshot BEFORE it stops SCO, and that order lives there so it is the
     * tested one.
     */
    public void keyDown() {
        boolean keysViaControlPath = controlsPtt(controlModeSource.controlMode());
        boolean hasRig = keyer.hasRig();
        boolean bluetoothRigTx = sco.needControlSco();
        txScoLatch.keyDown(
                bluetoothRigTx,
                keysViaControlPath && hasRig && bluetoothRigTx,
                new TxScoLatch.LinkState() {
                    @Override
                    public boolean isUpOrPending() {
                        return sco.isScoLinkUpOrPending();
                    }
                },
                new TxScoLatch.DeviceAddress() {
                    @Override
                    public String get() {
                        return sco.routedScoInputAddress();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        sco.stopSco();
                    }
                });
        if (keysViaControlPath && hasRig) {
            // Arm before the write, not after: if setPtt throws or the process
            // dies mid-key, we still recorded that the rig may be keyed and the
            // next reconnect settles it.
            pttSafetyLatch.onKeyed();
            keyer.setPtt(true);
        }
    }

    /** Release PTT (+ restore SCO); counterpart of {@link #keyDown()}. */
    public void keyUp() {
        if (controlsPtt(controlModeSource.controlMode()) && keyer.hasRig()) {
            keyer.setPtt(false);
            // Only a confirmed write clears the latch. A PTT-off issued at a
            // port that has already gone away leaves the rig keyed, so keep the
            // debt and let retryPendingUnkey() settle it when the link is back.
            pttSafetyLatch.onUnkeyAttempted(keyer.lastPttWriteReachedRig());
            if (sco.needControlSco()) {
                sco.startSco();
            }
        }
        // The post-TX SCO restart has been requested; the next transmission
        // takes a fresh snapshot in keyDown().
        txScoLatch.keyUp();
    }

    /**
     * Run one keyed transmission: PTT down, wait {@code settleDelayMs} for the
     * rig to switch over, run {@code play}, then PTT up — unconditionally, even
     * when play fails, is cancelled, or throws.
     *
     * @param settleDelayMs rig settle time after the PTT command (GeneralVariables.pttDelay)
     * @param play          the blocking audio action
     * @return {@code play}'s result; false when interrupted or on exception
     */
    public boolean transmit(long settleDelayMs, PlayAction play) {
        keyDown();
        try {
            if (settleDelayMs > 0) {
                sleeper.sleepMs(settleDelayMs);
            }
            return play.play();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            Log.e(TAG, "transmit: play action failed: " + e);
            return false;
        } finally {
            keyUp();
        }
    }

    // ---- SCO snapshot (TransmitAudioSink.ScoSnapshot) ---------------------------

    /**
     * Whether our own SCO link for the rig was up (or coming up) when the
     * current transmission was keyed. Stable for the whole transmission even
     * though {@link #keyDown()} has already asked the tracker to drop the link.
     */
    public boolean isScoHeldForTx() {
        return txScoLatch.heldForTx();
    }

    /** Address of the device that link was on at keying time, or null when unknown. */
    public String scoAddressForTx() {
        return txScoLatch.scoAddress();
    }

    @Override
    public boolean heldForTx() {
        return isScoHeldForTx();
    }

    @Override
    public String address() {
        return scoAddressForTx();
    }

    // ---- unkey debt (PttSafetyLatch) ----------------------------------------------

    /** Whether an unkey is still owed to the rig (a PTT-off write was never confirmed). */
    public boolean needsUnkey() {
        return pttSafetyLatch.needsUnkey();
    }

    /**
     * Send PTT-off if we still owe the rig one, and clear the debt when it lands.
     *
     * <p>Call wherever a CAT link may have just come back: after a cable
     * reconnect, and periodically as a backstop. Safe to call at any time — it
     * is a no-op unless an unkey is actually outstanding, and CAT PTT-off is
     * idempotent on a rig that is already receiving.
     *
     * <p>This is the recovery for the field failure where a USB brown-out killed
     * the port mid-transmission and the rig stayed keyed for 97 seconds while the
     * port itself was back within two.
     *
     * @return true if an unkey was owed and has now been sent successfully
     */
    public boolean retryPendingUnkey() {
        if (!pttSafetyLatch.needsUnkey()) return false;
        if (!keyer.hasRig() || !keyer.isRigConnected()) return false;
        logger.log("PTT: unkey still owed after link loss — re-sending PTT-off");
        keyer.setPtt(false);
        boolean ok = keyer.lastPttWriteReachedRig();
        pttSafetyLatch.onUnkeyAttempted(ok);
        logger.log("PTT: unkey retry " + (ok ? "delivered" : "FAILED, still owed"));
        return ok;
    }

    /**
     * Drop the unkey debt without sending anything. For teardown paths where
     * the rig object itself is going away (disconnect, rig change), so a stale
     * latch cannot key-chase a rig we are no longer driving.
     */
    public void resetUnkeyDebt() {
        pttSafetyLatch.reset();
    }
}
