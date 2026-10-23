package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class LanSettingsPatchTest {
  private static final String LEGACY =
      "class X {\n\t\tconst persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";\n}\n";

  @Test
  public void realDsh021Rc2SettingsModuleNeedsNoPatchAndIsUntouched() throws IOException {
    String source;
    try (InputStream in =
        getClass()
            .getResourceAsStream(
                "/dsh-fixtures/0.2.1-rc.2/dsh-client-ui-settings-client.js")) {
      assertNotNull(in);
      source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertFalse(LanSettingsPatch.required(source));
    assertSame(source, LanSettingsPatch.apply(source));
  }

  @Test
  public void legacy020Rc2AnchorIsStillPatchedIdempotently() {
    String patched = LanSettingsPatch.apply(LEGACY);
    assertTrue(patched.contains(LanSettingsPatch.AFTER));
    assertFalse(patched.contains("isLoopback"));
    assertEquals(patched, LanSettingsPatch.apply(patched));
  }

  @Test(expected = IllegalArgumentException.class)
  public void unknownLoopbackGateStillRefusesToGuess() {
    LanSettingsPatch.apply("const persistence = ctx.remote.$host.isLoopback ? 1 : 2;");
  }
}
