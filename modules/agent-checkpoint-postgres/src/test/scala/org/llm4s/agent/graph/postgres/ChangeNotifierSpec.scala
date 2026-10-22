package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.ThreadId
import org.llm4s.error.{ CancelledError, ProcessingError }
import org.llm4s.types.Result
import org.postgresql.{ PGConnection, PGNotification }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, SQLException, Statement }
import java.util.concurrent.{ CountDownLatch, LinkedBlockingQueue, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * The parts of the store's change notification (design 4.51) that need no server: the channel and payload, the
 * waiters' wake-ups, and the listener's handling of its connection - over a fake connection, so a connection that
 * stalls silently, as a lost network leaves one, is shown to be found by the heartbeat and replaced, within the call
 * timeout. The real listener against a server is `modules/it`'s `PostgresChangeNotificationSpec`.
 */
class ChangeNotifierSpec extends AnyFlatSpec with Matchers {

  "channelFor" should "name the schema where it fits an identifier, and hash it where it does not" in {
    ChangeNotifier.channelFor("llm4s") shouldBe "llm4s_checkpoint_llm4s"
    val long   = "a" * 63
    val hashed = ChangeNotifier.channelFor(long)
    hashed.length should be <= 63
    hashed should startWith("llm4s_checkpoint.")
    hashed should not be ChangeNotifier.channelFor("b" * 63)
  }

  "payload" should "be a fixed-size hash carrying nothing of the thread id" in {
    val id = ThreadId("customer-42-private-thread")
    val p  = ChangeNotifier.payload(id)
    (p should fullyMatch).regex("[0-9a-f]{16}")
    (p should not).include("customer")
    ChangeNotifier.payload(id) shouldBe p
    ChangeNotifier.payload(ThreadId("another")) should not be p
  }

  private def notifier(connect: () => Connection, callTimeout: FiniteDuration = 200.millis) =
    new ChangeNotifier("chan", connect, callTimeout, "spec")

  private def eventually(within: FiniteDuration)(body: => Boolean): Boolean = {
    val deadline = System.nanoTime() + within.toNanos
    @scala.annotation.tailrec
    def check(): Boolean =
      if body then true
      else if System.nanoTime() >= deadline then false
      else
        Thread.sleep(10)
        check()
    check()
  }

  "A waiter" should "be woken by its thread's payload only, keep a wake that came before it waited, and leave on close" in {
    val n     = notifier(() => throw new SQLException("no server"))
    val t     = ThreadId("t")
    val w     = n.register(t)
    val other = n.register(ThreadId("other"))
    n.wake(ChangeNotifier.payload(t))
    // a wake that landed while the caller read is not lost
    w.await(TimeUnit.SECONDS.toNanos(1)) shouldBe true
    other.await(TimeUnit.MILLISECONDS.toNanos(50)) shouldBe false
    // two wakes before one wait end that wait only
    n.wake(ChangeNotifier.payload(t))
    n.wake(ChangeNotifier.payload(t))
    w.await(TimeUnit.SECONDS.toNanos(1)) shouldBe true
    w.await(TimeUnit.MILLISECONDS.toNanos(50)) shouldBe false
    n.wakeAll()
    other.await(TimeUnit.SECONDS.toNanos(1)) shouldBe true
    w.await(TimeUnit.SECONDS.toNanos(1)) shouldBe true
    w.close()
    n.wake(ChangeNotifier.payload(t))
    w.await(TimeUnit.MILLISECONDS.toNanos(50)) shouldBe false
    other.close()
    n.close()
  }

  it should "end its wait when its thread is interrupted" in {
    val n = notifier(() => throw new SQLException("no server"))
    val w = n.register(ThreadId("t"))
    Thread.currentThread().interrupt()
    an[InterruptedException] should be thrownBy w.await(TimeUnit.SECONDS.toNanos(10))
    Thread.interrupted() shouldBe false
    w.close()
    n.close()
  }

  "readUntilWoken" should "return what the first read finds, and a failed read, at once, whatever the timeout" in {
    val n     = notifier(() => throw new SQLException("no server"))
    val t     = ThreadId("t")
    val w     = n.register(t)
    val reads = new AtomicInteger()
    val start = System.nanoTime()
    ChangeNotifier.readUntilWoken(t, w, 1.hour) { reads.incrementAndGet(); Right(Vector(1)) } shouldBe Right(Vector(1))
    val failed: Result[Vector[Int]] = Left(ProcessingError("read", "the store is closed"))
    ChangeNotifier.readUntilWoken(t, w, 1.hour) { reads.incrementAndGet(); failed } shouldBe failed
    reads.get shouldBe 2
    (System.nanoTime() - start).nanos should be < 5.seconds
    w.close()
    n.close()
  }

  it should "return empty once its timeout passes with no wake, reading once" in {
    val n     = notifier(() => throw new SQLException("no server"))
    val t     = ThreadId("t")
    val w     = n.register(t)
    val reads = new AtomicInteger()
    val start = System.nanoTime()
    ChangeNotifier.readUntilWoken(t, w, 100.millis) { reads.incrementAndGet(); Right(Vector.empty[Int]) } shouldBe
      Right(Vector.empty)
    (System.nanoTime() - start).nanos should be >= 100.millis
    reads.get shouldBe 1
    // nothing left of the timeout: one read, no wait
    ChangeNotifier.readUntilWoken(t, w, Duration.Zero) { reads.incrementAndGet(); Right(Vector.empty[Int]) } shouldBe
      Right(Vector.empty)
    reads.get shouldBe 2
    w.close()
    n.close()
  }

  it should "read again on each wake, waiting on through one that brought nothing, and return what a later one brings" in {
    val n     = notifier(() => throw new SQLException("no server"))
    val t     = ThreadId("t")
    val w     = n.register(t)
    val reads = new AtomicInteger()
    // the second read finds nothing (a colliding hash), the third finds the commit
    val waiting = new LinkedBlockingQueue[Result[Vector[Int]]]()
    val caller = Thread.ofVirtual().start { () =>
      waiting.put(ChangeNotifier.readUntilWoken(t, w, 1.hour) {
        if reads.incrementAndGet() < 3 then Right(Vector.empty) else Right(Vector(7))
      })
    }
    eventually(5.seconds)(reads.get == 1) shouldBe true
    n.wake(ChangeNotifier.payload(t))
    eventually(5.seconds)(reads.get == 2) shouldBe true
    waiting.poll(100, TimeUnit.MILLISECONDS) shouldBe null
    n.wake(ChangeNotifier.payload(t))
    Option(waiting.poll(5, TimeUnit.SECONDS)) shouldBe Some(Right(Vector(7)))
    reads.get shouldBe 3
    caller.join()
    w.close()
    n.close()
  }

  it should "end its wait with CancelledError when interrupted, the flag set again" in {
    val n = notifier(() => throw new SQLException("no server"))
    val t = ThreadId("t")
    val w = n.register(t)
    Thread.currentThread().interrupt()
    val outcome = ChangeNotifier.readUntilWoken(t, w, 1.hour)(Right(Vector.empty[Int]))
    Thread.interrupted() shouldBe true
    outcome.left.toOption.get shouldBe a[CancelledError]
    w.close()
    n.close()
  }

  "The listener" should "keep retrying while it cannot connect, the waiters polling meanwhile" in {
    val attempts = new AtomicInteger()
    val n = notifier { () =>
      attempts.incrementAndGet(); throw new SQLException("no server")
    }
    val w = n.register(ThreadId("t"))
    eventually(5.seconds)(attempts.get >= 3) shouldBe true
    n.listening shouldBe false
    w.await(TimeUnit.MILLISECONDS.toNanos(50)) shouldBe false
    w.close()
    n.close()
  }

  it should "wake a thread's waiters on its notification, and every waiter once it listens" in {
    val fake = new FakeConnection(stallHeartbeat = false)
    val n    = notifier(() => fake.connection)
    val t    = ThreadId("t")
    val w    = n.register(t)
    // listening, it wakes every waiter, so what was committed before the LISTEN is read
    w.await(TimeUnit.SECONDS.toNanos(5)) shouldBe true
    n.listening shouldBe true
    n.listenerPid shouldBe Some(4242)
    fake.listened.get shouldBe 1
    fake.deliver(ChangeNotifier.payload(t))
    w.await(TimeUnit.SECONDS.toNanos(5)) shouldBe true
    w.close()
    n.close()
    eventually(5.seconds)(fake.aborted.getCount == 0) shouldBe true
  }

  it should "find a connection that stalls silently by its heartbeat, within the call timeout, and connect again" in {
    val stalled = new FakeConnection(stallHeartbeat = true)
    val healthy = new FakeConnection(stallHeartbeat = false)
    val queue   = new LinkedBlockingQueue[FakeConnection](java.util.List.of(stalled, healthy))
    val n       = notifier(() => Option(queue.poll()).getOrElse(healthy).connection, callTimeout = 200.millis)
    val w       = n.register(ThreadId("t"))
    eventually(5.seconds)(n.listening) shouldBe true
    // quiet for a call timeout, the heartbeat stalls, and the watch aborts the connection after another
    stalled.aborted.await(5, TimeUnit.SECONDS) shouldBe true
    eventually(5.seconds)(n.connectionsOpened == 2 && n.listening) shouldBe true
    healthy.listened.get shouldBe 1
    w.close()
    n.close()
  }

  /** A connection that `LISTEN`s, delivers what [[deliver]] queues, and answers or stalls its heartbeat. */
  final private class FakeConnection(stallHeartbeat: Boolean) {
    val aborted                        = new CountDownLatch(1)
    val listened                       = new AtomicInteger()
    private val notifications          = new LinkedBlockingQueue[String]()
    def deliver(payload: String): Unit = notifications.put(payload)

    private def note(payload: String): PGNotification = new PGNotification {
      def getName: String      = "chan"
      def getPID: Int          = 1
      def getParameter: String = payload
    }

    private val statement: Statement = proxy[Statement](classOf[Statement]) { (_, method, args) =>
      method.getName match {
        case "execute" =>
          val sql = args(0).asInstanceOf[String]
          if sql.startsWith("LISTEN") then listened.incrementAndGet()
          else if stallHeartbeat then
            // a network that went quiet: nothing comes back until the connection is aborted
            aborted.await()
            throw new SQLException("aborted")
          java.lang.Boolean.TRUE
        case "close" => null
        case other   => throw new UnsupportedOperationException(other)
      }
    }

    val connection: Connection = proxy[Connection](classOf[Connection], classOf[PGConnection]) { (self, method, args) =>
      method.getName match {
        case "unwrap"          => self
        case "setAutoCommit"   => null
        case "createStatement" => statement
        case "getBackendPID"   => Integer.valueOf(4242)
        case "getNotifications" =>
          if aborted.getCount == 0 then throw new SQLException("closed")
          val wait = args(0).asInstanceOf[Int].toLong
          Option(notifications.poll(wait, TimeUnit.MILLISECONDS))
            .map(p => Array(note(p)))
            .getOrElse(Array.empty[PGNotification])
        case "abort" | "close" =>
          aborted.countDown()
          null
        case other => throw new UnsupportedOperationException(other)
      }
    }
  }

  private def proxy[A](interfaces: Class[?]*)(handle: (AnyRef, Method, Array[AnyRef]) => AnyRef): A =
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        interfaces.toArray,
        new InvocationHandler {
          def invoke(p: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
            handle(p, method, Option(args).getOrElse(Array.empty))
        }
      )
      .asInstanceOf[A]
}
