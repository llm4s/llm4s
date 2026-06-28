package org.llm4s.benchmarks

import org.llm4s.error.TokenizerError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Verifies each benchmark can initialize and execute at least one iteration without error.
 *
 * These tests are NOT JMH runs — they simply instantiate the benchmark state and call each
 * benchmark method once to confirm the harness is wired correctly before running the full
 * benchmark suite with `benchmarks/Jmh/run`.
 */
class BenchmarkSmokeTest extends AnyFlatSpec with Matchers {

  "TokenCountingBenchmark" should "initialize and run all methods without error" in {
    val bench = new TokenCountingBenchmark
    bench.setup()
    bench.countTokens10Messages() should be > 0
    bench.countTokens100Messages() should be > 0
    bench.countTokens1000Messages() should be > 0
  }

  "ContextCompressionBenchmark" should "initialize and run all methods without error" in {
    val bench = new ContextCompressionBenchmark
    bench.setup()
    bench.compressToolOutput20Messages().isRight shouldBe true
    bench.compressToolOutput200Messages().isRight shouldBe true
    bench.compressWithSubjectiveEdits500Messages().isRight shouldBe true
  }

  "ToolRegistryBenchmark" should "initialize and dispatch tools without error" in {
    val bench = new ToolRegistryBenchmark
    bench.setup()
    bench.dispatchSingleTool().isRight shouldBe true
    bench.dispatchBatch10Sequential().forall(_.isRight) shouldBe true
    bench.dispatchBatch10Parallel().forall(_.isRight) shouldBe true
  }

  "KeywordIndexBenchmark" should "initialize, index 1000 documents, and search without error" in {
    val bench = new KeywordIndexBenchmark
    bench.setup()
    bench.searchSingleTerm().isRight shouldBe true
    bench.searchMultiTerm().isRight shouldBe true
    bench.indexSingleDocument().isRight shouldBe true
    bench.tearDown()
  }

  it should "not fail tearDown when index was never initialized" in {
    val bench = new KeywordIndexBenchmark
    bench.tearDown() // index is null — must be a no-op, not NPE
  }

  "BenchmarkFixtures" should "produce correct fixture sizes" in {
    BenchmarkFixtures.makeConversation(10).messages should have size 10
    BenchmarkFixtures.makeConversation(100).messages should have size 100
    BenchmarkFixtures.makeDocuments(50) should have size 50
    BenchmarkFixtures.makeToolRegistry(5).tools should have size 5
  }

  "TokenCountingBenchmark" should "throw RuntimeException via setup when counter factory fails" in {
    val err   = TokenizerError.notAvailable("cl100k_base")
    val bench = new TokenCountingBenchmark(() => Left(err))
    val ex    = intercept[RuntimeException](bench.setup())
    ex.getMessage should include("cl100k_base")
  }

  "ContextCompressionBenchmark" should "throw RuntimeException via setup when counter factory fails" in {
    val err   = TokenizerError.notAvailable("cl100k_base")
    val bench = new ContextCompressionBenchmark(() => Left(err))
    val ex    = intercept[RuntimeException](bench.setup())
    ex.getMessage should include("cl100k_base")
  }

  "KeywordIndexBenchmark" should "throw RuntimeException via setup when index factory fails" in {
    val err   = TokenizerError.notAvailable("sqlite")
    val bench = new KeywordIndexBenchmark(() => Left(err))
    val ex    = intercept[RuntimeException](bench.setup())
    ex.getMessage should include("sqlite")
  }
}
