package org.llm4s.agent

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.AgentOutputSpec.{ invoiceSchema, Invoice }
import org.llm4s.agent.graph.GraphError
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters._

/**
 * `AgentBuilder.withOutputCheck` (#2920): an application check on the decoded structured answer, inside `withOutput`'s
 * validation and sharing its one corrective retry.
 */
class AgentOutputCheckSpec extends AnyFlatSpec with TestTimeLimit with Matchers {
  import AgentOutputCheckSpec._

  private def answers(texts: String*): ScriptedLLMClient = ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  private val acme      = """{"customer":"Acme","total":12.5}"""
  private val beta      = """{"customer":"Beta","total":3}"""
  private val unbilled  = """{"customer":"Nobody","total":1}"""
  private val notSchema = """{"customer":"Acme","total":"lots"}"""

  /** Refuses an invoice for a customer the application does not know: a rule the schema cannot express. */
  private def knownCustomer(invoice: Invoice): Seq[String] =
    if (Set("Acme", "Beta")(invoice.customer)) Nil
    else Seq(s"customer '${invoice.customer}' is not a known customer: use Acme or Beta")

  private def checked(client: ScriptedLLMClient): AgentBuilder =
    Agent.builder("extractor", client).withOutput(invoiceSchema).withOutputCheck[Invoice](knownCustomer)

  private def invalid(result: org.llm4s.types.Result[AgentResult]): Vector[String] = result.error match {
    case GraphError.OutputInvalid(errors) => errors
    case other                            => fail(s"expected OutputInvalid, got $other")
  }

  "withOutputCheck" should "accept an answer its check has no problem with, in one model call, giving it the decoded value" in {
    val seen   = new CopyOnWriteArrayList[Invoice]()
    val client = answers(acme)
    val agent = built(
      Agent.builder("extractor", client).withOutput(invoiceSchema).withOutputCheck[Invoice] { invoice =>
        seen.add(invoice)
        Nil
      }
    )

    agent.run("bill Acme").value.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
    client.callCount shouldBe 1
    seen.asScala.toVector shouldBe Vector(Invoice("Acme", 12.5))
  }

  it should "send a check's problems back as schema errors are, and store only the corrected answer" in {
    val client = answers(unbilled, beta)
    val result = built(checked(client)).run("bill someone").value

    result.decodeOutput[Invoice] shouldBe Right(Invoice("Beta", 3))
    client.callCount shouldBe 2
    val retry = client.sent(1)
    retry.dropRight(2) shouldBe client.sent(0)
    retry(retry.size - 2) shouldBe AssistantMessage(unbilled)
    // the same corrective message a schema error gets, with the check's own wording as the problem
    retry.last shouldBe UserMessage(OutputCorrection.message(Vector(knownCustomer(Invoice("Nobody", 1)).head)))
    result.messages shouldBe Vector(UserMessage("bill someone"), AssistantMessage(beta))
  }

  it should "end the turn Left(OutputInvalid) with the check's problems when the corrected answer fails it too" in {
    val client = answers(unbilled, """{"customer":"Nemo","total":2}""", acme)
    val agent  = built(checked(client))

    invalid(agent.run("bill someone")) shouldBe Vector("customer 'Nemo' is not a known customer: use Acme or Beta")
    // the one retry, not one of its own
    client.callCount shouldBe 2
  }

  it should "share the retry with the schema: a schema error, then a check's problem, is two calls and OutputInvalid" in {
    val client = answers(notSchema, unbilled, acme)

    invalid(built(checked(client)).run("bill someone")) shouldBe
      Vector("customer 'Nobody' is not a known customer: use Acme or Beta")
    client.callCount shouldBe 2
  }

  it should "share it the other way round too: a check's problem, then a schema error" in {
    val client = answers(unbilled, notSchema, acme)

    invalid(built(checked(client)).run("bill someone")).head should include("$.total")
    client.callCount shouldBe 2
  }

  it should "not run on an answer the schema refuses" in {
    val runs   = new java.util.concurrent.atomic.AtomicInteger()
    val client = answers(notSchema, "not json", acme)
    val agent = built(
      Agent.builder("extractor", client).withOutput(invoiceSchema).withOutputCheck[Invoice] { _ =>
        runs.incrementAndGet()
        Nil
      }
    )

    agent.run("bill Acme").isLeft shouldBe true
    runs.get shouldBe 0
  }

  it should "run every check added, in order, and report all their problems at once" in {
    val client = answers(unbilled, acme)
    val agent = built(
      checked(client)
        .withOutputCheck[Invoice](invoice => if (invoice.total < 10) Seq("total: must be at least 10") else Nil)
        .withOutputJsonCheck(json => if (json.obj.contains("currency")) Nil else Seq("currency: say which"))
    )

    // the second answer still lacks a currency, so the turn ends with what the checks said of it
    invalid(agent.run("bill someone")) shouldBe Vector("currency: say which")
    client.sent(1).last.content should (include("is not a known customer")
      .and(include("total: must be at least 10"))
      .and(include("currency: say which")))
    client.sent(1).last.content.indexOf("known customer") should be < client.sent(1).last.content.indexOf("total: must")
  }

  it should "bound the problems as schema errors are bounded" in {
    val client = answers(acme, acme)
    val agent = built(
      Agent
        .builder("extractor", client)
        .withOutput(invoiceSchema)
        .withOutputCheck[Invoice](_ => Seq.tabulate(50)(n => s"problem $n " + "x" * 1000))
    )

    val errors = invalid(agent.run("bill Acme"))
    errors should have size 20
    errors.foreach(_.length shouldBe 300)
  }

  "withOutputJsonCheck" should "check the answer's JSON as the schema validated it" in {
    val seen   = new CopyOnWriteArrayList[ujson.Value]()
    val client = answers(s"Here you go:\n```json\n$acme\n```")
    val agent = built(
      Agent.builder("extractor", client).withOutput(invoiceSchema).withOutputJsonCheck { json =>
        seen.add(json)
        Nil
      }
    )

    agent.run("bill Acme").value.answer shouldBe Some(acme)
    seen.asScala.toVector shouldBe Vector(ujson.read(acme))
  }

  "A check typed apart from the format" should "count an answer that does not read as its type as a problem" in {
    val client = answers(acme, acme)
    val agent = built(
      Agent.builder("extractor", client).withOutput(invoiceSchema).withOutputCheck[Dated](_ => Nil)
    )

    val errors = invalid(agent.run("bill Acme"))
    errors should have size 1
    errors.head should startWith("$: does not read as the type its check expects")
    client.sent(1).last.content should include("does not read as the type its check expects")
  }

  "build" should "refuse a check on an agent with no output schema" in {
    val refused = Agent.builder("extractor", answers()).withOutputCheck[Invoice](_ => Nil).build()

    refused.left.map(_.message).swap.getOrElse("") should (include("withOutputCheck").and(
      include("withOutput(schema)")
    ))
    Agent.builder("extractor", answers()).withOutputJsonCheck(_ => Nil).build().isLeft shouldBe true
  }

  it should "accept the check before the schema, and keep it when the schema is replaced" in {
    val client = answers(unbilled, unbilled)
    val agent = built(
      Agent
        .builder("extractor", client)
        .withOutputCheck[Invoice](knownCustomer)
        .withOutput(AgentOutputSpec.partialSchema)
        .withOutput(invoiceSchema)
    )

    invalid(agent.run("bill someone")).head should include("not a known customer")
  }

  "A check that throws" should "fail the turn rather than reach the model" in {
    val client = answers(acme, acme)
    val agent = built(
      Agent
        .builder("extractor", client)
        .withOutput(invoiceSchema)
        .withOutputCheck[Invoice](_ => throw new IllegalStateException("check broke"))
    )

    agent.run("bill Acme").isLeft shouldBe true
    client.callCount shouldBe 1
  }
}

object AgentOutputCheckSpec {

  /** A type the invoice answers do not read as: it needs a date they lack. */
  final case class Dated(customer: String, total: Double, date: String) derives ReadWriter
}
