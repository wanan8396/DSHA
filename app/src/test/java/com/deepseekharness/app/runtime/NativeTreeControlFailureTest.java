package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.Test;

public class NativeTreeControlFailureTest {
  @Test
  public void acceptWaitIsFifteenSeconds() {
    assertEquals(15_000, NativeTreeControl.ACCEPT_WAIT_MS);
    assertEquals(15_000, NativeTreeControl.READY_WAIT_MS);
  }

  @Test
  public void timeoutAndReceiveFailureAreDistinguished() {
    IOException timeout =
        NativeTreeControl.acceptFailure(new TimeoutException(), NativeTreeControl.ACCEPT_WAIT_MS);
    assertEquals("OWNED_CONTROL_ACCEPT_TIMEOUT:waited=15000ms", timeout.getMessage());
    IOException receive =
        NativeTreeControl.acceptFailure(
            new ExecutionException(new IOException("accept failed: EBADF\nsecret")), 15000);
    assertTrue(receive.getMessage(), receive.getMessage().startsWith("OWNED_CONTROL_ACCEPT_RECEIVE_FAILED:java.io.IOException/accept failed: EBADF?"));
    assertFalse(receive.getMessage().contains("TIMEOUT"));
    assertTrue(receive.getCause() instanceof ExecutionException);
  }
}
