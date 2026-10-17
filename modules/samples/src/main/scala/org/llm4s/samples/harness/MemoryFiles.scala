package org.llm4s.samples.harness

import org.llm4s.agent.graph.TenantId
import org.llm4s.agent.memory.{ MemoryStore, SQLiteMemoryStore }
import org.llm4s.types.Result

import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/**
 * One SQLite memory store per tenant, each its own file in `dir`, so no tenant reads another's memories (rule 8). One
 * instance per process: it keeps the stores it opened, and `close` closes them, as a process's exit would.
 */
final class MemoryFiles(dir: Path) extends AutoCloseable {
  private val opened = new ConcurrentHashMap[String, SQLiteMemoryStore]()

  /**
   * The file of `tenant`'s memories. Runs without a tenant and each tenant get names no other can: `memory.db`, and
   * `memory-<SHA-256 of the id's UTF-16 code units, in hex>.db` (every code unit as it is, so even an
   * unpaired surrogate, which a UTF-8 encoder would replace with `?`, keeps two ids apart), so no id - `default`, `../x`, one of any length - shares a
   * file with another, names one outside `dir`, or makes a name too long for the file system.
   */
  def fileOf(tenant: Option[TenantId]): Path =
    dir.resolve(tenant.fold("memory.db") { t =>
      val units  = t.value.foldLeft(ByteBuffer.allocate(t.value.length * 2))((b, c) => b.putChar(c))
      val digest = MessageDigest.getInstance("SHA-256").digest(units.array())
      s"memory-${digest.map(b => f"${b & 0xff}%02x").mkString}.db"
    })

  /** `tenant`'s store, opened on first use; the resolver a `SemanticMemoryMiddleware` takes. */
  def store(tenant: Option[TenantId]): Result[MemoryStore] = {
    val file = fileOf(tenant).toString
    Option(opened.get(file)) match {
      case Some(store) => Right(store)
      case None =>
        SQLiteMemoryStore(file).map { s =>
          Option(opened.putIfAbsent(file, s)) match {
            case Some(first) => s.close(); first // another call opened it first
            case None        => s
          }
        }
    }
  }

  def close(): Unit = opened.values.asScala.foreach(_.close())
}
