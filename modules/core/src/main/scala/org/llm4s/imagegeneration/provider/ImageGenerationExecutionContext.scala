package org.llm4s.imagegeneration.provider

import scala.concurrent.ExecutionContext
import java.util.concurrent.{ ThreadFactory, ThreadPoolExecutor, TimeUnit, LinkedBlockingQueue }
import java.util.concurrent.atomic.AtomicInteger

private[imagegeneration] object ImageGenerationExecutionContext {

  def bounded(namePrefix: String, corePoolSize: Int = 2, maxPoolSize: Int = 8): ExecutionContext = {
    val threadFactory = new ThreadFactory {
      private val counter = new AtomicInteger(0)
      override def newThread(r: Runnable): Thread = {
        val thread = new Thread(r, s"$namePrefix-${counter.incrementAndGet()}")
        thread.setDaemon(true)
        thread
      }
    }

    val executor = new ThreadPoolExecutor(
      corePoolSize,
      maxPoolSize,
      60L,
      TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable](100),
      threadFactory
    )

    executor.allowCoreThreadTimeOut(true)
    sys.addShutdownHook {
      executor.shutdown()
    }

    ExecutionContext.fromExecutor(executor)
  }
}
