package org.llm4s.trace

import scala.util.Try

/**
 * Reads the body of a Langfuse batch ingestion response.
 *
 * `POST /api/public/ingestion` answers `207 Multi-Status` with one entry per event:
 * `{"successes": [{"id": ..., "status": 201}], "errors": [{"id": ..., "status": 400, "message": ...}]}`.
 * A 207 is therefore not a success on its own - the batch may have been only partly accepted, and
 * the events in `errors` were dropped. Both senders use this so that a partial rejection is
 * reported rather than logged as a successful export.
 */
private[trace] object LangfuseIngestionResponse {

  /** An event Langfuse refused, as listed in the 207 body's `errors`. */
  final case class Rejection(id: String, status: Option[Int], message: String) {
    def describe: String = s"$id (${status.fold("")(s => s"$s: ")}$message)"
  }

  /**
   * The events a 207 body says were rejected: empty when every event was accepted, or when the body
   * lists no per-event errors. `Left` with the reason when the body cannot be read at all.
   */
  def rejections(body: String): Either[String, Seq[Rejection]] =
    Try(ujson.read(body)).toEither.left
      .map(e => s"unreadable 207 body: ${e.getMessage}")
      .flatMap(_.objOpt.toRight("207 body is not a JSON object"))
      .map { root =>
        root.get("errors").flatMap(_.arrOpt).map(_.toSeq).getOrElse(Seq.empty).map { entry =>
          val fields = entry.objOpt.getOrElse(ujson.Obj().obj)
          Rejection(
            id = fields.get("id").flatMap(_.strOpt).getOrElse("<no id>"),
            status = fields.get("status").flatMap(_.numOpt).map(_.toInt),
            message = fields
              .get("message")
              .orElse(fields.get("error"))
              .map(v => v.strOpt.getOrElse(v.render()))
              .getOrElse("no message")
          )
        }
      }

  /** One line for logs and errors: how many of the batch's events were rejected, and which. */
  def summary(rejected: Seq[Rejection], batchSize: Int): String =
    s"Langfuse rejected ${rejected.size} of $batchSize events: ${rejected.map(_.describe).mkString(", ")}"
}
