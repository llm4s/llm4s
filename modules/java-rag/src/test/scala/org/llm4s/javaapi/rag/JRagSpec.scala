package org.llm4s.javaapi.rag

import org.llm4s.error.{ CancelledError, RateLimitError }
import org.llm4s.javaapi.{ LlmErrorKind, LlmResult }
import org.llm4s.llmconnect.model.{ EmbeddingError, InputPurpose, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.util.Comparator
import scala.jdk.CollectionConverters.*

/**
 * `JRag` (#1491), driven through `JRagCheck`'s Java source, over a word-count embedding provider and a model that
 * answers with the first chunk it is given: no network, and every answer and source predictable.
 */
class JRagSpec extends AnyFlatSpec with Matchers {

  import RagFakes.*

  private def withTempDir[A](body: Path => A): A = {
    val dir = Files.createTempDirectory("jrag-spec")
    try body(dir)
    finally Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p): Unit)
  }

  private def pipeline(rag: LlmResult[JRag]): JRag = rag.get()

  "JRag" should "ingest two documents and answer a question from the one that matches, citing it" in {
    val embedder = new WordEmbeddings()
    val model    = new EchoingLlm()
    val log      = JRagCheck.ingestTwoAndAsk(embeddings(embedder), llm(model), "Where did the cat sit?").asScala

    log.head shouldBe "chunks:1+1 documents:2 total:2"
    log should contain("question:Where did the cat sit?")
    log should contain("answer:The cat sat on the mat.")
    log.filter(_.startsWith("source:")).head shouldBe "source:cats|The cat sat on the mat."
    log should contain("usage:12/3")
    log.last shouldBe "top1:[cats]"

    // the model was given the matching chunk as context, and the question
    val prompt = model.conversations.head.messages.collect { case m: UserMessage => m.content }.mkString
    prompt should include("[1] The cat sat on the mat.")
    prompt should include("Question: Where did the cat sit?")
    // chunks are embedded as documents, the question as a query, with the client's own model
    embedder.requests.filter(_.purpose == InputPurpose.Query).flatMap(_.input).toSet shouldBe
      Set("Where did the cat sit?")
    embedder.requests.filter(_.purpose == InputPurpose.Document).flatMap(_.input) should not contain
      "Where did the cat sit?"
    embedder.requests.map(_.model).toSet shouldBe Set(Model)
  }

  it should "search without a model, returning at most topK chunks, best first" in {
    JRagCheck.search(embeddings(), "Does Scala run on the JVM?").asScala.toList match {
      case List(default, top1) =>
        default should startWith("default:[scala, ")
        default.split(",").length shouldBe 2
        top1 shouldBe "top1:[scala]"
      case other => fail(s"unexpected log $other")
    }
  }

  it should "ingest every file of a directory, with the file's name as its id and source" in withTempDir { dir =>
    Files.writeString(dir.resolve("cats.txt"), "The cat sat on the mat.")
    Files.writeString(dir.resolve("dogs.txt"), "The dog chewed a bone in the garden.")
    Files.writeString(dir.resolve("ignored.bin"), "cat cat cat")

    val log = JRagCheck.ingestDirectory(embeddings(), llm(), dir, "Where did the cat sit?").asScala
    log.head shouldBe "chunks:2"
    log(1) shouldBe "cats.txt|cats.txt|docs|true"
    log.drop(1).map(_.takeWhile(_ != '|')).toSet shouldBe Set("cats.txt", "dogs.txt")
  }

  it should "ingest a single file" in withTempDir { dir =>
    val file = Files.writeString(dir.resolve("cats.md"), "The cat sat on the mat.")
    val rag  = pipeline(JRag.builder().embeddings(embeddings()).build())
    try {
      rag.ingest(file).get() shouldBe 1
      rag.search("cat").get().asScala.map(_.documentId) shouldBe Seq("cats.md")
    } finally rag.close()
  }

  it should "answer from no context, with no sources, when nothing is indexed" in {
    JRagCheck.empty(embeddings(), llm()).asScala shouldBe Seq(
      s"answer:$NoContext",
      "sources:0",
      "search:0",
      "documents:0 chunks:0"
    )
  }

  it should "keep what it ingested in SQLite across pipelines over the same file" in withTempDir { dir =>
    JRagCheck.sqlite(embeddings(), dir.resolve("docs.db")).asScala shouldBe Seq(
      "store:false:docs.db",
      "ingested:1",
      "found:[cats]"
    )
    Files.exists(dir.resolve("docs.db")) shouldBe true
    Files.exists(dir.resolve("docs-fts.db")) shouldBe true
  }

  it should "replace a document ingested again under the same id" in {
    val rag = pipeline(JRag.builder().embeddings(embeddings()).build())
    try {
      rag.ingestText("The cat sat on the mat.", "pets").get() shouldBe 1
      rag.ingestText("The dog chewed a bone.", "pets").get() shouldBe 1
      (rag.documentCount, rag.chunkCount) shouldBe ((1, 1))
      rag.search("cat").get().asScala.map(_.content) shouldBe Seq("The dog chewed a bone.")
    } finally rag.close()
  }

  "a failing embedding provider" should "fail every call with the kind of its error, never throwing" in {
    val unauthorised = EmbeddingError(Some("401"), "Authentication failed: bad key", "voyage")
    val rag          = pipeline(JRag.builder().embeddings(failingEmbeddings(unauthorised)).llm(llm()).build())
    try {
      val log = JRagCheck.failures(rag).asScala
      log.foreach(_ should include(s":${LlmErrorKind.AUTHENTICATION}:"))
      log.head should include("bad key")
      JRagCheck.react(rag.ask("Where did the cat sit?")) should startWith("fix the setup: ")
    } finally rag.close()
  }

  it should "turn an exception it throws into a failed result" in {
    val rag = pipeline(JRag.builder().embeddings(throwingEmbeddings(new IllegalStateException("boom"))).build())
    try {
      val failed = rag.ingestText("The cat sat on the mat.", "cats")
      failed.isFailure shouldBe true
      failed.getError().getKind shouldBe LlmErrorKind.OTHER
      failed.getError().getMessage should include("boom")
    } finally rag.close()
  }

  it should "report an interrupt as CANCELLED, leaving the interrupt flag set" in {
    val rag = pipeline(JRag.builder().embeddings(throwingEmbeddings(new InterruptedException("stop"))).build())
    try {
      val result      = rag.ingestText("The cat sat on the mat.", "cats")
      val interrupted = Thread.interrupted() // reads and clears the flag, so later tests run clean
      result.getError().getKind shouldBe LlmErrorKind.CANCELLED
      result.getError().error shouldBe a[CancelledError]
      interrupted shouldBe true
    } finally rag.close()
  }

  "a failing model" should "fail ask with the kind of its error, while ingest and search still work" in {
    val rag = pipeline(
      JRag.builder().embeddings(embeddings()).llm(llm(new EchoingLlm(Some(RateLimitError("openai"))))).build()
    )
    try {
      JRagCheck.failures(rag).asScala.toList match {
        case List(ingest, ask, search) =>
          ingest shouldBe "ingest:ok:1"
          ask should startWith(s"ask:${LlmErrorKind.RATE_LIMIT}:")
          search should startWith("search:ok:")
        case other => fail(s"unexpected log $other")
      }
      JRagCheck.react(rag.ask("Where did the cat sit?")) shouldBe "wait and retry"
    } finally rag.close()
  }

  "JRag's arguments" should "be refused as VALIDATION failures naming the field, and ask without a model as CONFIGURATION" in {
    val log     = JRagCheck.invalidArguments(embeddings()).asScala.toList
    val missing = (field: String) => s"VALIDATION:Field '$field' is required"
    val invalid = (field: String, why: String) => s"VALIDATION:Field '$field' is invalid: $why"
    val noSuch =
      log.find(_.startsWith("ingest VALIDATION:Field 'path' is invalid: ")).getOrElse(fail(log.mkString("\n")))
    noSuch should endWith("file.txt does not exist")
    log shouldBe List(
      s"build ${missing("embeddings")}",
      s"build ${missing("embeddings")}",
      s"build ${missing("store")}",
      s"build ${missing("store file")}",
      s"build ${invalid("chunk size", "must be at least 1, got 0")}",
      s"build ${invalid("chunk overlap", "must be at least 0 and less than 100")}",
      s"build ${invalid("chunk overlap", "must be at least 0 and less than 100")}",
      s"build ${invalid("topK", "must be at least 1, got 0")}",
      s"ingestText ${missing("text")}",
      s"ingestText ${missing("documentId")}",
      s"ingestText ${invalid("documentId", "must not be blank")}",
      s"ingestText ${missing("metadata")}",
      s"ingestText ${invalid("metadata[topic]", "must not be null")}",
      s"ingestText ${invalid("metadata", "must not have a null key")}",
      s"ingest ${missing("path")}",
      noSuch,
      s"ask ${missing("question")}",
      s"ask ${invalid("question", "must not be blank")}",
      s"ask ${invalid("topK", "must be at least 1, got 0")}",
      s"search ${missing("query")}",
      s"search ${invalid("topK", "must be at least 1, got 0")}",
      "ask without llm CONFIGURATION:JRag.ask needs a chat client to answer with: build the pipeline with " +
        "JRag.builder().llm(client). search(query) works without one.",
      "documents:0"
    )
  }

  "JRag's constructor" should "refuse a caller outside the package" in {
    an[UnsupportedOperationException] should be thrownBy new JRag(null, null)
  }

  "JRag's values" should "describe themselves and compare by value" in {
    val rag = pipeline(JRag.builder().embeddings(embeddings()).llm(llm()).build())
    try {
      rag.ingestText("The cat sat on the mat.", "cats", java.util.Map.of("topic", "pets")).get() shouldBe 1
      rag.toString shouldBe "JRag(1 documents, 1 chunks)"
      val first  = rag.ask("Where did the cat sit?").get()
      val second = rag.ask("Where did the cat sit?").get()
      first shouldBe second
      first.hashCode shouldBe second.hashCode
      first.toString shouldBe "JRagAnswer(The cat sat on the mat., 1 sources)"
      val source = first.sources.get(0)
      source.id should startWith("cats")
      (source.metadata.asScala should contain).allOf("topic" -> "pets", "docId" -> "cats", "chunkIndex" -> "0")
      source.score should be > 0.0
      source.toString should startWith(s"JRagSource(${source.id}, score ")
      a[UnsupportedOperationException] should be thrownBy source.metadata.put("x", "y")
      a[UnsupportedOperationException] should be thrownBy first.sources.add(source)
    } finally rag.close()

    JRag.inMemory() shouldBe JRag.inMemory()
    JRag.inMemory().isInMemory shouldBe true
    JRag.inMemory().file.isPresent shouldBe false
    JRag.inMemory().toString shouldBe "JRagStore(in memory)"
    JRag.sqlite(Path.of("a.db")) shouldBe JRag.sqlite(Path.of("a.db"))
    JRag.sqlite(Path.of("a.db")).hashCode shouldBe JRag.sqlite(Path.of("a.db")).hashCode
    JRag.sqlite(Path.of("a.db")) should not be JRag.sqlite(Path.of("b.db"))
    JRag.sqlite(Path.of("a.db")).toString shouldBe "JRagStore(sqlite a.db)"
    JRag.builder().toString shouldBe
      "JRagBuilder(embeddings=null, llm=none, store=JRagStore(in memory), chunking=default, topK=default, systemPrompt=default)"
    JRag.builder().llm(llm()).chunking(400, 50).topK(3).systemPrompt("Be brief.").toString should endWith(
      "llm=set, store=JRagStore(in memory), chunking=400/50, topK=3, systemPrompt=set)"
    )
  }

  "the builder's settings" should "reach the pipeline: system prompt, topK and chunking" in {
    val model = new EchoingLlm()
    val rag = pipeline(
      JRag
        .builder()
        .embeddings(embeddings())
        .llm(llm(model))
        .systemPrompt("Answer in one word.")
        .topK(1)
        .chunking(120, 10)
        .build()
    )
    try {
      val text = (1 to 12).map(i => s"Sentence number $i is about the cat on the mat.").mkString(" ")
      rag.ingestText(text, "pets").get().intValue should be > 1
      val answer = rag.ask("Where did the cat sit?").get()
      answer.sources.size shouldBe 1
      model.conversations.head.messages.head.content shouldBe "Answer in one word."
    } finally rag.close()
  }
}
