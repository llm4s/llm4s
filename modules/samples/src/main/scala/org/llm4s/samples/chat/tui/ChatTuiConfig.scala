package org.llm4s.samples.chat.tui

import org.llm4s.config.{ DefaultConfig, Llm4sConfig }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

import java.nio.file.{ Path, Paths }

/**
 * Static configuration for the chat-tui demo.
 *
 * Provider resolution prefers an explicit `LLM_MODEL=<provider>/<model>`
 * env var (matches the contract documented in CLAUDE.md), pulling the
 * matching `<PROVIDER>_API_KEY` and optional `<PROVIDER>_BASE_URL`. This
 * keeps the demo usable with the env-var conventions LLM4S users already
 * have set up. When `LLM_MODEL` is unset, falls back to
 * `Llm4sConfig.defaultProvider()` (which reads the named-providers
 * config in `application.conf`).
 *
 * `/model` switches the active model name for the current conversation;
 * provider routing stays pinned to whatever was resolved at startup.
 */
final case class ChatTuiConfig(
  providerConfig: ProviderConfig,
  modelRegistry: ModelRegistryService,
  modelName: String,
  systemPrompt: String,
  workspaceRoot: Path,
  allowedModels: Vector[String]
)

object ChatTuiConfig:

  private val DefaultSystem: String =
    "You are a helpful assistant running inside a terminal chat client. " +
      "Replies should be concise and Markdown-light. " +
      "When the user asks about a file, prefer the read_file tool over guessing."

  /** Hand-curated list of models accepted by `/model <name>`. */
  val allowedModels: Vector[String] = Vector(
    "openai/gpt-4o-mini",
    "openai/gpt-4o",
    "anthropic/claude-3-5-haiku-latest",
    "anthropic/claude-sonnet-4-5-latest",
    "anthropic/claude-sonnet-4-20250514",
    "ollama/qwen2.5:0.5b",
    "ollama/llama3.2",
    "gemini/gemini-2.0-flash"
  )

  def load(): Result[ChatTuiConfig] =
    for {
      registry <- Llm4sConfig.modelRegistryService()
      provider <- resolveProvider(registry)
    } yield ChatTuiConfig(
      providerConfig = provider,
      modelRegistry = registry,
      modelName = canonicalModelName(provider),
      systemPrompt = ChatTuiEnv.getOrElse("CHAT_TUI_SYSTEM_PROMPT", DefaultSystem),
      workspaceRoot = sys.props.get("user.dir").map(Paths.get(_)).getOrElse(Paths.get(".")),
      allowedModels = allowedModels
    )

  /**
   * Try to honour `LLM_MODEL=<provider>/<model>` first; fall back to the
   * named-providers config when the env var is unset.
   */
  private def resolveProvider(registry: ModelRegistryService): Result[ProviderConfig] =
    given ContextWindowResolver = ContextWindowResolver(registry)
    ChatTuiEnv.get("LLM_MODEL").map(_.trim).filter(_.nonEmpty) match {
      case Some(modelSpec) => fromLlmModel(modelSpec)
      case None            => Llm4sConfig.defaultProvider()
    }

  /**
   * Parse `LLM_MODEL=<provider>/<model>` and construct a `ProviderConfig`
   * directly from env vars. The model portion may itself contain `/`
   * (e.g. OpenRouter's `meta-llama/llama-3-...`), so we split on the
   * first `/` only.
   */
  private def fromLlmModel(spec: String)(using ContextWindowResolver): Result[ProviderConfig] =
    spec.indexOf('/') match {
      case -1 =>
        Left(ConfigurationError(s"LLM_MODEL must be 'provider/model'; got: $spec"))
      case i =>
        val provider = spec.substring(0, i).trim.toLowerCase
        val model    = spec.substring(i + 1).trim
        if provider.isEmpty || model.isEmpty then
          Left(ConfigurationError(s"LLM_MODEL must be 'provider/model'; got: $spec"))
        else buildProvider(provider, model)
    }

  private def buildProvider(provider: String, model: String)(using ContextWindowResolver): Result[ProviderConfig] =
    provider match {
      case "openai" =>
        requireKey("OPENAI_API_KEY").map { apiKey =>
          val baseUrl = ChatTuiEnv.getOrElse("OPENAI_BASE_URL", DefaultConfig.DEFAULT_OPENAI_BASE_URL)
          val org     = ChatTuiEnv.get("OPENAI_ORGANIZATION").filter(_.nonEmpty)
          OpenAIConfig.fromValues(model, apiKey, org, baseUrl)
        }

      case "openrouter" =>
        requireKey("OPENROUTER_API_KEY").map { apiKey =>
          val baseUrl = ChatTuiEnv.getOrElse("OPENAI_BASE_URL", DefaultConfig.DEFAULT_OPENROUTER_BASE_URL)
          OpenAIConfig.fromValues(model, apiKey, None, baseUrl)
        }

      case "requesty" =>
        requireKey("REQUESTY_API_KEY").map { apiKey =>
          val baseUrl = ChatTuiEnv.getOrElse("OPENAI_BASE_URL", DefaultConfig.DEFAULT_REQUESTY_BASE_URL)
          OpenAIConfig.fromValues(model, apiKey, None, baseUrl)
        }

      case "anthropic" =>
        requireKey("ANTHROPIC_API_KEY").map { apiKey =>
          val baseUrl = ChatTuiEnv.getOrElse("ANTHROPIC_BASE_URL", DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL)
          AnthropicConfig.fromValues(model, apiKey, baseUrl)
        }

      case "ollama" =>
        val baseUrl = ChatTuiEnv.getOrElse("OLLAMA_BASE_URL", "http://localhost:11434")
        Right(OllamaConfig.fromValues(model, baseUrl))

      case "gemini" =>
        // Gemini accepts either GOOGLE_API_KEY or GEMINI_API_KEY.
        val key = ChatTuiEnv.get("GEMINI_API_KEY").orElse(ChatTuiEnv.get("GOOGLE_API_KEY")).filter(_.nonEmpty)
        key match {
          case Some(apiKey) =>
            val baseUrl = ChatTuiEnv.getOrElse("GEMINI_BASE_URL", DefaultConfig.DEFAULT_GEMINI_BASE_URL)
            Right(GeminiConfig.fromValues(model, apiKey, baseUrl))
          case None =>
            Left(ConfigurationError("LLM_MODEL=gemini/... requires GOOGLE_API_KEY or GEMINI_API_KEY"))
        }

      case "zai" =>
        requireKey("ZAI_API_KEY").map { apiKey =>
          val baseUrl = ChatTuiEnv.getOrElse("ZAI_BASE_URL", ZaiConfig.DEFAULT_BASE_URL)
          ZaiConfig.fromValues(model, apiKey, baseUrl)
        }

      case "deepseek" =>
        requireKey("DEEPSEEK_API_KEY").map { apiKey =>
          val baseUrl = ChatTuiEnv.getOrElse("DEEPSEEK_BASE_URL", DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL)
          DeepSeekConfig.fromValues(model, apiKey, baseUrl)
        }

      case "mistral" =>
        requireKey("MISTRAL_API_KEY").map { apiKey =>
          val baseUrl = ChatTuiEnv.getOrElse("MISTRAL_BASE_URL", MistralConfig.DEFAULT_BASE_URL)
          MistralConfig.fromValues(model, apiKey, baseUrl)
        }

      case "cohere" =>
        requireKey("COHERE_API_KEY").map { apiKey =>
          val baseUrl = ChatTuiEnv.getOrElse("COHERE_BASE_URL", CohereConfig.DEFAULT_BASE_URL)
          CohereConfig.fromValues(model, apiKey, baseUrl)
        }

      case other =>
        Left(
          ConfigurationError(
            s"LLM_MODEL provider '$other' is not supported by chat-tui. " +
              "Use openai|anthropic|ollama|gemini|zai|deepseek|openrouter|requesty|mistral|cohere or unset LLM_MODEL " +
              "to fall back to the named-providers config."
          )
        )
    }

  private def requireKey(name: String): Result[String] =
    ChatTuiEnv.get(name).map(_.trim).filter(_.nonEmpty) match {
      case Some(v) => Right(v)
      case None    => Left(ConfigurationError(s"$name is required when LLM_MODEL is set"))
    }

  /**
   * Best-effort canonical "provider/model" label for the active provider.
   * Used in the title bar and `/model` semantics.
   */
  private def canonicalModelName(p: ProviderConfig): String =
    s"${p.provider.toString.toLowerCase}/${p.model}"
