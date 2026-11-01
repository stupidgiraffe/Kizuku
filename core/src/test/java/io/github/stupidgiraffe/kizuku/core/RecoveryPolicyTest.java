package io.github.stupidgiraffe.kizuku.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryPolicyTest {
    @Test public void responsiveBinderPreventsRestartForAStaleHeartbeat() {
        assertEquals(-1, RecoveryPolicy.retryDelay(true, true, true, 0));
    }
    @Test public void intentionalStopAndWirelessOutageNeverRestart() {
        assertEquals(-1, RecoveryPolicy.retryDelay(false, false, true, 0));
        assertEquals(-1, RecoveryPolicy.retryDelay(true, false, false, 0));
    }
    @Test public void recoveryBacksOffAndStopsAfterFiveAttempts() {
        assertEquals(2000, RecoveryPolicy.retryDelay(true, false, true, 0));
        assertEquals(32000, RecoveryPolicy.retryDelay(true, false, true, 4));
        assertEquals(-1, RecoveryPolicy.retryDelay(true, false, true, 5));
    }
}
