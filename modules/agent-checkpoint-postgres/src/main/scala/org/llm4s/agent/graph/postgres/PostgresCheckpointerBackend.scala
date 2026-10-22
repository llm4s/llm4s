package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.spi.{ CheckpointStore, CheckpointerBackend, CheckpointerSettings }
import org.llm4s.annotation.Experimental
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

/**
 * Registers [[PostgresCheckpointer]] for `llm4s.agent.checkpointer.kind = postgres` (design 4.54).
 *
 * Declared in this module's `META-INF/services/org.llm4s.agent.graph.spi.CheckpointerBackend`, so depending on
 * `llm4s-agent-checkpoint-postgres` is all it takes. It opens the store over a HikariCP pool of its own
 * ([[PostgresCheckpointer.open]]) from `llm4s.agent.checkpointer.postgres`, whose defaults and bindings are in this
 * module's `reference.conf`:
 *
 *  - `url` (`LLM4S_CHECKPOINTER_POSTGRES_URL`), `jdbc:postgresql://host:port/database`, and `user`
 *    (`LLM4S_CHECKPOINTER_POSTGRES_USER`): required;
 *  - `password` (`LLM4S_CHECKPOINTER_POSTGRES_PASSWORD`): unset for none; never logged or put in an error;
 *  - `schema`, `callTimeout`, `connectionTimeout`, `maxPoolSize`: [[PostgresCheckpointerConfig]]'s, with its defaults.
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader` instantiates it through a public no-arg
 * constructor.
 */
@Experimental
final class PostgresCheckpointerBackend extends CheckpointerBackend:
  val kind: String = PostgresCheckpointerBackend.Kind

  def open(settings: CheckpointerSettings): Result[CheckpointStore] =
    PostgresCheckpointerBackend
      .connection(settings)
      .flatMap(c => PostgresCheckpointer.open(c.url, c.user, c.password, c.config))
      .map(store => CheckpointStore(kind, store).withRunQueue(store.runQueue))

object PostgresCheckpointerBackend:

  /** The `llm4s.agent.checkpointer.kind` this backend serves. */
  val Kind: String = "postgres"

  /** A pgjdbc URL parameter that carries a credential (`password`, `sslpassword`), or user info before the host. */
  private val CredentialParameter = "(?i)[?&;](?:ssl)?password=|^jdbc:postgresql://[^/?]*@".r

  /** What the store is opened with; `toString` leaves out the password. */
  final private[postgres] case class Connection(
    url: String,
    user: String,
    password: String,
    config: PostgresCheckpointerConfig
  ):
    override def toString: String = s"Connection($url, $user, <password>, $config)"

  /** The connection `settings` describe, checked before anything is opened. */
  private[postgres] def connection(settings: CheckpointerSettings): Result[Connection] =
    val d = PostgresCheckpointerConfig.default
    for
      url <- settings.string("url", Some("LLM4S_CHECKPOINTER_POSTGRES_URL")).flatMap { url =>
        if !url.startsWith("jdbc:postgresql:") then
          Left(
            ConfigurationError(
              s"${settings.key("url")} must be a jdbc:postgresql://host:port/database URL",
              List(settings.key("url"))
            )
          )
        else if CredentialParameter.findFirstIn(url).isDefined then
          // Drivers echo the URL in their errors far more readily than a password: keep credentials out of it.
          Left(
            ConfigurationError(
              s"${settings.key("url")} must not carry a password: set ${settings.key("password")} " +
                "(LLM4S_CHECKPOINTER_POSTGRES_PASSWORD) instead",
              List(settings.key("url"))
            )
          )
        else Right(url)
      }
      user              <- settings.string("user", Some("LLM4S_CHECKPOINTER_POSTGRES_USER"))
      callTimeout       <- settings.duration("callTimeout", d.callTimeout)
      connectionTimeout <- settings.duration("connectionTimeout", d.connectionTimeout)
      maxPoolSize       <- settings.int("maxPoolSize", d.maxPoolSize)
      config <- PostgresCheckpointerConfig
        .of(settings.optionalString("schema").getOrElse(d.schema), callTimeout, connectionTimeout, maxPoolSize)
        .left
        .map { e =>
          val block = s"${CheckpointerSettings.Path}.$Kind"
          ConfigurationError(s"Invalid $block: ${e.message}", List(block))
        }
    yield Connection(url, user, settings.optionalString("password").getOrElse(""), config)
