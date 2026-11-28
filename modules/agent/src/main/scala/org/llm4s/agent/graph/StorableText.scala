package org.llm4s.agent.graph

import org.llm4s.error.ValidationError

/**
 * What every checkpoint store can store exactly (#3097): text with no NUL - PostgreSQL text holds none - and no
 * unpaired surrogate, which a driver encodes as `?`, so two ids would name one row. A store refuses an id holding
 * either with a `ValidationError` before it touches its storage, whatever its backend, so the stores agree.
 */
private[llm4s] object StorableText:

  /** Why `text` cannot be stored exactly, if it cannot. */
  def problem(text: String): Option[String] =
    var i     = 0
    var found = Option.empty[String]
    while found.isEmpty && i < text.length do
      val ch = text.charAt(i)
      if ch == '\u0000' then found = Some(s"holds a NUL character at index $i")
      else if Character.isHighSurrogate(ch) then
        if i + 1 < text.length && Character.isLowSurrogate(text.charAt(i + 1)) then i += 1
        else found = Some(s"holds an unpaired surrogate at index $i")
      else if Character.isLowSurrogate(ch) then found = Some(s"holds an unpaired surrogate at index $i")
      i += 1
    found

  /** A `ValidationError` for the first of `fields` (name, text) that cannot be stored exactly. */
  def refusal(fields: (String, String)*): Option[ValidationError] =
    fields.iterator.flatMap((name, text) => problem(text).map(p => ValidationError(name, s"$name $p"))).nextOption()

  /** [[refusal]] for a claim's ids: the thread, the run and the tenant. */
  def ofClaim(threadId: ThreadId, request: ClaimRequest): Option[ValidationError] =
    refusal(
      Vector("threadId" -> threadId.value, "runId" -> request.holder.value) ++
        request.tenantId.map(t => "tenantId" -> t.value)*
    )

  /**
   * [[refusal]] for every text of a commit a store keeps in a column of its own: the thread, its checkpoint's id, run,
   * tenant, graph and parent thread, its metadata's keys and values, and its effects' keys.
   */
  def ofCommit(threadId: ThreadId, commit: Commit): Option[ValidationError] =
    refusal(
      Vector("threadId" -> threadId.value) ++
        commit.checkpoint.toVector.flatMap(c =>
          Vector("checkpointId" -> c.id, "runId" -> c.runId, "graphId" -> c.snapshot.graphId) ++
            c.tenantId.map("tenantId" -> _) ++ c.origin.map(o => "parentThreadId" -> o.parent.value)
        ) ++
        commit.metadata.toVector.flatMap(
          _.entries.toVector.flatMap((k, v) => Vector("metadataKey" -> k, "metadataValue" -> v))
        ) ++
        commit.effects.map(e => "effectKey" -> e.key.value)*
    )
