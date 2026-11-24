package org.llm4s.agent.graph

import org.llm4s.annotation.{ Experimental, Stable }
import org.llm4s.toolapi.IdempotencyKey

/** Stable identifier of a node in a compiled graph; persisted in snapshots. */
opaque type NodeId = String

/** Identifier of one scheduled node execution; unique within a thread's history. */
opaque type TaskId = String

/** Stable identifier of a static or dynamic join barrier; persisted in snapshots. */
opaque type JoinId = String

/** Stable identifier of a [[StateKey]]; persisted in snapshots. */
opaque type StateKeyId = String

@Stable
object NodeId:
  def apply(value: String): NodeId         = value
  extension (id: NodeId) def value: String = id

@Experimental
object TaskId:
  def apply(value: String): TaskId         = value
  extension (id: TaskId) def value: String = id

@Experimental
object JoinId:
  def apply(value: String): JoinId         = value
  extension (id: JoinId) def value: String = id

@Experimental
object StateKeyId:
  def apply(value: String): StateKeyId         = value
  extension (id: StateKeyId) def value: String = id

/** A conversation or workflow thread: the address of its checkpoints and event log. */
opaque type ThreadId = String

/** One execution attempt on a thread; `start`, `recover` and (later) `resume` each begin a new run. */
opaque type RunId = String

@Stable
object ThreadId:
  def apply(value: String): ThreadId         = value
  extension (id: ThreadId) def value: String = id

  /**
   * The id of the child thread that the tool call `call` of a run on `parent` starts for agent `agent` (design 4.41):
   * `<parent>/~task/<agent>/<digest>`, where the digest is the first 24 hex characters of the SHA-256 of
   * `("llm4s-child-v1", parent, call, agent)`. The same call starting the same agent on the same thread always names
   * the same child, so a replayed call finds the child its first run started; any other call, thread or agent names
   * another. The id is hierarchical, so the nesting shows in it, and grows with depth.
   *
   * The id's shape decides nothing: a thread is a child thread only when its checkpoint records a [[RunOrigin]], so a
   * caller naming a top-level thread like this gets a top-level thread.
   */
  private[llm4s] def child(parent: ThreadId, agent: org.llm4s.agent.AgentId, call: IdempotencyKey): ThreadId =
    val digest = KeyDigest.of(Seq("llm4s-child-v1", parent, call.asString, agent.value)).take(24)
    s"$parent/~task/${agent.value}/$digest"

  /**
   * Encodes as a plain JSON string. Built from upickle's string codecs: inside this scope a `ThreadId` is a
   * `String`, so summoning `ReadWriter[String]` would find this given.
   */
  given upickle.default.ReadWriter[ThreadId] =
    upickle.default.ReadWriter.join(upickle.default.StringReader, upickle.default.StringWriter)

@Stable
object RunId:
  def apply(value: String): RunId         = value
  def random(): RunId                     = java.util.UUID.randomUUID().toString
  extension (id: RunId) def value: String = id

/** Identifies a parked continuation awaiting an answer; stable across processes. */
opaque type InterruptId = String

@Stable
object InterruptId:
  def apply(value: String): InterruptId         = value
  extension (id: InterruptId) def value: String = id

/** The tenant a thread belongs to. */
opaque type TenantId = String

@Stable
object TenantId:
  def apply(value: String): TenantId         = value
  extension (id: TenantId) def value: String = id

/** Who a run acts for. */
opaque type Principal = String

@Stable
object Principal:
  def apply(value: String): Principal         = value
  extension (id: Principal) def value: String = id

/**
 * The derivations of a tool call's [[org.llm4s.toolapi.IdempotencyKey]] (design §4.1, §4.24). The key type is in
 * core, where a tool reads it as `ToolCallInfo.idempotencyKey`; how the agent derives it, from its threads, tasks
 * and lineages, stays here.
 *
 * The agent loop derives a call's key with `ofCall` from the model task that issued the call (its key, lineage
 * included) and the call's id, name and arguments, and records it with the call, so it reaches the tool as
 * `ToolContext.idempotencyKey`.
 */
private[agent] object IdempotencyKeys:

  /**
   * The key of task `taskId` of lineage `lineage` on `threadId` ([[RunContext.taskKey]]): the SHA-256 of the
   * three under a `llm4s-task-v2` tag. Not the superstep the task runs at, which `updateState` moves on while
   * the task keeps its id and lineage.
   */
  private[graph] def ofTask(threadId: ThreadId, taskId: TaskId, lineage: String): IdempotencyKey =
    IdempotencyKey(KeyDigest.of(Seq("llm4s-task-v2", threadId.value, taskId.value, lineage)))

  /**
   * The key of tool call `call` issued by the model task whose key is `modelTask` ([[RunContext.taskKey]]):
   * the SHA-256 of the thread, that task, and the call's id, name and arguments. The model task's key carries
   * its lineage, so a model request made again from the same results - `recover` after the response was lost,
   * or work after the last durable checkpoint planned again after a takeover - that answers with the same call
   * issues it under the same key, and its effects replay rather than repeat; a request whose answer differs
   * issues other calls, with other keys, whatever ids the provider reuses (design §4.24).
   */
  def ofCall(
    threadId: ThreadId,
    modelTask: IdempotencyKey,
    call: org.llm4s.llmconnect.model.ToolCall
  ): IdempotencyKey =
    IdempotencyKey(
      KeyDigest.of(
        Seq(
          "llm4s-tool-call-v3",
          threadId.value,
          modelTask.asString,
          // a call built in Java may carry null parts: they hash as empty or JSON null, never as an error
          Option(call.id).map(_.asString).getOrElse(""),
          Option(call.name).getOrElse(""),
          KeyDigest.canonical(Option(call.arguments).getOrElse(ujson.Null))
        )
      )
    )

  /**
   * The key of an answered resume of the call `call` (design §4.24): the SHA-256, under a
   * `llm4s-tool-resume-v3` tag, of the call's key, the phase that asked (`None` for `execute`), and the
   * resume's identity, its question and answer as recorded when the answer was applied (`RunContext.resumeDigest`),
   * never recomputed from decoded values. A chain of resumes is a chain of phases, so a resume that asks the same
   * question again and is given the same answer is still a phase of its own.
   */
  private[graph] def ofResume(
    call: IdempotencyKey,
    asking: Option[IdempotencyKey],
    resume: String
  ): IdempotencyKey =
    IdempotencyKey(
      KeyDigest.of(Seq("llm4s-tool-resume-v3", call.asString, asking.map(_.asString).getOrElse(""), resume))
    )

/** The name of a tool, as the model calls it. */
opaque type ToolName = String

@Experimental
object ToolName:
  def apply(value: String): ToolName           = value
  extension (name: ToolName) def value: String = name

/**
 * The fencing token of a run's claim on a thread ([[RunClaim]]). A [[Checkpointer]] issues one with
 * every claim, strictly greater than every token it issued for that thread before - including
 * before a takeover, a release or a `deleteThread` - and accepts a commit only with the token of the
 * thread's current claim, so a writer that lost its claim cannot commit after another took over.
 */
opaque type FencingToken = Long

@Experimental
object FencingToken:
  def apply(value: Long): FencingToken            = value
  extension (token: FencingToken) def value: Long = token
  given Ordering[FencingToken]                    = Ordering.Long
  given upickle.default.ReadWriter[FencingToken] =
    upickle.default.ReadWriter.join(upickle.default.LongReader, upickle.default.LongWriter)
