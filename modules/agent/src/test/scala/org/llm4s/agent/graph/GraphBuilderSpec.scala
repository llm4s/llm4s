package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GraphBuilderSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val done: GraphNode[Unit] = (_, _, _) => NodeResult.Continue(Command.empty)

  private def problems(result: org.llm4s.types.Result[?]): List[String] =
    result.left.value match {
      case GraphError.InvalidGraph(_, found) => found
      case other                             => fail(s"expected InvalidGraph, got $other")
    }

  "GraphBuilder" should "compile a valid graph with a stable fingerprint" in {
    def build() = {
      val b     = GraphBuilder("g", "v1")
      val count = StateKey.replace[Int]("count", 0)
      val a     = b.node[Unit]("a", writes = Set(count))(done)
      val c     = b.node[Unit]("c")(done)
      b.edge(a, c)
      b.compile(a)(_.get(count)).value
    }
    val graph = build()
    graph.id shouldBe "g"
    graph.version shouldBe "v1"
    graph.fingerprint shouldBe build().fingerprint
  }

  it should "change the fingerprint when the structure changes" in {
    def build(withEdge: Boolean) = {
      val b = GraphBuilder("g", "v1")
      val a = b.node[Unit]("a")(done)
      val c = b.node[Unit]("c")(done)
      if withEdge then b.edge(a, c)
      b.compile(a)(_ => Right(())).value
    }
    build(withEdge = true).fingerprint should not be build(withEdge = false).fingerprint
  }

  it should "report every structural problem at once" in {
    val b     = GraphBuilder("", "v1")
    val other = GraphBuilder("other", "v1")
    val alien = other.node[Unit]("alien")(done)
    val a     = b.node[Unit]("a")(done)
    b.declare[Unit]("a")
    b.declare[Int]("unimplemented")
    b.node[Unit]("")(done)
    b.implement(a)(done)
    b.implement(alien)(done)
    b.edge(a, alien)
    b.staticJoin("j", Set.empty, a)
    b.dynamicJoin("j", a)
    b.dynamicJoin("", alien)
    b.staticJoin("k", Set(alien), a)

    val found = problems(b.compile(alien)(_ => Right(())))
    (found should contain).allOf(
      "node 'a' is declared twice",
      "node 'unimplemented' is never implemented",
      "a node id is empty",
      "node 'a' is implemented twice",
      "node 'alien' was issued by another builder",
      "edge a -> alien uses a node from another builder",
      "edge a -> alien uses an undeclared node",
      "static join 'j' has no sources",
      "join 'j' is declared twice",
      "a join id is empty",
      "dynamic join '' uses a node from another builder",
      "static join 'k' uses a node from another builder",
      "the graph id is empty",
      "entry node 'alien' was issued by another builder"
    )
  }

  it should "reject two distinct state keys sharing an id, and empty key ids" in {
    val b      = GraphBuilder("g", "v1")
    val first  = StateKey.replace[Int]("dup", 0)
    val second = StateKey.replace[String]("dup", "")
    val a      = b.node[Unit]("a", writes = Set(first))(done)
    b.node[Unit]("b", writes = Set(second))(done)
    b.stateKey(StateKey.replace[Int]("", 0))
    problems(b.compile(a)(_ => Right(()))) shouldBe List(
      "a state key id is empty",
      "state key id 'dup' is used by 2 different keys"
    )
  }

  it should "register a key that is read but never written" in {
    val b      = GraphBuilder("g", "v1")
    val seeded = StateKey.replace[String]("seeded", "default")
    b.stateKey(seeded)
    val a = b.node[Unit]("a")(done)
    runInMemory(b.compile(a)(_.get(seeded)).value, ()) match {
      case RunResult.Completed(_, output, _) => output shouldBe "default"
      case other                             => fail(other.toString)
    }
  }

  it should "describe its handles" in {
    val b = GraphBuilder("g", "v1")
    val a = b.node[Unit]("a")(done)
    a.toString shouldBe "NodeRef(a)"
    b.staticJoin("s", Set(a), a).toString shouldBe "StaticJoin(s)"
    b.dynamicJoin("d", a).toString shouldBe "DynamicJoin(d)"
  }
}
