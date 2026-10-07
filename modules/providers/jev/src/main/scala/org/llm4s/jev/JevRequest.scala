package org.llm4s.jev

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * One evaluation: a `state` and the named questions to ask about it.
 *
 * {{{
 * val request = JevRequest(
 *   "Charged twice; please refund",
 *   Map(
 *     "route"  -> JevQuestion.choice("Which team should handle this?", "billing" -> "Payments and refunds", "technical" -> "Product issues"),
 *     "urgent" -> JevQuestion.noul("Does this need immediate human attention?")
 *   )
 * )
 * }}}
 *
 * The answers come back under the same ids as the questions, which the model never sees. All questions are
 * answered against the one `state` in a single call, so ask everything you might need at once.
 *
 * @param state     what to evaluate: a string, or a JSON object or array for structured data
 * @param questions the questions, by the id their answers come back under
 * @param model     the model to use, or `None` for the client's configured model (`jev-latest` by default)
 * @param headers   extra HTTP headers for this request, sent unchanged on every retry of it. The client sets the
 *                  credentials, content type and `Accept` header itself and refuses to have them replaced.
 */
final case class JevRequest private (
  state: ujson.Value,
  questions: Map[String, JevQuestion],
  model: Option[String],
  headers: Map[String, String]
) {

  // A request is the kind of object that ends up in a log line: its state can be personal data and an extra header can
  // carry a secret, so only the sizes, the question ids and the header names are shown.
  override def toString: String = {
    val shape = state match {
      case ujson.Str(text) => s"text of ${text.length} characters"
      case _: ujson.Obj    => "an object"
      case _: ujson.Arr    => "an array"
      case _               => "invalid"
    }
    s"JevRequest(state=$shape, questions=${questions.keys.toSeq.sorted.mkString("[", ", ", "]")}, " +
      s"model=${model.getOrElse("default")}, headers=${headers.keys.toSeq.sorted.mkString("[", ", ", "]")})"
  }

  /** Uses `model` for this request instead of the client's configured model. */
  def withModel(model: String): JevRequest = copy(model = Some(model))

  /** Uses `model` for this request; `None` uses the client's configured model. */
  def withModel(model: Option[String]): JevRequest = copy(model = model)

  /** Adds one extra HTTP header, replacing one of the same name. */
  def withHeader(name: String, value: String): JevRequest = copy(headers = headers.updated(name, value))

  /** Replaces the extra HTTP headers. */
  def withHeaders(headers: Map[String, String]): JevRequest = copy(headers = headers)

  /** The request as the API's JSON body, using `defaultModel` when the request names none; assumes it validates. */
  private[jev] def toJson(defaultModel: String): ujson.Value = {
    val body = ujson.Obj("state" -> state, "model" -> model.getOrElse(defaultModel))
    val qs   = ujson.Obj()
    questions.foreach { case (id, question) => qs(id) = JevQuestion.toJson(question) }
    body("questions") = qs
    body
  }

  /** Checks the request against what the API documents, before anything is sent. */
  private[jev] def validate: Result[Unit] =
    for {
      _ <-
        if (JevQuestion.isTextOrStructure(state)) Right(())
        else Left(ValidationError("state", "must be a string, an object or an array"))
      _ <- if (questions.nonEmpty) Right(()) else Left(ValidationError("questions", "at least one question is needed"))
      _ <- questions.keys
        .collectFirst {
          case id if id.trim.isEmpty || id.exists(_.isControl) =>
            ValidationError("questions", "a question id must not be blank or contain control characters")
        }
        .fold[Result[Unit]](Right(()))(Left(_))
      _ <- questions.toSeq.sortBy(_._1).foldLeft[Result[Unit]](Right(())) { case (acc, (id, question)) =>
        acc.flatMap(_ => JevQuestion.validate(id, question))
      }
      _ <- model.fold[Result[Unit]](Right(())) { m =>
        if (m.trim.isEmpty || m.exists(_.isControl)) Left(ValidationError("model", "must not be blank")) else Right(())
      }
      _ <- JevHeaders.validate("headers", headers)
    } yield ()
}

object JevRequest {

  /** A request over structured or textual `state`. Named arguments are the supported way to set the optional parts. */
  def apply(
    state: ujson.Value,
    questions: Map[String, JevQuestion],
    model: Option[String] = None,
    headers: Map[String, String] = Map.empty
  ): JevRequest = new JevRequest(state, questions, model, headers)

  /** A request over text. */
  def apply(state: String, questions: Map[String, JevQuestion]): JevRequest =
    new JevRequest(ujson.Str(state), questions, None, Map.empty)
}
