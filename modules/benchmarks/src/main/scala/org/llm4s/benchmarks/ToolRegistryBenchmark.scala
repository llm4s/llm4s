package org.llm4s.benchmarks

import org.llm4s.toolapi.{ ToolCallError, ToolCallRequest, ToolExecutionStrategy, ToolRegistry }
import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class ToolRegistryBenchmark {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private var registry: ToolRegistry              = _
  private var singleRequest: ToolCallRequest      = _
  private var batchRequests: Seq[ToolCallRequest] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    registry = BenchmarkFixtures.makeToolRegistry(10)
    singleRequest = ToolCallRequest("echo_0", ujson.Obj("message" -> ujson.Str("hello")))
    batchRequests = (0 until 10).map(i => ToolCallRequest(s"echo_$i", ujson.Obj("message" -> ujson.Str(s"msg-$i"))))
  }

  @Benchmark
  def dispatchSingleTool(): Either[ToolCallError, ujson.Value] =
    registry.execute(singleRequest)

  @Benchmark
  def dispatchBatch10Sequential(): Seq[Either[ToolCallError, ujson.Value]] =
    Await.result(registry.executeAll(batchRequests, ToolExecutionStrategy.Sequential), 10.seconds)

  @Benchmark
  def dispatchBatch10Parallel(): Seq[Either[ToolCallError, ujson.Value]] =
    Await.result(registry.executeAll(batchRequests, ToolExecutionStrategy.Parallel), 10.seconds)
}
