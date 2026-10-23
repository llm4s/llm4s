package org.llm4s.deploy

import org.llm4s.agent.graph.{ EventRecord, TenantId }
import org.llm4s.agent.service.EntryId

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.util.Try

/**
 * The example agent service's endpoints (design 4.60), over an [[AgentService]]:
 *
 *  - `POST /threads/{threadId}/runs`: queue a run - `{"query": "..."}`, `{"answers": {...}}` or `{"recover": true}`,
 *    with optional `"policy"` (`reject` | `enqueue` | `interrupt`) and `"submissionKey"`. A `traceparent` header is
 *    continued by every attempt of the run. `202` with the ticket (`entryId`, `position`, `existing`).
 *  - `GET /runs/{entryId}`: the queue entry, open or closed with its outcome.
 *  - `GET /runs/{entryId}/events`: the run's events as Server-Sent Events, each with its thread sequence number as
 *    `id`, from after `Last-Event-ID` (or `?after=N`; from the thread's first event by default), live while the run
 *    goes on, on whichever machine runs it; an `end` event with the closed entry finishes the stream.
 *  - `POST /runs/{entryId}/cancel`: cancel the run, wherever it runs.
 *  - `GET /threads/{threadId}`: the thread's latest state and messages.
 *  - `GET /threads?status=&limit=&after=`: a page of root threads, newest first.
 *  - `GET /ready`: readiness, from the worker's health; `GET /metrics`: Prometheus text, when enabled.
 *
 * `X-Tenant-Id` names the tenant every call is scoped to. It is not authentication: this service is an example, and
 * the HTTP server product is a separate project.
 *
 * @param metrics renders the metrics in Prometheus text format; `None` when metrics are off
 */
class AgentRoutes(service: AgentService, config: AgentServiceConfig, metrics: Option[() => String])
    extends cask.Routes {

  import AgentRoutes.*

  @cask.post("/threads/:threadId/runs")
  def submit(threadId: String, request: cask.Request): cask.Response[String] =
    json(service.submit(tenantOf(request), threadId, request.text(), header(request, "traceparent")))

  @cask.get("/runs/:entryId")
  def entry(entryId: String, request: cask.Request): cask.Response[String] =
    json(service.entry(tenantOf(request), entryId))

  @cask.post("/runs/:entryId/cancel")
  def cancel(entryId: String, request: cask.Request): cask.Response[String] =
    json(service.cancel(tenantOf(request), entryId))

  @cask.get("/runs/:entryId/events")
  def events(entryId: String, request: cask.Request, after: String = ""): cask.Response[geny.Writable] = {
    val tenant = tenantOf(request)
    val start  = header(request, "last-event-id").orElse(Option(after).filter(_.nonEmpty)).getOrElse("0")
    val opened = for {
      id   <- AgentService.entryIdOf(entryId)
      from <- start.trim.toLongOption.filter(_ >= 0).toRight(AgentService.invalidCursor(start))
      _    <- service.submitter.entry(tenant, id)
    } yield (id, from)
    opened match {
      case Left(error) =>
        val reply = service.errorReply(error)
        cask.Response(writable(ujson.write(reply.body)), reply.status, JsonHeaders)
      case Right((id, from)) =>
        cask.Response(
          new EventStream(service, config, tenant, id, from),
          200,
          Seq("Content-Type" -> "text/event-stream", "Cache-Control" -> "no-cache", "X-Accel-Buffering" -> "no")
        )
    }
  }

  @cask.get("/threads/:threadId")
  def thread(threadId: String, request: cask.Request): cask.Response[String] =
    json(service.thread(tenantOf(request), threadId))

  @cask.get("/threads")
  def threads(request: cask.Request, status: String = "", limit: Int = 50, after: String = ""): cask.Response[String] =
    json(service.threads(tenantOf(request), Some(status), limit, Some(after)))

  @cask.get("/ready")
  def ready(): cask.Response[String] = json(service.ready())

  @cask.get("/metrics")
  def metricsText(): cask.Response[String] =
    metrics match {
      case Some(render) =>
        cask.Response(render(), 200, Seq("Content-Type" -> "text/plain; version=0.0.4; charset=utf-8"))
      case None => json(Reply(404, ujson.Obj("error" -> "NotFound", "message" -> "metrics are disabled")))
    }

  initialize()
}

object AgentRoutes {

  private val JsonHeaders = Seq("Content-Type" -> "application/json")

  private def json(reply: Reply): cask.Response[String] =
    cask.Response(ujson.write(reply.body), reply.status, JsonHeaders)

  private def writable(text: String): geny.Writable = geny.Writable.StringWritable(text)

  private[deploy] def header(request: cask.Request, name: String): Option[String] =
    request.headers.get(name).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  private[deploy] def tenantOf(request: cask.Request): Option[TenantId] =
    header(request, "x-tenant-id").map(TenantId(_))

  /** One Server-Sent Event: `id` and `event` lines when given, then the data, one line. */
  private[deploy] def frame(id: Option[Long], event: String, data: String): String =
    id.fold("")(i => s"id: $i\n") + s"event: $event\n" + s"data: $data\n\n"

  /** A keep-alive: a comment line, which a client ignores, sent when a read found nothing new. */
  private[deploy] val KeepAlive: String = ": keep-alive\n\n"

  /**
   * The body of `GET /runs/{entryId}/events`: reads the entry's events page by page through
   * `RunSubmitter.follow`, writing each as a `run-event` with its sequence number as `id`, a keep-alive comment after a
   * read that found none, and - once the entry is closed and all its events are written - an `end` event with the
   * closed entry. An error reading the store is an `error` event, ending the stream. A write that fails (the client
   * went away) ends it too.
   */
  final private[deploy] class EventStream(
    service: AgentService,
    config: AgentServiceConfig,
    tenant: Option[TenantId],
    entryId: EntryId,
    from: Long
  ) extends geny.Writable {

    override def httpContentType: Option[String] = Some("text/event-stream")

    override def writeBytesTo(out: OutputStream): Unit = {
      def send(text: String): Boolean =
        Try {
          out.write(text.getBytes(StandardCharsets.UTF_8))
          out.flush()
        }.isSuccess

      @tailrec def loop(after: Long): Unit =
        service.follow(tenant, entryId, after, config) match {
          case Left(error) =>
            send(frame(None, "error", ujson.write(service.errorReply(error).body))): Unit
          case Right(page) =>
            val frames =
              page.events.map(e => frame(Some(e.seq), "run-event", upickle.default.write[EventRecord](e)))
            val body = if (frames.isEmpty && !page.finished) KeepAlive else frames.mkString
            val sent = body.isEmpty || send(body)
            if (sent && page.finished) send(frame(None, "end", ujson.write(AgentService.entryJson(page.entry)))): Unit
            else if (sent && !Thread.currentThread().isInterrupted) loop(page.next)
        }

      loop(from)
    }
  }
}
