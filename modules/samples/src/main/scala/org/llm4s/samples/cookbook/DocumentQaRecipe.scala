package org.llm4s.samples.cookbook

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, MessageRole, SystemMessage, UserMessage }
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ KeywordDocument, SQLiteKeywordIndex }

/** An answer and the documents it was drawn from. */
final case class DocumentAnswer(text: String, sources: Seq[String])

/**
 * Recipe: answer a question from your own documents.
 *
 * The documents are split into chunks and put in an in-memory keyword index (BM25), the best chunk for the question
 * is retrieved, and only that chunk goes into the prompt. No embedding model is involved, so it runs anywhere.
 *
 * A keyword index matches words, not meaning: a question is turned into an OR of its content words, because a
 * question as written must contain every one of its words to match. For meaning-based search, see the vector stores
 * in `llm4s-rag`.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.DocumentQaRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.DocumentQaRecipe --live"   # your configured provider
 * }}}
 */
object DocumentQaRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "document-qa",
    title = "Answer questions from your documents",
    summary = "Chunk some text, index it, retrieve the best passage for a question and answer from it.",
    mainClass = "org.llm4s.samples.cookbook.DocumentQaRecipe"
  )

  val handbook: Map[String, String] = Map(
    "vacation" -> "Employees receive 25 days of paid vacation per year. Unused days carry over until 31 March.",
    "expenses" -> "Expenses above 50 euros need a receipt. Claims must be filed within 30 days.",
    "remote"   -> "Remote work is allowed up to three days a week with manager approval."
  )

  val question: String = "How many vacation days do employees get?"

  // snippet:start
  private val stopWords = Set("what", "when", "where", "which", "does", "many", "much", "have", "from", "with")

  /** The question as an OR of its content words, quoted so that FTS5 reads them as plain words. */
  private[cookbook] def keywordQuery(question: String): String =
    question.toLowerCase
      .split("[^a-z0-9]+")
      .filter(word => word.length > 3 && !stopWords.contains(word))
      .distinct
      .map(word => "\"" + word + "\"")
      .mkString(" OR ")

  def answer(client: LLMClient, documents: Map[String, String], question: String): Result[DocumentAnswer] =
    SQLiteKeywordIndex.inMemory().flatMap { index =>
      val chunking = ChunkingConfig(targetSize = 200, maxSize = 300, overlap = 0, minChunkSize = 0)
      val chunks = for {
        (source, text) <- documents.toSeq
        chunk          <- ChunkerFactory.simple().chunk(text, chunking)
      } yield KeywordDocument(s"$source-${chunk.index}", chunk.content, Map("source" -> source))

      val outcome = for {
        _    <- index.indexBatch(chunks)
        hits <- index.search(keywordQuery(question), topK = 1)
        reply <-
          if (hits.isEmpty) Right(None)
          else {
            val context = hits.map(_.content).mkString("\n")
            val prompt = Conversation(
              Seq(SystemMessage(s"Answer only from this context.\n\n$context"), UserMessage(question))
            )
            client.complete(prompt).map(completion => Some(completion.content))
          }
      } yield DocumentAnswer(
        reply.getOrElse("I could not find anything about that in the documents."),
        hits.flatMap(_.metadata.get("source")).distinct
      )

      index.close()
      outcome
    }
  // snippet:end

  /** Answers with the first sentence of the context it was given. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val system  = conversation.messages.find(_.role == MessageRole.System).map(_.content).getOrElse("")
    val passage = system.split("\n\n").lastOption.getOrElse("")
    Right(AssistantMessage(passage.takeWhile(_ != '.') + "."))
  })

  def demo(client: LLMClient): Result[String] =
    answer(client, handbook, question).map(a => s"${a.text}\n(source: ${a.sources.mkString(", ")})")
}
