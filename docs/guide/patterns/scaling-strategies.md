---
layout: page
title: Scaling Strategies
parent: Real-World Application Patterns
nav_order: 4
---

# Scaling Strategies

> **Note:** Code examples in this guide are illustrative pseudocode showing recommended patterns. For working examples using the actual LLM4S API, see [modules/samples](../../../modules/samples/).

Production patterns for handling high throughput, reducing latency, and distributing load. This guide covers caching, rate limiting, batch processing, and distributed execution.

## Caching Strategies

### Request-Level Caching

```text
object RequestCaching {
  
  import scala.collection.mutable
  import scala.concurrent.duration._
  
  case class CachedResponse(
    result: String,
    timestamp: Long,
    ttl: Duration
  ) {
    def isExpired: Boolean = 
      System.currentTimeMillis() - timestamp > ttl.toMillis
  }
  
  class RequestCache(maxSize: Int = 1000) {
    private val cache = mutable.LinkedHashMap[String, CachedResponse]()
    
    def get(key: String): Option[String] = {
      cache.get(key).flatMap { cached =>
        if (cached.isExpired) {
          cache.remove(key)
          None
        } else {
          Some(cached.result)
        }
      }
    }
    
    def put(key: String, value: String, ttl: Duration): Unit = {
      if (cache.size >= maxSize) {
        cache.remove(cache.head._1) // Remove oldest
      }
      cache(key) = CachedResponse(value, System.currentTimeMillis(), ttl)
    }
    
    def stats(): CacheStats = CacheStats(
      size = cache.size,
      hits = hitCount,
      misses = missCount,
      hitRate = hitCount.toDouble / (hitCount + missCount)
    )
  }
  
  case class CacheStats(size: Int, hits: Long, misses: Long, hitRate: Double)
}
```

### Distributed Cache (Redis)

```text
object DistributedCache {
  
  import redis.clients.jedis.Jedis
  
  class RedisCache(host: String = "localhost", port: Int = 6379) {
    private val jedis = new Jedis(host, port)
    
    def get(key: String): Option[String] = {
      Option(jedis.get(key))
    }
    
    def put(key: String, value: String, ttlSeconds: Int): Unit = {
      jedis.setex(key, ttlSeconds, value)
    }
    
    def invalidate(keyPattern: String): Long = {
      val keys = jedis.keys(keyPattern)
      if (keys.isEmpty) 0L
      else jedis.del(keys.toArray: _*)
    }
  }
  
  // Usage with LLM4S
  def answerWithCaching(
    query: String,
    agent: Agent,
    cache: RedisCache
  ): Result[String] = {
    val cacheKey = s"query:${query.hashCode}"
    
    cache.get(cacheKey) match {
      case Some(cachedAnswer) =>
        println(s"Cache hit for: $query")
        Result.success(cachedAnswer)
      case None =>
        agent.run(query).map { response =>
          cache.put(cacheKey, response.message, ttlSeconds = 3600)
          response.message
        }
    }
  }
}
```

---

## Rate Limiting

### Token Bucket Algorithm

```text
object RateLimiting {
  
  import scala.concurrent.duration._
  
  class TokenBucket(
    capacity: Int,
    refillRate: Int, // tokens per second
    refillInterval: Duration = 1.second
  ) {
    private var tokens = capacity.toDouble
    private var lastRefillTime = System.currentTimeMillis()
    
    def tryConsume(amount: Int = 1): Boolean = {
      refill()
      if (tokens >= amount) {
        tokens -= amount
        true
      } else {
        false
      }
    }
    
    def availableTokens: Int = tokens.toInt
    
    private def refill(): Unit = {
      val now = System.currentTimeMillis()
      val timeSinceLastRefill = now - lastRefillTime
      val tokensToAdd = (timeSinceLastRefill / refillInterval.toMillis) * refillRate
      tokens = Math.min(capacity, tokens + tokensToAdd)
      lastRefillTime = now
    }
  }
  
  // Usage
  val rateLimiter = new TokenBucket(
    capacity = 1000,
    refillRate = 100 // 100 tokens per second = 100 requests/sec
  )
  
  def limitedLLMCall(
    query: String,
    agent: Agent
  ): Result[String] = {
    if (rateLimiter.tryConsume()) {
      agent.run(query)
    } else {
      Result.failure(s"Rate limit exceeded. Available tokens: ${rateLimiter.availableTokens}")
    }
  }
}
```

### Per-User Rate Limiting

```text
object PerUserRateLimiting {
  
  import scala.collection.mutable
  
  class UserRateLimiter {
    private val buckets = mutable.Map[String, TokenBucket]()
    
    def getLimiter(userId: String): TokenBucket = {
      buckets.getOrElseUpdate(userId, new TokenBucket(100, 10))
    }
    
    def tryConsume(userId: String): Boolean = {
      getLimiter(userId).tryConsume()
    }
    
    def getStatus(userId: String): String = {
      val limiter = getLimiter(userId)
      s"User $userId has ${limiter.availableTokens} tokens available"
    }
  }
}
```

---

## Batch Processing

### Embedding Batching

```text
object EmbeddingBatching {
  
  import scala.concurrent.Future
  
  case class EmbeddingJob(
    text: String,
    callback: Vector[Double] => Unit
  )
  
  class BatchEmbeddingProcessor(
    embeddingClient: EmbeddingClient,
    batchSize: Int = 50,
    batchTimeoutMs: Int = 1000
  ) {
    private var batch = scala.collection.mutable.ListBuffer[EmbeddingJob]()
    private var lastBatchTime = System.currentTimeMillis()
    
    def embed(text: String): Future[Vector[Double]] = {
      val promise = scala.concurrent.Promise[Vector[Double]]()
      
      synchronized {
        batch += EmbeddingJob(text, embedding => {
          promise.success(embedding)
        })
        
        if (batch.size >= batchSize || 
            System.currentTimeMillis() - lastBatchTime > batchTimeoutMs) {
          processBatch()
        }
      }
      
      promise.future
    }
    
    private def processBatch(): Unit = {
      if (batch.nonEmpty) {
        val texts = batch.map(_.text).toList
        val callbacks = batch.map(_.callback).toList
        
        // Embed all at once
        val embeddings = embeddingClient.embedBatch(texts)
        
        // Call callbacks
        embeddings.zip(callbacks).foreach { case (emb, callback) =>
          callback(emb)
        }
        
        batch.clear()
        lastBatchTime = System.currentTimeMillis()
      }
    }
  }
}
```

---

## Distributed Execution

### Worker Pool Pattern

```text
object WorkerPool {
  
  import scala.concurrent.{ExecutionContext, Future}
  import java.util.concurrent.{Executors, ThreadPoolExecutor}
  
  class AgentWorkerPool(numWorkers: Int) {
    private implicit val ec = ExecutionContext.fromExecutor(
      Executors.newFixedThreadPool(numWorkers)
    )
    
    def processInParallel[T](
      tasks: List[String],
      processor: String => Result[T]
    ): Future[List[Result[T]]] = {
      val futures = tasks.map(task =>
        Future {
          processor(task)
        }
      )
      Future.sequence(futures)
    }
  }
  
  // Usage: Process multiple queries in parallel
  def processMultipleQueries(
    queries: List[String],
    agent: Agent
  ): Future[List[Result[String]]] = {
    val pool = new AgentWorkerPool(numWorkers = 4)
    
    pool.processInParallel(queries) { query =>
      agent.run(query).map(_.message)
    }
  }
}
```

### Load Balancing Across Models

```text
object LoadBalancing {
  
  case class ModelLoad(
    modelName: String,
    currentLoad: Int,
    capacity: Int
  ) {
    def utilization: Double = currentLoad.toDouble / capacity
  }
  
  class LoadBalancer(models: List[LlmClient]) {
    private val loads = scala.collection.mutable.Map[String, Int]()
    models.foreach(m => loads(m.modelName) = 0)
    
    def selectLeastLoaded(): LlmClient = {
      models.minBy(m => loads(m.modelName))
    }
    
    def recordRequest(modelName: String): Unit = {
      loads(modelName) = loads.getOrElse(modelName, 0) + 1
    }
    
    def recordCompletion(modelName: String): Unit = {
      loads(modelName) = Math.max(0, loads.getOrElse(modelName, 1) - 1)
    }
    
    def execute(query: String): Result[String] = {
      val model = selectLeastLoaded()
      recordRequest(model.modelName)
      try {
        model.generate(query)
      } finally {
        recordCompletion(model.modelName)
      }
    }
  }
}
```

---

## Queue-Based Processing

### Task Queue

```text
object TaskQueue {
  
  import scala.concurrent.{Future, Promise}
  import scala.collection.mutable
  
  sealed trait TaskStatus
  case object Pending extends TaskStatus
  case object Processing extends TaskStatus
  case object Completed extends TaskStatus
  case object Failed extends TaskStatus
  
  case class Task(
    id: String,
    query: String,
    status: TaskStatus = Pending,
    result: Option[String] = None
  )
  
  class TaskProcessor(agent: Agent, numWorkers: Int = 3) {
    private val queue = mutable.Queue[Task]()
    private val tasks = mutable.Map[String, Task]()
    private var running = true
    
    def submitTask(query: String): String = {
      val taskId = java.util.UUID.randomUUID().toString
      val task = Task(taskId, query)
      queue.enqueue(task)
      tasks(taskId) = task
      taskId
    }
    
    def getStatus(taskId: String): Option[TaskStatus] = {
      tasks.get(taskId).map(_.status)
    }
    
    def getResult(taskId: String): Option[String] = {
      tasks.get(taskId).flatMap(_.result)
    }
    
    def start(): Future[Unit] = {
      Future {
        while (running) {
          if (queue.nonEmpty) {
            val task = queue.dequeue()
            processTask(task)
          }
          Thread.sleep(100)
        }
      }(scala.concurrent.ExecutionContext.global)
    }
    
    private def processTask(task: Task): Unit = {
      tasks(task.id) = task.copy(status = Processing)
      
      agent.run(task.query) match {
        case Result.Success(response) =>
          tasks(task.id) = task.copy(
            status = Completed,
            result = Some(response.message)
          )
        case Result.Failure(error) =>
          tasks(task.id) = task.copy(
            status = Failed,
            result = Some(s"Error: $error")
          )
      }
    }
  }
}
```

---

## Performance Optimization

### Inference Optimization

```text
object InferenceOptimization {
  
  // Use smaller models for simple tasks
  def selectModelByComplexity(query: String): LlmClient = {
    if (query.length < 100) {
      fastModel // faster, cheaper
    } else {
      powerfulModel // slower, more capable
    }
  }
  
  // Parallel generation with early stopping
  def generateWithTimeout(
    agent: Agent,
    query: String,
    timeoutMs: Long = 5000
  ): Result[String] = {
    val future = scala.concurrent.Future {
      agent.run(query)
    }(scala.concurrent.ExecutionContext.global)
    
    scala.concurrent.Await.result(
      future,
      scala.concurrent.duration.Duration(timeoutMs, "ms")
    )
  }
  
  // Speculative decoding (decode multiple possibilities in parallel)
  def speculativeDecoding(
    agent: Agent,
    query: String
  ): Result[String] = {
    // Generate with draft model
    val draft = draftAgent.run(query)
    
    // Verify with main model
    val verified = agent.run(draft.message)
    
    verified
  }
}
```

---

## Scaling Checklist

### ✅ Before scaling

- [ ] Current implementation handles 10x load?
- [ ] Identified bottleneck (CPU, memory, I/O)?
- [ ] Have baseline metrics?
- [ ] Cost estimates for 10x scale?
- [ ] Cache strategy defined?
- [ ] Rate limiting in place?
- [ ] Error handling for overload?

### ✅ Scaling implementation

- [ ] Horizontal scaling set up?
- [ ] Load balancing configured?
- [ ] Database/cache scaled?
- [ ] Monitoring alarms set?
- [ ] Gradual rollout planned (10% → 25% → 50% → 100%)?
- [ ] Rollback plan ready?
- [ ] Performance verified at scale?

---

## See Also

- [Production Monitoring](./production-monitoring.md) - Monitor scaled systems
- [Error Recovery](./error-recovery.md) - Handle failures at scale
- [Security Best Practices](./security-best-practices.md) - Secure at scale

---

**Last Updated:** February 2026  
**Status:** Production Ready
