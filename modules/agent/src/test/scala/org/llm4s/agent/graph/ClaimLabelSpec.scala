package org.llm4s.agent.graph

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.core.read.ListAppender
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A runtime's label ([[ClaimPolicy.label]]): what tells an operator which runtime holds a thread. It is
 * logged with the run id when a claim is granted and when a renewal fails or the claim is lost, and goes
 * nowhere else - not to the store, and not into the [[GraphError.ThreadBusy]] another caller gets.
 */
class ClaimLabelSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterEach {

  private val thread = ThreadId("t")

  private def logger: LogbackLogger =
    LoggerFactory.getLogger(classOf[GraphRuntime]).asInstanceOf[LogbackLogger]

  private var appender: ListAppender[ILoggingEvent] = scala.compiletime.uninitialized
  private var previous: Level                       = scala.compiletime.uninitialized

  override def beforeEach(): Unit = {
    appender = new ListAppender[ILoggingEvent]()
    appender.start()
    previous = logger.getLevel
    logger.setLevel(Level.DEBUG)
    logger.addAppender(appender)
  }

  override def afterEach(): Unit = {
    logger.detachAppender(appender)
    logger.setLevel(previous)
    appender.stop()
  }

  private def logged(level: Level): Vector[String] =
    // appended to under the appender's own lock, from the runtime's threads
    appender.synchronized(appender.list.asScala.toVector).filter(_.getLevel == level).map(_.getFormattedMessage)

  /** Waits, at most 5 seconds, for `condition`, an observable of another thread's progress. */
  private def eventually(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while !condition && System.nanoTime() < deadline do java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L)
    condition shouldBe true
  }

  /** One node, which waits for `gate` the first time it runs. */
  final private class Held {
    val gate    = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    val log     = StateKey.appending[String]("log")
    val graph: CompiledGraph[String, Vector[String]] = {
      val b = GraphBuilder("held", "v1")
      val step = b.node[String]("step", writes = Set(log)) { (input, _, _) =>
        entered.countDown()
        gate.await(5, TimeUnit.SECONDS): Unit
        continue(Command.empty.update(log, input))
      }
      b.compile(step)(_.get(log)).value
    }
  }

  /** A store whose renewals answer as `onRenew` says, when it says anything. */
  private class Renewing(underlying: Checkpointer) extends Checkpointer {
    @volatile var onRenew: () => Option[Result[RunClaim]] = () => None
    def claim(threadId: ThreadId, request: ClaimRequest)  = underlying.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) =
      onRenew().getOrElse(underlying.renew(threadId, token, ttl))
    def release(threadId: ThreadId, token: FencingToken)            = underlying.release(threadId, token)
    def commit(threadId: ThreadId, commit: Commit)                  = underlying.commit(threadId, commit)
    def latest(threadId: ThreadId)                                  = underlying.latest(threadId)
    def effect(threadId: ThreadId, key: EffectKey)                  = underlying.effect(threadId, key)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long)          = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) =
      underlying.deleteThread(threadId, token, fence)
    def children(parent: ThreadId, after: Option[ThreadId], limit: Int) = underlying.children(parent, after, limit)
    def threads(query: org.llm4s.agent.graph.ThreadQuery)               = underlying.threads(query)
    def incompleteThreads(
      tenantId: Option[org.llm4s.agent.graph.TenantId],
      after: Option[org.llm4s.agent.graph.ThreadCursor],
      limit: Int
    ) = underlying.incompleteThreads(tenantId, after, limit)
    def latestOfRuns(threadId: org.llm4s.agent.graph.ThreadId, runIds: Set[org.llm4s.agent.graph.RunId]) =
      underlying.latestOfRuns(threadId, runIds)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)
  }

  private def run(id: String) = RunConfig().withRunId(RunId(id))

  "A runtime's label" should "be logged with the run id at INFO when a claim is granted" in {
    val held = Held()
    held.gate.countDown()
    val rt = GraphRuntime(InMemoryCheckpointer(), claims = ClaimPolicy.default.withLabel("worker-7"))
    await(rt.start(thread, held.graph, "x", run("r1")).value).completed
    logged(Level.INFO).filter(_.contains("claimed thread 't'")) should contain only
      "Run 'r1' (runtime 'worker-7') claimed thread 't' with token 1"
  }

  it should "be logged when a renewal fails and when the claim is lost" in {
    val store = Renewing(InMemoryCheckpointer())
    val held  = Held()
    val rt    = GraphRuntime(store, claims = ClaimPolicy(30.seconds, 10.millis).withLabel("worker-7"))
    store.onRenew = () => Some(Left(ProcessingError("store", "flaky")))
    val handle = rt.start(thread, held.graph, "x", run("r1")).value
    held.entered.await(5, TimeUnit.SECONDS) shouldBe true
    eventually(logged(Level.WARN).exists(_.startsWith("Renewing the claim of run 'r1' (runtime 'worker-7')")))
    store.onRenew = () => Some(Left(GraphError.StaleClaim("t", FencingToken(1L), Some(FencingToken(2L)))))
    awaitResult(handle).value.failed._2 shouldBe a[GraphError.ClaimLost]
    logged(Level.WARN).exists(_.startsWith("Run 'r1' (runtime 'worker-7') lost its claim on thread 't'")) shouldBe true
  }

  it should "reach neither the store nor the ThreadBusy another caller gets" in {
    val store  = InMemoryCheckpointer()
    val held   = Held()
    val first  = GraphRuntime(store, claims = ClaimPolicy.default.withLabel("worker-7"))
    val second = GraphRuntime(store)
    val handle = first.start(thread, held.graph, "x", run("r1")).value
    held.entered.await(5, TimeUnit.SECONDS) shouldBe true
    val busy = second.recover(thread, held.graph, run("r2")).left.value
    busy shouldBe GraphError.ThreadBusy(
      "t",
      store.latest(thread).value.map(_.checkpoint.id),
      GraphError.BusyHolder.Run(RunId("r1"))
    )
    (busy.message should not).include("worker-7")
    (busy.toString should not).include("worker-7")
    held.gate.countDown()
    await(handle).completed
  }

  "A runtime without a label" should "log a granted claim at DEBUG only" in {
    val held = Held()
    held.gate.countDown()
    await(GraphRuntime(InMemoryCheckpointer()).start(thread, held.graph, "x", run("r1")).value).completed
    logged(Level.INFO).filter(_.contains("claimed thread")) shouldBe empty
    logged(Level.DEBUG).filter(_.contains("claimed thread 't'")) should contain only
      "Run 'r1' claimed thread 't' with token 1"
  }

  "ClaimPolicy" should "carry an optional label, and refuse a blank one" in {
    ClaimPolicy.default.label shouldBe None
    ClaimPolicy.default.withLabel("worker-7").label shouldBe Some("worker-7")
    ClaimPolicy.default.withLabel("worker-7").withLabel(None).label shouldBe None
    ClaimPolicy.default.withLabel("worker-7").withTtl(1.minute).label shouldBe Some("worker-7")
    an[IllegalArgumentException] should be thrownBy ClaimPolicy.default.withLabel(" ")
    ClaimPolicy.of(label = Some("")).left.value.message should include("label must not be blank")
  }
}
