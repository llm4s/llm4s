package org.llm4s.javaapi;

import org.llm4s.error.CancelledError;

import java.util.concurrent.CompletableFuture;

/**
 * The Java snippets of docs/guide/java-threading-and-cancellation.md, as code that is compiled with the
 * module's tests and run by ThreadingModelSpec, so the guide cannot show code that does not compile.
 */
final class ThreadingGuideSnippets {

  private ThreadingGuideSnippets() {}

  /** "Which calls block": run a call off the caller's thread with its async variant, and wait for it. */
  static String onVirtualThread(JLlmClient client) {
    CompletableFuture<String> answer = client.completeAsync("Summarise this.");
    String text = answer.join();   // waits on your thread; answer.cancel(true) interrupts the call
    return text;
  }

  /** "Interrupting a call": was this failure a cancellation? */
  static boolean wasCancelled(LlmResult<String> result) {
    return result.isFailure() && result.getError().error() instanceof CancelledError;
  }

  /**
   * "InterruptedException is never thrown, so Java cannot catch it": a catch (InterruptedException e)
   * around complete does not compile (#1591); test the result for a CancelledError instead. The
   * interrupt flag is still set at that point, so there is nothing to restore.
   */
  static String completeOrNull(JLlmClient client) {
    LlmResult<String> result = client.complete("hi");
    if (result.isFailure() && result.getError().error() instanceof CancelledError) {
      // interrupted while blocked: Thread.currentThread().isInterrupted() is still true, so a
      // loop or an executor further up the stack sees the interruption too
      return null;
    }
    return result.getOrNull();
  }

  /** The same test, written as a predicate over both signals: the result and the thread's flag. */
  static boolean wasInterrupted(LlmResult<String> result) {
    return wasCancelled(result) && Thread.currentThread().isInterrupted();
  }
}
