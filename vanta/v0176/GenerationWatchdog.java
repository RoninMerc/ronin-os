package com.ronin.vanta;

import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Bounded logical output progress. Heartbeats are not progress; reasoning is not an answer. */
final class GenerationWatchdog implements AutoCloseable, GenerationProgress.Listener {
  static final class Expired extends SocketTimeoutException { Expired(String message) { super(message); } }
  static final long IDLE_MS = 4 * 60_000L, TOTAL_MS = 12 * 60_000L;
  private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "vanta-generation-watchdog"); thread.setDaemon(true); return thread;
  });
  private final Net.Call call;
  private final LongSupplier clock;
  private final long start, idleNanos, totalNanos;
  private long lastProgress;
  private int characters;
  private volatile String expired = "";
  private boolean closed;
  private final ScheduledFuture<?> future;

  GenerationWatchdog(Net.Call call) { this(call, System::nanoTime, IDLE_MS, TOTAL_MS); }
  GenerationWatchdog(Net.Call call, LongSupplier clock, long idleMs, long totalMs) {
    if (idleMs <= 0 || totalMs <= 0) throw new IllegalArgumentException("Positive watchdog limits required.");
    this.call = call; this.clock = clock;
    start = lastProgress = clock.getAsLong();
    idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMs); totalNanos = TimeUnit.MILLISECONDS.toNanos(totalMs);
    call.generation.start(this);
    future = TIMER.scheduleAtFixedRate(this::checkNow, 1, 1, TimeUnit.SECONDS);
  }
  /** Kept for transports/test doubles exposing cumulative answer text only. */
  synchronized void progress(String text) {
    if (!closed && expired.isEmpty() && text != null && !text.isBlank() && text.length() > characters) {
      characters = text.length(); lastProgress = clock.getAsLong();
    }
  }
  @Override public synchronized void advanced(String channel, int added) {
    if (!closed && expired.isEmpty() && added > 0 && ("answer".equals(channel) || "reasoning".equals(channel)))
      lastProgress = clock.getAsLong();
  }
  synchronized void checkNow() {
    if (closed || !expired.isEmpty() || call.isCancelled()) return;
    long now = clock.getAsLong();
    if (now - start >= totalNanos) expired = "Generation watchdog: request exceeded its total time limit.";
    else if (now - lastProgress >= idleNanos)
      expired = "Generation watchdog: no new answer or reasoning text within the idle time limit.";
    if (!expired.isEmpty()) call.abortTimedOutRequest(expired
        + " Completed files and partial output are retained. An accepted request may still be charged.");
  }
  boolean expired() { return !expired.isEmpty(); }
  SocketTimeoutException failure(Exception cause) {
    SocketTimeoutException error = new Expired(expired
        + " Completed files and partial output are retained; inspect before retrying.");
    error.initCause(cause); return error;
  }
  @Override public synchronized void close() {
    closed = true; future.cancel(false); call.generation.detach(this);
  }
}
