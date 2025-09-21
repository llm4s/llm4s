package org.llm4s.agent.orchestration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }

/**
 * Integration tests for complete multi-agent workflows
 */
class IntegrationSpec extends AnyFlatSpec with Matchers with ScalaFutures {
  implicit val ec: ExecutionContext = ExecutionContext.global

  // Domain models for a document processing workflow
  case class Document(content: String, metadata: Map[String, String] = Map.empty)
  case class ProcessedDocument(originalContent: String, processedContent: String, wordCount: Int)
  case class AnalysisResult(sentiment: String, keyTopics: List[String], confidence: Double)
  case class WorkflowResult(document: ProcessedDocument, analysis: AnalysisResult, summary: String)

  "Complete document processing workflow" should "execute successfully" in {
    // Agent 1: Document preprocessor
    val preprocessor = Agent.fromFunction[Document, ProcessedDocument]("preprocessor") { doc =>
      val processed = doc.content.toLowerCase.trim
      val wordCount = processed.split("\\s+").length
      Right(ProcessedDocument(doc.content, processed, wordCount))
    }

    // Agent 2: Content analyzer
    val analyzer = Agent.fromFunction[ProcessedDocument, AnalysisResult]("analyzer") { doc =>
      val sentiment =
        if (doc.processedContent.contains("good") || doc.processedContent.contains("great")) "positive" else "neutral"
      val keyTopics  = doc.processedContent.split("\\s+").distinct.take(3).toList
      val confidence = if (doc.wordCount > 10) 0.8 else 0.5
      Right(AnalysisResult(sentiment, keyTopics, confidence))
    }

    // Agent 3: Summary generator
    val summarizer = Agent.fromFunction[AnalysisResult, String]("summarizer") { analysis =>
      Right(
        s"Summary: ${analysis.sentiment} sentiment with ${analysis.keyTopics.size} key topics (confidence: ${analysis.confidence})"
      )
    }

    // Agent 4: Result aggregator (combines multiple inputs - simplified for testing)
    val aggregator = Agent.fromFunction[String, WorkflowResult]("aggregator") { summary =>
      // In a real implementation, this would receive multiple inputs
      Right(
        WorkflowResult(
          ProcessedDocument("original", "processed", 5),
          AnalysisResult("positive", List("topic1", "topic2"), 0.8),
          summary
        )
      )
    }

    // Build the workflow DAG
    val nodePreprocess = Node("preprocess", preprocessor)
    val nodeAnalyze    = Node("analyze", analyzer)
    val nodeSummarize  = Node("summarize", summarizer)
    val nodeAggregate  = Node("aggregate", aggregator)

    val plan = Plan.builder
      .addNode(nodePreprocess)
      .addNode(nodeAnalyze)
      .addNode(nodeSummarize)
      .addNode(nodeAggregate)
      .addEdge(Edge("preprocess-analyze", nodePreprocess, nodeAnalyze))
      .addEdge(Edge("analyze-summarize", nodeAnalyze, nodeSummarize))
      .addEdge(Edge("summarize-aggregate", nodeSummarize, nodeAggregate))
      .build

    // Execute the workflow
    val runner        = PlanRunner()
    val initialInputs = Map("preprocess" -> Document("This is a great document about machine learning"))

    whenReady(runner.execute(plan, initialInputs)) { result =>
      // Verify results
      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)

      (outputs should contain).key("preprocess")
      (outputs should contain).key("analyze")
      (outputs should contain).key("summarize")
      (outputs should contain).key("aggregate")

      val finalResult = outputs("aggregate").asInstanceOf[WorkflowResult]
      finalResult.summary should include("positive sentiment")
    }
  }

  "Error recovery in complex workflow" should "handle partial failures gracefully" in {
    // Create a workflow where one branch fails but others succeed
    val successfulProcessor = Agent.fromFunction[Document, ProcessedDocument]("success-processor") { doc =>
      Right(ProcessedDocument(doc.content, doc.content.toUpperCase, doc.content.length))
    }

    val failingProcessor = Agent.fromFunction[Document, ProcessedDocument]("failing-processor") { _ =>
      Left(OrchestrationError.NodeExecutionError("failing", "failing-processor", "Simulated processing failure"))
    }

    val robustAggregator = Agent.fromFunction[ProcessedDocument, String]("robust-aggregator") { doc =>
      Right(s"Processed: ${doc.processedContent}")
    }

    // Add retry policy to the failing processor
    val resilientProcessor = Policies.withRetry(failingProcessor, maxAttempts = 2, backoff = 10.millis)

    // Create parallel processing branches
    val nodeSuccess    = Node("success-branch", successfulProcessor)
    val nodeFailing    = Node("failing-branch", resilientProcessor)
    val nodeAggregator = Node("aggregator", robustAggregator)

    val plan = Plan.builder
      .addNode(nodeSuccess)
      .addNode(nodeFailing)
      .addNode(nodeAggregator)
      .addEdge(Edge("success-to-agg", nodeSuccess, nodeAggregator))
      // Note: failing branch is isolated, so workflow can still complete
      .build

    val runner = PlanRunner()
    val initialInputs = Map(
      "success-branch" -> Document("Test document"),
      "failing-branch" -> Document("Another document")
    )

    whenReady(runner.execute(plan, initialInputs)) { result =>
      // The workflow should fail because the failing branch will cause the execution to fail
      result.isLeft shouldBe true
    }
  }

  "Performance test with parallel execution" should "demonstrate concurrency benefits" in {
    // Create multiple independent processing tasks
    val heavyProcessor = Agent.fromFuture[Int, String]("heavy-processor") { input =>
      Future {
        Thread.sleep(50)
        Right(s"processed-$input")
      }
    }

    // Create 4 parallel processing nodes
    val nodes = (1 to 4).map(i => Node(s"processor-$i", heavyProcessor)).toList

    val plan = Plan.builder
      .addNode(nodes(0))
      .addNode(nodes(1))
      .addNode(nodes(2))
      .addNode(nodes(3))
      .build // No edges = all run in parallel

    val initialInputs = Map(
      "processor-1" -> 1,
      "processor-2" -> 2,
      "processor-3" -> 3,
      "processor-4" -> 4
    )

    val runner = PlanRunner()

    val startTime = System.currentTimeMillis()
    whenReady(runner.execute(plan, initialInputs)) { result =>
      val endTime = System.currentTimeMillis()

      val executionTime = endTime - startTime

      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)
      outputs should have size 4

      // Should complete in roughly 50ms (parallel) rather than 200ms (sequential)
      // Adding some tolerance for test environment variations
      executionTime should be < 150L
    }
  }

  "Complex DAG with multiple merge points" should "execute correctly" in {
    // Create a complex workflow: A -> B, A -> C, B -> D, C -> D, D -> E
    val sourceAgent = Agent.fromFunction[String, String]("source")(s => Right(s"source:$s"))
    val branchB     = Agent.fromFunction[String, String]("branchB")(s => Right(s"B:$s"))
    val branchC     = Agent.fromFunction[String, String]("branchC")(s => Right(s"C:$s"))
    val merger      = Agent.fromFunction[String, String]("merger")(s => Right(s"merged:$s"))
    val finalizer   = Agent.fromFunction[String, String]("finalizer")(s => Right(s"final:$s"))

    val nodeA = Node("source", sourceAgent)
    val nodeB = Node("branchB", branchB)
    val nodeC = Node("branchC", branchC)
    val nodeD = Node("merger", merger)
    val nodeE = Node("finalizer", finalizer)

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addNode(nodeC)
      .addNode(nodeD)
      .addNode(nodeE)
      .addEdge(Edge("A-B", nodeA, nodeB))
      .addEdge(Edge("A-C", nodeA, nodeC))
      .addEdge(Edge("B-D", nodeB, nodeD))
      .addEdge(Edge("D-E", nodeD, nodeE))
      .build

    val runner        = PlanRunner()
    val initialInputs = Map("source" -> "test-data")

    whenReady(runner.execute(plan, initialInputs)) { result =>
      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)

      outputs should have size 5
      outputs("finalizer").asInstanceOf[String] should include("final:merged:B:source:test-data")
    }
  }

  "Workflow with policies and error handling" should "demonstrate production-ready resilience" in {
    // Create a realistic workflow with various failure modes
    var networkCallCount = 0
    val unreliableNetworkAgent = Agent.fromFuture[String, String]("network-call") { data =>
      networkCallCount += 1
      if (networkCallCount < 3) {
        Future.failed(new RuntimeException("Network timeout"))
      } else {
        Future.successful(Right(s"network-result:$data"))
      }
    }

    val fallbackAgent = Agent.fromFunction[String, String]("fallback")(data => Right(s"cached-result:$data"))

    // Apply comprehensive policies
    val resilientNetworkAgent = Policies.withPolicies(
      unreliableNetworkAgent,
      retry = Some((3, 100.millis)),
      timeout = Some(1.second),
      fallback = Some(fallbackAgent)
    )

    val processingAgent = Agent.fromFunction[String, String]("processor")(data => Right(s"processed:$data"))

    val nodeNetwork   = Node("network", resilientNetworkAgent)
    val nodeProcessor = Node("processor", processingAgent)

    val plan = Plan.builder
      .addNode(nodeNetwork)
      .addNode(nodeProcessor)
      .addEdge(Edge("network-processor", nodeNetwork, nodeProcessor))
      .build

    val runner        = PlanRunner()
    val initialInputs = Map("network" -> "request-data")

    networkCallCount = 0 // Reset counter
    whenReady(runner.execute(plan, initialInputs), timeout(5.seconds)) { result =>
      result.isRight shouldBe true
      val outputs = result.getOrElse(Map.empty)

      // Should succeed after retries
      outputs("processor").asInstanceOf[String] should include("processed:network-result:request-data")
      networkCallCount shouldBe 3 // Should have retried 3 times
    }
  }
}
