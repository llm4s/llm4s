package org.llm4s.javaapi;

/**
 * What a {@link PendingInterrupt} waits for, so a Java {@code switch} or a Kotlin {@code when} covers it.
 *
 * <p>A Java enum, not a Scala 3 one: Java reads a Scala 3 enum's cases through its companion object,
 * and a {@code switch} that runs before that object is initialised finds them {@code null}.
 */
public enum InterruptKind {
  /**
   * A tool call that needs approval before it runs. Answer it with {@link Answer#approve},
   * {@link Answer#reject} or {@link Answer#edit}.
   */
  APPROVAL,

  /** A question a tool asked. Answer it with {@link Answer#reply}. */
  QUESTION,

  /**
   * A question a middleware asked - to review or edit an answer, or for missing information:
   * {@link PendingInterrupt#middleware()} names it. Answer it with {@link Answer#reply}, as JSON of the
   * middleware's answer type.
   */
  MIDDLEWARE_QUESTION,

  /**
   * A task a static breakpoint holds, before or after it ran: {@link PendingInterrupt#node()} and
   * {@link PendingInterrupt#phase()} say where. Continue it with {@link Answer#proceed}.
   */
  BREAKPOINT
}
