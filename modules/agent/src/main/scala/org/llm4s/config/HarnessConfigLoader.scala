// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import com.typesafe.config.Config
import org.llm4s.agent.graph.RetentionPolicy
import org.llm4s.agent.harness.{
  HarnessProfile,
  InstructionsConfig,
  OffloadConfig,
  PromptMode,
  SkillsConfig,
  SummarizationConfig,
  SummaryTrigger,
  TodoConfig,
  ToolSelection,
  VPath
}
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.types.Result
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*
import scala.util.Try

/**
 * Loads a [[org.llm4s.agent.harness.HarnessProfile]] from `llm4s.agent.harness` at the application's edge (design §4.37): the
 * [[org.llm4s.agent.harness.HarnessProfile.deepAgents]] preset with the configured settings - which tools, the tool timeout, the prompt mode,
 * each feature's limits or `enabled = false`, and the retention. `llm4s-agent`'s `reference.conf` holds the defaults,
 * which are the preset's, and the application's `application.conf` or `-D` system properties override them.
 *
 * {{{
 * for
 *   profile <- HarnessConfigLoader.load()
 *   agent   <- Agent.builder("researcher", client).withHarness(profile.withPermissions(rules)).build()
 * yield agent
 * }}}
 *
 * The loaded profile is checked as `AgentBuilder.build()` checks it, so a bad value is a `ConfigurationError` here -
 * except that `execute` may be selected before the sandbox it needs is set.
 * Backends, a sandbox, permission rules and a model compressor are code: set them on the loaded profile.
 */
@Experimental
object HarnessConfigLoader:

  /** The configuration path the profile is read from. */
  val Path: String = "llm4s.agent.harness"

  /** The profile from the current environment: system properties, `application.conf` and every `reference.conf`. */
  def load(): Result[HarnessProfile] = load(ConfigSource.default)

  /** The profile from `source`'s `llm4s.agent.harness`. */
  private[llm4s] def load(source: ConfigSource): Result[HarnessProfile] =
    for
      config <- source
        .at(Path)
        .load[Config]
        .left
        .map(failures => ConfigurationError(s"Failed to read $Path: ${failures.prettyPrint()}", List(Path)))
      profile <- read(config)
      _ <- profile.checkedBeforeSandbox.left.map(error =>
        ConfigurationError(s"Invalid $Path: ${error.message}", List(Path))
      )
    yield profile

  private def read(c: Config): Result[HarnessProfile] =
    for
      tools        <- field("tools")(toolSelection(c.getConfig("tools")))
      toolTimeout  <- field("toolTimeout")(optional(c, "toolTimeout")(c.getDuration(_).toScala))
      systemPrompt <- field("systemPrompt")(promptMode(c.getConfig("systemPrompt")))
      todos        <- section(c, "todos")(t => TodoConfig(t.getInt("maxTodos"), t.getInt("maxTodoChars")))
      summarization <- section(c, "summarization") { s =>
        SummarizationConfig(
          trigger(s.getString("trigger"), s.getInt("max")),
          s.getInt("keepRecent"),
          s.getInt("maxSummaryTokens")
        )
      }
      offload <- section(c, "offload")(o =>
        OffloadConfig(o.getInt("thresholdTokens"), o.getLong("maxResultBytes"), o.getLong("maxTotalBytes"))
      )
      skills <- section(c, "skills") { s =>
        SkillsConfig(
          paths(s, "sources"),
          s.getInt("maxSkills"),
          s.getInt("maxDescriptionChars"),
          s.getInt("maxSectionTokens"),
          s.getInt("maxScanned"),
          s.getInt("maxCachedRuns")
        )
      }
      instructions <- section(c, "instructions") { i =>
        InstructionsConfig(
          paths(i, "paths"),
          i.getInt("maxFileTokens"),
          i.getInt("maxTotalTokens"),
          i.getInt("maxCachedRuns")
        )
      }
      retention <- field("retention")(c.getConfig("retention")).flatMap(r =>
        for
          maxAge         <- field("retention.maxAge")(optional(r, "maxAge")(r.getDuration(_).toScala))
          maxCheckpoints <- field("retention.maxCheckpoints")(optional(r, "maxCheckpoints")(r.getInt))
          maxEvents      <- field("retention.maxEvents")(optional(r, "maxEvents")(r.getInt))
          policy <- RetentionPolicy
            .of(maxAge, maxCheckpoints, maxEvents)
            .left
            .map(e => ConfigurationError(s"Invalid $Path.retention: ${e.message}", List(s"$Path.retention")))
        yield policy
      )
    yield HarnessProfile(
      tools = tools,
      todos = todos,
      summarization = summarization,
      offload = offload,
      skills = skills,
      instructions = instructions,
      systemPrompt = systemPrompt,
      toolTimeout = toolTimeout,
      retention = retention
    )

  private def toolSelection(t: Config): ToolSelection =
    val names = t.getStringList("names").asScala.toSet
    t.getString("mode") match
      case "all" =>
        require(names.isEmpty, "names must be empty with mode = all")
        ToolSelection.All
      case "only"   => ToolSelection.Only(names)
      case "except" => ToolSelection.Except(names)
      case other    => throw new IllegalArgumentException(s"mode must be all, only or except, was '$other'")

  private def promptMode(p: Config): PromptMode =
    p.getString("mode") match
      case "default" => PromptMode.Default
      case "replace" => PromptMode.Replace(p.getString("text"))
      case "suffix"  => PromptMode.Suffix(p.getString("text"))
      case other     => throw new IllegalArgumentException(s"mode must be default, replace or suffix, was '$other'")

  private def trigger(kind: String, max: Int): SummaryTrigger =
    kind match
      case "tokens"   => SummaryTrigger.Tokens(max)
      case "messages" => SummaryTrigger.Messages(max)
      case other      => throw new IllegalArgumentException(s"trigger must be tokens or messages, was '$other'")

  private def paths(c: Config, key: String): Vector[VPath] =
    c.getStringList(key)
      .asScala
      .toVector
      .map(p => VPath(p).fold(e => throw new IllegalArgumentException(e.message), identity))

  private def optional[A](c: Config, key: String)(read: String => A): Option[A] =
    Option.when(c.hasPath(key))(read(key))

  /** A feature's settings, or `None` when its section says `enabled = false`. */
  private def section[A](c: Config, name: String)(read: Config => A): Result[Option[A]] =
    field(name) {
      val s = c.getConfig(name)
      Option.when(s.getBoolean("enabled"))(read(s))
    }

  /** `read`, a missing or mistyped key, or a value a type refuses, becoming a `ConfigurationError` naming `name`. */
  private def field[A](name: String)(read: => A): Result[A] =
    Try(read).toEither.left.map[LLMError](e =>
      ConfigurationError(s"Invalid $Path.$name: ${e.getMessage}", List(s"$Path.$name"))
    )
