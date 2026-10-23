package com.deepseekharness.app.util;

/**
 * 局域网代理的设置持久化补丁。dsh 0.2.0-rc.2 用 isLoopback 决定持久化位置，需要精确补丁；
 * 0.2.1-rc.2 起上游已重构为始终经 remote.settings 写入 Host，锚点不再存在，无需补丁。
 */
public final class LanSettingsPatch {
  private LanSettingsPatch() {}

  public static final String BEFORE =
      "const persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";";
  public static final String AFTER =
      "const persistence = \"host\"; // DSHA patch: LAN 代理场景强制 host 持久化";

  /** 返回补丁后的文本；上游已无该逻辑时原样返回。出现 isLoopback 判定但形态不符时仍拒绝猜测。 */
  public static String apply(String source) {
    if (source.contains(BEFORE) || source.contains(AFTER))
      return ExactTextPatch.apply(source, BEFORE, AFTER);
    if (source.contains("isLoopback")) return ExactTextPatch.apply(source, BEFORE, AFTER);
    return source;
  }

  public static boolean required(String source) {
    return source.contains(BEFORE) || source.contains(AFTER);
  }
}
