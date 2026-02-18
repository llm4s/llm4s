// Sample applications for Real-World Application Patterns
package org.llm4s.samples.patterns



// Example 1: Multi-Agent Orchestration
object MultiAgentExample extends App {
  println("=== Multi-Agent Orchestration Example ===\n")
  
  // Create specialized agents (example only)
  // In real usage, instantiate Agent with a configured LLM client.
  
  // Sequential workflow
  val query = "What are the latest advances in Scala?"
  
  println(s"Query: $query\n")
  println("Step 1: Research...")
  
  // In a real implementation, you would:
  // 1. Run researchAgent.run(query)
  // 2. Use output in analysisAgent.run(...)
  // 3. Use output in summaryAgent.run(...)
  // 4. Return final result
  
  println("Step 2: Analyze...")
  println("Step 3: Summarize...")
  println("\nOutput: [Final summary would appear here]")
}

// Example 2: RAG for Enterprise
object RAGExample extends App {
  println("=== RAG for Enterprise Example ===\n")
  
  // Document ingestion
  val documents = List(
    "LLM4S is a Scala framework for building LLM applications...",
    "The agent framework provides multi-turn conversation support...",
    "RAG systems require careful document chunking and retrieval..."
  )
  
  println(s"Indexing ${documents.length} documents...")
  
  // Chunk documents
  documents.foreach { doc =>
    val chunks = doc.split("\\.").map(_.trim)
    println(s"  Document split into ${chunks.length} chunks")
  }
  
  println("\nDocument indexing complete!\n")
  
  // Search
  val query = "How does RAG work?"
  println(s"Query: $query\n")
  println("Retrieved documents:")
  println("  1. RAG systems require careful document chunking... (score: 0.92)")
  println("  2. The agent framework provides multi-turn... (score: 0.78)")
}

// Example 3: Error Recovery
object ErrorRecoveryExample extends App {
  println("=== Error Recovery Example ===\n")
  
  // Exponential backoff retry
  println("Attempt 1: Calling API... [TIMEOUT]")
  println("Waiting 100ms before retry...\n")
  
  println("Attempt 2: Calling API... [ERROR]")
  println("Waiting 200ms before retry...\n")
  
  println("Attempt 3: Calling API... [SUCCESS]")
  println("Response received!\n")
  
  // Circuit breaker
  println("Circuit Breaker Status: CLOSED")
  println("Consecutive failures: 0/5\n")
  
  // Fallback
  println("Primary model failed. Trying secondary model...")
  println("Secondary model response: [Success]")
}

// Example 4: Production Monitoring
object MonitoringExample extends App {
  println("=== Production Monitoring Example ===\n")
  
  // Performance metrics
  println("Performance Metrics (last 24 hours):")
  println("  P50 Latency:  245ms")
  println("  P95 Latency:  1,245ms")
  println("  P99 Latency:  3,142ms")
  println("  Throughput:   125 req/sec")
  println("  Error Rate:   0.8%\n")
  
  // Cost tracking
  println("Cost Metrics:")
  println("  API Calls:    45,231")
  println("  Total Cost:   $$124.53")
  println("  Cost/Request: $$0.0027")
  println("  Daily Budget: $$200.00")
  println("  Usage:        62.3%\n")
  
  // Quality metrics
  println("Quality Metrics:")
  println("  Avg Grounding Score: 0.87")
  println("  User Satisfaction:   4.2/5.0")
  println("  Hallucination Rate:  2.1%")
}

// Example 5: Scaling Strategies
object ScalingExample extends App {
  println("=== Scaling Strategies Example ===\n")
  
  // Request caching
  println("Request Caching:")
  println("  Cache Size:     1,245 queries")
  println("  Hit Rate:       67.8%")
  println("  Savings:        $$23.45 today\n")
  
  // Rate limiting
  println("Rate Limiting:")
  println("  Tokens/Second:  1,000/2,000")
  println("  Usage:          50%")
  println("  Requests:       892/1,000\n")
  
  // Batch processing
  println("Batch Processing:")
  println("  Pending:        45 tasks")
  println("  Processing:     10 tasks")
  println("  Completed:      2,341 tasks")
  println("  Throughput:     234 tasks/min\n")
  
  // Load balancing
  println("Load Distribution:")
  println("  Model A (GPT-4):      450 requests (45%)")
  println("  Model B (Claude):     350 requests (35%)")
  println("  Model C (Mixtral):    200 requests (20%)")
}

// Example 6: Security Best Practices
object SecurityExample extends App {
  println("=== Security Best Practices Example ===\n")
  
  // API key management
  println("API Key Management:")
  println("  Status:       ✓ Loaded from vault")
  println("  Last Rotated: 15 days ago")
  println("  Expires In:   15 days\n")
  
  // Input validation
  println("Input Validation:")
  println("  Input length:  2,341 chars ✓")
  println("  No SQL injection patterns ✓")
  println("  No prompt injection ✓\n")
  
  // Output sanitization
  println("Output Sanitization:")
  println("  PII detected:  3 emails, 1 phone number")
  println("  Status:        ✓ Redacted\n")
  
  // Audit logging
  println("Audit Trail (sample):")
  println("  2024-02-18 10:34:22 | User: alice@example.com | API Call to OpenAI")
  println("  2024-02-18 10:35:01 | User: bob@example.com   | Access to audit logs")
  println("  2024-02-18 10:36:15 | System: Key rotation complete")
}
