package org.llm4s.samples.cookbook

import cats.syntax.traverse._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.caching.{ CacheConfig, CachingLLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, UserMessage }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.NoOpTracing
import org.llm4s.types.Result

import scala.concurrent.duration._

/** Recipe: answer a repeated question from a cache instead of calling the model again. */
object CachingRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "caching",
    title = "Cache repeated calls",
    summary = "Wrap a client in CachingLLMClient so that a question asked again is answered without a model call.",
    mainClass = "org.llm4s.samples.cookbook.CachingRecipe"
  )

  /** `client`, behind a cache that answers a prompt at least 0.98 similar to one seen in the last hour. */
  def cached(client: LLMClient): Result[LLMClient] =
    for {
      models <- Llm4sConfig.modelRegistryService()
      config <- CacheConfig.create(similarityThreshold = 0.98, ttl = 1.hour, maxSize = 500)
    } yield {
      given ModelRegistryService = models
      new CachingLLMClient(
        baseClient = client,
        // The stand-in embedding model; see "Use a real provider".
        embeddingClient = new EmbeddingClient(BagOfWordsEmbeddings.provider),
        embeddingModel = EmbeddingModelConfig(BagOfWordsEmbeddings.Model, BagOfWordsEmbeddings.Dimensions),
        config = config,
        tracing = new NoOpTracing
      )
    }

  /** Asks each question in turn through one cache, so a repeat is answered from it. */
  def askAll(client: LLMClient, questions: Seq[String]): Result[Seq[String]] =
    cached(client).flatMap { cache =>
      questions.toList.traverse(q => cache.complete(Conversation(Seq(UserMessage(q)))).map(_.content))
    }

  val questions: Seq[String] =
    Seq("When does the office open?", "Do you ship to Canada?", "When does the office open?")

  /** Numbers its answers, so the output shows which ones the model really gave. */
  def script: ScriptedClient =
    new ScriptedClient((conversation, call) => Right(AssistantMessage(s"(model call $call) ${answerTo(conversation)}")))

  private def answerTo(conversation: Conversation): String =
    if (conversation.messages.exists(_.content.contains("ship"))) "Yes, we ship to Canada." else "We open at nine."

  def demo(client: LLMClient): Result[String] =
    askAll(client, questions).map(answers => questions.zip(answers).map((q, a) => s"$q -> $a").mkString("\n"))
}
