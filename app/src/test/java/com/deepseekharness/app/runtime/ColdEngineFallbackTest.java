package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import org.junit.Test;

public class ColdEngineFallbackTest {
  @Test
  public void preflightUnconfirmedFallsBackAndRecordsSanitizedReason() {
    var failure =
        new IllegalStateException(
            "RUNTIME_CAPABILITY_UNCONFIRMED: OWNED_CONTROL_ACCEPT_TIMEOUT:waited=15000ms",
            new IOException("OWNED_CONTROL_ACCEPT_TIMEOUT:waited=15000ms"));
    assertTrue(ColdEngineFallback.eligible(failure));
    String reason = ColdEngineFallback.reason(failure);
    assertTrue(reason, reason.startsWith("bxroot->proot after preflight failure:"));
    assertTrue(reason, reason.contains("OWNED_CONTROL_ACCEPT_TIMEOUT"));
    assertTrue(reason.length() <= 700);
  }

  @Test
  public void interruptionBusinessStartAndApkInputFailuresDoNotFallBack() {
    assertFalse(ColdEngineFallback.eligible(new InterruptedIOException("CANCELLED")));
    assertFalse(
        ColdEngineFallback.eligible(
            new IllegalStateException("RUNTIME_BUSINESS_STARTED_NO_ENGINE_REPLAY")));
    assertFalse(
        ColdEngineFallback.eligible(
            new IllegalStateException(
                "RUNTIME_ENGINE_UNAVAILABLE: bxroot", new IOException("BXROOT_NATIVE_HASH_MISMATCH"))));
    assertFalse(ColdEngineFallback.eligible(new IllegalStateException("other")));
  }
}
