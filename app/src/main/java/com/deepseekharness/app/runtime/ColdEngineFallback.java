package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.SensitiveData;
import java.io.InterruptedIOException;

/** 冷安装期间 bxroot 预检未能确认时，决定是否自动改用 proot，并生成可记录的脱敏原因。 */
public final class ColdEngineFallback {
  /** 仅冷安装候选（尚无用户数据、未派发业务）上的预检失败可回退；中断/业务已派发不回退。 */
  public static boolean eligible(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof InterruptedIOException || current instanceof InterruptedException)
        return false;
      String message = current.getMessage();
      if (message != null
          && (message.contains("RUNTIME_BUSINESS_STARTED_NO_ENGINE_REPLAY")
              || message.contains("RUNTIME_ENGINE_UNAVAILABLE")
              || message.contains("COLD_CANDIDATE")))
        return false;
    }
    for (Throwable current = failure; current != null; current = current.getCause())
      if (current instanceof java.io.IOException
          || String.valueOf(current.getMessage()).contains("RUNTIME_CAPABILITY_UNCONFIRMED"))
        return true;
    return false;
  }

  public static String reason(Throwable failure) {
    StringBuilder text = new StringBuilder("bxroot->proot after preflight failure:");
    int depth = 0;
    for (Throwable current = failure; current != null && depth < 4; current = current.getCause(), depth++) {
      String message = current.getMessage();
      text.append(depth == 0 ? " " : " <- ")
          .append(current.getClass().getSimpleName())
          .append(message == null ? "" : "/" + message);
    }
    String safe = SensitiveData.redact(text.toString()).replaceAll("[^\\x20-\\x7e]", "?");
    return safe.length() > 700 ? safe.substring(0, 700) : safe;
  }

  private ColdEngineFallback() {}
}
