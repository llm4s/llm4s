package org.llm4s.agent.graph.tool

import org.llm4s.llmconnect.model.ToolCallId
import org.llm4s.agent.graph._
import org.llm4s.error.ValidationError
import org.llm4s.toolapi.{ IdempotencyKey, Schema, SchemaDefinition, ToolBuilder, ToolFunction, ToolHints }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

object AgentToolFixtures {
  final case class Search(query: String) derives ReadWriter
  final case class Confirm(prompt: String) derives ReadWriter
  final case class Reply(ok: Boolean) derives ReadWriter

  val searchSchema: SchemaDefinition[Search] =
    Schema.`object`[Search]("Search").withRequiredField("query", Schema.string("The query"))
}

class AgentToolContractSpec extends AnyFlatSpec with Matchers with EitherValues {
  import AgentToolFixtures._

  private def spec(name: String): AgentToolSpec[Search] = AgentToolSpec[Search](name, "Searches", searchSchema)

  private val context = ToolContext(
    new RunContext(
      RunConfig(),
      RunPosition(ThreadId("t"), RunId("r"), "", TaskId("task"), NodeId("node"), 0),
      NodeEventSink.none
    ),
    ToolCallId("call-1"),
    IdempotencyKey("key-1"),
    ThreadState.empty(Map.empty),
    approved = false
  )

  "AgentToolSpec.apply" should "accept a valid name" in {
    spec("search_web-2").name shouldBe "search_web-2"
    spec("a" * 64).name shouldBe "a" * 64
  }

  it should "throw for an empty name, a 65-character name and a name with a space" in {
    an[IllegalArgumentException] should be thrownBy spec("")
    an[IllegalArgumentException] should be thrownBy spec("a" * 65)
    an[IllegalArgumentException] should be thrownBy spec("search web")
  }

  it should "default to no extra validation and no question" in {
    val s = spec("search")
    s.question shouldBe None
    s.validateDecoded(Search("x")) shouldBe Right(())
  }

  "argumentSchema" should "be the schema rendered non-strict, as core's Anthropic and Gemini clients send it" in {
    spec("search").argumentSchema shouldBe searchSchema.toJsonSchema(false)
  }

  it should "require only the required fields, so a call may omit an optional one" in {
    val schema = Schema
      .`object`[Search]("Search")
      .withRequiredField("query", Schema.string("The query"))
      .withOptionalField("limit", Schema.integer("At most this many"))
    val argumentSchema = AgentToolSpec[Search]("search", "Searches", schema).argumentSchema
    val validator      = ToolArgumentValidator.default
    validator.validate(argumentSchema, ujson.Obj("query" -> "x")) shouldBe Vector.empty
    validator.validate(argumentSchema, ujson.Obj("query" -> "x", "limit" -> 3)) shouldBe Vector.empty
    validator.validate(argumentSchema, ujson.Obj("limit" -> 3)) shouldBe Vector("$.query: required property missing")
    validator.validate(argumentSchema, ujson.Obj("query" -> "x", "extra" -> 1)) shouldBe
      Vector("$.extra: property not allowed")
  }

  "toolDefinition" should "have the shape of ToolFunction.toolDefinition(strict = true)" in {
    val function = ToolFunction[Search, String]("search", "Searches", searchSchema, _ => Right("ok"))
    spec("search").toolDefinition shouldBe function.toolDefinition(strict = true)
  }

  it should "hand out a fresh copy each time, so editing one definition changes no other" in {
    val s          = spec("search")
    val definition = s.toolDefinition
    definition("function")("parameters").obj.remove("additionalProperties")
    definition("function")("parameters")("properties").obj.remove("query")
    s.argumentSchema shouldBe searchSchema.toJsonSchema(false)
    s.toolDefinition shouldBe ToolFunction[Search, String]("search", "Searches", searchSchema, _ => Right("ok"))
      .toolDefinition(strict = true)
  }

  "withValidation" should "store the check" in {
    val s = spec("search").withValidation(a => Either.cond(a.query.nonEmpty, (), ValidationError("query", "empty")))
    s.validateDecoded(Search("x")) shouldBe Right(())
    s.validateDecoded(Search("")).left.value.message should include("empty")
    s.name shouldBe "search"
  }

  "AgentToolSpec's setters" should "replace the name, description and schema" in {
    val other = Schema.`object`[Search]("Other").withRequiredField("query", Schema.string("Another query"))
    val s     = spec("search").withName("lookup").withDescription("Looks up").withSchema(other)
    s.name shouldBe "lookup"
    s.description shouldBe "Looks up"
    s.schema shouldBe other
    s.argumentSchema shouldBe other.toJsonSchema(false)
  }

  "withName" should "refuse an invalid name, as apply does" in {
    an[IllegalArgumentException] should be thrownBy spec("search").withName("search web")
  }

  it should "move the default resource to the new name, and keep declared resources" in {
    spec("search").withName("lookup").resourcesOf(Search("q")) shouldBe
      Vector(ToolResource(Operation.Invoke, "lookup"))
    val declared = Vector(ToolResource(Operation.Read, "/docs"))
    spec("search").withResources(_ => declared).withName("lookup").resourcesOf(Search("q")) shouldBe declared
  }

  "withTimeout" should "set the timeout from a value or an Option, and clear it with None" in {
    import scala.concurrent.duration.*
    spec("search").withTimeout(2.seconds).timeout shouldBe Some(2.seconds)
    spec("search").withTimeout(Some(3.seconds)).timeout shouldBe Some(3.seconds)
    spec("search").withTimeout(2.seconds).withTimeout(None).timeout shouldBe None
    an[IllegalArgumentException] should be thrownBy spec("search").withTimeout(Some(Duration.Zero))
  }

  "withLocks" should "set the locks from a function or an Option, and clear them with None" in {
    val locks: Search => Set[LockKey] = s => Set(LockKey("search", s.query))
    spec("search").withLocks(locks).locksOf.map(_(Search("q"))) shouldBe Some(Set(LockKey("search", "q")))
    spec("search").withLocks(Some(locks)).locksOf.map(_(Search("q"))) shouldBe Some(Set(LockKey("search", "q")))
    spec("search").withLocks(locks).withLocks(None).locksOf shouldBe None
  }

  "AgentTool.apply" should "run its function and declare its writes" in {
    val key = StateKey.replace[Int]("count", 0)
    val tool =
      AgentTool(spec("search"), Set(key))((args, _) => ToolOutcome.success(ToolContent.Json(ujson.Str(args.query))))
    tool.writes shouldBe Set(key)
    tool.execute(Search("hi"), context) shouldBe ToolOutcome.success(ToolContent.Json(ujson.Str("hi")))
  }

  it should "refuse answers when it does not ask" in {
    val tool = AgentTool(spec("search"))((_, _) => ToolOutcome.Error("x"))
    tool.writes shouldBe Set.empty
    tool.resumeErased(Search("hi"), Confirm("?"), Reply(true), context) shouldBe
      ToolOutcome.Error("tool 'search' does not take answers")
  }

  "AgentTool.Asking" should "carry its question codecs on its spec and resume typed" in {
    val tool = new AgentTool.Asking[Search, Confirm, Reply](spec("search")) {
      def execute(args: Search, context: ToolContext): ToolOutcome = ask(Confirm(args.query))
      def resume(args: Search, question: Confirm, answer: Reply, context: ToolContext): ToolOutcome =
        ToolOutcome.success(ToolContent.Json(ujson.Str(s"${question.prompt}:${answer.ok}")))
    }
    tool.spec.question shouldBe Some(ToolQuestion(summon[ReadWriter[Confirm]], summon[ReadWriter[Reply]]))
    tool.spec.name shouldBe "search"
    tool.execute(Search("go"), context) shouldBe ToolOutcome.Ask(Confirm("go"))
    tool.resumeErased(Search("go"), Confirm("go"), Reply(true), context) shouldBe
      ToolOutcome.success(ToolContent.Json(ujson.Str("go:true")))
  }

  "AgentTool.fromToolFunction" should "use the function's name, description and schema" in {
    val schema = Schema.`object`[Map[String, Any]]("Echo").withRequiredField("message", Schema.string("Message"))
    val function = ToolBuilder[Map[String, Any], Search]("echo", "Echoes", schema)
      .withHandler(extractor => extractor.getString("message").map(Search(_)))
      .buildSafe()
      .value
    val tool = AgentTool.fromToolFunction(function)
    tool.spec.name shouldBe "echo"
    tool.spec.description shouldBe "Echoes"
    tool.spec.argumentSchema shouldBe schema.toJsonSchema(false)
    tool.spec.toolDefinition shouldBe function.toolDefinition(strict = true)
    tool.writes shouldBe Set.empty
    tool.execute(ujson.Obj("message" -> "hi"), context) shouldBe ToolOutcome.success(
      ToolContent.Json(ujson.Obj("query" -> "hi"))
    )
    tool.execute(ujson.Obj(), context) match {
      case ToolOutcome.Error(message) => message should include("echo")
      case other                      => fail(s"expected an error, got $other")
    }
  }

  it should "not throw for a function whose name is invalid, leaving ToolSet.of to refuse it" in {
    val function = ToolFunction[Map[String, Any], String]("bad name", "d", Schema.`object`("o"), _ => Right("x"))
    AgentTool.fromToolFunction(function).spec.name shouldBe "bad name"
  }

  "ToolHints" should "default to the conservative MCP values" in {
    val hints = spec("search").hints
    hints shouldBe ToolHints.default
    (hints.readOnly, hints.destructive, hints.idempotent, hints.openWorld) shouldBe (false, true, false, true)
  }

  it should "be stored by withHints and kept by withValidation" in {
    val hinted = spec("search").withHints(ToolHints(readOnly = true))
    hinted.hints shouldBe ToolHints(readOnly = true)
    hinted.hints.readOnly shouldBe true
    hinted.withValidation(_ => Right(())).hints shouldBe hinted.hints
  }

  it should "be kept by an Asking tool's spec" in {
    val hinted = spec("search").withHints(ToolHints(readOnly = true))
    val tool = new AgentTool.Asking[Search, Confirm, Reply](hinted) {
      def execute(args: Search, context: ToolContext): ToolOutcome = ToolOutcome.success(ToolContent.Json(ujson.Null))
      def resume(args: Search, question: Confirm, answer: Reply, context: ToolContext): ToolOutcome =
        ToolOutcome.success(ToolContent.Json(ujson.Null))
    }
    tool.spec.hints shouldBe hinted.hints
    tool.spec.question should not be empty
  }

  it should "be the default for a tool adapted from a ToolFunction" in {
    val function = ToolFunction[Map[String, Any], String]("f", "d", Schema.`object`("o"), _ => Right("x"))
    AgentTool.fromToolFunction(function).spec.hints shouldBe ToolHints.default
  }

  it should "be the hints given for a tool adapted from a ToolFunction with them (an MCP tool's annotations)" in {
    val function = ToolFunction[Map[String, Any], String]("f", "d", Schema.`object`("o"), _ => Right("x"))
    val hints    = ToolHints(readOnly = true, openWorld = false)

    val tool = AgentTool.fromToolFunction(function, hints)

    tool.spec.hints shouldBe hints
    tool.spec.name shouldBe "f" // the rest of the adaptation is unchanged
    ToolSet.of(tool).map(_.toolFunctions) shouldBe Right(Seq(function))
  }

  it should "change only the field a setter names" in {
    val base = ToolHints.default
    base.withReadOnly(true) shouldBe ToolHints(readOnly = true)
    base.withDestructive(false) shouldBe ToolHints(destructive = false)
    base.withIdempotent(true) shouldBe ToolHints(idempotent = true)
    base.withOpenWorld(false) shouldBe ToolHints(openWorld = false)
  }

  "GraphError.ToolFailed" should "name the tool, the call and the cause" in {
    val error = GraphError.ToolFailed(ToolName("search"), ToolCallId("call-1"), ValidationError("x", "boom"))
    error.message should include("search")
    error.message should include("call-1")
    error.message should include("boom")
    error shouldBe a[org.llm4s.error.NonRecoverableError]
  }
}
