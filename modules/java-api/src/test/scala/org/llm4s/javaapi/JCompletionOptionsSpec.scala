package org.llm4s.javaapi

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{ Optional, OptionalInt }
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/**
 * [[JCompletionOptions]] and `JLlmClient.complete(Conversation, JCompletionOptions)` (#1488). The Java side is
 * `CompletionOptionsCheck.java`; this spec checks the core [[CompletionOptions]] each option set becomes, as the
 * client receives it.
 */
class JCompletionOptionsSpec extends AnyFlatSpec with Matchers {

  /** A client that records the options of every request and answers "4". */
  private class Capturing extends LLMClient {
    val received: ArrayBuffer[CompletionOptions] = ArrayBuffer.empty
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      received += o
      Right(Completion("id", 0L, "4", "m", AssistantMessage("4")))
    }
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def sent(options: JCompletionOptions): CompletionOptions = {
    val capturing = new Capturing
    CompletionOptionsCheck.send(new JLlmClient(capturing), options).get() shouldBe "4"
    capturing.received.toList match {
      case List(only) => only
      case other      => fail(s"expected one request, got $other")
    }
  }

  "options built from Java with every setting" should "reach the client as the matching CompletionOptions" in {
    val received = sent(CompletionOptionsCheck.everySetting())

    received.temperature shouldBe 0.2
    received.topP shouldBe 0.9
    received.maxTokens shouldBe Some(512)
    received.presencePenalty shouldBe 0.5
    received.frequencyPenalty shouldBe -0.5
    received.reasoning shouldBe Some(ReasoningEffort.High)
    received.budgetTokens shouldBe Some(4096)
    received.tools shouldBe empty
    received.responseFormat shouldBe None
    received shouldBe CompletionOptions(
      temperature = 0.2,
      topP = 0.9,
      maxTokens = Some(512),
      presencePenalty = 0.5,
      frequencyPenalty = -0.5,
      reasoning = Some(ReasoningEffort.High),
      budgetTokens = Some(4096)
    )
  }

  it should "read back from Java with Java types" in {
    CompletionOptionsCheck.readBack(CompletionOptionsCheck.everySetting()).asScala.toList shouldBe List(
      "temperature:0.2",
      "topP:0.9",
      "maxTokens:512",
      "presencePenalty:0.5",
      "frequencyPenalty:-0.5",
      "reasoning:high",
      "budgetTokens:4096"
    )
  }

  "options with nothing set" should "be core's defaults, read back as empty where a value may be absent" in {
    val defaults = JCompletionOptions.builder().build()
    sent(defaults) shouldBe CompletionOptions()
    CompletionOptionsCheck.readBack(defaults).asScala.toList shouldBe List(
      "temperature:0.7",
      "topP:1.0",
      "maxTokens:none",
      "presencePenalty:0.0",
      "frequencyPenalty:0.0",
      "reasoning:none",
      "budgetTokens:none"
    )
  }

  "the Optional setters" should "set a present value and clear an empty one" in {
    val set = CompletionOptionsCheck.throughOptionals()
    sent(set) shouldBe CompletionOptions(
      maxTokens = Some(256),
      reasoning = Some(ReasoningEffort.Low),
      budgetTokens = Some(1024)
    )
    sent(CompletionOptionsCheck.cleared(set)) shouldBe CompletionOptions()
    sent(CompletionOptionsCheck.cleared(CompletionOptionsCheck.everySetting())) shouldBe
      CompletionOptions(temperature = 0.2, topP = 0.9, presencePenalty = 0.5, frequencyPenalty = -0.5)
  }

  "every reasoning level" should "map to core's level of the same name, and back" in {
    val pairs = List(
      JReasoningEffort.NONE   -> ReasoningEffort.None,
      JReasoningEffort.LOW    -> ReasoningEffort.Low,
      JReasoningEffort.MEDIUM -> ReasoningEffort.Medium,
      JReasoningEffort.HIGH   -> ReasoningEffort.High
    )
    pairs.map(_._1) shouldBe JReasoningEffort.values.toList
    pairs.foreach { (java, core) =>
      val options = JCompletionOptions.builder().reasoning(java).build()
      options.underlying.reasoning shouldBe Some(core)
      options.reasoning shouldBe Optional.of(java)
      CompletionOptionsCheck.describe(java) shouldBe core.name
    }
  }

  "a builder" should "be immutable, so one base can be extended two ways" in {
    CompletionOptionsCheck.forked().asScala.toList.map(_.underlying) shouldBe List(
      CompletionOptions(temperature = 0.1),
      CompletionOptions(temperature = 0.1, maxTokens = Some(16)),
      CompletionOptions(temperature = 0.1, reasoning = Some(ReasoningEffort.Medium))
    )
  }

  it should "start from existing options with toBuilder" in {
    val base = CompletionOptionsCheck.everySetting()
    base.toBuilder.build() shouldBe base
    base.toBuilder.temperature(0.0).build().underlying shouldBe base.underlying.withTemperature(0.0)
  }

  it should "accept the edges of each range" in {
    val edges = JCompletionOptions
      .builder()
      .temperature(0.0)
      .topP(0.0)
      .maxTokens(1)
      .budgetTokens(1)
      .presencePenalty(-2.0)
      .frequencyPenalty(2.0)
      .build()
    edges.underlying shouldBe CompletionOptions(
      temperature = 0.0,
      topP = 0.0,
      maxTokens = Some(1),
      budgetTokens = Some(1),
      presencePenalty = -2.0,
      frequencyPenalty = 2.0
    )
    JCompletionOptions.builder().topP(1.0).build().topP shouldBe 1.0
  }

  it should "reject a value no provider accepts with IllegalArgumentException, naming the setting" in {
    val b = JCompletionOptions.builder()
    val rejected: List[(String, () => Any)] = List(
      "temperature"      -> (() => b.temperature(-0.1)),
      "temperature"      -> (() => b.temperature(Double.NaN)),
      "temperature"      -> (() => b.temperature(Double.PositiveInfinity)),
      "topP"             -> (() => b.topP(-0.1)),
      "topP"             -> (() => b.topP(1.1)),
      "topP"             -> (() => b.topP(Double.NaN)),
      "presencePenalty"  -> (() => b.presencePenalty(Double.NaN)),
      "frequencyPenalty" -> (() => b.frequencyPenalty(Double.NegativeInfinity)),
      "maxTokens"        -> (() => b.maxTokens(0)),
      "maxTokens"        -> (() => b.maxTokens(OptionalInt.of(-1))),
      "budgetTokens"     -> (() => b.budgetTokens(0)),
      "budgetTokens"     -> (() => b.budgetTokens(OptionalInt.of(0)))
    )
    rejected.foreach { (name, set) =>
      withClue(name)(intercept[IllegalArgumentException](set()).getMessage should startWith(s"$name must be"))
    }
    CompletionOptionsCheck.rejectedTemperature() shouldBe "temperature must be at least 0, got -1.0"
  }

  it should "reject a null with NullPointerException at once" in {
    val b = JCompletionOptions.builder()
    intercept[NullPointerException](b.reasoning(null: JReasoningEffort))
    intercept[NullPointerException](b.reasoning(null: Optional[JReasoningEffort]))
    intercept[NullPointerException](b.maxTokens(null: OptionalInt))
    intercept[NullPointerException](b.budgetTokens(null: OptionalInt))
  }

  "JCompletionOptions" should "be a value: equal, with equal hash codes, when every setting is" in {
    val a = CompletionOptionsCheck.everySetting()
    val b = CompletionOptionsCheck.everySetting()
    a shouldBe b
    a.hashCode shouldBe b.hashCode
    a should not be JCompletionOptions.builder().build()
    a.equals("options") shouldBe false
  }

  it should "print every setting" in {
    CompletionOptionsCheck.everySetting().toString shouldBe
      "JCompletionOptions(temperature=0.2, topP=0.9, maxTokens=512, presencePenalty=0.5, frequencyPenalty=-0.5, " +
      "reasoning=HIGH, budgetTokens=4096)"
    JCompletionOptions.builder().build().toString shouldBe
      "JCompletionOptions(temperature=0.7, topP=1.0, maxTokens=unset, presencePenalty=0.0, frequencyPenalty=0.0, " +
      "reasoning=unset, budgetTokens=unset)"
    // an unset level does not print like the level NONE
    JCompletionOptions.builder().reasoning(JReasoningEffort.NONE).build().toString should include("reasoning=NONE,")
  }

  "JLlmClient.complete(Conversation, JCompletionOptions)" should "fail, not throw, for a null argument" in {
    val client       = new JLlmClient(new Capturing)
    val conversation = ConversationBuilder.create().user("hi").build()

    val noOptions = client.complete(conversation, null: JCompletionOptions)
    noOptions.isFailure shouldBe true
    noOptions.getError().error shouldBe a[ValidationError]
    noOptions.getError().getMessage should include("options")

    val noConversation = client.complete(null, JCompletionOptions.builder().build())
    noConversation.getError().getMessage should include("conversation")

    val neither = client.complete(null, null: JCompletionOptions)
    neither.getError().getMessage should include("conversation")
  }

  it should "send the same request as the Scala overload with the same options" in {
    val viaJava  = new Capturing
    val viaScala = new Capturing
    val options  = CompletionOptionsCheck.everySetting()
    val conv     = ConversationBuilder.create().user("hi").build()
    new JLlmClient(viaJava).complete(conv, options)
    new JLlmClient(viaScala).complete(conv, options.underlying)
    viaJava.received.toList shouldBe viaScala.received.toList
  }
}
