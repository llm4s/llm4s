package org.llm4s.agent.graph

/** Stable identifier of a node in a compiled graph; persisted in snapshots. */
opaque type NodeId = String

/** Identifier of one scheduled node execution; unique within a thread's history. */
opaque type TaskId = String

/** Stable identifier of a static or dynamic join barrier; persisted in snapshots. */
opaque type JoinId = String

/** Stable identifier of a [[StateKey]]; persisted in snapshots. */
opaque type StateKeyId = String

object NodeId:
  def apply(value: String): NodeId         = value
  extension (id: NodeId) def value: String = id

object TaskId:
  def apply(value: String): TaskId         = value
  extension (id: TaskId) def value: String = id

object JoinId:
  def apply(value: String): JoinId         = value
  extension (id: JoinId) def value: String = id

object StateKeyId:
  def apply(value: String): StateKeyId         = value
  extension (id: StateKeyId) def value: String = id

/** A conversation or workflow thread: the address of its checkpoints and event log. */
opaque type ThreadId = String

/** One execution attempt on a thread; `start`, `recover` and (later) `resume` each begin a new run. */
opaque type RunId = String

object ThreadId:
  def apply(value: String): ThreadId         = value
  extension (id: ThreadId) def value: String = id

object RunId:
  def apply(value: String): RunId         = value
  def random(): RunId                     = java.util.UUID.randomUUID().toString
  extension (id: RunId) def value: String = id

/** Identifies a parked continuation awaiting an answer; stable across processes. */
opaque type InterruptId = String

object InterruptId:
  def apply(value: String): InterruptId         = value
  extension (id: InterruptId) def value: String = id

/** The tenant a thread belongs to. */
opaque type TenantId = String

object TenantId:
  def apply(value: String): TenantId         = value
  extension (id: TenantId) def value: String = id

/** Who a run acts for. */
opaque type Principal = String

object Principal:
  def apply(value: String): Principal         = value
  extension (id: Principal) def value: String = id

/** The provider's identifier of one tool call; unique within a conversation. */
opaque type ToolCallId = String

object ToolCallId:
  def apply(value: String): ToolCallId         = value
  extension (id: ToolCallId) def value: String = id

/**
 * The key a tool gives an external system so that a second run of the same tool call does not
 * repeat its side effect: one key per model-issued call, the same in every run of that call (a
 * retrying wrapper, an approval, an answered question, `recover` after a failure, a cancellation
 * or a crash), and different for a call from another model request, even one that reuses the
 * provider's call id.
 *
 * The agent loop derives it with [[IdempotencyKey.derive]] from the thread, the checkpoint the
 * model call that issued the call ran at, and the call's id, and records it with the call, so it
 * reaches the tool as `ToolContext.idempotencyKey`. It de-duplicates only where the external
 * system honours it: the runtime runs a tool at least once, never exactly once (design §4.1).
 */
opaque type IdempotencyKey = String

object IdempotencyKey:

  /** A key with this exact value, such as one read back from an external system's log. */
  def apply(value: String): IdempotencyKey = value

  /**
   * The key of the call `toolCallId` issued by a model call that ran at `checkpointId` on
   * `threadId`: 64 lowercase hexadecimal characters, the SHA-256 of the three values, so it is
   * deterministic, of fixed length and reveals none of them. The checkpoint is what tells two model
   * requests apart: a model request made again after a failure runs at a new checkpoint, so its
   * calls get new keys even when the provider reuses their ids (design §5.3).
   */
  def derive(threadId: ThreadId, checkpointId: String, toolCallId: ToolCallId): IdempotencyKey =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    // each part length-prefixed, so no two different triples share an input
    Seq("llm4s-tool-call-v1", threadId.value, checkpointId, toolCallId.value).foreach { part =>
      val bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
    }
    digest.digest().map(b => f"${b & 0xff}%02x").mkString

  extension (key: IdempotencyKey) def value: String = key

  /**
   * Encodes as a plain JSON string. Built from upickle's string codecs: inside this scope an
   * `IdempotencyKey` is a `String`, so summoning `ReadWriter[String]` would find this given.
   */
  given upickle.default.ReadWriter[IdempotencyKey] =
    upickle.default.ReadWriter.join(upickle.default.StringReader, upickle.default.StringWriter)

/** The name of a tool, as the model calls it. */
opaque type ToolName = String

object ToolName:
  def apply(value: String): ToolName           = value
  extension (name: ToolName) def value: String = name
