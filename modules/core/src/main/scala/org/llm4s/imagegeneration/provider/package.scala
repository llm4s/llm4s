package org.llm4s.imagegeneration

import java.util.concurrent.{ Executors, ThreadFactory }
import scala.concurrent.ExecutionContext

package object provider {
  // Shared blocking execution context for I/O operations
  // This avoids duplicating thread pool setup across providers
  private val threadCounter = new java.util.concurrent.atomic.AtomicLong(0)
  lazy val blockingEc: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newCachedThreadPool(new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r)
        t.setName("image-gen-io-pool-" + threadCounter.incrementAndGet())
        t.setDaemon(true)
        t
      }
    })
  )
}
