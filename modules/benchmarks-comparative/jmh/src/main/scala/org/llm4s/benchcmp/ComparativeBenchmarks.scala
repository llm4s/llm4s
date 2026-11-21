package org.llm4s.benchcmp

import org.llm4s.benchcmp.harness.{ ChatLibrary, MockOpenAiServer, MockServerProcess }
import org.llm4s.benchcmp.langchain4j.LangChain4jLibrary
import org.llm4s.benchcmp.llm4s.Llm4sLibrary
import org.llm4s.benchcmp.springai.SpringAiLibrary
import org.openjdk.jmh.annotations._

import java.util.concurrent.{ Callable, ExecutorService, Executors, TimeUnit }
import scala.util.Using

/** The compared libraries, by the name a benchmark's `library` parameter takes. */
object Libraries:
  def create(name: String, baseUrl: String): ChatLibrary = name match
    case "llm4s"       => new Llm4sLibrary(baseUrl)
    case "langchain4j" => new LangChain4jLibrary(baseUrl)
    case "spring-ai"   => new SpringAiLibrary(baseUrl)
    case other         => throw new IllegalArgumentException(s"unknown library: $other")

/**
 * One library's clients against a mock server in its own process (started per trial, so per fork). Setup checks
 * that the library really gets the canned reply, so a misconfigured adapter fails the run instead of measuring an
 * error path.
 */
@State(Scope.Benchmark)
abstract class LibraryState:
  @Param(Array("llm4s", "langchain4j", "spring-ai"))
  var library: String = scala.compiletime.uninitialized

  protected var server: MockServerProcess = scala.compiletime.uninitialized
  protected var lib: ChatLibrary          = scala.compiletime.uninitialized

  protected def tokens: Int = 1

  @Setup(Level.Trial)
  def setup(): Unit =
    server = MockServerProcess.start()
    lib = Libraries.create(library, server.baseUrl(tokens))
    check()

  protected def check(): Unit =
    val reply = lib.chat("Hello")
    require(reply == MockOpenAiServer.REPLY, s"$library: unexpected reply '$reply'")

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    lib.close()
    server.close()

/** Scenario 1: one non-streamed chat completion. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = Array("-Xms1g", "-Xmx1g"))
class ChatBenchmark extends LibraryState:
  @Benchmark def chat(): String = lib.chat("Hello")

/**
 * Scenario 2: one streamed completion of `tokens` chunks, read to the end. With 1 chunk the time is an upper bound
 * of time to first token (request, first chunk, end of stream); `(time(500) - time(1)) / 499` is the per-chunk cost.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = Array("-Xms1g", "-Xmx1g"))
class StreamBenchmark extends LibraryState:
  @Param(Array("1", "500"))
  var chunks: Int = 0

  override protected def tokens: Int = chunks

  override protected def check(): Unit =
    super.check()
    val received = lib.stream("Hello")
    require(received == chunks, s"$library: streamed $received chunks, expected $chunks")

  @Benchmark def stream(): Int = lib.stream("Hello")

/** Scenario 3: the library's tool-calling agent, 3 tool rounds (4 HTTP calls) per run. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = Array("-Xms1g", "-Xmx1g"))
class ToolAgentBenchmark extends LibraryState:
  override protected def check(): Unit =
    super.check()
    val answer = lib.toolAgent("What are the values of k0, k1 and k2?")
    require(answer == MockOpenAiServer.REPLY, s"$library: unexpected agent answer '$answer'")

  @Benchmark def toolAgent(): String = lib.toolAgent("What are the values of k0, k1 and k2?")

/**
 * Scenario 4: 200 chat completions at once on one shared client, one virtual thread each, or, for a library that
 * cannot run on virtual threads (`ChatLibrary.virtualThreads`), one thread each of a pool of 200 platform threads
 * created at setup.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = Array("-Xms1g", "-Xmx1g"))
class ConcurrentBenchmark extends LibraryState:
  private var platformPool: Option[ExecutorService] = None

  override protected def check(): Unit =
    super.check()
    if !lib.virtualThreads() then platformPool = Some(Executors.newFixedThreadPool(ConcurrentBenchmark.Requests))

  @TearDown(Level.Trial)
  def shutdownPool(): Unit = platformPool.foreach(_.shutdownNow())

  @Benchmark def concurrent200(): Int = platformPool match
    case Some(pool) => run(pool)
    case None       => Using.resource(Executors.newVirtualThreadPerTaskExecutor())(run)

  private def run(executor: ExecutorService): Int =
    val calls =
      (1 to ConcurrentBenchmark.Requests).map(_ => executor.submit((() => lib.chat("Hello")): Callable[String]))
    calls.count(_.get() == MockOpenAiServer.REPLY)

object ConcurrentBenchmark:
  val Requests: Int = 200
