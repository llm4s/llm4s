package org.llm4s.deploy

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.service.*
import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.trace.TraceContext
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{ Base64, Locale }
import scala.util.Try

/** An HTTP answer before it is written: a status and a JSON body. */
final case class Reply(status: Int, body: ujson.Value)

/**
 * The example service's operations (design 4.60), over a store's [[org.llm4s.agent.service.RunSubmitter
 * RunSubmitter]] and `Checkpointer`, independent of the web stack so they are tested without a server. Each answers a
 * [[Reply]]; [[AgentRoutes]] puts them on cask.
 *
 * Every call is scoped to `tenant` (Stage 5 rule 9), which the routes read from the `X-Tenant-Id` header. That header
 * is **not authentication**: this is an example, and a real service decides the tenant from its own auth.
 *
 * It is a small example, not an API standard: the HTTP server product is a separate project.
 */
final class AgentService(
  val submitter: RunSubmitter,
  val checkpointer: Checkpointer,
  val agentId: AgentId,
  val worker: Option[AgentWorker]
):
  import AgentService.*

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Queues a run on `threadId` from the JSON `body`: exactly one of `"query"` (a new turn), `"answers"` (an object of
   * interrupt id to answer, resuming a suspended thread) or `"recover": true`; optional `"policy"` (`reject`, the
   * default, `enqueue` or `interrupt`) and `"submissionKey"`. `traceParent` is the request's W3C `traceparent`
   * header, which every attempt of the run continues; an invalid one is ignored, as W3C Trace Context asks.
   * `202` with the ticket; `409` when the thread is busy under `reject`, or the key names another submission.
   */
  def submit(tenant: Option[TenantId], threadId: String, body: String, traceParent: Option[String]): Reply =
    answer {
      for
        thread <- threadIdOf(threadId)
        json   <- parse(body)
        input  <- inputOf(json)
        policy <- policyOf(json)
        key    <- keyOf(json)
        ticket <- submitter.submit(
          thread,
          agentId,
          input,
          tenant,
          policy,
          key,
          traceParent.flatMap(traceContextOf)
        )
      yield Reply(
        202,
        ujson.Obj(
          "entryId"  -> ticket.entryId.value.toString,
          "threadId" -> ticket.threadId.value,
          "position" -> ticket.position.fold[ujson.Value](ujson.Null)(p => ujson.Num(p.toDouble)),
          "existing" -> ticket.existing
        )
      )
    }

  /** The queue entry `entryId` as it is now: `200`, `404` for an unknown one, `410` once it has been reclaimed. */
  def entry(tenant: Option[TenantId], entryId: String): Reply =
    answer(entryIdOf(entryId).flatMap(submitter.entry(tenant, _)).map(e => Reply(200, entryJson(e))))

  /**
   * Cancels the queue entry `entryId`: one not yet started closes at once; a running one is stopped by the worker
   * holding it, on whichever machine. `202` with the entry as it is after the request.
   */
  def cancel(tenant: Option[TenantId], entryId: String): Reply =
    answer {
      entryIdOf(entryId).flatMap { id =>
        submitter.cancel(tenant, CancelTarget.Entry(id), Some("cancelled through the example service")).map { entries =>
          Reply(202, entries.find(_.id == id).fold[ujson.Value](ujson.Null)(entryJson))
        }
      }
    }

  /**
   * The thread `threadId`: its latest checkpoint's status, run and agent, how its last run settled, and its messages
   * as the agent stores them. `404` for a thread that does not exist, or is another tenant's.
   */
  def thread(tenant: Option[TenantId], threadId: String): Reply =
    answer {
      threadIdOf(threadId).flatMap { thread =>
        checkpointer.latest(thread).map {
          case Some(stored) if stored.checkpoint.tenantId == tenant.map(_.value) =>
            val c = stored.checkpoint
            Reply(
              200,
              ujson.Obj(
                "threadId"     -> c.threadId,
                "status"       -> c.status.toString.toLowerCase(Locale.ROOT),
                "checkpointId" -> c.id,
                "runId"        -> c.runId,
                "agent"        -> c.snapshot.graphId,
                "outcome"      -> c.outcome.fold[ujson.Value](ujson.Null)(upickle.default.writeJs(_)),
                "messages"     -> c.snapshot.state.get(MessagesKey).fold[ujson.Value](ujson.Arr())(_.value)
              )
            )
          case _ => notFound(s"no thread $threadId")
        }
      }
    }

  /**
   * A page of the tenant's root threads, newest first: optionally of one `status` (`idle`, `running`, `suspended`,
   * `failed`, `completed`, `deleted`), at most `limit` (1 to 500), after the `next` cursor an earlier page returned.
   */
  def threads(tenant: Option[TenantId], status: Option[String], limit: Int, after: Option[String]): Reply =
    answer {
      for
        s      <- status.filter(_.nonEmpty).fold[Result[Option[ThreadStatus]]](Right(None))(statusOf(_).map(Some(_)))
        n      <- ThreadQuery.checkLimit(limit)
        cursor <- after.filter(_.nonEmpty).fold[Result[Option[ThreadCursor]]](Right(None))(cursorOf(_).map(Some(_)))
        page   <- checkpointer.threads(ThreadQuery(tenant, status = s, after = cursor, limit = n))
      yield Reply(
        200,
        ujson.Obj(
          "threads" -> ujson.Arr.from(page.threads.map { t =>
            ujson.Obj(
              "threadId"     -> t.threadId.value,
              "agent"        -> t.agent,
              "status"       -> t.status.toString.toLowerCase(Locale.ROOT),
              "checkpointId" -> t.checkpointId,
              "metadata"     -> t.metadata.toJson,
              "updatedAt"    -> t.updatedAt.toString
            )
          }),
          "next" -> page.next.fold[ujson.Value](ujson.Null)(c => ujson.Str(cursorText(c)))
        )
      )
    }

  /**
   * Readiness: the worker's own answer (`AgentWorker.health`) - running, its loop alive, the store answering. `200`
   * when ready, `503` otherwise, and `503` for a service built with no worker.
   */
  def ready(): Reply =
    worker match
      case None => Reply(503, ujson.Obj("ready" -> false, "problems" -> ujson.Arr("this service runs no worker")))
      case Some(w) =>
        val h = Try(w.health()).getOrElse(WorkerHealth(false, w.currentState, 0, Vector("the health check failed")))
        Reply(
          if h.ready then 200 else 503,
          ujson.Obj(
            "ready"    -> h.ready,
            "state"    -> h.state.toString.toLowerCase(Locale.ROOT),
            "running"  -> h.running,
            "problems" -> ujson.Arr.from(h.problems.map(ujson.Str(_)))
          )
        )

  /**
   * One read of entry `entryId`'s events after thread event `after`, waiting up to the configured bound for a commit:
   * see `RunSubmitter.follow`. For [[AgentRoutes]]'s event stream.
   */
  def follow(
    tenant: Option[TenantId],
    entryId: EntryId,
    after: Long,
    settings: AgentServiceConfig
  ): Result[FollowedRun] =
    submitter.follow(tenant, entryId, after, settings.pageSize, settings.eventsWait)

  /** The `Reply` for `result`, mapping an error to its status. */
  private def answer(result: Result[Reply]): Reply = result.fold(errorReply, identity)

  /** The status and body an error is answered with; a server-side error's message stays in the log. */
  private[deploy] def errorReply(error: LLMError): Reply =
    val status = statusOf(error)
    if status >= 500 then
      logger.warn(s"Request failed: ${error.getClass.getSimpleName}: ${error.message}")
      Reply(status, ujson.Obj("error" -> error.getClass.getSimpleName))
    else Reply(status, ujson.Obj("error" -> error.getClass.getSimpleName, "message" -> error.message))

object AgentService:

  /** The state key the agent keeps its messages under. */
  private val MessagesKey = "messages"

  /** The HTTP status an error is answered with. */
  private[deploy] def statusOf(error: LLMError): Int = error match
    case _: ValidationError                                           => 400
    case _: GraphError.ThreadBusy | _: QueueError.SubmissionKeyReused => 409
    case _: GraphError.PendingInterrupts | _: GraphError.NotSuspended => 409
    case _: GraphError.NothingToRecover                               => 409
    case _: QueueError.TicketNotFound | _: GraphError.TenantMismatch  => 404
    case _: QueueError.TicketExpired | _: GraphError.ThreadDeleted    => 410
    case _                                                            => 500

  private def notFound(message: String): Reply = Reply(404, ujson.Obj("error" -> "NotFound", "message" -> message))

  private def invalid(field: String, why: String): ValidationError = ValidationError(field, why)

  private def threadIdOf(raw: String): Result[ThreadId] =
    Either.cond(
      raw.trim.nonEmpty && raw.length <= 256,
      ThreadId(raw),
      invalid("threadId", "must be 1 to 256 characters")
    )

  private[deploy] def entryIdOf(raw: String): Result[EntryId] = EntryId.parse(raw)

  /** The error for a `Last-Event-ID` (or `after`) that is not an event number. */
  private[deploy] def invalidCursor(raw: String): ValidationError =
    invalid("Last-Event-ID", s"must be a non-negative event number, was '${raw.take(32)}'")

  private def parse(body: String): Result[ujson.Obj] =
    Try(ujson.read(body)).toOption.flatMap(_.objOpt.map(ujson.Obj(_))).toRight(invalid("body", "must be a JSON object"))

  private def inputOf(json: ujson.Obj): Result[QueuedInput] =
    Vector("query", "answers", "recover").filter(json.value.contains) match
      case Vector("query") =>
        json("query").strOpt.filter(_.nonEmpty).map(QueuedInput.query(_)).toRight(invalid("query", "must be a string"))
      case Vector("answers") =>
        json("answers").objOpt
          .map(answers => QueuedInput.Resume(answers.iterator.map((k, v) => InterruptId(k) -> v).toMap))
          .toRight(invalid("answers", "must be an object of interrupt id to answer"))
      case Vector("recover") =>
        Either.cond(json("recover").boolOpt.contains(true), QueuedInput.Recover, invalid("recover", "must be true"))
      case _ => Left(invalid("body", "must hold exactly one of query, answers or recover"))

  private def policyOf(json: ujson.Obj): Result[BusyThreadPolicy] =
    json.value.get("policy").map(_.strOpt.map(_.toLowerCase(Locale.ROOT))) match
      case None | Some(Some("reject")) => Right(BusyThreadPolicy.Reject)
      case Some(Some("enqueue"))       => Right(BusyThreadPolicy.Enqueue)
      case Some(Some("interrupt"))     => Right(BusyThreadPolicy.Interrupt)
      case _                           => Left(invalid("policy", "must be reject, enqueue or interrupt"))

  private def keyOf(json: ujson.Obj): Result[Option[SubmissionKey]] =
    json.value.get("submissionKey") match
      case None => Right(None)
      case Some(value) =>
        value.strOpt.toRight(invalid("submissionKey", "must be a string")).flatMap(SubmissionKey.of).map(Some(_))

  /** The request's trace position, or none for a header that is not a valid `traceparent`. */
  private[deploy] def traceContextOf(header: String): Option[TraceContext] = TraceContext.parse(header.trim).toOption

  private def statusOf(raw: String): Result[ThreadStatus] =
    ThreadStatus.values
      .find(_.toString.equalsIgnoreCase(raw))
      .toRight(
        invalid(
          "status",
          s"must be one of ${ThreadStatus.values.map(_.toString.toLowerCase(Locale.ROOT)).mkString(", ")}"
        )
      )

  /** A registry cursor as an opaque string: the base64url of `<updatedAt>\n<threadId>`. */
  private[deploy] def cursorText(cursor: ThreadCursor): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(
      s"${cursor.updatedAt}\n${cursor.threadId.value}".getBytes(StandardCharsets.UTF_8)
    )

  private[deploy] def cursorOf(text: String): Result[ThreadCursor] =
    Try(new String(Base64.getUrlDecoder.decode(text), StandardCharsets.UTF_8)).toOption
      .flatMap { decoded =>
        decoded.split("\n", 2) match
          case Array(at, thread) => Try(ThreadCursor(Instant.parse(at), ThreadId(thread))).toOption
          case _                 => None
      }
      .toRight(invalid("after", "is not a cursor this service returned"))

  /** An entry as the service shows it: no input, which is the caller's own (Stage 5 rule 10). */
  private[deploy] def entryJson(e: QueueEntry): ujson.Obj =
    ujson.Obj(
      "entryId"   -> e.id.value.toString,
      "threadId"  -> e.threadId.value,
      "agent"     -> e.agentId.value,
      "kind"      -> e.input.kind,
      "policy"    -> e.policy.toString.toLowerCase(Locale.ROOT),
      "state"     -> e.outcome.fold("open")(_.state),
      "outcome"   -> e.outcome.fold[ujson.Value](ujson.Null)(outcomeJson),
      "attempts"  -> e.attempts.size,
      "admitted"  -> e.admitted,
      "position"  -> e.position.fold[ujson.Value](ujson.Null)(p => ujson.Num(p.toDouble)),
      "submitted" -> e.submittedAt.toString,
      "closed"    -> e.closedAt.fold[ujson.Value](ujson.Null)(t => ujson.Str(t.toString))
    )

  private def outcomeJson(o: EntryOutcome): ujson.Obj = o match
    case EntryOutcome.Done(checkpoint, status) =>
      ujson.Obj(
        "state"        -> "done",
        "checkpointId" -> checkpoint.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
        "status"       -> status.fold[ujson.Value](ujson.Null)(s => ujson.Str(s.toString.toLowerCase(Locale.ROOT)))
      )
    case EntryOutcome.Failed(reason) => ujson.Obj("state" -> "failed", "reason" -> reason.productPrefix)
    case EntryOutcome.Cancelled(reason) =>
      ujson.Obj(
        "state" -> "cancelled",
        "reason" -> (reason match
          case CancelReason.ThreadDeleted => "threadDeleted"
          case CancelReason.Requested(_)  => "requested"
        )
      )
    case EntryOutcome.Interrupted(by) =>
      ujson.Obj("state" -> "interrupted", "by" -> by.fold[ujson.Value](ujson.Null)(id => ujson.Str(id.value.toString)))
