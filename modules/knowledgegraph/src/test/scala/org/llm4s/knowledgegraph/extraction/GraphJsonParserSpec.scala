package org.llm4s.knowledgegraph.extraction

import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.llm4s.error.ProcessingError
import org.llm4s.knowledgegraph.{ Edge, Graph, Node }
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/**
 * `GraphJsonParser` turns the JSON an LLM returns into a [[Graph]]. Both the generator and the
 * schema-guided extractor depend on it, so these tests pin the contract at its boundary: what a
 * valid document becomes, which wrapping it tolerates, and which `Left` each kind of bad input
 * gives.
 *
 * Messages are asserted by a stable fragment only (the field or phase they name), never by their
 * full wording.
 */
class GraphJsonParserSpec extends AnyFunSuite with Matchers {

  private val ErrorCode = "test_extraction"

  private def parse(input: String): Result[Graph] = GraphJsonParser.parse(input, ErrorCode)

  private val validJson: String =
    """{
      |  "nodes": [
      |    {"id": "alice", "label": "Person",
      |     "properties": {"name": "Alice", "age": 30, "active": true, "tags": ["a", "b"], "boss": null}},
      |    {"id": "acme", "label": "Organization"}
      |  ],
      |  "edges": [
      |    {"source": "alice", "target": "acme", "relationship": "WORKS_FOR", "properties": {"since": 2020}},
      |    {"source": "acme", "target": "alice", "relationship": "EMPLOYS"}
      |  ]
      |}""".stripMargin

  private def parsed(input: String): Graph = parse(input) match {
    case Right(graph) => graph
    case Left(error)  => fail(s"expected a graph, got: ${error.formatted}")
  }

  private def processingError(input: String): ProcessingError = parse(input) match {
    case Left(e: ProcessingError) => e
    case Left(other)              => fail(s"expected a ProcessingError, got: ${other.formatted}")
    case Right(graph)             => fail(s"expected a Left, got: $graph")
  }

  // ---------------------------------------------------------------------------
  // A valid document
  // ---------------------------------------------------------------------------

  test("a valid document gives nodes keyed by id and the edges in document order") {
    val graph = parsed(validJson)

    graph.nodes.keySet shouldBe Set("alice", "acme")
    graph.nodes("alice").label shouldBe "Person"
    graph.nodes("acme").label shouldBe "Organization"
    graph.edges.map(e => (e.source, e.target, e.relationship)) shouldBe
      List(("alice", "acme", "WORKS_FOR"), ("acme", "alice", "EMPLOYS"))
  }

  test("properties keep their JSON types") {
    val props = parsed(validJson).nodes("alice").properties

    props("name") shouldBe ujson.Str("Alice")
    props("age") shouldBe ujson.Num(30)
    props("active") shouldBe ujson.Bool(true)
    props("tags") shouldBe ujson.Arr("a", "b")
    props("boss") shouldBe ujson.Null
    parsed(validJson).edges.head.properties shouldBe Map("since" -> ujson.Num(2020))
  }

  test("a node or edge without properties gets an empty map") {
    val graph = parsed(validJson)

    graph.nodes("acme").properties shouldBe empty
    graph.edges(1).properties shouldBe empty
  }

  test("an empty properties object is an empty map") {
    val graph = parsed(
      """{"nodes": [{"id": "a", "label": "X", "properties": {}}], "edges": []}"""
    )

    graph.nodes("a").properties shouldBe empty
  }

  test("keys the parser does not know are ignored") {
    val graph = parsed(
      """{"version": 2,
        | "nodes": [{"id": "a", "label": "X", "confidence": 0.9}],
        | "edges": [{"source": "a", "target": "a", "relationship": "SELF", "weight": 3}]}""".stripMargin
    )

    graph.nodes("a") shouldBe Node("a", "X")
    graph.edges shouldBe List(Edge("a", "a", "SELF"))
  }

  test("an empty document gives an empty graph") {
    parse("""{"nodes": [], "edges": []}""") shouldBe Right(Graph(Map.empty, Nil))
  }

  test("ids, labels and properties may be non-ASCII") {
    val graph = parsed(
      """{"nodes": [{"id": "東京", "label": "都市", "properties": {"name": "Zürich 🚀"}}], "edges": []}"""
    )

    graph.nodes("東京").label shouldBe "都市"
    graph.nodes("東京").properties("name") shouldBe ujson.Str("Zürich 🚀")
  }

  test("a large document keeps every node and edge") {
    val n     = 2000
    val nodes = (0 until n).map(i => s"""{"id": "n$i", "label": "L"}""").mkString(",")
    val edges =
      (0 until n - 1).map(i => s"""{"source": "n$i", "target": "n${i + 1}", "relationship": "NEXT"}""").mkString(",")

    val graph = parsed(s"""{"nodes": [$nodes], "edges": [$edges]}""")

    graph.nodes.size shouldBe n
    graph.edges.size shouldBe n - 1
    graph.edges.last shouldBe Edge(s"n${n - 2}", s"n${n - 1}", "NEXT")
  }

  // ---------------------------------------------------------------------------
  // Wrapping the model puts around the JSON
  // ---------------------------------------------------------------------------

  test("a json code fence, a plain code fence and surrounding whitespace parse to the same graph") {
    val expected = parsed(validJson)

    parsed(s"```json\n$validJson\n```") shouldBe expected
    parsed(s"```\n$validJson\n```") shouldBe expected
    parsed(s"\n  \t$validJson \n\n") shouldBe expected
    parsed(s"  ```json\n$validJson\n```  \n") shouldBe expected
  }

  test("backticks inside a value are left alone") {
    val graph = parsed(
      """{"nodes": [{"id": "a", "label": "uses ``` fences"}], "edges": []}"""
    )

    graph.nodes("a").label shouldBe "uses ``` fences"
  }

  // ---------------------------------------------------------------------------
  // Input that is not a graph document
  // ---------------------------------------------------------------------------

  test("text that is not JSON is a ProcessingError carrying the error code") {
    List("this is not json", "", "   ", """{"nodes": [""", "```json\n```").foreach { input =>
      withClue(s"input: ${input.take(20)}") {
        processingError(input).operation shouldBe ErrorCode
      }
    }
  }

  test("a document without nodes or without edges says both fields are required") {
    List("""{"edges": []}""", """{"nodes": []}""", "{}").foreach { input =>
      withClue(s"input: $input") {
        val error = processingError(input)
        error.operation shouldBe ErrorCode
        error.message should include("nodes")
        error.message should include("edges")
      }
    }
  }

  test("a node or edge missing a required field, or holding one of the wrong type, fails extraction") {
    val malformed = List(
      "node without id"          -> """{"nodes": [{"label": "X"}], "edges": []}""",
      "node without label"       -> """{"nodes": [{"id": "a"}], "edges": []}""",
      "numeric id"               -> """{"nodes": [{"id": 1, "label": "X"}], "edges": []}""",
      "numeric label"            -> """{"nodes": [{"id": "a", "label": 5}], "edges": []}""",
      "properties not an object" -> """{"nodes": [{"id": "a", "label": "X", "properties": [1]}], "edges": []}""",
      "node is not an object"    -> """{"nodes": ["a"], "edges": []}""",
      "nodes not an array"       -> """{"nodes": {"id": "a"}, "edges": []}""",
      "edges not an array"       -> """{"nodes": [], "edges": "none"}""",
      "edge without source" -> """{"nodes": [{"id": "a", "label": "X"}], "edges": [{"target": "a", "relationship": "R"}]}""",
      "edge without target" -> """{"nodes": [{"id": "a", "label": "X"}], "edges": [{"source": "a", "relationship": "R"}]}""",
      "edge without relationship" -> """{"nodes": [{"id": "a", "label": "X"}], "edges": [{"source": "a", "target": "a"}]}""",
      "edge source not a string" -> """{"nodes": [{"id": "a", "label": "X"}], "edges": [{"source": 1, "target": "a", "relationship": "R"}]}""",
      "edge properties not object" -> """{"nodes": [{"id": "a", "label": "X"}], "edges": [{"source": "a", "target": "a", "relationship": "R", "properties": "x"}]}"""
    )

    malformed.foreach { case (name, input) =>
      withClue(name) {
        val error = processingError(input)
        error.operation shouldBe ErrorCode
        error.message should include("Failed to extract graph structure")
      }
    }
  }

  test("one bad node fails the whole document, even after good ones") {
    val error = processingError(
      """{"nodes": [{"id": "a", "label": "X"}, {"id": "b"}], "edges": []}"""
    )

    error.message should include("Failed to extract graph structure")
  }

  // ---------------------------------------------------------------------------
  // Integrity: edges must join nodes the document defines
  // ---------------------------------------------------------------------------

  test("an edge to a node that is not defined fails the integrity check, not under the caller's error code") {
    val inputs = List(
      "missing target" -> """{"nodes": [{"id": "a", "label": "X"}], "edges": [{"source": "a", "target": "ghost", "relationship": "R"}]}""",
      "missing source" -> """{"nodes": [{"id": "a", "label": "X"}], "edges": [{"source": "ghost", "target": "a", "relationship": "R"}]}""",
      "no nodes at all" -> """{"nodes": [], "edges": [{"source": "a", "target": "b", "relationship": "R"}]}"""
    )

    inputs.foreach { case (name, input) =>
      withClue(name) {
        val error = processingError(input)
        error.operation shouldBe "graph_integrity_violation"
        error.operation should not be ErrorCode
      }
    }
  }

  test("two nodes with the same id leave one node under that id and keep the edges") {
    val graph = parsed(
      """{"nodes": [{"id": "a", "label": "X"}, {"id": "a", "label": "Y"}, {"id": "b", "label": "Z"}],
        | "edges": [{"source": "a", "target": "b", "relationship": "R"}]}""".stripMargin
    )

    graph.nodes.keySet shouldBe Set("a", "b")
    graph.nodes("a").id shouldBe "a"
    graph.edges.map(e => (e.source, e.target)) shouldBe List(("a", "b"))
  }

  // ---------------------------------------------------------------------------
  // A top-level value that is not an object
  // ---------------------------------------------------------------------------

  // A model can answer with any JSON value. Only an object can hold a graph, so every other
  // top-level value is a `ProcessingError` under the caller's error code, never an exception.
  List(
    "array"            -> "[]",
    "array of objects" -> """[{"nodes": [], "edges": []}]""",
    "string"           -> "\"nodes\"",
    "number"           -> "42",
    "null"             -> "null",
    "true"             -> "true",
    "false"            -> "false",
    "fenced array"     -> "```json\n[]\n```",
    "fenced string"    -> "```\n\"edges\"\n```"
  ).foreach { case (name, input) =>
    test(s"a top-level $name is a ProcessingError that names the required fields") {
      val error = processingError(input)

      error.operation shouldBe ErrorCode
      error.message should include("nodes")
      error.message should include("edges")
    }
  }

  // ---------------------------------------------------------------------------
  // JSON null where the parser expects a value
  // ---------------------------------------------------------------------------

  test("JSON null for nodes, edges, a node, an edge, an id or properties fails extraction") {
    val inputs = List(
      "nodes null"      -> """{"nodes": null, "edges": []}""",
      "edges null"      -> """{"nodes": [], "edges": null}""",
      "node null"       -> """{"nodes": [null], "edges": []}""",
      "edge null"       -> """{"nodes": [], "edges": [null]}""",
      "id null"         -> """{"nodes": [{"id": null, "label": "X"}], "edges": []}""",
      "properties null" -> """{"nodes": [{"id": "a", "label": "X", "properties": null}], "edges": []}"""
    )

    inputs.foreach { case (name, input) =>
      withClue(name) {
        val error = processingError(input)
        error.operation shouldBe ErrorCode
        error.message should include("Failed to extract graph structure")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Nesting depth: the reply is model output. A reply nested too deeply is refused before it is
  // parsed, because every later traversal of the value (rendering it into a log line or an error
  // message, serialising the graph) recurses once per level, and a StackOverflowError is not caught
  // by `Try`, so it would escape `parse` (#1562). Run on a 1 MB stack so the outcome does not depend
  // on the JVM's default stack size; the outcome is reduced inside it so no deep value is rendered.
  // ---------------------------------------------------------------------------

  test("a reply nested too deeply is a ProcessingError naming the limit, not parsed") {
    val inputs = List(
      "unbalanced arrays"  -> ("[" * 100000),
      "balanced arrays"    -> ("[" * 100000 + "]" * 100000),
      "balanced objects"   -> ("{\"nodes\":" * 100000 + "1" + "}" * 100000),
      "objects in a fence" -> ("```json\n" + "{\"nodes\":" * 100000 + "1" + "}" * 100000 + "\n```")
    )
    inputs.foreach { case (name, input) =>
      withClue(name) {
        org.llm4s.testutil.SmallStack.run(parse(input).left.map(e => (e, e.message)).map(_ => "a graph")) match {
          case Right(Left((e: ProcessingError, message))) =>
            e.operation shouldBe ErrorCode
            message should include("Failed to parse LLM output as graph")
            message should include("512")
          case Right(other) => fail(s"expected a ProcessingError, got ${other.fold(_.getClass.getName, identity)}")
          case Left(thrown) => fail(s"expected a Left, but parse threw $thrown")
        }
      }
    }
  }

  test("a reply nested 512 levels deep is parsed, 513 is refused") {
    // the document, its nodes array, the node and its properties are four levels; the arrays add the rest
    def nested(depth: Int): String =
      """{"nodes": [{"id": "a", "label": "X", "properties": {"deep": """ + "[" * (depth - 4) + "]" * (depth - 4) +
        """}}], "edges": []}"""
    org.llm4s.testutil.SmallStack.run(parse(nested(512))).map(_.map(_.nodes.size)) shouldBe Right(Right(1))
    org.llm4s.testutil.SmallStack.run(parse(nested(513))).map(_.isLeft) shouldBe Right(true)
  }

  // ---------------------------------------------------------------------------
  // What a failure logs. The reply is model output and can be megabytes, and it echoes the documents
  // the graph was extracted from, so the ERROR line a failure writes carries a bounded preview of
  // it, never the whole reply (#1635).
  // ---------------------------------------------------------------------------

  private val twoHundredKb: Int = 200 * 1024

  private def capturingErrors[A](body: => A): (A, Seq[String]) = {
    val logger   = LoggerFactory.getLogger(GraphJsonParser.getClass).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.ERROR)
    val result =
      try body
      finally {
        logger.detachAppender(appender)
        logger.setLevel(previous)
      }
    (result, appender.list.asScala.toSeq.map(_.getFormattedMessage))
  }

  private def assertBoundedErrorLine(logged: Seq[String], replyLength: Int, prefix: String): Unit = {
    logged should have size 1
    val line = logged.head
    line should startWith(prefix)
    line.length should be <= 1024
    line should include(s"original length: $replyLength")
  }

  test("a 200 KB reply that is not JSON logs one ERROR line bounded to a preview, not the whole reply") {
    val reply = "{" + "x" * twoHundredKb

    val (result, logged) = capturingErrors(parse(reply))

    result.isLeft shouldBe true
    assertBoundedErrorLine(logged, reply.length, "Failed to parse graph JSON")
  }

  test("a 200 KB reply nested too deeply logs one ERROR line bounded to a preview") {
    val reply = "[" * twoHundredKb

    val (result, logged) = capturingErrors(org.llm4s.testutil.SmallStack.run(parse(reply)))

    result.map(_.isLeft) shouldBe Right(true)
    assertBoundedErrorLine(logged, reply.length, "Failed to parse graph JSON")
  }

  test("a 200 KB JSON reply whose structure is not a graph logs one ERROR line bounded to a preview") {
    // valid JSON with the required fields, but the node has no id, so extraction fails after parsing
    val reply = s"""{"nodes": [{"label": "X", "text": "${"x" * twoHundredKb}"}], "edges": []}"""

    val (result, logged) = capturingErrors(parse(reply))

    result.isLeft shouldBe true
    assertBoundedErrorLine(logged, reply.length, "Failed to extract graph structure from JSON")
  }

  test("a short reply is logged whole") {
    val reply = """{"nodes":[{"label":"X"}],"edges":[]}"""

    val (_, logged) = capturingErrors(parse(reply))

    logged should have size 1
    logged.head should include(reply)
    (logged.head should not).include("original length")
  }
}
