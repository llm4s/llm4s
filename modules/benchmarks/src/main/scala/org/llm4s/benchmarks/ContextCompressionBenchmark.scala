package org.llm4s.benchmarks

import org.llm4s.context.{ ConversationTokenCounter, DeterministicCompressor }
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.Message
import org.llm4s.types.Result
import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class ContextCompressionBenchmark(
  private val counterFactory: () => Result[ConversationTokenCounter]
) {
  def this() = this(() => ConversationTokenCounter.openAI())

  private var counter: ConversationTokenCounter = _
  private var small: Seq[Message]               = _
  private var medium: Seq[Message]              = _
  private var large: Seq[Message]               = _

  private[benchmarks] def raiseSetupError(e: LLMError): Nothing =
    throw new RuntimeException(e.formatted)

  @Setup(Level.Trial)
  def setup(): Unit = {
    counter = counterFactory().fold(raiseSetupError, identity)
    small = BenchmarkFixtures.makeConversation(20).messages
    medium = BenchmarkFixtures.makeConversation(200).messages
    large = BenchmarkFixtures.makeConversation(500).messages
  }

  @Benchmark
  def compressToolOutput20Messages(): Result[Seq[Message]] =
    DeterministicCompressor.compressToCap(small, counter, capTokens = 500)

  @Benchmark
  def compressToolOutput200Messages(): Result[Seq[Message]] =
    DeterministicCompressor.compressToCap(medium, counter, capTokens = 2000)

  @Benchmark
  def compressWithSubjectiveEdits500Messages(): Result[Seq[Message]] =
    DeterministicCompressor.compressToCap(large, counter, capTokens = 4000, enableSubjectiveEdits = true)
}
