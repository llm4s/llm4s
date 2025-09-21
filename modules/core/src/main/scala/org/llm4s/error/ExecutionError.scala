package org.llm4s.error

/**
 * Execution errors that might succeed on retry
 */
final case class ExecutionError(
  message: String,
  operation: String,
  exitCode: Option[Int] = None,
  cause: Option[Throwable] = None,
  override val context: Map[String, String] = Map.empty
) extends LLMError
    with RecoverableError {

  def withContext(key: String, value: String): ExecutionError =
    copy(context = context + (key -> value))

  def withContext(entries: Map[String, String]): ExecutionError =
    copy(context = context ++ entries)

  def withExitCode(code: Int): ExecutionError =
    copy(exitCode = Some(code), context = context + ("exitCode" -> code.toString))
}
