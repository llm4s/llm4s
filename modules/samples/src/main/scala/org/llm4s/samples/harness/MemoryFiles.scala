package org.llm4s.samples.harness

import org.llm4s.agent.graph.TenantId
import org.llm4s.agent.memory.{ MemoryStore, SQLiteMemoryStore }
import org.llm4s.types.Result

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/**
 * One SQLite memory store per tenant, each its own file in `dir`, so no tenant reads another's memories (rule 8). One
 * instance per process: it keeps the stores it opened, and `close` closes them, as a process's exit would.
 */
final class MemoryFiles(dir: Path) extends AutoCloseable {
  private val opened = new ConcurrentHashMap[String, SQLiteMemoryStore]()

  /** The file of `tenant`'s memories. */
  def fileOf(tenant: Option[TenantId]): Path = dir.resolve(s"memory-${tenant.fold("default")(_.value)}.db")

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
