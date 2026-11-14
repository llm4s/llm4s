package org.llm4s.benchcmp.harness;

/**
 * What each compared library implements, idiomatically, against {@link MockOpenAiServer}. One instance holds one
 * library's clients for one base URL, built once and reused, as an application would.
 */
public interface ChatLibrary extends AutoCloseable {

  /** One non-streamed chat completion; returns the reply text. */
  String chat(String prompt);

  /** One streamed chat completion, read to the end; returns the number of non-empty text chunks received. */
  int stream(String prompt);

  /**
   * One run of the library's tool-calling agent with a {@code lookup(key)} tool: the server asks for
   * {@link MockOpenAiServer#TOOL_ROUNDS} tool calls before it answers. Returns the final answer.
   */
  String toolAgent(String prompt);

  /**
   * Whether {@link #chat} can run on many virtual threads at once on JDK 21. The concurrency scenario uses virtual
   * threads where this is true and a pool of platform threads, one per request, where it is not.
   */
  default boolean virtualThreads() {
    return true;
  }

  @Override
  default void close() {}
}
