package org.llm4s.javaapi

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ APIError, CancelledError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.util.Using

/**
 * The threading facts that `docs/guide/java-threading-and-cancellation.md` states, one test per
 * statement: which thread runs a call, whether one client or agent is safe to share, whether many
 * virtual threads can be inside the facade at once, and what an interrupt does.
 *
 * Nothing here waits on the clock to prove a point. Concurrency is proved with a latch that opens
 * only when every call is in flight at the same time (so a facade that serialised its callers
 * would hold the latch shut and the test would fail by its bounded wait, not by a tight margin);
 * interruption is proved with a server that never answers. Every wait is bounded at
 * [[waitSeconds]], so a regression fails the test instead of hanging the build.
 *
 * The calls that go over HTTP use the real Ollama client against a local server, so the claims
 * hold for a real provider's request path and not only for a fake.
 */
class ThreadingModelSpec extends AnyFlatSpec with Matchers {

  private def waitSeconds: Long = 20L

  /**
   * How many virtual threads the concurrency test puts inside a provider call at once: more than
   * there are cores, so more than there are carrier threads.
   */
  private def concurrentCallers: Int = math.max(64, 4 * Runtime.getRuntime.availableProcessors())

  private def completion(text: String): Completion =
    Completion("id", 0L, text, "test-model", AssistantMessage(text))

  private def lastUser(conversation: Conversation): String =
    conversation.messages.collect { case u: UserMessage => u.content }.lastOption.getOrElse("")

  private def fake(onComplete: Conversation => Result[Completion]): LLMClient = new LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      onComplete(conversation)
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  /** Nanoseconds left until `deadline` (`System.nanoTime` based); zero or less once it has passed. */
  private def remainingNanos(deadline: Long): Long = deadline - System.nanoTime()

  /**
   * A client whose every call waits until `n` calls are in flight at once, then echoes its query.
   * All calls share one deadline of [[waitSeconds]], so when the calls cannot overlap (a facade
   * that serialised them) the first waits out the deadline and the rest fail at once: the test
   * fails after about [[waitSeconds]], not after `n` times that.
   */
  private def echoClientAfter(n: Int): JLlmClient = {
    val inFlight = new CountDownLatch(n)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds)
    new JLlmClient(fake { conversation =>
      inFlight.countDown()
      if (remainingNanos(deadline) > 0 && inFlight.await(remainingNanos(deadline), TimeUnit.NANOSECONDS))
        Right(completion("echo:" + lastUser(conversation)))
      else Left(org.llm4s.error.ProcessingError("test", s"fewer than $n calls were in flight at once"))
    })
  }

  private val okBody =
    """{"model":"llama3","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"ok"},""" +
      """"done":true,"prompt_eval_count":1,"eval_count":1}"""

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()
  }

  /**
   * A local HTTP server (handlers on virtual threads). `release` is opened when the server closes,
   * so a handler that holds a request open is let go and the server can stop.
   *
   * The listen backlog is sized for [[concurrentCallers]] connections arriving at once. The JDK's
   * default is 50, and the server's dispatcher thread accepts one connection per selector pass, so
   * on a loaded runner more than 50 can be queued. Linux and macOS then drop the extra SYNs and the
   * client retransmits them a second later, unseen; Windows refuses them, which the JDK HttpClient
   * retries once, at once, and then reports as a `ConnectException` - a flake seen only on `windows-latest`.
   */
  final private class TestServer(handler: (HttpExchange, CountDownLatch) => Unit) extends AutoCloseable {
    private val release  = new CountDownLatch(1)
    private val handlers = Executors.newVirtualThreadPerTaskExecutor()
    private val server   = HttpServer.create(new InetSocketAddress("localhost", 0), 2 * concurrentCallers)
    server.createContext("/", exchange => handler(exchange, release))
    server.setExecutor(handlers)
    server.start()

    def client(): JLlmClient =
      Llm4s
        .createClient(OllamaConfig("llama3", s"http://localhost:${server.getAddress.getPort}", 4096, 512))
        .get()

    override def close(): Unit = {
      release.countDown()
      server.stop(0)
      handlers.shutdownNow(): Unit
    }
  }

  /**
   * An executor that is stopped with `shutdownNow`, which returns at once. `Using.resource` on the
   * executor itself would wait for every task, and when a regression leaves calls stuck that wait
   * turns a failing test into a very long one.
   */
  final private class Pool(val executor: java.util.concurrent.ExecutorService) extends AutoCloseable {
    override def close(): Unit = executor.shutdownNow(): Unit
  }

  private def platformThread(body: Runnable): Thread = {
    val thread = new Thread(body)
    thread.setDaemon(true)
    thread.start()
    thread
  }

  private def virtualThread(body: Runnable): Thread = Thread.ofVirtual().start(body)

  /**
   * Runs `body` on a platform thread of its own and returns what it gave and whether that thread's
   * interrupt flag was set when it returned - checked on a thread of its own so the flag does not
   * leak into other tests. A `body` that throws leaves the value `null`, and the throwable is
   * reported, since the facade's contract is that it never throws.
   */
  private def onOwnThread[A <: AnyRef](body: () => A): (A, Boolean) = {
    val value   = new AtomicReference[A]()
    val thrown  = new AtomicReference[Throwable]()
    val flagSet = new AtomicReference[java.lang.Boolean](java.lang.Boolean.FALSE)
    val thread = platformThread { () =>
      scala.util.Try(body()).fold(thrown.set, value.set)
      flagSet.set(java.lang.Boolean.valueOf(Thread.currentThread().isInterrupted))
    }
    thread.join(waitSeconds * 1000)
    thread.isAlive shouldBe false
    withClue("the call threw instead of returning a result: ")(thrown.get() shouldBe null)
    (value.get(), flagSet.get().booleanValue())
  }

  /**
   * Runs `call` on a thread made by `start`, interrupts that thread once the server holds its
   * request, and returns the call's result and whether the thread's interrupt flag was still set
   * when the call returned.
   */
  private def interruptedWhileBlocked[A <: AnyRef](start: Runnable => Thread)(call: JLlmClient => A): (A, Boolean) = {
    val entered = new CountDownLatch(1)
    val result  = new AtomicReference[A]()
    val flagSet = new AtomicReference[java.lang.Boolean](java.lang.Boolean.FALSE)
    Using.resource(new TestServer((_, release) => { entered.countDown(); release.await() })) { server =>
      val client = server.client()
      val caller = start { () =>
        result.set(call(client))
        flagSet.set(java.lang.Boolean.valueOf(Thread.currentThread().isInterrupted))
      }
      entered.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
      caller.interrupt()
      caller.join(waitSeconds * 1000)
      caller.isAlive shouldBe false
    }
    (result.get(), flagSet.get().booleanValue())
  }

  /** A client that throws `InterruptedException` from every call, as a custom `LLMClient` might. */
  private def interruptingClient: JLlmClient = new JLlmClient(fake(_ => throw new InterruptedException("stop")))

  // ---- which thread runs a call ----

  "JLlmClient.complete" should "run the provider call on the calling thread, platform or virtual" in {
    val seen = new AtomicReference[Thread]()
    val client = new JLlmClient(fake { _ =>
      seen.set(Thread.currentThread()); Right(completion("x"))
    })

    client.complete("hi").get() shouldBe "x"
    (seen.get() should be).theSameInstanceAs(Thread.currentThread())

    val onVirtual = new AtomicReference[Thread]()
    val caller = virtualThread { () =>
      client.complete("hi").get()
      onVirtual.set(Thread.currentThread())
    }
    caller.join(waitSeconds * 1000)
    (seen.get() should be).theSameInstanceAs(onVirtual.get())
    seen.get().isVirtual shouldBe true
  }

  "JAgent.run" should "block the caller while the model call runs on a virtual daemon thread of the library" in {
    val seen = new AtomicReference[Thread]()
    val agent = Llm4s.createAgent(new JLlmClient(fake { _ =>
      seen.set(Thread.currentThread()); Right(completion("x"))
    }))
    val caller = Thread.currentThread()

    agent.run("hi").get().messages.asScala.last.content shouldBe "x"

    (seen.get() should not).be(theSameInstanceAs(caller))
    seen.get().isVirtual shouldBe true
    seen.get().isDaemon shouldBe true
  }

  // ---- sharing one client or agent between threads ----

  "one JLlmClient shared by 50 platform threads" should "give each thread the answer to its own query" in {
    val n      = 50
    val client = echoClientAfter(n)
    val answers = Using.resource(new Pool(Executors.newFixedThreadPool(n))) { pool =>
      val futures = (0 until n).map(i => pool.executor.submit(() => client.complete("q" + i).get()))
      futures.map(_.get(waitSeconds, TimeUnit.SECONDS))
    }
    answers shouldBe (0 until n).map(i => "echo:q" + i)
  }

  "one JAgent shared by 32 virtual threads" should "give each thread the answer to its own query" in {
    val n     = 32
    val agent = Llm4s.createAgent(echoClientAfter(n))
    val answers = Using.resource(new Pool(Executors.newVirtualThreadPerTaskExecutor())) { pool =>
      val futures =
        (0 until n).map(i => pool.executor.submit(() => agent.run("a" + i).get().messages.asScala.last.content))
      futures.map(_.get(waitSeconds, TimeUnit.SECONDS))
    }
    answers shouldBe (0 until n).map(i => "echo:a" + i)
  }

  // ---- virtual threads ----

  "a real provider client reached through JLlmClient" should
    "serve more virtual threads at once than there are cores, so a call does not hold a carrier thread" in {
      val n        = concurrentCallers
      val arrived  = new CountDownLatch(n)
      val inside   = new AtomicInteger(0)
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds)
      val handler = (exchange: HttpExchange, _: CountDownLatch) => {
        arrived.countDown()
        // opens only when all n requests are on the server at once; one shared deadline, so a client
        // that serialised its calls fails the test after about waitSeconds, not n times that
        if (remainingNanos(deadline) > 0 && arrived.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
          inside.incrementAndGet()
          respond(exchange, 200, okBody)
        } else respond(exchange, 503, "{}")
      }
      Using.resource(new TestServer(handler)) { server =>
        val client = server.client()
        val results = Using.resource(new Pool(Executors.newVirtualThreadPerTaskExecutor())) { pool =>
          val futures = (0 until n).map(_ => pool.executor.submit(() => client.complete("hi")))
          futures.map(_.get(waitSeconds + 10, TimeUnit.SECONDS))
        }
        // a failure names the calls that failed and how many requests the server saw, so a flake
        // is told apart from a facade that serialised its callers
        val failed = results.filter(_.isFailure).map(_.getError().error)
        withClue(s"${n - arrived.getCount} of $n requests reached the server; failed calls: $failed:") {
          failed shouldBe empty
        }
        inside.get() shouldBe n
      }
    }

  // ---- interruption and cancellation ----

  "interrupting a platform thread blocked in a provider call" should
    "return a CancelledError with the thread's interrupt flag still set" in {
      val (result, flagSet) = interruptedWhileBlocked(platformThread)(_.complete("hi"))
      result.isFailure shouldBe true
      result.getError().error shouldBe a[CancelledError]
      flagSet shouldBe true
    }

  "interrupting a virtual thread blocked in a provider call" should
    "return a CancelledError with the thread's interrupt flag still set" in {
      val (result, flagSet) = interruptedWhileBlocked(virtualThread)(_.complete("hi"))
      result.isFailure shouldBe true
      result.getError().error shouldBe a[CancelledError]
      flagSet shouldBe true
    }

  "Future.cancel(true) on an executor task around a blocked call" should
    "interrupt the provider call, which returns a CancelledError" in {
      val entered = new CountDownLatch(1)
      val done    = new CountDownLatch(1)
      val result  = new AtomicReference[LlmResult[String]]()
      Using.resource(new TestServer((_, release) => { entered.countDown(); release.await() })) { server =>
        val client = server.client()
        Using.resource(new Pool(Executors.newSingleThreadExecutor())) { pool =>
          val task = pool.executor.submit(new Runnable {
            override def run(): Unit = { result.set(client.complete("hi")); done.countDown() }
          })
          entered.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
          task.cancel(true) shouldBe true
          task.isCancelled shouldBe true
          done.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
        }
      }
      result.get().getError().error shouldBe a[CancelledError]
    }

  // #1591: the facade never throws InterruptedException, not even for a custom client that does

  "a custom client that throws InterruptedException" should
    "have JLlmClient.complete return a CancelledError with the interrupt flag restored, never throw" in {
      val client            = interruptingClient
      val (result, flagSet) = onOwnThread(() => client.complete("hi"))
      result.isFailure shouldBe true
      result.getError().error shouldBe a[CancelledError]
      result.getError().getCause shouldBe an[InterruptedException]
      flagSet shouldBe true
    }

  it should "have every complete overload answer the same way" in {
    val client       = interruptingClient
    val conversation = Conversation(Seq(UserMessage("hi")))
    val (results, flagSet) = onOwnThread { () =>
      List(client.complete(conversation), client.complete(conversation, CompletionOptions()))
    }
    results.foreach(_.getError().error shouldBe a[CancelledError])
    flagSet shouldBe true
  }

  it should "have JAgent.run return a failed result, never throw, and leave the caller's flag alone" in {
    // the model call runs on a library thread, so the interrupt is that thread's, not the caller's
    val agent             = Llm4s.createAgent(interruptingClient)
    val (result, flagSet) = onOwnThread(() => agent.run("hi"))
    result.isFailure shouldBe true
    flagSet shouldBe false
  }

  "the blocking facade methods" should
    "declare no checked exception, so Java rejects a catch of InterruptedException around them" in {
      val methods = Seq(
        classOf[JLlmClient].getMethod("complete", classOf[String]),
        classOf[JLlmClient].getMethod("complete", classOf[Conversation]),
        classOf[JLlmClient].getMethod("complete", classOf[Conversation], classOf[CompletionOptions]),
        classOf[JAgent].getMethod("run", classOf[String]),
        classOf[JAgent].getMethod("continueConversation", classOf[JAgentResult], classOf[String])
      )
      methods.foreach(m => withClue(m.toString)(m.getExceptionTypes shouldBe empty))
    }

  "interrupting the thread that called JAgent.run" should
    "return a CancelledError while the run, and its model call, carry on" in {
      val entered         = new CountDownLatch(1)
      val release         = new CountDownLatch(1)
      val modelDone       = new CountDownLatch(1)
      val modelInterrupts = new AtomicInteger(0)
      val agent = Llm4s.createAgent(new JLlmClient(fake { _ =>
        entered.countDown()
        CancelledError.catchInterrupt(release.await(waitSeconds, TimeUnit.SECONDS)).left.foreach { _ =>
          modelInterrupts.incrementAndGet(): Unit
        }
        modelDone.countDown()
        Right(completion("done"))
      }))
      val result  = new AtomicReference[LlmResult[JAgentResult]]()
      val flagSet = new AtomicReference[java.lang.Boolean](java.lang.Boolean.FALSE)

      val caller = platformThread { () =>
        result.set(agent.run("hi"))
        flagSet.set(java.lang.Boolean.valueOf(Thread.currentThread().isInterrupted))
      }
      entered.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
      caller.interrupt()
      caller.join(waitSeconds * 1000)

      caller.isAlive shouldBe false
      result.get().getError().error shouldBe a[CancelledError]
      flagSet.get().booleanValue() shouldBe true
      // the run did not stop: its model call is still waiting and was never interrupted
      modelDone.getCount shouldBe 1L
      modelInterrupts.get() shouldBe 0

      release.countDown()
      modelDone.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
      modelInterrupts.get() shouldBe 0
    }

  // ---- the guide's Java snippets ----

  "the guide's Java snippets" should "behave as the guide says" in {
    val ok = new JLlmClient(fake(_ => Right(completion("x"))))
    ThreadingGuideSnippets.onVirtualThread(ok) shouldBe "x"

    ThreadingGuideSnippets.wasCancelled(LlmResult.failure(CancelledError("test", None))) shouldBe true
    ThreadingGuideSnippets.wasCancelled(LlmResult.failure(APIError("test", "down"))) shouldBe false
    ThreadingGuideSnippets.wasCancelled(LlmResult.success("x")) shouldBe false

    ThreadingGuideSnippets.completeOrNull(ok) shouldBe "x"
    ThreadingGuideSnippets.wasInterrupted(LlmResult.failure(CancelledError("test", None))) shouldBe false

    // "InterruptedException is never thrown": the snippet sees an interrupt while blocked in a real
    // provider call as null, with the thread's flag still set (#1591)
    val (blocked, blockedFlag) = interruptedWhileBlocked(platformThread)(ThreadingGuideSnippets.completeOrNull)
    blocked shouldBe null
    blockedFlag shouldBe true

    // and sees a custom client's escaping InterruptedException the same way, flag restored
    val interrupting        = interruptingClient
    val (returned, flagSet) = onOwnThread(() => ThreadingGuideSnippets.completeOrNull(interrupting))
    returned shouldBe null
    flagSet shouldBe true
    val (bothSignals, stillSet) = onOwnThread { () =>
      java.lang.Boolean.valueOf(ThreadingGuideSnippets.wasInterrupted(interrupting.complete("hi")))
    }
    bothSignals.booleanValue() shouldBe true
    stillSet shouldBe true
  }

  "LlmResult.toCompletableFuture" should "return a future that is already complete, so cancel has nothing to stop" in {
    val future = LlmResult.success("x").toCompletableFuture
    future.isDone shouldBe true
    future.cancel(true) shouldBe false
    future.isCancelled shouldBe false
    future.get() shouldBe "x"
  }
}
