package org.llm4s.eval

/**
 * Context for evaluating a RAG response.
 *
 * @param query The original user query
 * @param contexts The retrieved context chunks from the vector store
 * @param answer The generated answer to evaluate
 * @param expectedAnswer Optional expected/reference answer for comparison
 */
final case class EvalContext(
  query: String,
  contexts: Seq[String],
  answer: String,
  expectedAnswer: Option[String] = None
) {

  def combinedContext: String = contexts.mkString("\n\n")

  def hasExpectedAnswer: Boolean = expectedAnswer.isDefined
}

object EvalContext {

  def apply(query: String, contexts: Seq[String], answer: String): EvalContext =
    new EvalContext(query, contexts, answer, None)

  def withExpected(
    query: String,
    contexts: Seq[String],
    answer: String,
    expected: String
  ): EvalContext =
    new EvalContext(query, contexts, answer, Some(expected))
}
