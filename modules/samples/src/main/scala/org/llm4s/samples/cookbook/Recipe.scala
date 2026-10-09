package org.llm4s.samples.cookbook

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicReference

/**
 * Where a cookbook recipe lives. The docs page `docs/examples/cookbook.md` and the spec that keeps it honest both
 * read [[Cookbook.recipes]], so a recipe cannot be added to the code and forgotten on the page, or the reverse.
 *
 * @param id        short name used in the docs page (`tool-calling`)
 * @param title     one-line title
 * @param summary   what the recipe shows, in a sentence
 * @param mainClass fully qualified name of the object to pass to `sbt "samples/runMain ..."`
 */
final case class RecipeInfo(id: String, title: String, summary: String, mainClass: String) {

  /** The recipe's source file, relative to the repository root. */
  def sourcePath: String = s"modules/samples/src/main/scala/${mainClass.replace('.', '/')}.scala"
}

/** Every recipe of the cookbook, in the order the docs page lists them. */
object Cookbook {
  val apps: Seq[RecipeApp] =
    Seq(
      ToolCallingRecipe,
      StructuredOutputRecipe,
      GuardrailsRecipe,
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

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = complete(conversation, options)

  override def getContextWindow(): Int     = 8192
  override def getReserveCompletion(): Int = 1024
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
    val outcome = for {
      client <- if (live) RecipeApp.liveClient() else Right(script)
      text   <- demo(client)
    } yield text

    outcome match {
      case Right(text) =>
        println(s"${info.title} (${if (live) "live provider" else "scripted client, no API key needed"})")
        println(text)
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
