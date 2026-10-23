package org.llm4s.deploy

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.llm4s.agent.graph.spi.CheckpointStore
import org.llm4s.agent.metrics.MeteredCheckpointer
import org.llm4s.agent.service.{ AgentWorker, DrainReport, QueuedAgent, RunSubmitter }
import org.llm4s.config.{ CheckpointerConfigLoader, RunQueueConfigLoader, RuntimeConfigLoader, WorkerConfigLoader }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.metrics.{ MetricsCollector, PrometheusMetrics }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

/**
 * The example service's store, agent, worker and metrics, opened from configuration alone (design 4.60):
 *
 *  - the store and its run queue from `llm4s.agent.checkpointer` (`CheckpointerConfigLoader.open()`): `memory` by
 *    default, `postgres` with `LLM4S_CHECKPOINTER=postgres` and `LLM4S_CHECKPOINTER_POSTGRES_URL` / `_USER` /
 *    `_PASSWORD`, so several instances share threads;
 *  - the runtime's claim and watch policies from `llm4s.agent.runtime` (`RuntimeConfigLoader`), whose claim label is
 *    `LLM4S_RUNTIME_LABEL` - the pod name, in the manifests;
 *  - the queue's settings from `llm4s.agent.queue` and the worker's from `llm4s.agent.worker`, whose label is
 *    `LLM4S_WORKER_LABEL`;
 *  - with metrics on, a Prometheus registry fed by a `MeteredCheckpointer` and the worker (design 4.59).
 *
 * [[start]] starts the worker; [[drain]] drains it (`AgentWorker.shutdown`); [[close]] drains it and closes the client
 * and the store.
 */
final class ServiceRuntime private (
  val service: AgentService,
  val worker: AgentWorker,
  val store: CheckpointStore,
  client: LLMClient,
  registry: Option[PrometheusRegistry]
):

  private val logger  = LoggerFactory.getLogger(getClass)
  private val drained = new AtomicBoolean(false)
  private val closed  = new AtomicBoolean(false)

  /** Renders the metrics in Prometheus text format; `None` with metrics off. */
  val metricsText: Option[() => String] = registry.map(r =>
    () =>
      val out = new ByteArrayOutputStream()
      PrometheusTextFormatWriter.create().write(out, r.scrape())
      out.toString(StandardCharsets.UTF_8)
  )

  /** Starts the worker taking queued runs. */
  def start(): Unit = worker.start()

  /**
   * Drains the worker: it stops taking entries, lets running runs finish within `llm4s.agent.worker.drain.grace`, then
   * cancels the rest, whose entries another instance recovers at once. Only the first call drains; later ones answer
   * an empty report. The HTTP endpoints keep answering from the store until [[close]].
   */
  def drain(): DrainReport =
    if !drained.compareAndSet(false, true) then DrainReport(Vector.empty, Vector.empty, Vector.empty)
    else
      val report = worker.shutdown()
      logger.info(
        s"Drained: ${report.finished.size} finished, ${report.cancelled.size} cancelled, " +
          s"${report.abandoned.size} abandoned"
      )
      report

  /** [[drain]], then closes the model client and the store, once. */
  def close(): Unit =
    drain(): Unit
    if closed.compareAndSet(false, true) then
      Try(client.close()).failed.foreach(e => logger.warn("Closing the model client failed", e))
      Try(store.close()).failed.foreach(e => logger.warn("Closing the checkpoint store failed", e))

object ServiceRuntime:

  /** Opens the store `llm4s.agent.checkpointer` names and builds the service over it; nothing is left open on failure. */
  def open(config: AgentServiceConfig): Result[ServiceRuntime] =
    CheckpointerConfigLoader.open().flatMap { store =>
      val built = over(store, config)
      built.left.foreach(_ => store.close())
      built
    }

  /** The service over an open `store`, which the result's [[ServiceRuntime.close]] closes. */
  def over(store: CheckpointStore, config: AgentServiceConfig): Result[ServiceRuntime] =
    val registry = Option.when(config.metricsEnabled)(new PrometheusRegistry())
    val metrics  = registry.fold(MetricsCollector.noop)(new PrometheusMetrics(_))
    val checkpointer =
      if config.metricsEnabled then MeteredCheckpointer(store.checkpointer, metrics) else store.checkpointer
    for
      queue          <- store.runQueue
      queueSettings  <- RunQueueConfigLoader.load()
      workerSettings <- WorkerConfigLoader.load()
      runtime        <- RuntimeConfigLoader.runtime(checkpointer)
      client         <- ExampleAgent.client(config)
      built = for
        agent  <- ExampleAgent.build(config.agentId, client, runtime, config)
        worker <- AgentWorker(queue, checkpointer, Seq(QueuedAgent(agent)), workerSettings, queueSettings, metrics)
      yield worker
      _ = built.left.foreach(_ => Try(client.close()))
      worker <- built
    yield
      val submitter = new RunSubmitter(queue, checkpointer, queueSettings, metrics)
      new ServiceRuntime(
        new AgentService(submitter, checkpointer, config.agentId, Some(worker)),
        worker,
        store,
        client,
        registry
      )
