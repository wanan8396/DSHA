package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.ProcessIdentity;

/** Current pre-guest signed birth only; never used to claim an old PID or release an exit barrier. */
final class OwnedBirthEvidence {
  static ProcessIdentity current(
      ProcessIdentity born, ProcessIdentity inspected, boolean rawAlive, int sid) {
    if (born == null || !rawAlive || !born.ownsSession() || born.exited()) return null;
    if (inspected != null)
      return born.sameProcess(inspected) && inspected.ownsSession() && !inspected.exited()
          ? inspected
          : null;
    return sid == born.session ? born : null;
  }

  /**
   * 部分 ROM（如 OnePlus/ColorOS 15）同时拒读子进程 /proc/&lt;pid&gt;/stat 与 getsid(SELinux getsession)。
   * 出生行由本次私有管道从监督器自身 /proc/self/stat 读出（父进程为本进程、会话领导、未退出），
   * 且随后 accept 仍强制核对控制套接字 SO_PEERCRED 的 pid/uid；此时“无法检查”不等于“身份错误”。
   * ESRCH/EINVAL 表示进程不存在或参数无效，仍然拒绝。
   */
  static ProcessIdentity uninspectable(
      ProcessIdentity born, ProcessIdentity inspected, boolean rawAlive, int sid) {
    if (born == null || inspected != null || !rawAlive || !born.ownsSession() || born.exited())
      return null;
    return sid < 0 && sid != -ESRCH && sid != -EINVAL ? born : null;
  }

  static boolean sessionUnreadable(int sid) {
    return sid < 0 && sid != -ESRCH && sid != -EINVAL;
  }

  static String describe(ProcessIdentity born, ProcessIdentity inspected, boolean rawAlive, int sid) {
    return "proc="
        + (inspected == null
            ? "unreadable"
            : "pid"
                + inspected.pid
                + "/ppid"
                + inspected.parent
                + "/pgid"
                + inspected.group
                + "/sid"
                + inspected.session
                + "/state"
                + inspected.state
                + "/same="
                + (born != null && born.sameProcess(inspected)))
        + " getsid="
        + sid
        + " alive="
        + rawAlive
        + (born == null ? "" : " birth=pid" + born.pid + "/sid" + born.session);
  }

  private static final int ESRCH = 3, EINVAL = 22;

  static boolean peer(ProcessIdentity born, int pid, int uid, int appUid, boolean rawAlive) {
    return born != null
        && rawAlive
        && born.ownsSession()
        && !born.exited()
        && born.pid == pid
        && uid == appUid;
  }

  private OwnedBirthEvidence() {}
}
