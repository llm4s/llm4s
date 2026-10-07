package org.llm4s.javaapi;

import org.llm4s.error.CancelledError;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Java snippets of docs/guide/java-threading-and-cancellation.md, as code that is compiled with the
 * module's tests and run by ThreadingModelSpec, so the guide cannot show code that does not compile.
 */
final class ThreadingGuideSnippets {

  private ThreadingGuideSnippets() {}

  /** "Which calls block": run a call on a virtual thread and wait for it. */
  static String onVirtualThread(JLlmClient client) throws Exception {
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<LlmResult<String>> answer = pool.submit(() -> client.complete("Summarise this."));
      LlmResult<String> result = answer.get();
      return result.get();
    }
  }

  /** "Interrupting a call": was this failure a cancellation? */
  static boolean wasCancelled(LlmResult<String> result) {
    return result.isFailure() && result.getError().error() instanceof CancelledError;
  }

  /**
   * "InterruptedException is thrown, but Java cannot catch it by name": catch Exception, restore the
   * interrupt flag and return.
   */
  static String completeOrNull(JLlmClient client) {
    try {
      return client.complete("hi").get();
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }
}
