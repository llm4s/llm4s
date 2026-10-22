package org.llm4s.javaapi

import org.llm4s.javaapi.StreamFixtures.*
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.ToolCallInfo
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** Agent tools defined from Java (#1484): `JTool.builder`, `JTool.fromAnnotated`, `Llm4s.createAgentWithTools`. */
class JToolSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def beforeEach(): Unit = ToolsCheck.CALLS.clear()

  /** A model that asks for `call` first, then answers with what the tool returned. */
  private def callingThen(call: ToolCall): org.llm4s.llmconnect.LLMClient = {
    val n = new AtomicInteger(0)
    def reply(conversation: Conversation): Result[Completion] =
      if (n.getAndIncrement() == 0) {
        val message = AssistantMessage(contentOpt = None, toolCalls = Seq(call))
        Right(Completion("id", 0L, "", "m", message, toolCalls = List(call)))
      } else {
        val result = conversation.messages.collect { case t: ToolMessage => t.content }.lastOption.getOrElse("none")
        Right(completion(s"tool said: $result"))
      }
    val last = new java.util.concurrent.atomic.AtomicReference[Conversation]()
    new org.llm4s.llmconnect.LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] = { last.set(c); reply(c) }
      def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      def getContextWindow(): Int     = 4096
      def getReserveCompletion(): Int = 256
    }
  }

  private def run(call: ToolCall): String =
    ToolsCheck.agent(Llm4s.wrapClient(callingThen(call))).get().run("go").get().answer().orElseThrow()

  "An annotated Java tool" should "be called with the model's arguments, and its result returned to the model" in {
    run(
      ToolCall("c1", "weather", ujson.Obj("city" -> "Paris", "unit" -> "CELSIUS"))
    ) shouldBe "tool said: Sunny in Paris, 21C"
    ToolsCheck.CALLS.asScala.toList shouldBe List("weather:Paris,CELSIUS,null")
  }

  it should "receive an optional argument the model passed" in {
    run(ToolCall("c1", "weather", ujson.Obj("city" -> "Oslo", "unit" -> "FAHRENHEIT", "days" -> 2)))
    ToolsCheck.CALLS.asScala.toList shouldBe List("weather:Oslo,FAHRENHEIT,2")
  }

  it should "turn an exception into a tool error the model sees, and keep the agent running" in {
    run(ToolCall("c1", "explode", ujson.Obj("why" -> "test"))) should include("IllegalStateException: cannot: test")
    ToolsCheck.CALLS.asScala.toList shouldBe List("explode:test")
  }

  it should "refuse a missing, mistyped or unknown argument as a tool error, without calling the method" in {
    // the agent checks the arguments against the tool's schema before the tool runs
    run(ToolCall("c1", "weather", ujson.Obj("unit" -> "CELSIUS"))) should include("required property missing")
    run(ToolCall("c1", "weather", ujson.Obj("city" -> 5, "unit" -> "CELSIUS"))) should include("expected string")
    run(ToolCall("c1", "weather", ujson.Obj("city" -> "x", "unit" -> "KELVIN"))) should include("KELVIN")
    ToolsCheck.CALLS.asScala.toList shouldBe empty
  }

  it should "check the arguments itself when called directly, outside an agent" in {
    val weather = JTool.fromAnnotated(new ToolsCheck.WeatherTools).get().asScala.find(_.name() == "weather").get
    def error(args: ujson.Value) = weather.function.execute(args).left.map(_.getMessage).left.getOrElse("")
    error(ujson.Obj("unit" -> "CELSIUS")) should include("missing required parameter 'city'")
    error(ujson.Obj("city" -> 5, "unit" -> "CELSIUS")) should include("parameter 'city': expected a string")
    error(ujson.Obj("city" -> "x", "unit" -> "KELVIN")) should include("'KELVIN' is not one of CELSIUS, FAHRENHEIT")
    error(ujson.Obj("city" -> "x", "unit" -> "CELSIUS", "days" -> 1.5)) should include(
      "parameter 'days': expected an integer"
    )
    error(ujson.Arr(1, 2)) should include("expects an object of arguments")
    error(ujson.Obj("city" -> "x", "unit" -> "CELSIUS", "days" -> ujson.Null)) shouldBe ""
    ToolsCheck.CALLS.asScala.toList shouldBe List("weather:x,CELSIUS,null")
  }

  "A lambda Java tool" should "read each kind of argument, and return JSON for a map" in {
    run(ToolCall("c1", "add", ujson.Obj("a" -> 2, "b" -> 0.5))) shouldBe "tool said: 2.5"
    run(ToolCall("c1", "add", ujson.Obj("a" -> 2, "b" -> 0.5, "round" -> true))) shouldBe """tool said: {"sum":3}"""
    ToolsCheck.CALLS.asScala.toList shouldBe List("add:2,0.5,false", "add:2,0.5,true")
    val add                      = ToolsCheck.calculator().get()
    def error(args: ujson.Value) = add.function.execute(args).left.map(_.getMessage).left.getOrElse("")
    error(ujson.Obj("a" -> 2, "b" -> "x")) should include("parameter 'b': expected a number")
    error(ujson.Obj("a" -> 1e30, "b" -> 1)) should include("an integer that fits a long")
    error(ujson.Obj("a" -> 1, "b" -> 1, "round" -> "yes")) should include("parameter 'round': expected a boolean")
  }

  it should "turn its handler's exception into a tool error naming it" in {
    val tool = JTool.builder("boom", "Fails").handler(_ => throw new IllegalArgumentException("no luck")).build().get()
    tool.function.execute(ujson.Obj()).left.map(_.getMessage).left.getOrElse("") should include(
      "IllegalArgumentException: no luck"
    )
  }

  "JTool.builder" should "describe the tool and its parameters to the model" in {
    val tool = ToolsCheck.calculator().get()
    tool.name() shouldBe "add"
    tool.description() shouldBe "Adds two numbers"
    tool.toString shouldBe "JTool(add)"
    val schema = tool.function.toOpenAITool(strict = false)("function")("parameters")
    schema("properties")("a")("type").str shouldBe "integer"
    schema("properties")("b")("type").str shouldBe "number"
    schema("properties")("round")("type").str shouldBe "boolean"
    schema("required").arr.map(_.str).toSet shouldBe Set("a", "b")
  }

  it should "derive every supported type's schema" in {
    val tool = JTool
      .builder("t", "d")
      .param("s", classOf[String], "")
      .param("i", classOf[Int], "")
      .param("bi", classOf[Integer], "")
      .param("sh", classOf[Short], "")
      .param("by", classOf[java.lang.Byte], "")
      .param("f", classOf[Float], "")
      .param("u", classOf[ToolsCheck.Unit], "")
      .handler(args =>
        s"${args.getString("s")}${args
            .getInt("i")}${args.get("bi", classOf[Integer])}${args.get("sh", classOf[java.lang.Short])}" +
          s"${args.get("by", classOf[java.lang.Byte])}${args.get("f", classOf[java.lang.Float])}${args
              .get("u", classOf[ToolsCheck.Unit])}"
      )
      .build()
      .get()
    tool.function.execute(
      ujson.Obj("s" -> "a", "i" -> 1, "bi" -> 2, "sh" -> 3, "by" -> 4, "f" -> 1.5, "u" -> "CELSIUS")
    ) shouldBe Right(ujson.Str("a12341.5CELSIUS"))
    tool.function
      .execute(ujson.Obj("s" -> "a", "i" -> 1, "bi" -> 2, "sh" -> 70000, "by" -> 4, "f" -> 1, "u" -> "CELSIUS"))
      .left
      .map(_.getMessage)
      .left
      .getOrElse("") should include("fits a short")
    tool.function
      .execute(ujson.Obj("s" -> "a", "i" -> 1, "bi" -> 2, "sh" -> 1, "by" -> 400, "f" -> 1, "u" -> "CELSIUS"))
      .left
      .map(_.getMessage)
      .left
      .getOrElse("") should include("fits a Byte")
    tool.function
      .execute(ujson.Obj("s" -> "a", "i" -> 1, "bi" -> 2, "sh" -> 1, "by" -> 4, "f" -> 1, "u" -> 1))
      .left
      .map(_.getMessage)
      .left
      .getOrElse("") should include("parameter 'u': expected a string")
  }

  it should "refuse a tool it cannot build, as a failed result" in {
    def fails(b: JTool.Builder) = b.build().getError().getMessage
    val ok                      = JTool.builder("t", "d").handler(_ => "x")
    fails(JTool.builder(" ", "d").handler(_ => "x")) should include("needs a name")
    fails(JTool.builder(null, "d").handler(_ => "x")) should include("needs a name")
    fails(JTool.builder("t", "d")) should include("has no handler")
    fails(ok.handler(null)) should include("has no handler")
    fails(ok.param("a", classOf[String], "").param("a", classOf[Int], "")) should include(
      "declares parameter 'a' twice"
    )
    fails(ok.param("m", classOf[java.util.Map[?, ?]], "")) should include("java.util.Map is not supported")
    fails(ok.param("n", null, "")) should include("null is not supported")
    fails(ok.param(" ", classOf[String], "")) should include("has a parameter with no name")
    fails(ok.optionalParam("p", classOf[Int], "")) should include("cannot be a primitive")
    JTool.builder("t", null).param("a", classOf[String], null).handler(_ => "x").build().isSuccess shouldBe true
  }

  "JTool.fromAnnotated" should "refuse a target with no tool, no target, and an unnamed parameter" in {
    JTool.fromAnnotated(new ToolsCheck.NoTools).getError().getMessage should include("has no public @Tool method")
    JTool.fromAnnotated(null).getError().getMessage should include("target")
    JTool.fromAnnotated(new ToolsCheck.Unnamed).getError().getMessage should include(
      "give it @Param(name = ...) or compile with javac -parameters"
    )
  }

  it should "use the compiled parameter names of a class built with javac -parameters" in {
    import javax.tools.{ JavaFileObject, SimpleJavaFileObject, ToolProvider }
    val source =
      """package user;
        |import org.llm4s.javaapi.Tool;
        |public class Greeter {
        |  @Tool(description = "Greets someone")
        |  public String greet(String person) { return "Hello, " + person; }
        |}
        |""".stripMargin
    val file =
      new SimpleJavaFileObject(java.net.URI.create("string:///user/Greeter.java"), JavaFileObject.Kind.SOURCE) {
        override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
      }
    val out     = java.nio.file.Files.createTempDirectory("greeter")
    val classes = java.nio.file.Paths.get(classOf[Tool].getProtectionDomain.getCodeSource.getLocation.toURI).toString
    ToolProvider.getSystemJavaCompiler
      .getTask(
        null,
        null,
        null,
        java.util.List.of("-parameters", "-classpath", classes, "-d", out.toString),
        null,
        java.util.List.of(file)
      )
      .call() shouldBe true
    val loader  = new java.net.URLClassLoader(Array(out.toUri.toURL), getClass.getClassLoader)
    val greeter = loader.loadClass("user.Greeter").getDeclaredConstructor().newInstance()
    val tool    = JTool.fromAnnotated(greeter).get().get(0)
    tool.name() shouldBe "greet"
    tool.function.execute(ujson.Obj("person" -> "Ada")) shouldBe Right(ujson.Str("Hello, Ada"))
  }

  it should "name each tool from its annotation, or its method" in {
    JTool.fromAnnotated(new ToolsCheck.WeatherTools).get().asScala.map(_.name()).toList shouldBe List(
      "explode",
      "weather"
    )
  }

  "Llm4s.createAgentWithTools" should "refuse a null, a null tool or two tools with one name, as a failed result" in {
    val client = Llm4s.wrapClient(answering("x"))
    val tool   = ToolsCheck.calculator().get()
    Llm4s.createAgentWithTools(null, java.util.List.of(tool)).getError().getMessage should include("client")
    Llm4s.createAgentWithTools(client, null).getError().getMessage should include("tools")
    Llm4s.createAgentWithTools(client, java.util.Arrays.asList(tool, null)).getError().getMessage should include("null")
    Llm4s.createAgentWithTools(client, java.util.List.of(tool, tool)).getError().getMessage should include(
      "two tools are named 'add'"
    )
    Llm4s.createAgentWithTools(client, java.util.List.of()).get().run("hi").get().answer().orElseThrow() shouldBe "x"
  }

  it should "refuse tools the agent cannot offer, such as an invalid name" in {
    val bad = JTool.builder("not a valid name!", "d").handler(_ => "x").build().get()
    Llm4s.createAgentWithTools(Llm4s.wrapClient(answering("x")), java.util.List.of(bad)).isFailure shouldBe true
  }

  "JToolArgs" should "answer has, get and asMap, and print its values" in {
    val args = new JToolArgs(FacadeToken.INSTANCE, java.util.Map.of("a", "1"), None)
    args.has("a") shouldBe true
    args.toolCallId() shouldBe java.util.Optional.empty()
    args.idempotencyKey() shouldBe java.util.Optional.empty()
    args.has("b") shouldBe false
    args.getString("b") shouldBe null
    args.asMap().get("a") shouldBe "1"
    args.toString shouldBe "JToolArgs({a=1})"
  }

  it should "carry the call an agent runs: its id and idempotency key (#1740)" in {
    val args = new JToolArgs(FacadeToken.INSTANCE, java.util.Map.of(), Some(ToolCallInfo("c1", "k1")))
    args.toolCallId() shouldBe java.util.Optional.of("c1")
    args.idempotencyKey() shouldBe java.util.Optional.of("k1")
  }

  "A Java tool run by an agent" should "read the call's id and idempotency key, annotated and lambda alike (#1740)" in {
    val charged  = run(ToolCall("c7", "charge", ujson.Obj("cents" -> 5))).stripPrefix("tool said: ")
    val refunded = run(ToolCall("c8", "refund", ujson.Obj("cents" -> 5))).stripPrefix("tool said: ")
    Seq(charged, refunded).foreach(_ should fullyMatch regex "[0-9a-f]{64}")
    charged should not be refunded
    ToolsCheck.CALLS.asScala.toList shouldBe List(s"charge:5,c7,$charged", s"refund:5,c8,$refunded")
  }

  it should "see no id or key outside an agent, and the call it is given through execute(args, call)" in {
    val charge = JTool.fromAnnotated(new ToolsCheck.KeyedTools).get().get(0)
    val refund = ToolsCheck.refund().get()
    Seq(charge, refund).foreach { tool =>
      tool.function.execute(ujson.Obj("cents" -> 1)) shouldBe Right(ujson.Str("none"))
      tool.function.execute(ujson.Obj("cents" -> 2), ToolCallInfo("id", "key")) shouldBe Right(ujson.Str("key"))
    }
    ToolsCheck.CALLS.asScala.toList shouldBe List(
      "charge:1,none,none",
      "charge:2,id,key",
      "refund:1,none,none",
      "refund:2,id,key"
    )
  }

  "An annotated tool's JToolArgs parameter" should "not be shown to the model" in {
    val charge = JTool.fromAnnotated(new ToolsCheck.KeyedTools).get().get(0)
    val schema = charge.function.toOpenAITool(strict = false)("function")("parameters")
    schema("properties").obj.keySet shouldBe Set("cents")
    schema("required").arr.map(_.str).toList shouldBe List("cents")
  }

  "A handler's result" should "become JSON: text, numbers, booleans, enums, maps, lists, null and anything else" in {
    JTools.toJson(null) shouldBe ujson.Null
    JTools.toJson("s") shouldBe ujson.Str("s")
    JTools.toJson(java.lang.Boolean.TRUE) shouldBe ujson.Bool(true)
    JTools.toJson(Integer.valueOf(3)) shouldBe ujson.Num(3)
    JTools.toJson(ToolsCheck.Unit.CELSIUS) shouldBe ujson.Str("CELSIUS")
    JTools.toJson(java.util.Map.of("k", java.util.List.of(1, 2))) shouldBe ujson.Obj("k" -> ujson.Arr(1, 2))
    JTools.toJson(new Object { override def toString = "other" }) shouldBe ujson.Str("other")
  }

  "The Java guide's Tools snippet" should "run an agent that calls an annotated tool" in {
    GuideSnippets
      .tools(Llm4s.wrapClient(callingThen(ToolCall("c1", "weather", ujson.Obj("city" -> "Paris")))))
      .answer()
      .orElseThrow() shouldBe "tool said: Sunny in Paris"
  }
}
