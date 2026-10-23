package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** 回归：fix1/fix2 随包的 libdsha-session.so 是旧版（无 V2 控制协议），导致 execvp("--control=…") ENOENT。 */
public class SessionLauncherBinaryTest {
  private static File binary() {
    for (String base : new String[] {"src/main", "app/src/main"}) {
      File file = new File(base, "jniLibs/arm64-v8a/libdsha-session.so");
      if (file.isFile()) return file;
    }
    fail("libdsha-session.so missing");
    return null;
  }

  @Test
  public void shippedSupervisorImplementsProtocolJavaExpects() throws Exception {
    File file = binary();
    assertTrue(IsolatedInstallProcess.speaksOwnedTreeV2(file));
    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
    for (String marker :
        new String[] {
          "READY DSHA_OWNED_TREE_V2", "STARTED", "COMMIT", "OWNED_TREE_EMPTY ECHILD",
          "COMMAND_EXITED", "NO_GUEST", "--stop-signal=", "--pty-existing-session", "--control="
        })
      assertTrue(marker, text.contains(marker));
  }

  @Test
  public void staleLauncherIsRejected() throws Exception {
    File stale = File.createTempFile("stale-session", ".so");
    stale.deleteOnExit();
    Files.write(stale.toPath(), "DSHA_SESSION_STAT_V1 --birth-handshake DSHA_SESSION_EXEC".getBytes(StandardCharsets.US_ASCII));
    assertFalse(IsolatedInstallProcess.speaksOwnedTreeV2(stale));
    assertFalse(IsolatedInstallProcess.speaksOwnedTreeV2(new File("/nonexistent/launcher")));
  }
}
