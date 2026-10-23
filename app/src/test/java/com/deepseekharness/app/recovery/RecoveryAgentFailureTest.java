package com.deepseekharness.app.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecoveryAgentFailureTest {
  @Test
  public void bareMarkerKeepsLegacyCode() {
    assertEquals(
        "RECOVERY_TOOL_CHECK_FAILED",
        RecoveryController.agentFailure("DSHA_RECOVERY_AGENT_FAILED", "", ""));
  }

  @Test
  public void detailIsSanitizedTruncatedAndKeepsErrorCodeParseable() {
    String token = "a".repeat(64);
    String line =
        "DSHA_RECOVERY_AGENT_FAILED:Error: RECOVERY_PRESET_ROSTER "
            + token
            + "\u0001 "
            + "x".repeat(600);
    String value = RecoveryController.agentFailure(line, token, token);
    assertTrue(value, value.startsWith("RECOVERY_TOOL_CHECK_FAILED: Error: RECOVERY_PRESET_ROSTER"));
    assertFalse(value.contains(token));
    assertTrue(value.length() <= "RECOVERY_TOOL_CHECK_FAILED: ".length() + 300);
    assertEquals("RECOVERY_TOOL_CHECK_FAILED", value.split("[:\\s]", 2)[0]);
  }
}
