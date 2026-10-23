package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.*;
import java.util.regex.*;

/** 仅探测本机监听能力；不访问占用者、不读取端口进程表、不发送凭据。 */
public final class WebPortPolicy {
  private WebPortPolicy() {}

  public interface Probe {
    boolean available(int port) throws IOException;
  }

  public static final class Choice {
    public final int preferred, listen;

    Choice(int preferred, int listen) {
      this.preferred = preferred;
      this.listen = listen;
    }

    public boolean fallback() {
      return preferred != listen;
    }
  }

  /** 0 交给 dsh/操作系统在真正监听时分配，不预先挑号再赌端口仍空闲。 */
  public static Choice choose(int preferred, int previous, Probe probe) throws IOException {
    if (preferred < 0 || preferred > 65535) throw new IllegalArgumentException("Invalid Web port");
    if (preferred == 0) {
      if (previous > 0 && previous <= 65535 && !reserved(previous) && probe.available(previous))
        return new Choice(0, previous);
      return new Choice(0, 0);
    }
    if (!reserved(preferred) && probe.available(preferred)) return new Choice(preferred, preferred);
    if (previous > 0
        && previous <= 65535
        && previous != preferred
        && !reserved(previous)
        && probe.available(previous)) return new Choice(preferred, previous);
    return new Choice(preferred, 0);
  }

  /** Retry only a proven bind race of this launcher, before authentication and once. */
  public static boolean retryBindRace(
      int listen,
      boolean alreadyRetried,
      boolean authenticated,
      int conflict,
      boolean guestExited) {
    return listen > 0 && !alreadyRetried && !authenticated && conflict == listen && guestExited;
  }

  public static boolean onlyBindConflict(java.util.Map<String, String> issues, int listen) {
    if (listen <= 0 || issues == null || issues.isEmpty()) return false;
    for (java.util.Map.Entry<String, String> issue : issues.entrySet()) {
      if (!issue.getKey().isEmpty() || conflictPort(issue.getValue()) != listen) return false;
    }
    return true;
  }

  public static boolean reserved(int port) {
    return port == 3081 || port == 3090;
  }

  interface Binder {
    void bind(int port) throws IOException;
  }

  public static boolean available(int port) throws IOException {
    return available(
        port,
        value -> {
          try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), value), 1);
          }
        });
  }

  static boolean available(int port, Binder binder) throws IOException {
    try {
      binder.bind(port);
      return true;
    } catch (BindException error) {
      // 探测只是挑端口：EADDRINUSE、EPERM（部分 ROM/SELinux 对特定端口拒绝 bind）等任何绑定失败
      // 都视为“该端口不可用”，由 choose() 回退到动态端口，不能让探测错误中止启动。
      return false;
    }
  }

  public static String authentication(String output, int listen) {
    // 动态端口只能来自本轮进程的完整官方启动行，不宽松扫描插件日志内的 URL。
    DshAuthUrl.Parsed parsed = DshAuthUrl.fromStartupOutput(output);
    if (parsed == null) return null;
    int port = URI.create(parsed.authUrl).getPort();
    return reserved(port) || listen > 0 && port != listen ? null : parsed.authUrl;
  }

  public static int conflictPort(String detail) {
    if (detail == null
        || !detail.contains("@deepseek-ai/dsh-host-webserver")
        || !detail.contains("EADDRINUSE")
        || !detail.contains("listen")) return -1;
    Matcher match = Pattern.compile("127\\.0\\.0\\.1:([0-9]{1,5})(?![0-9])").matcher(detail);
    if (!match.find()) return -1;
    int port = Integer.parseInt(match.group(1));
    return port > 0 && port <= 65535 ? port : -1;
  }
}
