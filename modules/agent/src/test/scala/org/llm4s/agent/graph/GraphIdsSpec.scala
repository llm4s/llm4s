package org.llm4s.agent.graph

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import scala.jdk.CollectionConverters.*

/**
 * The identifier types in `GraphIds.scala` are opaque strings: the value goes in and comes out unchanged
 * ("stable identifier ... persisted in snapshots"), and the compiler keeps the types apart.
 *
 * Nothing here pins a JSON shape: these types carry no codec of their own.
 */
class GraphIdsSpec extends AnyFlatSpec with Matchers:

  /** One identifier type: how to build it from a string and read the string back. */
  final private case class IdType[A](name: String, make: String => A, read: A => String)

  private val idTypes: Seq[IdType[?]] = Seq(
    IdType[NodeId]("NodeId", NodeId.apply, _.value),
    IdType[TaskId]("TaskId", TaskId.apply, _.value),
    IdType[JoinId]("JoinId", JoinId.apply, _.value),
    IdType[StateKeyId]("StateKeyId", StateKeyId.apply, _.value),
    IdType[ThreadId]("ThreadId", ThreadId.apply, _.value),
    IdType[RunId]("RunId", RunId.apply, _.value),
    IdType[InterruptId]("InterruptId", InterruptId.apply, _.value),
    IdType[TenantId]("TenantId", TenantId.apply, _.value),
    IdType[Principal]("Principal", Principal.apply, _.value),
    IdType[ToolCallId]("ToolCallId", ToolCallId.apply, _.value),
    IdType[ToolName]("ToolName", ToolName.apply, _.value)
  )

  // Values a persisted identifier must survive byte for byte: no trimming, case folding or normalisation.
  private val awkwardValues: Seq[String] = Seq(
    "node-1",
    "UPPER and lower",
    "with space and\ttab",
    " padded ",
    "ünïcode-識別子",
    "call_0123456789abcdef",
    "x" * 4096
  )

  private def roundTrips[A](id: IdType[A], value: String): Boolean = id.read(id.make(value)) == value

  for id <- idTypes do
    s"${id.name}" should "give back exactly the string it was built from" in {
      awkwardValues.foreach { value =>
        withClue(s"${id.name}(${value.take(20)}): ")(roundTrips(id, value) shouldBe true)
      }
    }

    it should "be equal to, and hash like, another id built from the same string" in {
      def build[A](t: IdType[A]): (A, A) = (t.make("same"), t.make("same"))
      val (a, b)                         = build(id)
      (a == b) shouldBe true
      a.hashCode shouldBe b.hashCode
    }

    it should "differ from an id built from a different string" in {
      def build[A](t: IdType[A]): (A, A) = (t.make("one"), t.make("two"))
      val (a, b)                         = build(id)
      (a == b) shouldBe false
    }

    it should "collapse duplicates when used as a set element or map key" in {
      def keys[A](t: IdType[A]): (Int, Int) =
        val ids = Seq("a", "b", "a", "c", "b").map(t.make)
        (ids.toSet.size, ids.groupBy(identity).size)
      keys(id) shouldBe ((3, 3))
    }

  "The identifier types" should "not be interchangeable with String or with each other" in {
    assertTypeError("""val n: NodeId = "a"""")
    assertTypeError("""val s: String = ThreadId("a")""")
    assertTypeError("""val t: TaskId = NodeId("a")""")
    assertTypeError("""val r: RunId = ThreadId("a")""")
    assertTypeError("""val k: ToolName = ToolCallId("a")""")
    assertTypeError("""val i: InterruptId = TaskId("a")""")
  }

  "RunId.random" should "produce canonical random (version 4) UUID strings" in {
    (1 to 100).foreach { _ =>
      val text = RunId.random().value
      val uuid = UUID.fromString(text)
      withClue(s"$text: ") {
        uuid.toString shouldBe text // canonical lower-case, hyphenated form
        uuid.version() shouldBe 4
        uuid.variant() shouldBe 2
      }
    }
  }

  it should "not repeat across many calls" in {
    val ids = Seq.fill(10000)(RunId.random().value)
    ids.distinct.size shouldBe ids.size
  }

  it should "not repeat across threads generating at the same time" in {
    val threads   = 8
    val perThread = 2000
    val pool      = Executors.newFixedThreadPool(threads)
    val start     = new CountDownLatch(1)
    val done      = new CountDownLatch(threads)
    val seen      = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    (1 to threads).foreach { _ =>
      pool.execute { () =>
        start.await()
        (1 to perThread).foreach(_ => seen.add(RunId.random().value))
        done.countDown()
      }
    }
    start.countDown()
    val finished = done.await(60, TimeUnit.SECONDS)
    pool.shutdownNow()
    finished shouldBe true
    val all = seen.asScala.toSeq
    all.size shouldBe threads * perThread
    all.distinct.size shouldBe all.size
  }

  "RunConfig" should "give each default-constructed config its own fresh RunId" in {
    // RunConfig's Scaladoc: "`RunConfig()` is evaluated per call, so each gets a fresh RunId."
    val ids = Seq.fill(1000)(RunConfig().runId.value)
    ids.distinct.size shouldBe ids.size
    ids.foreach(text => UUID.fromString(text).toString shouldBe text)
  }

  it should "keep a RunId it was given, and let withRunId replace it" in {
    RunConfig(runId = RunId("given")).runId.value shouldBe "given"
    RunConfig().withRunId(RunId("replaced")).runId.value shouldBe "replaced"
  }
