package org.llm4s.codegen

/**
 * Represents a code task to be performed by the CodeWorker.
 */
case class CodeTask(
  description: String,
  maxSteps: Option[Int] = None,
  sourceDirectory: Option[String] = None
)

/**
 * Represents the result of a code task execution.
 */
case class CodeTaskResult(
  success: Boolean,
  message: String,
  logs: Seq[String] = Seq.empty
)
