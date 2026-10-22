package org.llm4s.vectorstore.redis

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * What [[RedisVectorStore]] needs to reach one search index of a Redis server with the Redis Query Engine (Redis
 * Stack, or Redis 8 and later), or of a Valkey server with the `valkey-search` module.
 *
 * Load it from configuration with [[org.llm4s.config.RedisVectorStoreConfigLoader]], which reads
 * `llm4s.vectorstore.redis`, or build one in code:
 *
 * {{{
 * val config = RedisConfig("redis.example.com")
 *   .withTls(true)
 *   .withPassword("secret")
 *   .withIndex("docs")
 * }}}
 *
 * The store checks it with [[RedisConfig#validate]] when it is built. `toString` never prints the password.
 *
 * @param host           the server's host name or address
 * @param port           the server's port
 * @param tls            whether to connect over TLS. Without it the password crosses the network in clear text, which
 *                       is acceptable only on a trusted network.
 * @param database       the logical database (`SELECT`); Redis Query Engine indexes only database 0 on most servers
 * @param username       the ACL user; unset means the `default` user
 * @param password       the password (`AUTH`); unset means no authentication
 * @param index          the search index's name (`FT.CREATE`)
 * @param keyPrefix      the prefix of every record's hash key; unset means `"<index>:"`. Two stores must not share
 *                       a prefix.
 * @param algorithm      the vector index structure the index is created with
 * @param distanceMetric the distance the index is created with
 * @param dimensions     the vector size the index is created with; when unset, the size of the first record upserted.
 *                       A record of another size is refused before it is sent.
 * @param timeout        how long connecting and each command may take
 */
final case class RedisConfig private (
  host: String,
  port: Int,
  tls: Boolean,
  database: Int,
  username: Option[String],
  password: Option[String],
  index: String,
  keyPrefix: Option[String],
  algorithm: RedisVectorAlgorithm,
  distanceMetric: RedisDistanceMetric,
  dimensions: Option[Int],
  timeout: FiniteDuration
) {

  def withHost(host: String): RedisConfig                                  = copy(host = host)
  def withPort(port: Int): RedisConfig                                     = copy(port = port)
  def withTls(tls: Boolean): RedisConfig                                   = copy(tls = tls)
  def withDatabase(database: Int): RedisConfig                             = copy(database = database)
  def withUsername(username: String): RedisConfig                          = copy(username = Some(username))
  def withUsername(username: Option[String]): RedisConfig                  = copy(username = username)
  def withPassword(password: String): RedisConfig                          = copy(password = Some(password))
  def withPassword(password: Option[String]): RedisConfig                  = copy(password = password)
  def withIndex(index: String): RedisConfig                                = copy(index = index)
  def withKeyPrefix(keyPrefix: String): RedisConfig                        = copy(keyPrefix = Some(keyPrefix))
  def withKeyPrefix(keyPrefix: Option[String]): RedisConfig                = copy(keyPrefix = keyPrefix)
  def withAlgorithm(algorithm: RedisVectorAlgorithm): RedisConfig          = copy(algorithm = algorithm)
  def withDistanceMetric(distanceMetric: RedisDistanceMetric): RedisConfig = copy(distanceMetric = distanceMetric)
  def withDimensions(dimensions: Int): RedisConfig                         = copy(dimensions = Some(dimensions))
  def withDimensions(dimensions: Option[Int]): RedisConfig                 = copy(dimensions = dimensions)
  def withTimeout(timeout: FiniteDuration): RedisConfig                    = copy(timeout = timeout)

  /** The prefix of every record's key: [[keyPrefix]], or `"<index>:"`. */
  def effectivePrefix: String = keyPrefix.getOrElse(s"$index:")

  /** Checks the config; the first problem is reported, naming the setting. */
  def validate: Result[RedisConfig] = {
    def bad(why: String): Result[RedisConfig] = Left(ConfigurationError(s"The Redis $why"))
    def unsafe(s: String): Boolean            = s.exists(c => c.isWhitespace || c.isControl)
    if (host.isEmpty || unsafe(host)) bad("host is blank or contains whitespace")
    else if (port < 1 || port > 65535) bad(s"port must be between 1 and 65535, got $port")
    else if (database < 0) bad(s"database must not be negative, got $database")
    else if (username.exists(u => u.isEmpty || unsafe(u))) bad("username is blank or contains whitespace")
    else if (username.isDefined && password.isEmpty) bad("username is set without a password")
    else if (password.exists(_.isEmpty)) bad("password is empty")
    else if (index.isEmpty || unsafe(index)) bad("index name is blank or contains whitespace")
    else if (keyPrefix.exists(p => p.isEmpty || p.exists(_.isControl))) bad("keyPrefix is empty")
    else if (dimensions.exists(d => d < 1 || d > RedisConfig.MaxDimensions))
      bad(s"dimensions must be between 1 and ${RedisConfig.MaxDimensions}, got ${dimensions.getOrElse(0)}")
    else if (timeout <= Duration.Zero || timeout.toMillis > Int.MaxValue) bad("timeout must be positive")
    else Right(this)
  }

  override def toString: String =
    s"RedisConfig($host, $port, tls=$tls, database=$database, username=${username.getOrElse("default")}, " +
      s"password=${if (password.isDefined) "***" else "none"}, index=$index, keyPrefix=$effectivePrefix, " +
      s"algorithm=$algorithm, distanceMetric=$distanceMetric, dimensions=${dimensions.fold("auto")(_.toString)}, " +
      s"timeout=$timeout)"
}

object RedisConfig {

  /** A local server's host. */
  val DefaultHost: String = "localhost"

  /** Redis's default port. */
  val DefaultPort: Int = 6379

  /** The index used unless one is configured. */
  val DefaultIndex: String = "llm4s-vectors"

  /** How long connecting and each command may take unless configured otherwise. */
  val DefaultTimeout: FiniteDuration = 10.seconds

  /** The largest vector size this store accepts. */
  val MaxDimensions: Int = 32768

  /** Creates a config for the server at `host`. Named arguments are the supported way to set the rest. */
  def apply(
    host: String = DefaultHost,
    port: Int = DefaultPort,
    tls: Boolean = false,
    database: Int = 0,
    username: Option[String] = None,
    password: Option[String] = None,
    index: String = DefaultIndex,
    keyPrefix: Option[String] = None,
    algorithm: RedisVectorAlgorithm = RedisVectorAlgorithm.Hnsw,
    distanceMetric: RedisDistanceMetric = RedisDistanceMetric.Cosine,
    dimensions: Option[Int] = None,
    timeout: FiniteDuration = DefaultTimeout
  ): RedisConfig =
    new RedisConfig(
      host,
      port,
      tls,
      database,
      username,
      password,
      index,
      keyPrefix,
      algorithm,
      distanceMetric,
      dimensions,
      timeout
    )
}
