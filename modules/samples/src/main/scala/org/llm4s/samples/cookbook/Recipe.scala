package org.llm4s.samples.cookbook

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicReference

/**
 * Where a cookbook recipe lives. Each recipe has a page, `docs/examples/cookbook/<id>.md`, that shows its whole source
 * file; the index page `docs/examples/cookbook.md` links them in the order of [[Cookbook.recipes]], and the spec that
 * keeps the pages honest reads the same list, so a recipe cannot be added to the code and forgotten in the docs, or the
 * reverse.
 *
 * @param id        short name, and the name of the recipe's page (`tool-calling`)
 * @param title     one-line title
 * @param summary   what the recipe shows, in a sentence
 * @param mainClass fully qualified name of the object to pass to `sbt "samples/runMain ..."`
 */
final case class RecipeInfo(id: String, title: String, summary: String, mainClass: String) {

  /** The recipe's source file, relative to the repository root. */
  def sourcePath: String = s"modules/samples/src/main/scala/${mainClass.replace('.', '/')}.scala"

  /** The recipe's docs page, relative to the repository root. */
  def pagePath: String = s"docs/examples/cookbook/$id.md"
}

/** Every recipe of the cookbook, in the order the docs page lists them. */
object Cookbook {
  val apps: Seq[RecipeApp] =
    Seq(
      StructuredOutputRecipe,
      EmailExtractionRecipe,
      SummariseRecipe,
      FolderQaRecipe,
      ToolCallingRecipe,
      GuardrailsRecipe,
      StreamingRecipe,
      FallbackRecipe,
      CachingRecipe,
      JudgeRecipe,
      DocumentQaRecipe,
      MemoryRecipe,
      MultiAgentGraphRecipe
    )

  val recipes: Seq[RecipeInfo] = apps.map(_.info)
}

/**
 * A stand-in for a model: it answers from a function of the conversation and never touches the network.
 *
 * Each recipe ships one, so `sbt "samples/runMain ..."` works with no API key, and the spec for the recipe runs the
 * same code against it in CI. It also records what it was asked, so a spec can check what reached "the model".
 *
 * @param reply called with the conversation and the 1-based call number; its `Right` is the assistant message
 */
final class ScriptedClient(reply: (Conversation, Int) => Result[AssistantMessage]) extends LLMClient {
  private val seen = new AtomicReference(Vector.empty[(Conversation, CompletionOptions)])

  /** Every conversation this client was asked to complete, with the options of the call, oldest first. */
  def calls: Vector[(Conversation, CompletionOptions)] = seen.get

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
    val callNumber = seen.updateAndGet(_ :+ ((conversation, options))).size
    reply(conversation, callNumber).map { message =>
      Completion(
        id = s"scripted-$callNumber",
        created = 0L,
        content = message.content,
        model = "scripted",
        message = message,
        toolCalls = message.toolCalls.toList
      )
    }
  }

  /** Sends the scripted reply a word at a time, each word with the space after it, as a model streams its tokens. */
  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options).map { completion =>
      completion.content
        .split("(?<=\\s)")
        .filter(_.nonEmpty)
        .foreach(piece => onChunk(StreamedChunk(id = completion.id, content = Some(piece))))
      completion
    }

  override def getContextWindow(): Int     = 8192
  override def getReserveCompletion(): Int = 1024
}

/**
 * A stand-in for an embedding model, for the recipes that embed text (`FolderQaRecipe`, `CachingRecipe`).
 *
 * Each text becomes its word counts, hashed into [[Dimensions]] buckets and scaled to length 1, so texts that share
 * words are close and the same text always gives the same vector. It never touches the network. It is a real
 * [[EmbeddingProviderDescriptor]], so a recipe registers it exactly as it would a provider module's descriptor.
 */
object BagOfWordsEmbeddings extends EmbeddingProviderDescriptor {
  val id: ProviderId                             = ProviderId("bagofwords")
  val Model: String                              = "words-256"
  val Dimensions: Int                            = 256
  override val modelDimensions: Map[String, Int] = Map(Model -> Dimensions)

  /** The vector of `text`: its word counts, hashed into buckets, at length 1 (all zeros for a text with no words). */
  def vector(text: String): Seq[Double] = {
    val words  = text.toLowerCase.split("[^a-z0-9]+").filter(_.nonEmpty).toSeq
    val counts = words.groupMapReduce(word => Math.floorMod(word.hashCode, Dimensions))(_ => 1.0)(_ + _)
    val length = math.sqrt(counts.values.map(count => count * count).sum)
    Vector.tabulate(Dimensions)(i => if (length == 0.0) 0.0 else counts.getOrElse(i, 0.0) / length)
  }

  val provider: EmbeddingProvider = new EmbeddingProvider {
    def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map(vector), dim = Some(Dimensions)))
  }

  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = Right(provider)

  /** The provider config a pipeline built on this provider needs: it has no endpoint and no key. */
  val config: EmbeddingProviderConfig = EmbeddingProviderConfig(baseUrl = "", model = Model, apiKey = "")
}

/**
 * The shape shared by the recipes: `demo` runs the recipe against any client and returns the text to print, `script`
 * is the scripted client that stands in for a model, and `main` runs `demo` against the script, or, with `--live`,
 * against the provider chosen by the normal configuration (`llm4s.providers.provider` in `application.conf`).
 */
abstract class RecipeApp {
  def info: RecipeInfo

  /** The scripted client that answers instead of a model. */
  def script: LLMClient

  /** Runs the recipe against `client` and returns what to print. */
  def demo(client: LLMClient): Result[String]

  final def main(args: Array[String]): Unit = {
    val live = args.contains("--live")
    println(s"${info.title} (${if (live) "live provider" else "scripted client, no API key needed"})")
    val outcome = for {
      client <- if (live) RecipeApp.liveClient() else Right(script)
      text   <- demo(client)
    } yield text

    outcome match {
      case Right(text) => println(text)
      case Left(error) =>
        println(s"${info.title} failed: ${error.formatted}")
        sys.exit(1)
    }
  }
}

object RecipeApp {

  /** The client for the provider named by `llm4s.providers.provider`. */
  def liveClient(): Result[LLMClient] =
    for {
      providerConfig  <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerConfig)
    } yield client
}
