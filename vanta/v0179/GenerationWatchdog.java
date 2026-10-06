package com.ronin.vanta;

import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Counts decoded output; distinguishes an idle stream, total deadline and no usable source. */
final class GenerationWatchdog implements AutoCloseable, GenerationProgress.Listener {
  static class Expired extends SocketTimeoutException { Expired(String message) { super(message); } }
  static final class PlanningExhausted extends Expired { PlanningExhausted(String message) { super(message); } }
  static final class SourceExhausted extends Expired { SourceExhausted(String message) { super(message); } }
  static final class TotalLimit extends Expired { TotalLimit(String message) { super(message); } }
  static final long IDLE_MS = 4 * 60_000L, TOTAL_MS = 12 * 60_000L;
  static final long PLAN_REASONING_MS = 3 * 60_000L, PLAN_REASONING_CHARS = 24000;
  static final long SOURCE_REASONING_MS = 3 * 60_000L, SOURCE_REASONING_CHARS = 24000;
  private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "vanta-generation-watchdog"); thread.setDaemon(true); return thread;
  });
  private final Net.Call call;
  private final LongSupplier clock;
  private final long start, idleNanos, totalNanos;
  private final boolean planning, sourceFile;
  private long lastProgress, answerChars, reasoningChars;
  private int characters;
  private volatile String expired = "";
  private boolean closed, planningExceeded, sourceExceeded, totalExceeded;
  private final ScheduledFuture<?> future;

  GenerationWatchdog(Net.Call call) { this(call, false); }
  GenerationWatchdog(Net.Call call, boolean planning) { this(call, System::nanoTime, IDLE_MS, TOTAL_MS, planning); }
  GenerationWatchdog(Net.Call call, String phase) { this(call, System::nanoTime, IDLE_MS, TOTAL_MS, phase); }
  GenerationWatchdog(Net.Call call, LongSupplier clock, long idleMs, long totalMs) {
    this(call, clock, idleMs, totalMs, false);
  }
  GenerationWatchdog(Net.Call call, LongSupplier clock, long idleMs, long totalMs, boolean planning) {
    this(call, clock, idleMs, totalMs, planning, false);
  }
  GenerationWatchdog(Net.Call call, LongSupplier clock, long idleMs, long totalMs, String phase) {
    this(call, clock, idleMs, totalMs, ForgeRequestPolicy.planning(phase), ForgeRequestPolicy.sourceFile(phase));
  }
  private GenerationWatchdog(Net.Call call, LongSupplier clock, long idleMs, long totalMs,
      boolean planning, boolean sourceFile) {
    if (idleMs <= 0 || totalMs <= 0) throw new IllegalArgumentException("Positive watchdog limits required.");
    this.call = call; this.clock = clock; this.planning = planning; this.sourceFile = sourceFile;
    start = lastProgress = clock.getAsLong();
    idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMs); totalNanos = TimeUnit.MILLISECONDS.toNanos(totalMs);
    call.generation.start(this);
    future = TIMER.scheduleAtFixedRate(this::checkNow, 1, 1, TimeUnit.SECONDS);
  }
  /** Compatibility with test doubles providing cumulative actual answer text, not UI labels. */
  synchronized void progress(String text) {
    if (!closed && expired.isEmpty() && text != null && !text.isBlank() && text.length() > characters) {
      characters = text.length(); answerChars = Math.max(answerChars, characters); lastProgress = clock.getAsLong();
    }
  }
  @Override public synchronized void advanced(String channel, int added) {
    if (closed || !expired.isEmpty() || added <= 0) return;
    if ("answer".equals(channel)) answerChars += added;
    else if ("reasoning".equals(channel)) reasoningChars += added;
    else return;
    lastProgress = clock.getAsLong();
  }
  synchronized void checkNow() {
    if (closed || !expired.isEmpty() || call.isCancelled()) return;
    long now = clock.getAsLong();
    if (now - start >= totalNanos) {
      totalExceeded = true;
      expired = "Generation watchdog: request exceeded its total time limit. This does not by itself mean the stream was idle.";
    } else if (now - lastProgress >= idleNanos)
      expired = "Generation watchdog: no new answer or reasoning text within the idle time limit.";
    else if (answerChars == 0 && reasoningChars > 0) {
      if (planning && (reasoningChars >= PLAN_REASONING_CHARS
          || now - start >= TimeUnit.MILLISECONDS.toNanos(PLAN_REASONING_MS))) {
        planningExceeded = true;
        expired = "Planning output budget: the stream is active but supplied reasoning without a usable plan. "
            + "Stopped at the bounded planning-only allowance; no automatic replay was made. "
            + "The model may not support the requested direct-answer mode. Inspect diagnostics or explicitly choose a compatible coding model.";
      } else if (sourceFile && (reasoningChars >= SOURCE_REASONING_CHARS
          || now - start >= TimeUnit.MILLISECONDS.toNanos(SOURCE_REASONING_MS))) {
        sourceExceeded = true;
        expired = "Source output budget: the stream is active but supplied reasoning without source text. "
            + "The model did not deliver the requested direct file output within its bounded allowance. "
            + "No larger same-model request or automatic replay was made; inspect diagnostics or explicitly choose a compatible coding model.";
      }
    }
    if (!expired.isEmpty()) call.abortTimedOutRequest(expired
        + " Completed files and partial output are retained. An accepted request may still be charged.");
  }
  boolean expired() { return !expired.isEmpty(); }
  SocketTimeoutException failure(Exception cause) {
    String text = expired + " Completed files and partial output are retained; inspect before retrying.";
    SocketTimeoutException error = planningExceeded ? new PlanningExhausted(text)
        : sourceExceeded ? new SourceExhausted(text) : totalExceeded ? new TotalLimit(text) : new Expired(text);
    error.initCause(cause); return error;
  }
  @Override public synchronized void close() {
    closed = true; future.cancel(false); call.generation.detach(this);
  }
}
