package org.llm4s.agent.graph

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[CompiledGraph.toMermaid]]: the declared structure as a flowchart, the same text every time. */
class MermaidExportSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val noop: GraphNode[Unit] = (_, _, _) => NodeResult.Continue(Command.empty)

  /**
   * plan -> fetch and parse; fetch and parse join (static) before report; a fan-out join (dynamic)
   * leads to collect; approve is a resume node. `nodeOrder` is the order the nodes are declared in.
   */
  private def pipeline(nodeOrder: Seq[String]): CompiledGraph[Unit, Unit] = {
    val b = GraphBuilder("pipeline", "v1")
    val refs = nodeOrder.map {
      case "approve" =>
        val resume = b.resumeNode[String, String]("approve")((_, _, _) => NodeResult.Continue(Command.empty))
        "approve" -> resume.node
      case name => name -> b.node[Unit](name)(noop)
    }.toMap
    def unit(name: String): NodeRef[Unit] = refs(name).asInstanceOf[NodeRef[Unit]]
    b.edge(unit("plan"), unit("fetch"))
    b.edge(unit("plan"), unit("parse"))
    b.staticJoin("gather", Set(unit("fetch"), unit("parse")), unit("report"))
    b.dynamicJoin("fan", unit("collect"))
    b.compile(unit("plan"))(_ => Right(())).value
  }

  private val natural = Seq("plan", "fetch", "parse", "report", "collect", "approve")

  private val expected =
    """flowchart TD
      |    start((start)) --> n4
      |    n0(["approve"])
      |    n1["collect"]
      |    n2["fetch"]
      |    n3["parse"]
      |    n4["plan"]
      |    n5["report"]
      |    j0{{"gather"}}
      |    d0{{"fan"}}
      |    n4 --> n2
      |    n4 --> n3
      |    n2 --> j0
      |    n3 --> j0
      |    j0 --> n5
      |    d0 -.-> n1""".stripMargin

  "toMermaid" should "draw nodes, edges, a resume node and both kinds of join" in {
    pipeline(natural).toMermaid shouldBe expected
  }

  it should "give the same text whatever order the nodes were declared in" in {
    pipeline(natural.reverse).toMermaid shouldBe expected
    pipeline(natural.sorted).toMermaid shouldBe expected
  }

  it should "order several joins of each kind by id, whatever order they were declared in" in {
    def render(joinOrder: Seq[String]): String = {
      val b      = GraphBuilder("joins", "v1")
      val entry  = b.node[Unit]("entry")(noop)
      val left   = b.node[Unit]("left")(noop)
      val right  = b.node[Unit]("right")(noop)
      val target = b.node[Unit]("target")(noop)
      joinOrder.foreach {
        case id if id.endsWith("-join") => b.staticJoin(id, Set(left, right), target): Unit
        case id                         => b.dynamicJoin(id, target): Unit
      }
      b.compile(entry)(_ => Right(())).value.toMermaid
    }

    val expectedJoins = List(
      """    j0{{"a-join"}}""",
      """    j1{{"b-join"}}""",
      """    d0{{"y-fan"}}""",
      """    d1{{"z-fan"}}"""
    )
    val ordered  = render(Seq("a-join", "b-join", "y-fan", "z-fan"))
    val shuffled = render(Seq("z-fan", "b-join", "y-fan", "a-join"))

    ordered.linesIterator.filter(l => l.contains("{{")).toList shouldBe expectedJoins
    shuffled shouldBe ordered
    // each join's edges use its own alias: j0 is a-join, j1 is b-join, d0 is y-fan, d1 is z-fan
    ordered.linesIterator.filter(_.contains("j0")).toList should contain("    j0 --> n3")
    ordered.linesIterator.filter(_.contains("-.->")).toList shouldBe List("    d0 -.-> n3", "    d1 -.-> n3")
  }

  it should "give the same text on every call" in {
    val graph = pipeline(natural)

    graph.toMermaid shouldBe graph.toMermaid
  }

  it should "draw a single node with only the entry marker" in {
    val b     = GraphBuilder("one", "v1")
    val only  = b.node[Unit]("only")(noop)
    val graph = b.compile(only)(_ => Right(())).value

    graph.toMermaid shouldBe
      """flowchart TD
        |    start((start)) --> n0
        |    n0["only"]""".stripMargin
  }

  it should "keep a node's edges in the order they were declared" in {
    val b      = GraphBuilder("order", "v1")
    val entry  = b.node[Unit]("entry")(noop)
    val second = b.node[Unit]("a-second")(noop)
    val first  = b.node[Unit]("z-first")(noop)
    b.edge(entry, first)
    b.edge(entry, second)
    val graph = b.compile(entry)(_ => Right(())).value

    // sorted ids: a-second = n0, entry = n1, z-first = n2; entry's edges follow declaration, not id
    graph.toMermaid.linesIterator.filter(_.contains("-->")).toList shouldBe
      List("    start((start)) --> n1", "    n1 --> n2", "    n1 --> n0")
  }

  it should "escape labels and keep generated identifiers" in {
    val b     = GraphBuilder("escapes", "v1")
    val entry = b.node[Unit]("a<b>&c")(noop)
    b.node[Unit]("say \"hi\"")(noop)
    b.node[Unit]("two\nlines")(noop)
    val graph = b.compile(entry)(_ => Right(())).value

    graph.toMermaid shouldBe
      """flowchart TD
        |    start((start)) --> n0
        |    n0["a#lt;b#gt;#amp;c"]
        |    n1["say #quot;hi#quot;"]
        |    n2["two lines"]""".stripMargin
  }

  it should "not draw routes a node returns at run time" in {
    val b      = GraphBuilder("runtime-routes", "v1")
    val target = b.node[Unit]("target")(noop)
    val entry  = b.node[Unit]("entry")((_, _, _) => NodeResult.Continue(Command.empty.goto(target)))
    val graph  = b.compile(entry)(_ => Right(())).value

    graph.toMermaid.linesIterator.filter(l => l.contains("-->") && !l.contains("start")).toList shouldBe empty
  }
}
