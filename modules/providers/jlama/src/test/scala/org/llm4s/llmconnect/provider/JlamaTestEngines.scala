package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.JlamaConfig
import org.llm4s.metrics.MetricsCollector
import org.llm4s.types.Result

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.locks.LockSupport
import scala.collection.mutable

/** Stand-ins for a loaded Jlama model, so that no test downloads or loads one. */
object JlamaTestEngines:

  val config: JlamaConfig =
    JlamaConfig.fromValues("tjake/Llama-3.2-1B-Instruct-JQ4").fold(e => sys.error(e.message), identity)

  /** What a [[ScriptedEngine]] was asked to do. */
  final case class Request(turns: Seq[JlamaTurn], temperature: Float, maxNewTokens: Int)

  /** Emits `tokens`, one callback each, and reports them joined as the reply. */
  final class ScriptedEngine(
    tokens: Seq[String] = Seq("Hello", " there"),
    finish: JlamaFinish = JlamaFinish.Stop,
    promptTokens: Int = 7,
    generatedTokens: Int = 2
  ) extends JlamaEngine:
    val requests: mutable.Buffer[Request] = mutable.Buffer.empty
    val closed: AtomicBoolean             = new AtomicBoolean(false)

    def generate(
      turns: Seq[JlamaTurn],
      temperature: Float,
      maxNewTokens: Int,
      onToken: String => Unit
    ): JlamaGeneration =
      requests += Request(turns, temperature, maxNewTokens)
      tokens.foreach(onToken)
      JlamaGeneration(tokens.mkString, finish, promptTokens, generatedTokens)

    def close(): Unit = closed.set(true)

  /** Never finishes: emits a token, then another, until the callback stops it. `started` opens at the first token. */
  final class EndlessEngine extends JlamaEngine:
    val started: CountDownLatch = new CountDownLatch(1)

    def generate(
      turns: Seq[JlamaTurn],
      temperature: Float,
      maxNewTokens: Int,
      onToken: String => Unit
    ): JlamaGeneration =
      while true do
        onToken("x")
        started.countDown()
        LockSupport.parkNanos(1_000_000L) // returns at once when interrupted; the next token then stops
      sys.error("unreachable")

    def close(): Unit = ()

  /** Throws from `generate`, as Jlama does for a prompt longer than the model's context. */
  final class FailingEngine(message: String) extends JlamaEngine:
    def generate(turns: Seq[JlamaTurn], temperature: Float, maxNewTokens: Int, onToken: String => Unit) =
      throw new IllegalArgumentException(message)
    def close(): Unit = ()

  /** A loader handing out `engine`, counting its calls. */
  final class CountingLoader(engine: => Result[JlamaEngine]) extends (JlamaConfig => Result[JlamaEngine]):
    val loads: AtomicInteger = new AtomicInteger(0)
    def apply(config: JlamaConfig): Result[JlamaEngine] =
      loads.incrementAndGet()
      engine

  def client(engine: JlamaEngine): JlamaClient = new JlamaClient(config, _ => Right(engine), MetricsCollector.noop)

  def client(loader: JlamaConfig => Result[JlamaEngine]): JlamaClient =
    new JlamaClient(config, loader, MetricsCollector.noop)
