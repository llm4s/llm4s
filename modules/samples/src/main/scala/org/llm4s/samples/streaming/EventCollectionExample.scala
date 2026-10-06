package org.llm4s.samples.streaming

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.{ GraphRuntime, RunEvent, StreamEvent, ThreadId }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.slf4j.LoggerFactory

import java.util.concurrent.CountDownLatch
import scala.collection.mutable

/**
 * Live versus durable events. A run's events are collected as it streams; afterwards the runtime's
 * log is replayed with `runtime.subscribe(threadId, afterSeq = 0)`. The replay holds the durable
 * events - structure and counts, no text, arguments or tool results.
 *
 * To run: sbt "samples/runMain org.llm4s.samples.streaming.EventCollectionExample"
 */
object EventCollectionExample:
  private val logger = LoggerFactory.getLogger(getClass)

  private def isTerminal(e: RunEvent): Boolean = e match
    case RunEvent.RunCompleted | RunEvent.RunSuspended(_) | RunEvent.RunCancelled | RunEvent.RunTimedOut => true
    case RunEvent.RunFailed(_)                                                                           => true
    case _                                                                                               => false

  private def nameOf(e: RunEvent): String = e match
    case RunEvent.Custom(name, _, _) => name
    case other                       => other.productPrefix

  def main(args: Array[String]): Unit =
    val runtime  = GraphRuntime.inMemory()
    val threadId = ThreadId("event-collection-sample")
    val result = for
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)
      agent <- Agent
        .builder("assistant", client)
        .withSystemPrompt("You are concise.")
        .withStreaming()
        .withRuntime(runtime)
        .build()
      live = Vector.newBuilder[StreamEvent]
      run  <- agent.stream(threadId, "Name three Scala collections.")(e => live.synchronized { live += e; () })
      done <- run.await()
      liveEvents = live.synchronized(live.result())
      durableCount = liveEvents.count {
        case _: StreamEvent.Durable => true
        case _                      => false
      }
      _        = println(s"Live: ${liveEvents.size - durableCount}, durable: $durableCount")
      replayed = mutable.ArrayBuffer.empty[StreamEvent]
      latch    = new CountDownLatch(1)
      sub <- runtime.subscribe(threadId, afterSeq = 0) { e =>
        replayed.synchronized {
          replayed += e
          e match
            case StreamEvent.Durable(r) if isTerminal(r.event) => latch.countDown()
            case _: StreamEvent.Disconnected                   => latch.countDown()
            case _                                             => ()
        }
      }
      _ = latch.await()
      _ = sub.cancel()
      _ = replayed.synchronized(replayed.toList).foreach {
        case StreamEvent.Durable(r) => println(s"  #${r.seq} ${nameOf(r.event)}")
        case _                      => ()
      }
    yield done
    result.fold(e => logger.error("Failed: {}", e.formatted), r => logger.info("Status: {}", r.status))
