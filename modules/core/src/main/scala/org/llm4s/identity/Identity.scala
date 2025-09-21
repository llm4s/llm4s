package org.llm4s.identity

case class RuntimeId(name: String)

//noinspection TypeAnnotation,ScalaUnusedSymbol
object RuntimeId {
  val AZURE     = RuntimeId("azure")
  val OPEN_AI   = RuntimeId("openai")
  val ANTHROPIC = RuntimeId("anthropic")
}

case class ModelId(name: String)

//noinspection TypeAnnotation,ScalaUnusedSymbol
object ModelId {
  val GPT_4_5                    = ModelId("gpt-4.5")
  val GPT_4_5_PREVIEW_2025_02_27 = ModelId("gpt-4.5-preview-2025-02-27")
  val O3_MINI                    = ModelId("o3-mini")
  val O3_MINI_2025_01_31         = ModelId("o3-mini-2025-01-31")
  val GPT_4o                     = ModelId("gpt-4o")
  val GPT_4o_2024_11_20          = ModelId("gpt-4o-2024-11-20")
  val GPT_4o_2024_08_06          = ModelId("gpt-4o-2024-08-06")
  val GPT_4o_2024_05_13          = ModelId("gpt-4o-2024-05-13")
}

case class TokenizerId(name: String)

//noinspection TypeAnnotation,ScalaUnusedSymbol
object TokenizerId {
  val R50K_BASE   = TokenizerId("r50k_base")   // gpt-3
  val P50K_BASE   = TokenizerId("p50k_base")
  val P50K_EDIT   = TokenizerId("p50k_edit")
  val CL100K_BASE = TokenizerId("cl100k_base") // gpt-4, gpt-3.5
  val O200K_BASE  = TokenizerId("o200k_base")  // gpt-4o
}
