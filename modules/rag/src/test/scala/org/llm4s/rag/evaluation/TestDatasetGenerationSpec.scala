package org.llm4s.rag.evaluation

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class TestDatasetGenerationSpec extends AnyFlatSpec with Matchers with EitherValues {

  private class ScriptedClient(responses: Result[String]*) extends LLMClient {
    private val remainingResponses          = responses.iterator
    var conversations: Vector[Conversation] = Vector.empty

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      conversations :+= conversation
      remainingResponses.next().map { response =>
        Completion(
          id = "test-completion",
          created = Instant.EPOCH,
          content = response,
          model = "test-model",
          message = AssistantMessage(contentOpt = Some(response))
        )
      }
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private val firstDocument  = "Paris is the capital of France. France is in Europe."
  private val secondDocument = "Tokyo is the capital of Japan."
  private val firstResponse =
    """[{"question":"What is France's capital?","answer":"Paris"},
      |{"question":"Where is France?","answer":"Europe"}]""".stripMargin
  private val secondResponse = """[{"question":"What is Japan's capital?","answer":"Tokyo"}]"""

  "TestDataset.generateFromDocuments" should "create one sample per QA pair with its source document and ground truth" in {
    val client = new ScriptedClient(Right(firstResponse), Right(secondResponse))

    val dataset = TestDataset
      .generateFromDocuments(Seq(firstDocument, secondDocument), client, samplesPerDoc = 5, datasetName = "capitals")
      .value

    dataset.samples shouldBe Seq(
      EvalSample(
        question = "What is France's capital?",
        answer = "Paris",
        contexts = Seq(firstDocument),
        groundTruth = Some("Paris"),
        metadata = Map("sourceDoc" -> "0", "generated" -> "true")
      ),
      EvalSample(
        question = "Where is France?",
        answer = "Europe",
        contexts = Seq(firstDocument),
        groundTruth = Some("Europe"),
        metadata = Map("sourceDoc" -> "0", "generated" -> "true")
      ),
      EvalSample(
        question = "What is Japan's capital?",
        answer = "Tokyo",
        contexts = Seq(secondDocument),
        groundTruth = Some("Tokyo"),
        metadata = Map("sourceDoc" -> "1", "generated" -> "true")
      )
    )
    dataset.name shouldBe "capitals"
    dataset.metadata shouldBe Map("generated" -> "true", "documentCount" -> "2", "samplesPerDoc" -> "5")
    client.conversations should have size 2
  }

  Seq(
    "a JSON Markdown code fence" -> s"```json\n$secondResponse\n```",
    "prose surrounding the JSON array" -> s"Here are the requested pairs:\n$secondResponse\nThese are grounded in the document."
  ).foreach { case (format, response) =>
    it should s"parse a reply with $format" in {
      val client = new ScriptedClient(Right(response))

      val dataset = TestDataset.generateFromDocuments(Seq(secondDocument), client).value

      dataset.samples shouldBe Seq(
        EvalSample(
          question = "What is Japan's capital?",
          answer = "Tokyo",
          contexts = Seq(secondDocument),
          groundTruth = Some("Tokyo"),
          metadata = Map("sourceDoc" -> "0", "generated" -> "true")
        )
      )
    }
  }

  Seq(
    "invalid JSON"                -> Right("not JSON"),
    "a QA pair without an answer" -> Right("""[{"question":"What is missing?"}]"""),
    "a client error"              -> Left(EvaluationError("LLM unavailable"))
  ).foreach { case (failure, response) =>
    it should s"skip only the document with $failure and preserve the remaining source indices" in {
      val client = new ScriptedClient(Right(firstResponse), response, Right(secondResponse))

      val dataset = TestDataset
        .generateFromDocuments(Seq(firstDocument, "The failed document.", secondDocument), client)
        .value

      dataset.samples
        .map(_.question) shouldBe Seq("What is France's capital?", "Where is France?", "What is Japan's capital?")
      dataset.samples.map(_.answer) shouldBe Seq("Paris", "Europe", "Tokyo")
      dataset.samples.map(_.contexts) shouldBe Seq(Seq(firstDocument), Seq(firstDocument), Seq(secondDocument))
      dataset.samples.map(_.metadata("sourceDoc")) shouldBe Seq("0", "0", "2")
      dataset.metadata("documentCount") shouldBe "3"
      client.conversations should have size 3
    }
  }

  it should "return an evaluation error when no document produces samples" in {
    val client = new ScriptedClient(
      Right("not JSON"),
      Right("""[{"question":"What is missing?"}]"""),
      Left(EvaluationError("LLM unavailable"))
    )

    TestDataset.generateFromDocuments(Seq("first", "second", "third"), client) shouldBe
      Left(EvaluationError("Failed to generate any samples from documents"))
    client.conversations should have size 3
  }

  it should "include each document and the requested sample count in its prompt" in {
    val client    = new ScriptedClient(Right(firstResponse), Right(secondResponse))
    val documents = Seq(firstDocument, secondDocument)

    TestDataset.generateFromDocuments(documents, client, samplesPerDoc = 7).isRight shouldBe true

    client.conversations should have size documents.size
    client.conversations.zip(documents).foreach { case (conversation, document) =>
      val userMessages = conversation.messages.collect { case message: UserMessage => message.content }
      userMessages should have size 1
      userMessages.head should include(document)
      userMessages.head should include("7 question-answer pairs")
    }
  }
}
