package org.llm4s.agent.orchestration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for DAG structures (Plan, Node, Edge)
 */
class DAGSpec extends AnyFlatSpec with Matchers {

  // Test agents for DAG construction
  val agentA = Agent.fromFunction[String, Int]("agent-a")(s => Right(s.length))
  val agentB = Agent.fromFunction[Int, String]("agent-b")(i => Right(s"processed-$i"))
  val agentC = Agent.fromFunction[String, Boolean]("agent-c")(s => Right(s.contains("processed")))

  "Node" should "be created with proper agent wrapping" in {
    val node = Node("test-node", agentA, Some("Test node description"))

    node.id shouldBe "test-node"
    node.agent shouldBe agentA
    node.description shouldBe Some("Test node description")
  }

  "Edge" should "connect nodes with type safety at compile time" in {
    val nodeA = Node("node-a", agentA)
    val nodeB = Node("node-b", agentB)

    // This should compile - Int output connects to Int input
    val edge = Edge("a-to-b", nodeA, nodeB, Some("Connection from A to B"))

    edge.id shouldBe "a-to-b"
    edge.source shouldBe nodeA
    edge.target shouldBe nodeB
    edge.description shouldBe Some("Connection from A to B")
  }

  "Plan.empty" should "create an empty plan" in {
    val plan = Plan.empty

    plan.nodes shouldBe empty
    plan.edges shouldBe empty
    plan.entryPoints shouldBe empty
    plan.exitPoints shouldBe empty
  }

  "Plan.builder" should "build a valid linear plan" in {
    val nodeA = Node("a", agentA)
    val nodeB = Node("b", agentB)
    val nodeC = Node("c", agentC)

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addNode(nodeC)
      .addEdge(Edge("a-b", nodeA, nodeB))
      .addEdge(Edge("b-c", nodeB, nodeC))
      .build

    plan.nodes should have size 3
    plan.edges should have size 2
    plan.entryPoints should contain(nodeA)
    plan.exitPoints should contain(nodeC)
  }

  "Plan.validate" should "detect valid DAG" in {
    val nodeA = Node("a", agentA)
    val nodeB = Node("b", agentB)

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addEdge(Edge("a-b", nodeA, nodeB))
      .build

    plan.validate shouldBe Right(())
  }

  "Plan.validate" should "detect cycles" in {
    val nodeA = Node("a", agentA)
    val nodeB = Node("b", agentB)
    val nodeC = Node("c", Agent.fromFunction[String, String]("agent-c")(s => Right(s)))

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addNode(nodeC)
      .addEdge(Edge("a-b", nodeA, nodeB))
      .addEdge(Edge("b-c", nodeB, nodeC))
      .addEdge(Edge("c-a", nodeC, nodeA)) // Creates cycle
      .build

    plan.validate.isLeft shouldBe true
    plan.validate.swap.getOrElse(throw new RuntimeException("Expected Left")) should include("cycle")
  }

  "Plan.topologicalOrder" should "return correct execution order" in {
    val nodeA = Node("a", agentA)
    val nodeB = Node("b", agentB)
    val nodeC = Node("c", agentC)

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addNode(nodeC)
      .addEdge(Edge("a-b", nodeA, nodeB))
      .addEdge(Edge("b-c", nodeB, nodeC))
      .build

    val order = plan.topologicalOrder
    order.isRight shouldBe true

    val nodes = order.getOrElse(List.empty)
    nodes should have size 3

    // A should come before B, B should come before C
    val aIndex = nodes.indexWhere(_.id == "a")
    val bIndex = nodes.indexWhere(_.id == "b")
    val cIndex = nodes.indexWhere(_.id == "c")

    aIndex should be < bIndex
    bIndex should be < cIndex
  }

  "Plan.getParallelBatches" should "identify independent nodes" in {
    // Create a diamond-shaped DAG: A -> B, A -> C, B -> D, C -> D
    val nodeA = Node("a", agentA)
    val nodeB = Node("b", agentB)
    val nodeC = Node("c", Agent.fromFunction[Int, String]("agent-c")(i => Right(s"alt-$i")))
    val nodeD = Node("d", Agent.fromFunction[String, Boolean]("agent-d")(s => Right(s.nonEmpty)))

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addNode(nodeC)
      .addNode(nodeD)
      .addEdge(Edge("a-b", nodeA, nodeB))
      .addEdge(Edge("a-c", nodeA, nodeC))
      .addEdge(Edge("b-d", nodeB, nodeD))
      .addEdge(Edge("c-d", nodeC, nodeD))
      .build

    val batches = plan.getParallelBatches
    batches.isRight shouldBe true

    val batchList = batches.getOrElse(List.empty)
    batchList should have size 3

    // First batch: A (entry point)
    batchList(0) should contain(nodeA)

    // Second batch: B and C (can run in parallel)
    (batchList(1) should contain).allOf(nodeB, nodeC)

    // Third batch: D (exit point)
    batchList(2) should contain(nodeD)
  }

  "Plan with disconnected components" should "handle multiple entry points" in {
    val nodeA = Node("a", agentA)
    val nodeB = Node("b", agentB)
    val nodeC = Node("c", Agent.fromFunction[String, String]("isolated")(s => Right(s"isolated: $s")))

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addNode(nodeC)
      .addEdge(Edge("a-b", nodeA, nodeB))
      // nodeC is isolated
      .build

    (plan.entryPoints should contain).allOf(nodeA, nodeC)
    (plan.exitPoints should contain).allOf(nodeB, nodeC)
  }
}
