package org.llm4s.llmconnect

import org.llm4s.error.{ AuthenticationError, ValidationError }
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ ObjectSchema, Schema }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import java.util.concurrent.{ ConcurrentLinkedQueue, Executors, TimeUnit }
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Provider-agnostic contract tests for [[LLMClient.completeStructured]]: what is sent to
 * `complete`, which options survive, and how failures are mapped.
 */
class CompleteStructuredContractSpec extends AnyFlatSpec with Matchers {

  case class Person(name: String, age: Int)
  object Person { implicit val rw: ReadWriter[Person] = macroRW }

  private lazy val personSchema: ObjectSchema[Person] = Schema
    .`object`[Person]("A person")
    .withRequiredField("name", Schema.string("Name"))
    .withRequiredField("age", Schema.integer("Age"))
    .withProperty(org.llm4s.toolapi.PropertyDefinition("nick", Schema.string("Nickname"), required = false))

  private def completion(content: String): Completion =
    Completion(id = "id", created = 0L, content = content, model = "m", message = AssistantMessage(Some(content)))

  /** Thread-safe recording client: all calls are captured, the reply is computed from the request. */
  private class RecordingClient(reply: (Conversation, CompletionOptions) => Result[Completion]) extends LLMClient {
    val calls = new ConcurrentLinkedQueue[(Conversation, CompletionOptions)]()

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.add((conversation, options))
      reply(conversation, options)
    }
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = Left(ValidationError("test", "streamComplete must not be used by completeStructured"))
    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 512

    def recorded: Seq[(Conversation, CompletionOptions)] = calls.asScala.toSeq
  }

  private val goodJson = """{"name":"Ada","age":36}"""
  private val conv     = Conversation(Seq(SystemMessage("be terse"), UserMessage("who?")))

  private def okClient = new RecordingClient((_, _) => Right(completion(goodJson)))

  "completeStructured" should "send the conversation to complete() unchanged and use complete(), not streamComplete()" in {
    val c = okClient
    c.completeStructured[Person](conv, personSchema) shouldBe Right(Person("Ada", 36))
    c.recorded.map(_._1) shouldBe Seq(conv)
  }

  it should "preserve every caller option except responseFormat" in {
    val callerOpts = CompletionOptions(
      temperature = 0.3,
      topP = 0.8,
      maxTokens = Some(777),
      presencePenalty = 0.1,
      frequencyPenalty = 0.2,
      reasoning = Some(ReasoningEffort.High),
      budgetTokens = Some(2048)
    )
    val c = okClient
    c.completeStructured[Person](conv, personSchema, callerOpts)
    val sent = c.recorded.head._2
    sent.withResponseFormat(None) shouldBe callerOpts
    sent.responseFormat.isDefined shouldBe true
  }

  it should "not mutate or reuse the caller's options value" in {
    val callerOpts = CompletionOptions(temperature = 0.9)
    val c          = okClient
    c.completeStructured[Person](conv, personSchema, callerOpts)
    callerOpts.responseFormat shouldBe None
    callerOpts shouldBe CompletionOptions(temperature = 0.9)
  }

  it should "override a caller-supplied ResponseFormat.Json with the JSON schema format" in {
    val c = okClient
    c.completeStructured[Person](conv, personSchema, CompletionOptions().withResponseFormat(ResponseFormat.Json))
    c.recorded.head._2.responseFormat.get shouldBe a[ResponseFormat.JsonSchema]
  }

  it should "send a strict JsonSchema derived from the schema, with all properties required and no extras" in {
    val c = okClient
    c.completeStructured[Person](conv, personSchema)
    val js = c.recorded.head._2.responseFormat.get.asInstanceOf[ResponseFormat.JsonSchema]
    js.strict shouldBe true
    js.name shouldBe "response"
    js.schema shouldBe personSchema.toJsonSchema(strict = true)
    js.schema("type").str shouldBe "object"
    js.schema("additionalProperties").bool shouldBe false
    // documented consequence of strict mode: even the optional "nick" is listed as required
    js.schema("required").arr.map(_.str).toSet shouldBe Set("name", "age", "nick")
  }

  it should "map non-JSON output to ValidationError" in {
    val c = new RecordingClient((_, _) => Right(completion("I refuse")))
    c.completeStructured[Person](conv, personSchema) match {
      case Left(e: ValidationError) => e.message should include("not valid JSON")
      case other                    => fail(s"expected ValidationError, got $other")
    }
  }

  it should "map schema mismatches (missing field, wrong type) to ValidationError" in {
    Seq("""{"name":"Ada"}""", """{"name":"Ada","age":"old"}""", "[1,2]", "42", "null").foreach { body =>
      val c = new RecordingClient((_, _) => Right(completion(body)))
      withClue(body) {
        c.completeStructured[Person](conv, personSchema) match {
          case Left(e: ValidationError) => e.message should include("expected schema")
          case other                    => fail(s"expected ValidationError, got $other")
        }
      }
    }
  }

  it should "recover JSON followed by trailing prose and from a fence" in {
    Seq(s"$goodJson\nHope that helps!", s"```json\n$goodJson\n```", s"Sure {not json} here: $goodJson").foreach {
      body =>
        val c = new RecordingClient((_, _) => Right(completion(body)))
        withClue(body)(c.completeStructured[Person](conv, personSchema) shouldBe Right(Person("Ada", 36)))
    }
  }

  it should "return provider errors untouched and not attempt to parse" in {
    val err = AuthenticationError("p", "nope")
    val c   = new RecordingClient((_, _) => Left(err))
    c.completeStructured[Person](conv, personSchema) shouldBe Left(err)
    c.recorded should have size 1L
  }

  it should "be safe to call concurrently on a shared client" in {
    val pool = Executors.newFixedThreadPool(8)
    val ec   = ExecutionContext.fromExecutorService(pool)
    val c = new RecordingClient((cv, _) => {
      val q = cv.messages.collect { case UserMessage(t) => t }.head
      Right(completion(s"""{"name":"$q","age":${q.length}}"""))
    })
    val futures = (1 to 200).map { i =>
      Future {
        val name = s"user$i"
        c.completeStructured[Person](Conversation(Seq(UserMessage(name))), personSchema) -> name
      }(ec)
    }
    val results = Await.result(Future.sequence(futures)(implicitly, ec), 30.seconds)
    pool.shutdown()
    pool.awaitTermination(5, TimeUnit.SECONDS)
    results.foreach { case (r, name) => r shouldBe Right(Person(name, name.length)) }
    c.recorded should have size 200L
  }
}
