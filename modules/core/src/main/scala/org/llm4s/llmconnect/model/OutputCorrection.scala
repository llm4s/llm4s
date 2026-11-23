package org.llm4s.llmconnect.model

/**
 * The request to answer again that follows a structured answer which did not match its JSON Schema: the text of the
 * user message, and the test that recognises it.
 *
 * It is here, not beside the agent runtime's response format that sends it, so that `llm4s-testkit` - which depends
 * on core only - recognises the message by the same constant the runtime writes it with (`AfterCorrection`, #2730).
 */
private[llm4s] object OutputCorrection {

  /** How every correction request starts. Nothing else identifies one: a user message carries no marker but its text. */
  val Prefix: String = "Your answer did not match the required JSON Schema:"

  /** The correction request's text: [[Prefix]], the answer's `problems` one to a line, and the request to answer again. */
  def message(problems: Seq[String]): String =
    s"$Prefix\n${problems.map("- " + _).mkString("\n")}\n" +
      "Reply again with only a JSON value that matches the schema."

  /** True for a user message whose text starts with [[Prefix]]. */
  def isCorrection(message: Message): Boolean = message match {
    case user: UserMessage => user.content.startsWith(Prefix)
    case _                 => false
  }
}
