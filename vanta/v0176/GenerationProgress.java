package com.ronin.vanta;

import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Per-request counters only. Reasoning text is never retained or exposed as source/answer. */
final class GenerationProgress {
  interface Listener { void advanced(String channel, int characters); }
  private final LongSupplier clock;
  private long events, reasoningCharacters, answerCharacters, lastMeaningful = -1;
  private String channel = "WAITING_FOR_OUTPUT";
  private Listener listener;

  GenerationProgress() { this(System::nanoTime); }
  GenerationProgress(LongSupplier clock) { this.clock = clock; }

  synchronized void start(Listener value) {
    if (listener != null && listener != value)
      throw new IllegalStateException("A generation observer is already attached to this call.");
    events = reasoningCharacters = answerCharacters = 0;
    lastMeaningful = -1; channel = "WAITING_FOR_OUTPUT"; listener = value;
  }
  synchronized void detach(Listener value) { if (listener == value) listener = null; }
  synchronized void event() { events++; }

  void text(String kind, String text) {
    if (text == null || text.isBlank()) return;
    Listener notify;
    synchronized (this) {
      if ("reasoning".equals(kind)) reasoningCharacters += text.length();
      else if ("answer".equals(kind)) answerCharacters += text.length();
      else return;
      lastMeaningful = clock.getAsLong();
      channel = "reasoning".equals(kind) ? "RECEIVING_REASONING" : "RECEIVING_ANSWER";
      notify = listener;
    }
    // Never call the watchdog while holding this counter's lock.
    if (notify != null) notify.advanced(kind, text.length());
  }
  synchronized JSONObject diagnostics() throws Exception {
    JSONObject result = new JSONObject().put("stream_events", events)
        .put("reasoning_characters", reasoningCharacters)
        .put("answer_characters", answerCharacters).put("output_stage", channel);
    if (lastMeaningful >= 0)
      result.put("idle_since_meaningful_output_ms", Math.max(0, (clock.getAsLong() - lastMeaningful) / 1_000_000));
    return result;
  }
}
