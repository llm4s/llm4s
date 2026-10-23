package org.llm4s.deploy

import org.llm4s.agent.graph.{ GraphRuntime, InMemoryCheckpointer }
import org.llm4s.agent.service.*

import scala.concurrent.duration.*

/** An example service over a fresh in-memory store, with a worker that polls fast. Shut it down with [[stop]]. */
final class ServiceFixture(toolDelay: FiniteDuration = 50.millis, startWorker: Boolean = true) {

  val config: AgentServiceConfig =
    AgentServiceConfig.default.copy(toolDelay = toolDelay, eventsWait = 200.millis, pageSize = 3)

  val store: InMemoryCheckpointer = new InMemoryCheckpointer()

  private val queueSettings = RunQueueSettings(lease = LeasePolicy(ttl = 2.seconds, renewEvery = 200.millis))

  val worker: AgentWorker = {
    val agent = ExampleAgent
      .build(config.agentId, new DemoModel(toolDelay), new GraphRuntime(store), config)
      .fold(e => throw new IllegalStateException(e.message), identity)
    AgentWorker(
      store.runQueue,
      store,
      Seq(QueuedAgent(agent)),
      WorkerSettings(pollInterval = 20.millis, sweepEvery = None, drainGrace = 2.seconds),
      queueSettings
    ).fold(e => throw new IllegalStateException(e.message), identity)
  }

  val submitter: RunSubmitter = new RunSubmitter(store.runQueue, store, queueSettings)

  val service: AgentService = new AgentService(submitter, store, config.agentId, Some(worker))

  if (startWorker) worker.start()

  def stop(): Unit = worker.shutdown(): Unit
}
