package org.llm4s.rag.transform

import org.llm4s.types.Result

/**
 * Pass-through transformer that returns the query unchanged.
 *
 * Useful for:
 * - Testing pipeline composition without side effects
 * - Default/placeholder when no transformation is needed
 * - Verifying that the transform chain is invoked correctly
 */
final class IdentityTransformer extends QueryTransformer {

  override val name: String = "identity"

  override def transform(query: String): Result[String] =
    Right(query)
}

object IdentityTransformer {

  def apply(): IdentityTransformer = new IdentityTransformer()
}
