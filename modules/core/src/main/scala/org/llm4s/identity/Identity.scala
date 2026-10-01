package org.llm4s.identity

/**
 * Identifies a BPE tokenizer vocabulary by its canonical name.
 *
 * Tokenizer IDs are used by context-window estimation logic to select the
 * correct byte-pair-encoding vocabulary for a given model, so that prompt
 * and completion token counts are accurate without calling the provider API.
 * The mapping from model names to [[TokenizerId]] is maintained by the
 * context package.
 *
 * @param name Tokenizer vocabulary name as used by tiktoken and related
 *             libraries (e.g. `"cl100k_base"` for GPT-4 / GPT-3.5).
 */
case class TokenizerId(name: String)

//noinspection TypeAnnotation,ScalaUnusedSymbol
object TokenizerId {
  val R50K_BASE   = TokenizerId("r50k_base")   // gpt-3
  val P50K_BASE   = TokenizerId("p50k_base")
  val P50K_EDIT   = TokenizerId("p50k_edit")
  val CL100K_BASE = TokenizerId("cl100k_base") // gpt-4, gpt-3.5
  val O200K_BASE  = TokenizerId("o200k_base")  // gpt-4o
}
