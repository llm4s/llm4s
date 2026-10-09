package org.llm4s.samples.docs

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ Completion, Conversation, TokenUsage }
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result

/**
 * The values that fragments in the documentation assume are already in scope ("Assuming `client` is injected").
 *
 * The compiled snippets (see `project/DocSnippets.scala`) are objects that extend this trait, so a fragment that
 * says `client.complete(...)` compiles against the real `LLMClient` type, and renaming a method it calls fails the
 * build. The values are never evaluated: the snippet methods are only compiled, never called.
 *
 * Each member is here because a fragment on one of the compiled pages uses it. Add one only for a name the page
 * text itself says is assumed; a name a page forgot to define or import is a documentation bug to fix on the page.
 */
trait SnippetEnv {
  protected def client: LLMClient                    = ???
  protected def embedder: EmbeddingClient            = ???
  protected def embeddingModel: EmbeddingModelConfig = ???
  protected def result: Result[Completion]           = ???
  protected def usage: TokenUsage                    = ???
  protected def loadDocuments(): List[String]        = ???
  protected def conversation: Conversation           = ???
  protected def chunks: Seq[String]                  = ???
  protected def model: EmbeddingModelConfig          = ???
  protected def question: String                     = ???
  protected def myTools: Seq[ToolFunction[_, _]]     = ???
}
