package org.llm4s.benchmarks

import org.llm4s.error.LLMError
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ KeywordDocument, KeywordIndex, KeywordSearchResult, SQLiteKeywordIndex }
import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class KeywordIndexBenchmark(
  private val indexFactory: () => Result[KeywordIndex]
) {
  def this() = this(() => SQLiteKeywordIndex.inMemory())

  private var index: KeywordIndex     = _
  private var oneDoc: KeywordDocument = _

  private[benchmarks] def raiseSetupError(e: LLMError): Nothing =
    throw new RuntimeException(e.formatted)

  @Setup(Level.Trial)
  def setup(): Unit = {
    index = indexFactory().fold(raiseSetupError, identity)
    oneDoc = KeywordDocument("bench-single", "scala jvm programming language benchmark performance")
    index.indexBatch(BenchmarkFixtures.makeDocuments(1000))
  }

  @TearDown(Level.Trial)
  def tearDown(): Unit = if (index != null) index.close()

  @Benchmark
  def searchSingleTerm(): Result[Seq[KeywordSearchResult]] = index.search("scala", topK = 10)

  @Benchmark
  def searchMultiTerm(): Result[Seq[KeywordSearchResult]] =
    index.search("scala programming language", topK = 10)

  @Benchmark
  def indexSingleDocument(): Result[Unit] = index.index(oneDoc)
}
