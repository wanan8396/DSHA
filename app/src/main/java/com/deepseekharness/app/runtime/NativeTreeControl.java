package com.deepseekharness.app.runtime;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import com.deepseekharness.app.util.ProcessIdentity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** 仅签名本轮监督器可报告树空；业务 stdout/PTY 不参与证明。 */
public final class NativeTreeControl implements AutoCloseable {
  private final LocalServerSocket server;
  private final String name;
  private LocalSocket socket;
  private volatile Integer commandExit;
  private volatile boolean treeEmpty, started;
  private volatile boolean startAttempted;
  private volatile boolean engineNeverExecuted;
  private boolean noGuest;
  private volatile String unknown = "";
  private volatile IOException failure;
  private final BoundedGuestSessions.Operation record;

  NativeTreeControl(BoundedGuestSessions.Operation record) throws IOException {
    this.record = record;
    name = "dsha-owned-" + java.util.UUID.randomUUID().toString().replace("-", "");
    server = new LocalServerSocket(name);
  }

  public String argument() {
    return "--control=" + name;
  }

  public boolean treeEmpty() {
    return treeEmpty;
  }

  public boolean started() {
    return started;
  }

  public boolean neverDispatched() {
    return !startAttempted;
  }

  public boolean engineNeverExecuted() {
    return engineNeverExecuted;
  }

  public Integer commandExit() {
    return commandExit;
  }

  public String unknown() {
    return failure == null ? unknown : failure.getMessage();
  }

  static final long ACCEPT_WAIT_MS = 15_000;
  static final int READY_WAIT_MS = 15_000;

  /** 区分“等待超时”和“接收失败”，并附带脱敏后的异常类与消息，便于诊断回执定位真实原因。 */
  static IOException acceptFailure(Throwable error, long waitedMs) {
    Throwable root = error instanceof ExecutionException && error.getCause() != null
        ? error.getCause()
        : error;
    if (error instanceof TimeoutException)
      return new IOException(
          "OWNED_CONTROL_ACCEPT_TIMEOUT:waited=" + waitedMs + "ms", error);
    return new IOException("OWNED_CONTROL_ACCEPT_RECEIVE_FAILED:" + describe(root), error);
  }

  static String describe(Throwable error) {
    String message = error.getMessage();
    String text = error.getClass().getName() + (message == null ? "" : "/" + message);
    text = com.deepseekharness.app.util.SensitiveData.redact(text);
    text = text.replaceAll("[^\\x20-\\x7e]", "?");
    return text.length() > 200 ? text.substring(0, 200) : text;
  }

  void accept(ProcessIdentity identity, java.util.function.BooleanSupplier rawAlive)
      throws IOException {
    ExecutorService accepting =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "dsha-owned-accept");
              thread.setDaemon(true);
              return thread;
            });
    Future<LocalSocket> accepted = accepting.submit(server::accept);
    try {
      socket = accepted.get(ACCEPT_WAIT_MS, TimeUnit.MILLISECONDS);
      var peer = socket.getPeerCredentials();
      if (!OwnedBirthEvidence.peer(
          identity,
          peer.getPid(),
          peer.getUid(),
          android.os.Process.myUid(),
          rawAlive.getAsBoolean())) throw new IOException("OWNED_CONTROL_PEER_UNCONFIRMED");
      socket.setSoTimeout(READY_WAIT_MS);
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      String ready;
      try {
        ready = readLine(reader);
      } catch (java.net.SocketTimeoutException timeout) {
        throw new IOException("OWNED_CONTROL_READY_TIMEOUT:waited=" + READY_WAIT_MS + "ms", timeout);
      } catch (IOException failed) {
        if (failed.getMessage() != null && failed.getMessage().startsWith("OWNED_CONTROL_"))
          throw failed;
        throw new IOException("OWNED_CONTROL_READY_RECEIVE_FAILED:" + describe(failed), failed);
      }
      if (!"READY DSHA_OWNED_TREE_V2".equals(ready))
        throw new IOException(
            "OWNED_CONTROL_PROTOCOL:" + (ready == null ? "EOF" : describe(new IOException(ready))));
      socket.setSoTimeout(0);
      // 持久出生身份先于 START；这是唯一允许 guest 创建的通道。
      if (record != null) record.beforeLaunch(identity);
      if (!rawAlive.getAsBoolean()) throw new IOException("OWNED_CONTROL_ORIGINAL_PROCESS_EXITED");
      Thread pump = new Thread(() -> drain(reader), "dsha-owned-tree");
      pump.setDaemon(true);
      pump.start();
      // 即使 native 在持久写入期间已超时，也先收取 NO_GUEST/ECHILD，再处理发送失败。
      startAttempted = true;
      send("START");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("OWNED_CONTROL_INTERRUPTED", interrupted);
    } catch (ExecutionException | TimeoutException error) {
      throw acceptFailure(error, ACCEPT_WAIT_MS);
    } finally {
      accepting.shutdownNow();
      server.close();
    }
  }

  private static String readLine(BufferedReader reader) throws IOException {
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < 128; i++) {
      int ch = reader.read();
      if (ch < 0) return value.length() == 0 ? null : value.toString();
      if (ch == '\n') return value.toString();
      if (ch < 32 || ch > 126) throw new IOException("OWNED_CONTROL_VALUE");
      value.append((char) ch);
    }
    throw new IOException("OWNED_CONTROL_LIMIT");
  }

  private void drain(BufferedReader reader) {
    try {
      String line;
      while ((line = readLine(reader)) != null) {
        if (line.equals("STARTED") && !started && !noGuest) started = true;
        else if (line.equals("NO_GUEST") && !started && !noGuest && commandExit == null)
          noGuest = true;
        else if (line.matches("ENGINE_EXEC_FAILED [1-9][0-9]{0,3}")
            && started
            && commandExit == null
            && !engineNeverExecuted) engineNeverExecuted = true;
        else if (line.matches("COMMAND_EXITED [0-9]{1,3}")
            && (started || noGuest)
            && commandExit == null) {
          int value = Integer.parseInt(line.substring(15));
          if (value > 255) throw new IOException("OWNED_CONTROL_EXIT");
          commandExit = value;
        } else if (line.equals("OWNED_TREE_EMPTY ECHILD") && commandExit != null && !treeEmpty) {
          if (record != null) record.treeProof(commandExit);
          treeEmpty = true;
          send("COMMIT");
        } else if (line.startsWith("UNCONFIRMED ")) unknown = line;
        else throw new IOException("OWNED_CONTROL_SEQUENCE");
      }
      if (!treeEmpty) throw new IOException("OWNED_CONTROL_LOST_WITHOUT_TREE_PROOF");
    } catch (IOException | RuntimeException error) {
      failure =
          error instanceof IOException
              ? (IOException) error
              : new IOException("OWNED_CONTROL_FAILED", error);
      if (record != null) record.uncertain();
    }
  }

  synchronized void send(String command) throws IOException {
    if (socket == null) throw new IOException("OWNED_CONTROL_NOT_CONNECTED");
    socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  public void stop(boolean hard) throws IOException {
    if (!treeEmpty) send(hard ? "HARD" : "STOP");
  }

  public void close() throws IOException {
    server.close();
    if (socket != null) socket.close();
  }
}
