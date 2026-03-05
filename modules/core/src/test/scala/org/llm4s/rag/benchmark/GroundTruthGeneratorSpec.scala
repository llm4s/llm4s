package org.llm4s.rag.benchmark

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.rag.evaluation.EvaluationError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class GroundTruthGeneratorSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // Test doubles
  // =========================================================================

  /** Returns a fixed string response for every LLM call. */
  class FixedResponseClient(val response: String) extends LLMClient {
    var callCount: Int = 0
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      callCount += 1
      Right(
        Completion(
          id = "mock-id",
          created = 0L,
          content = response,
          model = "mock-model",
          message = AssistantMessage(contentOpt = Some(response)),
          usage = Some(TokenUsage(10, 10, 20))
        )
      )
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  /** Succeeds on all calls except the first. */
  class FlakyClient(successResponse: String) extends LLMClient {
    var callCount: Int = 0
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      callCount += 1
      if (callCount == 1) Left(EvaluationError("deliberate first-call failure"))
      else
        Right(
          Completion(
            id = "mock",
            created = 0L,
            content = successResponse,
            model = "mock",
            message = AssistantMessage(contentOpt = Some(successResponse)),
            usage = Some(TokenUsage(10, 10, 20))
          )
        )
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  /** Always returns Left. */
  class AlwaysFailingClient extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = Left(EvaluationError("LLM unavailable"))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  // Sample JSON payloads the generator expects from the LLM
  private val twoQAJson =
    """[{"question": "What is X?", "answer": "X is important."},{"question": "How does X work?", "answer": "X works by Y."}]"""

  private val twoQAJsonCodeBlock =
    "```json\n" + twoQAJson + "\n```"

  private val singleQAJson =
    """{"question": "How does A relate to B?", "answer": "A and B are connected."}"""

  // =========================================================================
  // GeneratorOptions
  // =========================================================================

  "GeneratorOptions" should "have sensible defaults" in {
    val opts = GeneratorOptions()
    opts.temperature shouldBe 0.7
    opts.maxTokens shouldBe 2000
  }

  it should "allow custom temperature and token limit" in {
    val opts = GeneratorOptions(temperature = 0.2, maxTokens = 500)
    opts.temperature shouldBe 0.2
    opts.maxTokens shouldBe 500
  }

  // =========================================================================
  // Factory methods
  // =========================================================================

  "GroundTruthGenerator.apply(client)" should "create a generator with default options" in {
    val g = GroundTruthGenerator(new FixedResponseClient(twoQAJson))
    g should not be null
  }

  "GroundTruthGenerator.apply(client, options)" should "create a generator with custom options" in {
    val opts = GeneratorOptions(temperature = 0.1, maxTokens = 1000)
    val g    = GroundTruthGenerator(new FixedResponseClient(twoQAJson), opts)
    g should not be null
  }

  // =========================================================================
  // generateFromDocuments
  // =========================================================================

  "generateFromDocuments" should "return Left for an empty document list" in {
    val gen    = GroundTruthGenerator(new FixedResponseClient(twoQAJson))
    val result = gen.generateFromDocuments(Seq.empty)
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("No documents provided")
  }

  it should "return Left when all LLM calls fail" in {
    val gen    = GroundTruthGenerator(new AlwaysFailingClient())
    val result = gen.generateFromDocuments(Seq("doc1" -> "Some content"))
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Failed to generate any samples")
  }

  it should "skip failed documents and include successful ones" in {
    // First doc fails (first LLM call), second succeeds
    val client = new FlakyClient(twoQAJson)
    val gen    = GroundTruthGenerator(client)
    val result = gen.generateFromDocuments(
      Seq("doc1" -> "Content A", "doc2" -> "Content B")
    )
    result.isRight shouldBe true
    result.toOption.get.samples should not be empty
  }

  it should "return Right with a named TestDataset on success" in {
    val gen = GroundTruthGenerator(new FixedResponseClient(twoQAJson))
    val result = gen.generateFromDocuments(
      Seq("d1" -> "Doc one text", "d2" -> "Doc two text"),
      questionsPerDoc = 2,
      datasetName = "my-dataset"
    )
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "my-dataset"
    result.toOption.get.samples should not be empty
  }

  it should "embed generation provenance in dataset metadata" in {
    val gen  = GroundTruthGenerator(new FixedResponseClient(twoQAJson))
    val docs = Seq("doc1" -> "Alpha text", "doc2" -> "Beta text")
    val meta = gen
      .generateFromDocuments(docs, questionsPerDoc = 2, datasetName = "meta-test")
      .toOption
      .get
      .metadata
    meta("generated") shouldBe "true"
    meta("generator") shouldBe "GroundTruthGenerator"
    meta("documentCount") shouldBe "2"
    meta("questionsPerDoc") shouldBe "2"
  }

  // =========================================================================
  // generateFromDocument
  // =========================================================================

  "generateFromDocument" should "return EvalSamples with correct source metadata" in {
    val gen    = GroundTruthGenerator(new FixedResponseClient(twoQAJson))
    val result = gen.generateFromDocument("my-doc", "Some detailed content", count = 2)
    result.isRight shouldBe true
    val samples = result.toOption.get
    samples should not be empty
    samples.foreach { s =>
      s.metadata("sourceDocId") shouldBe "my-doc"
      s.metadata("generated") shouldBe "true"
    }
  }

  it should "set the document content as the context for each sample" in {
    val content = "The document body used as context."
    val gen     = GroundTruthGenerator(new FixedResponseClient(twoQAJson))
    val samples = gen.generateFromDocument("d", content).toOption.get
    samples.foreach(_.contexts shouldBe Seq(content))
  }

  it should "set groundTruth equal to the generated answer" in {
    val gen     = GroundTruthGenerator(new FixedResponseClient(twoQAJson))
    val samples = gen.generateFromDocument("d", "content").toOption.get
    samples.foreach(s => s.groundTruth shouldBe Some(s.answer))
  }

  it should "parse JSON wrapped in a markdown code block" in {
    val gen    = GroundTruthGenerator(new FixedResponseClient(twoQAJsonCodeBlock))
    val result = gen.generateFromDocument("d", "content", count = 2)
    result.isRight shouldBe true
    result.toOption.get should not be empty
  }

  it should "return Left when the LLM response is not valid JSON" in {
    val gen    = GroundTruthGenerator(new FixedResponseClient("This is not JSON."))
    val result = gen.generateFromDocument("d", "content")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Failed to parse")
  }

  it should "return Left when JSON is an array but items are missing required fields" in {
    // Missing "answer" key
    val gen    = GroundTruthGenerator(new FixedResponseClient("""[{"question": "Q?"}]"""))
    val result = gen.generateFromDocument("d", "content")
    result.isLeft shouldBe true
  }

  it should "return Left when the LLM call fails" in {
    val gen    = GroundTruthGenerator(new AlwaysFailingClient())
    val result = gen.generateFromDocument("d", "content")
    result.isLeft shouldBe true
  }

  // =========================================================================
  // generateMultiHop
  // =========================================================================

  "generateMultiHop" should "return Left with fewer than 2 documents" in {
    val gen    = GroundTruthGenerator(new FixedResponseClient(singleQAJson))
    val result = gen.generateMultiHop(Seq("doc1" -> "Only doc"))
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("at least 2 documents")
  }

  it should "return Right with empty list when LLM response cannot be parsed as a single QA object" in {
    // twoQAJson is an array, not an object — parseSingleQA will fail gracefully
    val gen    = GroundTruthGenerator(new FixedResponseClient(twoQAJson))
    val result = gen.generateMultiHop(Seq("d1" -> "Content 1", "d2" -> "Content 2"))
    result.isRight shouldBe true
    result.toOption.get shouldBe empty
  }

  it should "return samples that each carry two contexts" in {
    val gen = GroundTruthGenerator(new FixedResponseClient(singleQAJson))
    val result = gen.generateMultiHop(
      Seq("d1" -> "First doc", "d2" -> "Second doc", "d3" -> "Third doc"),
      count = 2
    )
    result.isRight shouldBe true
    val samples = result.toOption.get
    samples should not be empty
    samples.foreach(_.contexts should have size 2)
  }

  it should "mark multi-hop samples with appropriate metadata" in {
    val gen     = GroundTruthGenerator(new FixedResponseClient(singleQAJson))
    val samples = gen.generateMultiHop(Seq("d1" -> "A", "d2" -> "B")).toOption.get
    samples.foreach { s =>
      s.metadata("multiHop") shouldBe "true"
      s.metadata("generated") shouldBe "true"
      s.metadata.contains("sourceDoc1") shouldBe true
      s.metadata.contains("sourceDoc2") shouldBe true
    }
  }

  it should "respect the count parameter for sliding window pairs" in {
    val gen = GroundTruthGenerator(new FixedResponseClient(singleQAJson))
    // 5 docs → 4 adjacent pairs → take(count=2) → at most 2 samples
    val result =
      gen.generateMultiHop((1 to 5).map(i => s"d$i" -> s"Document $i content"), count = 2)
    result.isRight shouldBe true
    result.toOption.get.size should be <= 2
  }

  // =========================================================================
  // GroundTruthGenerator.generateAndSave (companion object)
  // =========================================================================

  "GroundTruthGenerator.generateAndSave" should "write a JSON file to the given path" in {
    val tmpFile = Files.createTempFile("gt-save-test", ".json")
    try {
      val result = GroundTruthGenerator.generateAndSave(
        llmClient = new FixedResponseClient(twoQAJson),
        documents = Seq("doc1" -> "Document content for save test"),
        outputPath = tmpFile.toString
      )
      result.isRight shouldBe true
      Files.exists(tmpFile) shouldBe true
      new String(Files.readAllBytes(tmpFile)) should include("question")
    } finally {
      Files.deleteIfExists(tmpFile)
      ()
    }
  }

  it should "return Left when LLM fails during generation" in {
    val tmpFile = Files.createTempFile("gt-fail-test", ".json")
    try {
      val result = GroundTruthGenerator.generateAndSave(
        llmClient = new AlwaysFailingClient(),
        documents = Seq("doc1" -> "Content"),
        outputPath = tmpFile.toString
      )
      result.isLeft shouldBe true
    } finally {
      Files.deleteIfExists(tmpFile)
      ()
    }
  }
}
