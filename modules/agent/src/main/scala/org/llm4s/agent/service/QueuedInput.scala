package org.llm4s.agent.service

import org.llm4s.agent.graph.InterruptId
import org.llm4s.agent.graph.toolloop.AgentInput
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.Message

/**
 * What a queued run does to its thread (design 4.55), in the JSON form the entry stores. The queue runs `Agent`s and
 * `TypedAgent[I, O]`s, the two entry points whose inputs have codecs; a raw `CompiledGraph` is not queued.
 *
 *  - `Start`: a new turn with `input` - an [[org.llm4s.agent.graph.toolloop.AgentInput AgentInput]] for an `Agent`
 *    ([[QueuedInput.query]]), an `I` for a `TypedAgent[I, O]` ([[QueuedInput.typed]]).
 *  - `Resume`: answers to the thread's pending interrupts, as `Agent.resume` takes them.
 *  - `Recover`: continue the thread's failed or interrupted run, as `Agent.recover` does. A queued recover is the thread
 *    owner's explicit transfer of the thread's recovery to the queue: its first attempt recovers the thread whatever
 *    ran on it before (rule 4 otherwise forbids a worker from adopting a run started outside the queue).
 *
 * The input is the caller's to store: the queue keeps it in its entry until the entry is reclaimed, but no metric,
 * trace attribute or log line carries it (Stage 5 rule 10).
 */
@Experimental
enum QueuedInput:
  case Start(input: ujson.Value)
  case Resume(answers: Map[InterruptId, ujson.Value])
  case Recover

  /** `start`, `resume` or `recover`. */
  def kind: String = this match
    case Start(_)  => "start"
    case Resume(_) => "resume"
    case Recover   => "recover"

@Experimental
object QueuedInput:

  /** A new `Agent` turn: `query`, after `history` (no system messages), as `Agent.run` takes them. */
  def query(query: String, history: Seq[Message] = Nil): QueuedInput =
    Start(upickle.default.writeJs(AgentInput(query, history.toVector)))

  /** A new `TypedAgent[I, O]` turn with `input`, in `I`'s JSON form. */
  def typed[I: upickle.default.Writer](input: I): QueuedInput = Start(upickle.default.writeJs(input))

  /** The input as the entry stores it: `{"kind": ..., ...}`. */
  private[llm4s] def toJson(input: QueuedInput): ujson.Value = input match
    case Start(value) => ujson.Obj("kind" -> "start", "input" -> ujson.copy(value))
    case Resume(answers) =>
      ujson.Obj(
        "kind"    -> "resume",
        "answers" -> ujson.Obj.from(answers.toSeq.sortBy(_._1.value).map((id, a) => id.value -> ujson.copy(a)))
      )
    case Recover => ujson.Obj("kind" -> "recover")

  /** The input [[toJson]] stored, or why it cannot be read. */
  private[llm4s] def fromJson(json: ujson.Value): Either[String, QueuedInput] =
    json.objOpt.flatMap(_.get("kind")).flatMap(_.strOpt) match
      case Some("start") =>
        json.obj.get("input").map(i => Start(ujson.copy(i))).toRight("a start input has no `input`")
      case Some("resume") =>
        json.obj
          .get("answers")
          .flatMap(_.objOpt)
          .map(answers => Resume(answers.iterator.map((k, v) => InterruptId(k) -> ujson.copy(v)).toMap))
          .toRight("a resume input has no `answers` object")
      case Some("recover") => Right(Recover)
      case other           => Left(s"unknown input kind ${other.getOrElse("<none>")}")
