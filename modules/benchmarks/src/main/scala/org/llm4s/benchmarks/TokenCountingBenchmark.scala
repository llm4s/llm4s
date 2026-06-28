package org.llm4s.benchmarks

import org.llm4s.context.ConversationTokenCounter
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.types.Result
import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class TokenCountingBenchmark(
  private val counterFactory: () => Result[ConversationTokenCounter]
) {
  def this() = this(() => ConversationTokenCounter.openAI())

  private var counter: ConversationTokenCounter = _
  private var small: Conversation               = _
  private var medium: Conversation              = _
  private var large: Conversation               = _

  private[benchmarks] def raiseSetupError(e: LLMError): Nothing =
    throw new RuntimeException(e.formatted)

  @Setup(Level.Trial)
  def setup(): Unit = {
    counter = counterFactory().fold(raiseSetupError, identity)
    small = BenchmarkFixtures.makeConversation(10)
    medium = BenchmarkFixtures.makeConversation(100)
    large = BenchmarkFixtures.makeConversation(1000)
  }

  @Benchmark
  def countTokens10Messages(): Int = counter.countConversation(small)

  @Benchmark
  def countTokens100Messages(): Int = counter.countConversation(medium)

  @Benchmark
  def countTokens1000Messages(): Int = counter.countConversation(large)
}
