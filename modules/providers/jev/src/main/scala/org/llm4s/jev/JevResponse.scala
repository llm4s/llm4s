package org.llm4s.jev

import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

/**
 * Jev's answers to one [[JevRequest]].
 *
 * @param model     the versioned model that answered (`jev-1.13.0` for the alias `jev-latest`): log it, because an
 *                  alias moves when a new release ships
 * @param answers   one answer per question, under the id the question was asked with
 * @param usage     tokens used
 * @param requestId the API's request id (`x-typesafe-request-id`), for support, when it sent one
 */
final case class JevResponse private (
  model: String,
  answers: Map[String, JevAnswer],
  usage: JevUsage,
  requestId: Option[String]
) {

  /** Adds the API's request id. */
  def withRequestId(requestId: Option[String]): JevResponse = copy(requestId = requestId)

  /** The answer to the Noul question `id`, or a [[org.llm4s.error.ValidationError]] when it is absent or of another type. */
  def noul(id: String): Result[NoulAnswer] = typed(id, "noul") { case a: NoulAnswer => a }

  /** The answer to the Choice question `id`, or a [[org.llm4s.error.ValidationError]] when it is absent or of another type. */
  def choice(id: String): Result[ChoiceAnswer] = typed(id, "choice") { case a: ChoiceAnswer => a }

  /** The answer to the Score question `id`, or a [[org.llm4s.error.ValidationError]] when it is absent or of another type. */
  def score(id: String): Result[ScoreAnswer] = typed(id, "score") { case a: ScoreAnswer => a }

  private def typed[A <: JevAnswer](id: String, kind: String)(pick: PartialFunction[JevAnswer, A]): Result[A] =
    answers.get(id) match {
      case None         => Left(ValidationError(s"answers.$id", "no answer came back for this question"))
      case Some(answer) => pick.lift(answer).toRight(ValidationError(s"answers.$id", s"is not a $kind answer"))
    }
}

object JevResponse {

  /** Creates a [[JevResponse]]. */
  def apply(
    model: String,
    answers: Map[String, JevAnswer],
    usage: JevUsage,
    requestId: Option[String] = None
  ): JevResponse = new JevResponse(model, answers, usage, requestId)

  private def fail[A](path: String, problem: String): Result[A] =
    Left(ProcessingError("jev-response", s"Jev sent a response that does not match its API at $path: $problem"))

  private def field(obj: ujson.Obj, path: String, name: String): Result[ujson.Value] =
    obj.value.get(name).toRight(ProcessingError("jev-response", s"Jev's response has no `$name` at $path"))

  private def string(value: ujson.Value, path: String): Result[String] = value match {
    case ujson.Str(s) => Right(s)
    case _            => fail(path, "expected a string")
  }

  private def description(value: ujson.Value, path: String): Result[ujson.Value] = value match {
    case _: ujson.Str | _: ujson.Obj | _: ujson.Arr => Right(value)
    case _                                          => fail(path, "expected a string, object or array")
  }

  private def number(value: ujson.Value, path: String): Result[Double] = value match {
    case ujson.Num(d) if !d.isNaN && !d.isInfinity => Right(d)
    case _                                         => fail(path, "expected a finite number")
  }

  private def unit(value: ujson.Value, path: String): Result[Double] =
    number(value, path).flatMap(d => if (d >= 0.0 && d <= 1.0) Right(d) else fail(path, s"$d is outside 0 to 1"))

  private def count(value: ujson.Value, path: String): Result[Int] =
    number(value, path).flatMap { d =>
      if (d >= 0 && d == math.rint(d) && d <= Int.MaxValue) Right(d.toInt) else fail(path, "expected a count")
    }

  private def obj(value: ujson.Value, path: String): Result[ujson.Obj] = value match {
    case o: ujson.Obj => Right(o)
    case _            => fail(path, "expected an object")
  }

  private def sequence[A](items: Seq[Result[A]]): Result[Seq[A]] =
    items.foldLeft[Result[Seq[A]]](Right(Vector.empty))((acc, item) => acc.flatMap(a => item.map(a :+ _)))

  private def parseUsage(value: ujson.Value): Result[JevUsage] =
    for {
      o   <- obj(value, "usage")
      in  <- field(o, "usage", "input_tokens").flatMap(count(_, "usage.input_tokens"))
      out <- field(o, "usage", "output_tokens").flatMap(count(_, "usage.output_tokens"))
    } yield JevUsage(in, out)

  private def probabilities(value: ujson.Value, path: String): Result[Map[String, Double]] =
    obj(value, path).flatMap { o =>
      sequence(o.value.toSeq.map { case (key, v) => unit(v, s"$path.$key").map(key -> _) }).map(_.toMap)
    }

  private def parseAnswer(id: String, value: ujson.Value): Result[JevAnswer] = {
    val at = s"answers.$id"
    for {
      o    <- obj(value, at)
      kind <- field(o, at, "type").flatMap(string(_, s"$at.type"))
      answer <- kind match {
        case "noul" =>
          field(o, at, "noul").flatMap(unit(_, s"$at.noul")).map(NoulAnswer(_))
        case "choice" =>
          for {
            choice <- field(o, at, "choice").flatMap(string(_, s"$at.choice"))
            probs  <- field(o, at, "probabilities").flatMap(probabilities(_, s"$at.probabilities"))
            _ <-
              if (probs.contains(choice)) Right(())
              else fail(s"$at.choice", s"'$choice' is not among the probabilities")
            _ <-
              if (probs.values.forall(_ <= probs(choice))) Right(())
              else fail(s"$at.choice", "selected choice does not have the highest probability")
            confidence <- field(o, at, "confidence").flatMap(unit(_, s"$at.confidence"))
          } yield ChoiceAnswer(choice, probs, confidence)
        case "score" => parseScore(o, at)
        case other   => fail(s"$at.type", s"unsupported answer type '$other'")
      }
    } yield answer
  }

  /** A level key is a level number written canonically (`0`, `1`, `12`), so no two keys can name the same level. */
  private val LevelKey = "0|[1-9][0-9]*".r

  private def levelIndex(key: String, path: String): Result[Int] =
    Option(key)
      .filter(LevelKey.matches)
      .flatMap(_.toIntOption)
      .toRight(ProcessingError("jev-response", s"Jev's level key at $path is not a level number"))

  private def parseScore(o: ujson.Obj, at: String): Result[JevAnswer] =
    for {
      score  <- field(o, at, "score").flatMap(number(_, s"$at.score"))
      legend <- field(o, at, "legend").flatMap(obj(_, s"$at.legend"))
      probs  <- field(o, at, "probabilities").flatMap(probabilities(_, s"$at.probabilities"))
      described <- sequence(legend.value.toSeq.map { case (key, v) =>
        for {
          index       <- levelIndex(key, s"$at.legend")
          description <- description(v, s"$at.legend.$key")
        } yield index -> (key, description)
      })
      _ <-
        if (probs.keySet == described.map(_._2._1).toSet) Right(())
        else fail(s"$at.probabilities", "the levels do not match the legend")
      // The score is the probability-weighted level, so it lies between the lowest level and the highest.
      top = described.map(_._1).maxOption.getOrElse(0)
      _ <-
        if (score >= 0.0 && score <= top) Right(())
        else fail(s"$at.score", s"$score is outside the levels 0 to $top")
      confidence <- field(o, at, "confidence").flatMap(unit(_, s"$at.confidence"))
    } yield ScoreAnswer(
      score,
      described.sortBy(_._1).map { case (index, (key, description)) => ScoreLevel(index, description, probs(key)) },
      confidence
    )

  /**
   * Reads the API's response body (https://docs.typesafe.ai/api.md#response-body).
   *
   * Strict about what the documentation bounds (probabilities and confidence from 0 to 1, a count of tokens, a
   * choice among its probabilities, score levels matching the legend, each level numbered once, and a score within
   * the levels) and silent about the rest. The body is never quoted in an error, only the path to the part that does
   * not match. A body nested more than [[org.llm4s.util.BoundedJson.MaxDepth]] levels deep is refused unparsed: a
   * value that deep, kept in a level's description, would overflow the stack of whatever later printed, hashed or
   * compared the response.
   */
  private[jev] def parse(body: String, requestId: Option[String]): Result[JevResponse] =
    BoundedJson
      .read(body)
      .left
      .map {
        case BoundedJson.TooDeep() =>
          ProcessingError(
            "jev-response",
            s"Jev's response is nested more than ${BoundedJson.MaxDepth} levels deep, which its API never is"
          )
        case _ => ProcessingError("jev-response", "Jev's response is not valid JSON")
      }
      .flatMap { json =>
        for {
          root    <- obj(json, "the response")
          model   <- field(root, "the response", "model").flatMap(string(_, "model"))
          usage   <- field(root, "the response", "usage").flatMap(parseUsage)
          answers <- field(root, "the response", "answers").flatMap(obj(_, "answers"))
          parsed  <- sequence(answers.value.toSeq.map { case (id, v) => parseAnswer(id, v).map(id -> _) })
        } yield JevResponse(model, parsed.toMap, usage, requestId)
      }
}
