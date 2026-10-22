package org.llm4s.vectorstore.redis

import org.llm4s.error.{
  AuthenticationError,
  ConfigurationError,
  LLMError,
  NetworkError,
  ProcessingError,
  TimeoutError
}
import org.llm4s.types.Result
import redis.clients.jedis.commands.ProtocolCommand
import redis.clients.jedis.exceptions.{ JedisAccessControlException, JedisConnectionException, JedisDataException }
import redis.clients.jedis.{
  CommandArguments,
  DefaultJedisClientConfig,
  HostAndPort,
  RedisClient,
  RedisProtocol,
  SslOptions
}

import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/** One RESP2 reply, as [[RedisVectorStore]] reads it. */
private[redis] enum RedisReply {

  /** A bulk or simple string. */
  case Bulk(bytes: Array[Byte])

  /** An integer. */
  case Integer(value: Long)

  /** An array, possibly nested. */
  case Arr(items: Seq[RedisReply])

  /** The null reply. */
  case Nil

  /** An error reply: the server refused the command. */
  case Error(message: String)

  /** The reply as text: a bulk string decoded as UTF-8, an integer's digits, otherwise `None`. */
  def text: Option[String] = this match {
    case Bulk(bytes) => Some(new String(bytes, StandardCharsets.UTF_8))
    case Integer(v)  => Some(v.toString)
    case _           => None
  }
}

private[redis] object RedisReply {

  /** A bulk string holding `s` in UTF-8. */
  def str(s: String): RedisReply = Bulk(s.getBytes(StandardCharsets.UTF_8))

  /** The `OK` status reply. */
  val Ok: RedisReply = str("OK")
}

/**
 * The commands [[RedisVectorStore]] sends, as raw RESP arguments (the command name first). An internal seam: the
 * store's unit tests replace it with a fake that records every command, and the client library stays out of the
 * public API.
 *
 * A transport failure is a `Left`; a reply, including an error reply, is a `Right`, for the store to interpret.
 */
private[redis] trait RedisConnection {

  /** Sends one command. */
  def execute(command: Seq[Array[Byte]]): Result[RedisReply]

  /** Sends `commands` in one round trip; the replies come back in order, one per command. */
  def pipeline(commands: Seq[Seq[Array[Byte]]]): Result[Seq[RedisReply]]

  /** Releases the connections. */
  def close(): Unit
}

/** A [[RedisConnection]] over a Jedis [[redis.clients.jedis.RedisClient]], which pools connections. */
final private[redis] class JedisConnection(client: RedisClient, endpoint: String, timeout: FiniteDuration)
    extends RedisConnection {

  import JedisConnection.*

  override def execute(command: Seq[Array[Byte]]): Result[RedisReply] =
    Try(client.executeCommand(arguments(command))).toEither match {
      case Right(reply) => Right(fromJedis(reply))
      case Left(e)      => toReplyOrError(e, endpoint, timeout)
    }

  override def pipeline(commands: Seq[Seq[Array[Byte]]]): Result[Seq[RedisReply]] =
    if (commands.isEmpty) Right(Seq.empty)
    else
      Try {
        Using.resource(client.pipelined()) { p =>
          commands.foreach(c => p.sendCommand(arguments(c)))
          p.syncAndReturnAll().asScala.toSeq.map(fromJedis)
        }
      }.toEither.left.map(e => failure(e, endpoint, timeout))

  override def close(): Unit = client.close()
}

private[redis] object JedisConnection {

  /** A Jedis client for `config`'s server, speaking RESP2. No connection is opened until the first command. */
  def apply(config: RedisConfig): Result[JedisConnection] = {
    val millis = config.timeout.toMillis.toInt
    Try {
      val base = DefaultJedisClientConfig
        .builder()
        .protocol(RedisProtocol.RESP2)
        .database(config.database)
        .connectionTimeoutMillis(millis)
        .socketTimeoutMillis(millis)
        .clientName("llm4s-vectorstore")
      val secured      = if (config.tls) base.sslOptions(SslOptions.defaults()) else base
      val withUser     = config.username.fold(secured)(secured.user)
      val clientConfig = config.password.fold(withUser)(withUser.password).build()
      RedisClient.builder().hostAndPort(new HostAndPort(config.host, config.port)).clientConfig(clientConfig).build()
    }.toEither.left
      .map(e => ConfigurationError(s"Cannot create a Redis client for ${config.host}:${config.port}: ${e.getMessage}"))
      .map(client => new JedisConnection(client, s"${config.host}:${config.port}", config.timeout))
  }

  /** A raw command: its name, then its arguments. */
  private def arguments(command: Seq[Array[Byte]]): CommandArguments = {
    val name = command.head
    command.tail.foldLeft(new CommandArguments(new ProtocolCommand { override def getRaw: Array[Byte] = name }))(
      (args, arg) => args.add(arg)
    )
  }

  /** A Jedis reply object as a [[RedisReply]]; an exception inside a pipeline's or a transaction's replies is an error. */
  def fromJedis(reply: Any): RedisReply = reply match {
    case null                    => RedisReply.Nil
    case bytes: Array[Byte]      => RedisReply.Bulk(bytes)
    case n: java.lang.Long       => RedisReply.Integer(n.longValue)
    case list: java.util.List[?] => RedisReply.Arr(list.asScala.toSeq.map(fromJedis))
    case e: JedisDataException   => RedisReply.Error(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    case other                   => RedisReply.str(other.toString)
  }

  /**
   * An exception thrown by a command: an error reply for a server's refusal (so the store can tell "no such index"
   * from a failure), an [[org.llm4s.error.LLMError]] otherwise.
   */
  def toReplyOrError(e: Throwable, endpoint: String, timeout: FiniteDuration): Result[RedisReply] = e match {
    case _: JedisAccessControlException => Left(failure(e, endpoint, timeout))
    case refused: JedisDataException    => Right(RedisReply.Error(Option(refused.getMessage).getOrElse("error")))
    case other                          => Left(failure(other, endpoint, timeout))
  }

  /** The [[org.llm4s.error.LLMError]] for an exception a command or a pipeline threw. */
  def failure(e: Throwable, endpoint: String, timeout: FiniteDuration): LLMError = e match {
    case denied: JedisAccessControlException =>
      AuthenticationError("redis", s"Redis refused the credentials: ${denied.getMessage}")
    case c: JedisConnectionException if Option(c.getCause).exists(_.isInstanceOf[SocketTimeoutException]) =>
      TimeoutError(s"Redis at $endpoint did not answer within $timeout", timeout, "redis")
    case c: JedisConnectionException =>
      NetworkError(s"Cannot reach Redis at $endpoint: ${c.getMessage}", Some(c), endpoint)
    case other =>
      ProcessingError("redis-store", s"Redis command failed: ${other.getClass.getSimpleName}: ${other.getMessage}")
  }
}
