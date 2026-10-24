package com.sbro.emucorer.translate;

import org.junit.Test;
import static org.junit.Assert.*;

public class DownloadConditionsTest {
    @Test public void explicitDownloadDoesNotWaitForWifiOrCharging() {
        com.google.mlkit.common.model.DownloadConditions conditions = DukeTranslator.downloadConditions();
        assertFalse(conditions.isWifiRequired());
        assertFalse(conditions.isChargingRequired());
    }
}
