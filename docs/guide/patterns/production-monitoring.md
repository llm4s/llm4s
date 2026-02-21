---
layout: page
title: Production Monitoring
parent: Real-World Application Patterns
nav_order: 3
---

# Production Monitoring

> **Note:** Code examples in this guide are illustrative pseudocode showing recommended patterns. For working examples using the actual LLM4S API, see [modules/samples](../../../modules/samples/).

Comprehensive strategies for observing, debugging, and optimizing LLM applications in production. This guide covers metrics collection, alerting, cost tracking, and debugging techniques.

## Overview

Production monitoring for LLM applications requires tracking multiple dimensions:

- **Performance**: Latency, throughput, error rates
- **Quality**: Output quality, grounding scores, user satisfaction
- **Cost**: Token usage, API costs, ROI by feature
- **Reliability**: Uptime, error recovery, circuit breaker status

---

## Key Metrics to Track

### Performance Metrics

```text
object PerformanceMetrics {
  
  case class PerformanceSnapshot(
    p50Latency: Long, // milliseconds
    p95Latency: Long,
    p99Latency: Long,
    avgLatency: Double,
    throughput: Double, // requests/second
    errorRate: Double // percentage
  )
  
  class LatencyTracker {
    private val latencies = scala.collection.mutable.ListBuffer[Long]()
    
    def recordLatency(ms: Long): Unit = {
      latencies += ms
    }
    
    def snapshot(): PerformanceSnapshot = {
      val sorted = latencies.sorted
      val p50 = sorted((sorted.length * 0.5).toInt)
      val p95 = sorted((sorted.length * 0.95).toInt)
      val p99 = sorted((sorted.length * 0.99).toInt)
      val avg = sorted.sum.toDouble / sorted.length
      
      PerformanceSnapshot(
        p50Latency = p50,
        p95Latency = p95,
        p99Latency = p99,
        avgLatency = avg,
        throughput = calculateThroughput(),
        errorRate = calculateErrorRate()
      )
    }
    
    private def calculateThroughput(): Double = {
      // requests per second
      latencies.length.toDouble / (latencies.sum.toDouble / 1000.0)
    }
    
    private def calculateErrorRate(): Double = {
      // Calculate percentage of requests that failed
      0.0 // implementation
    }
  }
}
```

### Quality Metrics

```text
object QualityMetrics {
  
  case class QualityScore(
    groundingScore: Double, // 0.0-1.0
    relevanceScore: Double, // 0.0-1.0
    userSatisfaction: Double, // 1-5 stars
    hallucination: Boolean,
    timeToFirstToken: Long // ms
  ) {
    def overallQuality: Double = 
      (groundingScore + relevanceScore + userSatisfaction / 5.0) / 3.0
  }
  
  class QualityMonitor(groundingChecker: GroundingChecker) {
    private val scores = scala.collection.mutable.ListBuffer[QualityScore]()
    
    def recordScore(score: QualityScore): Unit = {
      scores += score
    }
    
    def getAverageQuality(): Double = {
      if (scores.isEmpty) 0.0
      else scores.map(_.overallQuality).sum / scores.length
    }
    
    def getQualityByModel(model: String): Double = {
      // Track quality per model to detect regressions
      0.0
    }
  }
}
```

### Cost Metrics

```text
object CostMetrics {
  
  case class CostSnapshot(
    totalTokensUsed: Long,
    totalCost: Double,
    costPerRequest: Double,
    costByProvider: Map[String, Double],
    costTrend: List[Double] // last 7 days
  )
  
  class CostMonitor {
    private val dailyCosts = scala.collection.mutable.Map[String, Double]()
    private val providerCosts = scala.collection.mutable.Map[String, Double]()
    
    def recordAPICall(
      provider: String,
      inputTokens: Int,
      outputTokens: Int,
      cost: Double
    ): Unit = {
      val today = java.time.LocalDate.now().toString
      dailyCosts(today) = dailyCosts.getOrElse(today, 0.0) + cost
      providerCosts(provider) = providerCosts.getOrElse(provider, 0.0) + cost
    }
    
    def getDailyBudgetStatus(dailyBudget: Double): String = {
      val today = java.time.LocalDate.now().toString
      val spent = dailyCosts.getOrElse(today, 0.0)
      val percentUsed = (spent / dailyBudget) * 100
      
      s"Daily budget: $$${spent}/ $$${dailyBudget} (${percentUsed.toInt}%)"
    }
    
    def predictMonthlySpend(): Double = {
      val currentDay = java.time.LocalDate.now().getDayOfMonth
      val spentToDate = dailyCosts.values.sum
      (spentToDate / currentDay) * 30
    }
  }
}
```

---

## Alerting Strategy

### Alert Rules

```text
object AlertRules {
  
  sealed trait AlertSeverity
  case object Critical extends AlertSeverity
  case object Warning extends AlertSeverity
  case object Info extends AlertSeverity
  
  case class Alert(
    severity: AlertSeverity,
    title: String,
    message: String,
    affectedComponent: String,
    timestamp: Long = System.currentTimeMillis()
  )
  
  class AlertManager {
    private val alertHandlers = scala.collection.mutable.ListBuffer[
      Alert => Unit
    ]()
    
    def registerHandler(handler: Alert => Unit): Unit = {
      alertHandlers += handler
    }
    
    def raiseAlert(alert: Alert): Unit = {
      alertHandlers.foreach(_(alert))
    }
  }
  
  // Alert rules
  def setupAlerts(alertManager: AlertManager): Unit = {
    // High error rate
    if (errorRate > 0.05) { // > 5%
      alertManager.raiseAlert(Alert(
        severity = Critical,
        title = "High error rate detected",
        message = s"Error rate is ${(errorRate * 100).toInt}%, threshold is 5%",
        affectedComponent = "LLM API"
      ))
    }
    
    // High latency
    if (p99Latency > 30000) { // > 30 seconds
      alertManager.raiseAlert(Alert(
        severity = Warning,
        title = "High p99 latency",
        message = s"P99 latency is ${p99Latency}ms",
        affectedComponent = "API Gateway"
      ))
    }
    
    // Daily spend exceeded
    if (dailySpend > dailyBudget * 1.1) { // > 110% of budget
      alertManager.raiseAlert(Alert(
        severity = Warning,
        title = "Daily budget exceeded",
        message = s"Spent $$${dailySpend}, budget is $$${dailyBudget}",
        affectedComponent = "Cost Tracking"
      ))
    }
    
    // Low grounding score
    if (averageGroundingScore < 0.7) {
      alertManager.raiseAlert(Alert(
        severity = Warning,
        title = "Low grounding score",
        message = s"Average grounding score is ${averageGroundingScore}, target is 0.8+",
        affectedComponent = "RAG Quality"
      ))
    }
  }
}
```

---

## Debugging in Production

### Request Tracing

```text
object RequestTracing {
  
  case class TraceSpan(
    spanId: String,
    traceId: String,
    operationName: String,
    startTime: Long,
    endTime: Long,
    attributes: Map[String, String],
    events: List[TraceEvent],
    status: String
  ) {
    def duration: Long = endTime - startTime
  }
  
  case class TraceEvent(
    timestamp: Long,
    name: String,
    attributes: Map[String, String]
  )
  
  class RequestTracer {
    private val traces = scala.collection.mutable.Map[String, TraceSpan]()
    
    def startSpan(
      traceId: String,
      operationName: String
    ): String = {
      val spanId = java.util.UUID.randomUUID().toString
      traces(spanId) = TraceSpan(
        spanId = spanId,
        traceId = traceId,
        operationName = operationName,
        startTime = System.currentTimeMillis(),
        endTime = 0,
        attributes = Map(),
        events = List(),
        status = "UNSET"
      )
      spanId
    }
    
    def addEvent(
      spanId: String,
      eventName: String,
      attributes: Map[String, String]
    ): Unit = {
      traces.get(spanId).foreach { span =>
        val event = TraceEvent(System.currentTimeMillis(), eventName, attributes)
        traces(spanId) = span.copy(
          events = span.events :+ event
        )
      }
    }
    
    def endSpan(
      spanId: String,
      status: String = "OK"
    ): Unit = {
      traces.get(spanId).foreach { span =>
        traces(spanId) = span.copy(
          endTime = System.currentTimeMillis(),
          status = status
        )
      }
    }
    
    def getTrace(traceId: String): List[TraceSpan] = {
      traces.values.filter(_.traceId == traceId).toList
    }
  }
}
```

### Debug Logging

```text
object DebugLogging {
  
  import org.slf4j.LoggerFactory
  
  val logger = LoggerFactory.getLogger("LLM4S")
  
  def debugRAGQuery(
    query: String,
    results: List[SearchResult],
    retrievalTime: Long
  ): Unit = {
    logger.debug(s"RAG Query: $query")
    logger.debug(s"Retrieved ${results.length} documents in ${retrievalTime}ms")
    results.take(3).foreach { result =>
      logger.debug(s"  - ${result.source} (score: ${result.score})")
    }
  }
  
  def debugAgentExecution(
    agentName: String,
    input: String,
    output: String,
    tokens: (Int, Int), // (input, output)
    latency: Long
  ): Unit = {
    logger.debug(s"Agent: $agentName")
    logger.debug(s"Input tokens: ${tokens._1}, Output tokens: ${tokens._2}")
    logger.debug(s"Latency: ${latency}ms")
    logger.debug(s"Output length: ${output.length} chars")
  }
}
```

---

## Cost Tracking in Detail

### Per-Feature Cost Breakdown

```text
object FeatureCostTracking {
  
  case class FeatureCost(
    featureName: String,
    usage: Long, // number of times used
    totalCost: Double,
    costPerUse: Double,
    roi: Double // revenue generated / cost
  )
  
  class FeatureCostMonitor {
    private val features = scala.collection.mutable.Map[String, FeatureCost]()
    
    def recordFeatureUsage(
      featureName: String,
      tokensUsed: Int,
      costIncurred: Double,
      revenueGenerated: Double = 0.0
    ): Unit = {
      features.get(featureName) match {
        case Some(existing) =>
          features(featureName) = existing.copy(
            usage = existing.usage + 1,
            totalCost = existing.totalCost + costIncurred,
            costPerUse = (existing.totalCost + costIncurred) / (existing.usage + 1),
            roi = revenueGenerated / costIncurred
          )
        case None =>
          features(featureName) = FeatureCost(
            featureName = featureName,
            usage = 1,
            totalCost = costIncurred,
            costPerUse = costIncurred,
            roi = revenueGenerated / costIncurred
          )
      }
    }
    
    def getLeastProfitableFeatures(topN: Int = 5): List[FeatureCost] = {
      features.values.toList
        .sortBy(_.roi)
        .take(topN)
    }
    
    def getMostCostlyFeatures(topN: Int = 5): List[FeatureCost] = {
      features.values.toList
        .sortBy(_.totalCost)
        .reverse
        .take(topN)
    }
  }
}
```

---

## Production Dashboard Queries

### BigQuery Examples

```sql
-- Daily API costs by provider
SELECT
  DATE(timestamp) as date,
  provider,
  COUNT(*) as requests,
  SUM(input_tokens + output_tokens) as total_tokens,
  SUM(cost) as daily_cost
FROM llm_calls
GROUP BY date, provider
ORDER BY date DESC, daily_cost DESC;

-- Latency percentiles
SELECT
  PERCENTILE_CONT(latency_ms, 0.50) as p50,
  PERCENTILE_CONT(latency_ms, 0.95) as p95,
  PERCENTILE_CONT(latency_ms, 0.99) as p99,
  AVG(latency_ms) as avg
FROM llm_calls
WHERE timestamp > TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 24 HOUR);

-- Agent success rates
SELECT
  agent_name,
  COUNT(*) as executions,
  COUNTIF(status = 'SUCCESS') as successes,
  ROUND(COUNTIF(status = 'SUCCESS') / COUNT(*) * 100, 2) as success_rate
FROM agent_executions
GROUP BY agent_name;

-- Grounding score distribution
SELECT
  grounding_score,
  COUNT(*) as count,
  ROUND(COUNT(*) / SUM(COUNT(*)) OVER () * 100, 2) as percentage
FROM rag_outputs
GROUP BY grounding_score
ORDER BY grounding_score DESC;
```

---

## Best Practices

### ✅ Do's

- **Track at request level**: Include latency, tokens, cost in every call
- **Set alert thresholds**: Define what "bad" looks like
- **Monitor trends**: Track metrics over days/weeks, not just seconds
- **Tag your spans**: Add metadata for easy filtering
- **Sample logs intelligently**: Don't log every request, but log errors
- **Archive metrics**: Keep historical data for trend analysis
- **Test your alerts**: Verify alerts actually fire
- **Correlate metrics**: Link errors to cost spikes, latency to quality

### ❌ Don'ts

- **Don't ignore errors**: Every error is a signal
- **Don't forget context**: Without tags, logs are useless
- **Don't alert on everything**: Alert fatigue leads to ignored alerts
- **Don't assume correlation**: High cost doesn't always mean issues
- **Don't hardcode thresholds**: Make them configurable
- **Don't lose audit trails**: Keep complete history for compliance
- **Don't mix concerns**: Separate performance from cost from quality metrics

---

## See Also

- [Cost Tracking & Optimization](../issues/#11) - Detailed cost analysis
- [Error Recovery](./error-recovery.md) - Handle failures
- [Security Best Practices](./security-best-practices.md) - Audit logging

---

**Last Updated:** February 2026  
**Status:** Production Ready
