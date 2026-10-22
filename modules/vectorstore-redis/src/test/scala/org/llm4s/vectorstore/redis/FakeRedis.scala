package org.llm4s.vectorstore.redis

import org.llm4s.types.Result

import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
 * A [[RedisConnection]] that records every command and answers from a script.
 *
 * Replies queued with [[enqueue]] for a command name are used first, in order; then the latest matching
 * [[respond]] handler; then a default that suits an empty server without the index: `FT.INFO` and `FT.SEARCH` say
 * there is no such index, `FT.CREATE` and `FT.DROPINDEX` answer `OK`, `DEL` and `HSET` count their keys or fields,
 * `HGETALL` is empty and `SCAN` finds nothing.
 */
final class FakeRedis extends RedisConnection {

  /** Every command sent, raw, in order; a pipeline's commands are recorded one by one. */
  val sent: mutable.ArrayBuffer[Seq[Array[Byte]]] = mutable.ArrayBuffer.empty

  /** How many pipelines were sent. */
  var pipelines: Int = 0

  var closed: Boolean = false

  private val queued   = mutable.Map.empty[String, mutable.Queue[Result[RedisReply]]]
  private var handlers = List.empty[PartialFunction[Seq[String], Result[RedisReply]]]

  /** Answers the next commands named `name` with `replies`, in order. */
  def enqueue(name: String, replies: RedisReply*): this.type = {
    queued.getOrElseUpdate(name, mutable.Queue.empty) ++= replies.map(Right(_))
    this
  }

  /** Fails the next command named `name` with `result`'s error. */
  def enqueueResult(name: String, result: Result[RedisReply]): this.type = {
    queued.getOrElseUpdate(name, mutable.Queue.empty) += result
    this
  }

  /** Answers every matching command with `pf`'s reply; a later handler wins. */
  def respond(pf: PartialFunction[Seq[String], RedisReply]): this.type = {
    handlers = pf.andThen(Right(_)) :: handlers
    this
  }

  /** Every command sent, decoded as UTF-8 text. */
  def commands: Seq[Seq[String]] = sent.toSeq.map(_.map(FakeRedis.utf8))

  /** The names of the commands sent. */
  def names: Seq[String] = commands.map(_.head)

  /** The commands sent named `name`. */
  def named(name: String): Seq[Seq[String]] = commands.filter(_.head == name)

  /** The raw commands sent named `name`. */
  def rawNamed(name: String): Seq[Seq[Array[Byte]]] = sent.toSeq.filter(c => FakeRedis.utf8(c.head) == name)

  def reset(): Unit = {
    sent.clear()
    pipelines = 0
  }

  private def answer(command: Seq[Array[Byte]]): Result[RedisReply] = {
    sent += command
    val text = command.map(FakeRedis.utf8)
    queued.get(text.head).filter(_.nonEmpty) match {
      case Some(queue) => queue.dequeue()
      case None =>
        handlers.find(_.isDefinedAt(text)) match {
          case Some(handler) => handler(text)
          case None          => Right(FakeRedis.default(text))
        }
    }
  }

  override def execute(command: Seq[Array[Byte]]): Result[RedisReply] = answer(command)

  override def pipeline(commands: Seq[Seq[Array[Byte]]]): Result[Seq[RedisReply]] = {
    pipelines += 1
    val replies = commands.map(answer)
    replies.collectFirst { case Left(e) => e }.toLeft(replies.collect { case Right(r) => r })
  }

  override def close(): Unit = closed = true
}

object FakeRedis {

  def utf8(bytes: Array[Byte]): String = new String(bytes, StandardCharsets.UTF_8)

  val NoIndex: RedisReply = RedisReply.Error("Unknown index name")

  def default(command: Seq[String]): RedisReply = command.head match {
    case "FT.INFO" | "FT.SEARCH"      => NoIndex
    case "FT.CREATE" | "FT.DROPINDEX" => RedisReply.Ok
    case "DEL"                        => RedisReply.Integer(command.size - 1L)
    case "HSET"                       => RedisReply.Integer((command.size - 2L) / 2)
    case "HGETALL"                    => RedisReply.Arr(Seq.empty)
    case "SCAN"                       => scan("0")
    case other                        => RedisReply.Error(s"ERR unknown command '$other'")
  }

  /** A `SCAN` reply. */
  def scan(cursor: String, keys: String*): RedisReply =
    RedisReply.Arr(Seq(RedisReply.str(cursor), RedisReply.Arr(keys.map(RedisReply.str))))

  /** An `FT.INFO` reply in Redis Query Engine's shape, for an index of `dim`-sized vectors. */
  def info(dim: Int): RedisReply =
    RedisReply.Arr(
      Seq(
        RedisReply.str("index_name"),
        RedisReply.str("llm4s-vectors"),
        RedisReply.str("attributes"),
        RedisReply.Arr(
          Seq(
            RedisReply.Arr(Seq("identifier", "seq", "type", "NUMERIC").map(RedisReply.str)),
            RedisReply.Arr(
              Seq("identifier", "embedding", "type", "VECTOR", "algorithm", "HNSW").map(RedisReply.str) ++
                Seq(RedisReply.str("dim"), RedisReply.Integer(dim.toLong))
            )
          )
        ),
        RedisReply.str("num_docs"),
        RedisReply.str("4")
      )
    )

  /** A hash's fields as an `HGETALL` reply or a search document's fields. */
  def fields(pairs: (String, RedisReply)*): RedisReply =
    RedisReply.Arr(pairs.flatMap { case (k, v) => Seq(RedisReply.str(k), v) })

  /** The stored fields of a record, as the store writes them. */
  def recordFields(
    id: String,
    vector: Array[Float],
    content: Option[String] = None,
    metadata: Map[String, String] = Map.empty
  ): Seq[(String, RedisReply)] =
    Seq(
      "id"        -> RedisReply.str(id),
      "embedding" -> RedisReply.Bulk(RedisQueries.vectorBytes(vector)),
      "metadata"  -> RedisReply.str(RedisQueries.metadataJson(metadata))
    ) ++ content.map(c => "content" -> RedisReply.str(c))

  /** An `FT.SEARCH` reply holding `docs` (key and fields). */
  def search(total: Long, docs: (String, Seq[(String, RedisReply)])*): RedisReply =
    RedisReply.Arr(
      RedisReply.Integer(total) +: docs.flatMap { case (key, fs) => Seq(RedisReply.str(key), fields(fs*)) }
    )

  /** An `FT.SEARCH ... NOCONTENT` reply. */
  def keys(total: Long, keys: String*): RedisReply =
    RedisReply.Arr(RedisReply.Integer(total) +: keys.map(RedisReply.str))
}
