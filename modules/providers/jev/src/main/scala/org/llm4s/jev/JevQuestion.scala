package org.llm4s.jev

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.annotation.tailrec

/**
 * A typed question for Jev, TypeSafe's System One decision model.
 *
 * Jev evaluates one `state` against a map of named questions and answers each with a typed answer:
 * a [[JevQuestion.Noul]] with the probability of "yes", a [[JevQuestion.Choice]] with the selected option
 * and a distribution over the options, a [[JevQuestion.Score]] with a score along ordered levels. See
 * [[JevAnswer]] for the answers.
 *
 * Wire format (https://docs.typesafe.ai/api.md): every question has a `type` and `instructions`; `instructions`
 * and every criterion is a string, an object or an array (JSON structure is allowed so a question can carry
 * the data it refers to). The limits the API documents are checked before a request is sent: at most
 * [[JevQuestion.MaxChoiceOptions]] options in a Choice, between [[JevQuestion.MinScoreLevels]] and
 * [[JevQuestion.MaxScoreLevels]] levels in a Score.
 *
 * `ujson.Value` is the JSON type the rest of LLM4S's public API uses (tool parameters, HTTP responses).
 * It is mutable: do not change a value after it has been handed to a question.
 */
sealed trait JevQuestion {

  /** What the model should decide: a string, an object or an array. */
  def instructions: ujson.Value
}

object JevQuestion {

  /** The most options the API accepts in one Choice. */
  val MaxChoiceOptions: Int = 255

  /** The fewest levels a Score should have. */
  val MinScoreLevels: Int = 2

  /** The most levels the API accepts in one Score. */
  val MaxScoreLevels: Int = 10

  /**
   * A yes/no question; the answer is the probability of yes.
   *
   * @param instructions the yes/no question
   * @param yes          what a yes (a value near 1) means, if it needs saying
   * @param no           what a no (a value near 0) means, if it needs saying
   */
  final case class Noul(
    instructions: ujson.Value,
    yes: Option[ujson.Value] = None,
    no: Option[ujson.Value] = None
  ) extends JevQuestion

  /**
   * Picks one option from a set you define.
   *
   * @param instructions what the model should decide
   * @param options      each option with its description; `None` when an option needs no description.
   *                     Order is kept on the wire.
   */
  final case class Choice(
    instructions: ujson.Value,
    options: Seq[(String, Option[ujson.Value])]
  ) extends JevQuestion

  /**
   * Rates the state along ordered levels you describe; the answer is a probability-weighted score that can
   * land between levels.
   *
   * @param instructions what the model should rate
   * @param levels       the level descriptions, lowest first (level `0` is the first)
   */
  final case class Score(
    instructions: ujson.Value,
    levels: Seq[ujson.Value]
  ) extends JevQuestion

  /** A yes/no question given as text. */
  def noul(question: String): Noul = Noul(ujson.Str(question))

  /** A yes/no question given as text, with a description of what yes and no mean. */
  def noul(question: String, yes: String, no: String): Noul =
    Noul(ujson.Str(question), Some(ujson.Str(yes)), Some(ujson.Str(no)))

  /** A Choice given as text: `options` pairs each option with its description. */
  def choice(instructions: String, options: (String, String)*): Choice =
    Choice(
      ujson.Str(instructions),
      options.map { case (option, description) => option -> Some(ujson.Str(description)) }
    )

  /** A Choice of bare options that need no description. */
  def choiceOf(instructions: String, options: String*): Choice =
    Choice(ujson.Str(instructions), options.map(option => option -> None))

  /** A Score given as text: `levels` describe the levels, lowest first. */
  def score(instructions: String, levels: String*): Score =
    Score(ujson.Str(instructions), levels.map(ujson.Str(_)))

  /** Whether `value` is one of the JSON shapes the API accepts for text: a string, an object or an array. */
  private[jev] def isTextOrStructure(value: ujson.Value): Boolean = value match {
    case _: ujson.Str | _: ujson.Obj | _: ujson.Arr => true
    case _                                          => false
  }

  private def blankText(value: ujson.Value): Boolean = value match {
    case ujson.Str(text) => text.trim.isEmpty
    case _               => false
  }

  private def checkText(field: String, value: ujson.Value): Result[Unit] =
    if (!isTextOrStructure(value))
      Left(ValidationError(field, "must be a string, an object or an array"))
    else if (blankText(value))
      Left(ValidationError(field, "must not be blank"))
    else Right(())

  /** Checks a question against what the API documents; `id` names the question in the error. */
  private[jev] def validate(id: String, question: JevQuestion): Result[Unit] = {
    val at = s"questions.$id"
    for {
      _ <- checkText(s"$at.instructions", question.instructions)
      _ <- question match {
        case Noul(_, yes, no) =>
          for {
            _ <- yes.fold[Result[Unit]](Right(()))(checkText(s"$at.criteria.true", _))
            _ <- no.fold[Result[Unit]](Right(()))(checkText(s"$at.criteria.false", _))
          } yield ()
        case Choice(_, options) => validateChoice(at, options)
        case Score(_, levels)   => validateScore(at, levels)
      }
    } yield ()
  }

  private def validateChoice(at: String, options: Seq[(String, Option[ujson.Value])]): Result[Unit] = {
    val names = options.map(_._1)
    if (options.isEmpty) Left(ValidationError(s"$at.criteria", "a Choice needs at least one option"))
    else if (options.size > MaxChoiceOptions)
      Left(ValidationError(s"$at.criteria", s"a Choice takes at most $MaxChoiceOptions options, got ${options.size}"))
    else if (names.exists(_.trim.isEmpty))
      Left(ValidationError(s"$at.criteria", "an option name must not be blank"))
    else if (names.distinct.size != names.size)
      Left(ValidationError(s"$at.criteria", "option names must be unique"))
    else
      options
        .collectFirst {
          case (name, Some(description)) if checkText(s"$at.criteria.$name", description).isLeft =>
            checkText(s"$at.criteria.$name", description)
        }
        .getOrElse(Right(()))
  }

  private def validateScore(at: String, levels: Seq[ujson.Value]): Result[Unit] =
    if (levels.size < MinScoreLevels || levels.size > MaxScoreLevels)
      Left(
        ValidationError(
          s"$at.criteria",
          s"a Score takes between $MinScoreLevels and $MaxScoreLevels levels, got ${levels.size}"
        )
      )
    else
      levels.zipWithIndex
        .collectFirst {
          case (level, index) if checkLevel(s"$at.criteria[$index]", level).isLeft =>
            checkLevel(s"$at.criteria[$index]", level)
        }
        .getOrElse(Right(()))

  /**
   * A level description is checked as text, and for depth: the API echoes it back in the answer's `legend`, and a
   * response deeper than [[JevResponse.MaxResponseDepth]] is refused, so a description deeper than
   * [[JevResponse.MaxLevelDescriptionDepth]] would be sent, billed and then unreadable. No other value of a request
   * comes back in the response, so none other is limited.
   */
  private def checkLevel(field: String, level: ujson.Value): Result[Unit] =
    checkText(field, level).flatMap { _ =>
      val limit = JevResponse.MaxLevelDescriptionDepth
      if (nestedDeeperThan(level, limit))
        Left(
          ValidationError(
            field,
            s"is nested more than $limit levels deep; Jev echoes a level description back in its response, " +
              s"which is read to at most ${JevResponse.MaxResponseDepth} levels including its envelope"
          )
        )
      else Right(())
    }

  /**
   * Whether `value` nests arrays and objects more than `limit` levels deep (a string is 0, `[]` is 1), counted as
   * [[org.llm4s.util.BoundedJson.exceedsDepth]] counts text. The walk is iterative and stops one level past `limit`,
   * so a caller's value nested 100,000 levels deep is judged without recursion, on any stack.
   */
  private[jev] def nestedDeeperThan(value: ujson.Value, limit: Int): Boolean = {
    @tailrec
    def walk(pending: List[(ujson.Value, Int)]): Boolean = pending match {
      case Nil => false
      case (current, depth) :: rest =>
        val children = current match {
          case o: ujson.Obj => Some(o.value.valuesIterator)
          case a: ujson.Arr => Some(a.value.iterator)
          case _            => None
        }
        children match {
          case None                         => walk(rest)
          case Some(_) if depth + 1 > limit => true
          case Some(items)                  => walk(items.map(_ -> (depth + 1)).toList ::: rest)
        }
    }
    walk(List(value -> 0))
  }

  /** The question as the API's JSON; assumes it has been validated. */
  private[jev] def toJson(question: JevQuestion): ujson.Value = question match {
    case Noul(instructions, yes, no) =>
      val base     = ujson.Obj("type" -> "noul", "instructions" -> instructions)
      val criteria = ujson.Obj()
      yes.foreach(value => criteria("true") = value)
      no.foreach(value => criteria("false") = value)
      if (criteria.value.nonEmpty) base("criteria") = criteria
      base
    case Choice(instructions, options) =>
      val criteria = ujson.Obj()
      options.foreach { case (name, description) => criteria(name) = description.getOrElse(ujson.Null) }
      ujson.Obj("type" -> "choice", "instructions" -> instructions, "criteria" -> criteria)
    case Score(instructions, levels) =>
      ujson.Obj("type" -> "score", "instructions" -> instructions, "criteria" -> ujson.Arr(levels*))
  }
}
