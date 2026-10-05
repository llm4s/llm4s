package org.llm4s.agent.graph

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import scala.jdk.CollectionConverters.*

/** The hex SHA-256 of a string's UTF-8 bytes. */
private[graph] object Sha256:
  def hex(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x")
      .mkString

/**
 * One node's cache of results by input, bounded and least-recently-used. `ticker` is a monotonic
 * clock in nanoseconds, injected so specs can expire entries without waiting.
 *
 * Each call holds one `ReentrantLock` for a map operation only, never across a node run, so a
 * virtual thread's carrier is not pinned (see [[withLock]]).
 */
final private[graph] class NodeCache(policy: CachePolicy, ticker: () => Long):
  final private case class Entry(command: Command, expiresAt: Option[Long])

  private val lock = new ReentrantLock()
  // access order: `get` moves an entry to the end, so the first entry is the least recently used
  private val entries = new java.util.LinkedHashMap[String, Entry](16, 0.75f, true)

  /** The command stored for `key`, unless it was evicted or has expired. */
  def get(key: String): Option[Command] = withLock(lock) {
    Option(entries.get(key)).flatMap { entry =>
      if entry.expiresAt.exists(at => ticker() - at >= 0) then
        entries.remove(key)
        None
      else Some(entry.command)
    }
  }

  def put(key: String, command: Command): Unit = withLock(lock) {
    entries.put(key, Entry(command, policy.ttl.map(ttl => ticker() + ttl.toNanos)))
    val overflow = entries.size() - policy.maxEntries
    if overflow > 0 then
      val evicted = entries.keySet().iterator().asScala.take(overflow).toVector
      evicted.foreach(k => entries.remove(k): Unit)
  }

  def size: Int = withLock(lock)(entries.size())

/** The key under which a node's result for `input` is cached. */
private[graph] object NodeCache:
  def key(node: NodeDef[?], input: Any): Option[String] =
    scala.util.Try(node.encode(input)).toOption.map { encoded =>
      s"${node.ref.id.value}\u0000${encoded.version}\u0000${Sha256.hex(ujson.write(encoded.value))}"
    }
