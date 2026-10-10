package org.llm4s.samples.cookbook

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGAnswerResult }
import org.llm4s.types.{ Result, TryOps }

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.util.Try

/** Recipe: answer questions over a folder of files with the RAG pipeline (embeddings, hybrid search, an answer). */
object FolderQaRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "folder-qa",
    title = "Answer questions over a folder of files",
    summary =
      "Ingest every file in a folder into a RAG pipeline, retrieve the passages for a question, answer from them.",
    mainClass = "org.llm4s.samples.cookbook.FolderQaRecipe"
  )

  def answer(client: LLMClient, folder: Path, question: String): Result[RAGAnswerResult] = {
    // The stand-in embedding model, registered like any provider module's; see "Use a real provider".
    given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(BagOfWordsEmbeddings)
    for {
      models <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = models
      config = RAG
        .builder()
        .withEmbeddings(BagOfWordsEmbeddings.id.asString, BagOfWordsEmbeddings.Model, BagOfWordsEmbeddings.Dimensions)
        .withTopK(2)
        .withLLM(client)
      rag <- RAG.build(config, _ => Right(BagOfWordsEmbeddings.config))
      result = rag.ingest(folder).flatMap(_ => rag.queryWithAnswer(question))
      _      = rag.close() // closed whether the question was answered or not
      answer <- result
    } yield answer
  }

  val handbook: Map[String, String] = Map(
    "vacation.txt" -> "Employees receive 25 days of paid vacation per year. Unused days carry over until 31 March.",
    "expenses.txt" -> "Expenses above 50 euros need a receipt. Claims must be filed within 30 days.",
    "remote.txt"   -> "Remote work is allowed up to three days a week with manager approval."
  )

  /** Writes [[handbook]] to a new temporary folder, standing in for a folder of your own documents. */
  def writeHandbook(): Result[Path] = Try {
    val folder = Files.createTempDirectory("cookbook-handbook")
    folder.toFile.deleteOnExit() // registered first, so deleted last, once its files are gone
    handbook.foreach { (name, text) =>
      Files.write(folder.resolve(name), text.getBytes(StandardCharsets.UTF_8)).toFile.deleteOnExit()
    }
    folder
  }.toResult

  /** Answers with the first passage of the context the pipeline put in the prompt. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val prompt = conversation.messages.filter(_.role == MessageRole.User).map(_.content).mkString
    Right(AssistantMessage(prompt.linesIterator.find(_.startsWith("[1] ")).fold("I do not know.")(_.drop(4))))
  })

  def demo(client: LLMClient): Result[String] =
    for {
      folder <- writeHandbook()
      result <- answer(client, folder, "How many days of paid vacation do employees get?")
    } yield s"${result.answer}\n(sources: ${result.contexts.flatMap(_.metadata.get("source")).distinct.mkString(", ")})"
}
