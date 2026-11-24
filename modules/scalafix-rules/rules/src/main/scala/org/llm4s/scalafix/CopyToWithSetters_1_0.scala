package org.llm4s.scalafix

/**
 * `.copy(field = value)` on the types made growth-safe since 0.5.1 becomes `.withField(value)`.
 *
 * After 0.5.1 another set of types became growth-prone (private `new` and `copy`, `with*` setters): the
 * OpenAI-compatible, Gemini, Vertex AI and Azure configs, core's config and data types, the `TraceEvent` and
 * `AssistantError` cases, the provider SPI reports, the tool schemas, the context types and `RerankProviderConfig`.
 * `config.copy(timeout = None)` on a `ToolExecutionConfig` becomes `config.withTimeout(None)`. `ToolRetryPolicy` is
 * not rewritten: its setters check the value and return a `Result` (#2816). An `apiKey` that was a
 * `String` is wrapped in `Secret(...)`, which is now its type, and `org.llm4s.security.Secret` is imported. A
 * positional `copy` and a `new X(...)` are reported, with a link to the type's migration note.
 */
class CopyToWithSetters_1_0 extends CopyToSetters("CopyToWithSetters_1_0") {

  override def description: String =
    "Rewrites .copy(field = value) on the types made growth-safe since llm4s 0.5.1 to .withField(value) setters"

  override protected def types: Set[String]                      = CopyToWithSetters_1_0.types.keySet
  override protected def release: String                         = "0.5.1"
  override protected def secrets: Map[(String, String), Boolean] = CopyToWithSetters_1_0.secrets
  override protected def newtypes: Map[(String, String), String] = CopyToWithSetters_1_0.newtypes
  override protected def reportNew: Boolean                      = true
  override protected def privateCopyGuide(owner: String): String = CopyToWithSetters_1_0.types(owner)
  override protected def setter(owner: String, field: String): String =
    if (owner == "org/llm4s/toolapi/StringSchema" && field == "enumValues") "withEnum"
    else if (owner == "org/llm4s/context/CompressionRule" && field == "apply") "withTransform"
    else super.setter(owner, field)
}

object CopyToWithSetters_1_0 {

  /**
   * The types, as 0.5.1 named them, and the migration note of each. Every field of each type's 0.5.1 constructor has a
   * `with` setter of the same name on main (`StringSchema.enumValues`: `withEnum`; `CompressionRule.apply`, now
   * `transform`: `withTransform`), taking the value and, for an
   * `Option` field, the `Option` too.
   */
  val types: Map[String, String] = {
    val byNote: List[(String, List[String])] =
      List(
        "the-openai-compatible-provider-configs-are-growth-safe-2264" -> List(
          "llmconnect/config/CohereConfig",
          "llmconnect/config/DeepSeekConfig",
          "llmconnect/config/MistralConfig",
          "llmconnect/config/ZaiConfig"
        ),
        "geminiconfig-vertexaiconfig-and-azureconfig-are-growth-safe-2263" -> List(
          "llmconnect/config/AzureConfig",
          "llmconnect/config/GeminiConfig",
          "llmconnect/config/VertexAIConfig"
        ),
        "core-config-types-are-growth-safe-2346" -> List(
          "llmconnect/LlmClientOptions",
          "llmconnect/config/EmbeddingModelConfig",
          "llmconnect/config/EmbeddingProviderConfig",
          "llmconnect/config/LocalEmbeddingModels",
          "llmconnect/config/TracingSettings",
          "toolapi/ToolExecutionConfig"
        ),
        "core-data-types-are-growth-safe-2356" -> List(
          "config/DiscoveredModel",
          "http/HttpRawResponse",
          "http/HttpResponse",
          "http/JsonHttpResponse",
          "http/StreamingHttpResponse",
          "llmconnect/ProviderExchange",
          "llmconnect/model/EmbeddingResponse",
          "llmconnect/model/EmbeddingUsage",
          "llmconnect/model/ModelUsage",
          "llmconnect/model/UsageSummary",
          "model/ModelPricing"
        ),
        "llmconnectmodel-types-are-growth-safe-2378" -> List(
          "llmconnect/model/AudioInput",
          "llmconnect/model/Conversation",
          "llmconnect/model/EmbeddingVector",
          "llmconnect/model/ImageInput",
          "llmconnect/model/MultimediaEmbeddingRequest",
          "llmconnect/model/RawMediaInput",
          "llmconnect/model/ResponseFormat.JsonSchema",
          "llmconnect/model/SystemMessage",
          "llmconnect/model/ToolCall",
          "llmconnect/model/VideoInput"
        ),
        "traceevent-cases-are-growth-safe-2377" -> List(
          "trace/TraceEvent.AgentRunEnded",
          "trace/TraceEvent.CacheHit",
          "trace/TraceEvent.CacheMiss",
          "trace/TraceEvent.CompletionReceived",
          "trace/TraceEvent.CostRecorded",
          "trace/TraceEvent.CustomEvent",
          "trace/TraceEvent.EmbeddingUsageRecorded",
          "trace/TraceEvent.ErrorOccurred",
          "trace/TraceEvent.ImageGenerationCompleted",
          "trace/TraceEvent.RAGOperationCompleted",
          "trace/TraceEvent.TokenUsageRecorded",
          "trace/TraceEvent.ToolExecuted"
        ),
        "assistanterror-cases-are-growth-safe-2383" -> List(
          "error/AssistantError.CommandParseError",
          "error/AssistantError.DisplayError",
          "error/AssistantError.EOFError",
          "error/AssistantError.FileError",
          "error/AssistantError.IOError",
          "error/AssistantError.SerializationError",
          "error/AssistantError.SessionError"
        ),
        "provider-spi-caching-streaming-and-exchange-sink-types-are-growth-safe-2379" -> List(
          "llmconnect/JsonlProviderExchangeSink",
          "llmconnect/caching/CacheStats",
          "llmconnect/provider/OpenAICompatibleClient.Settings",
          "llmconnect/spi/EmbeddingProviderSection",
          "llmconnect/spi/ProviderConfigKey",
          "llmconnect/spi/ProviderDiscoveryFailure",
          "llmconnect/spi/ProviderIdCollision",
          "llmconnect/spi/ProviderModuleReport",
          "llmconnect/spi/ProviderRegistryReport",
          "llmconnect/streaming/SSEParser.SSEEvent"
        ),
        "toolcallerror-and-toolparametererror-cases-2382" -> List(
          "toolapi/ToolCallError.UnknownFunction",
          "toolapi/ToolParameterError.MissingParameter"
        ),
        "tool-schemas-and-tool-call-types-are-growth-safe-2381" -> List(
          "toolapi/ArraySchema",
          "toolapi/BooleanSchema",
          "toolapi/IntegerSchema",
          "toolapi/NumberSchema",
          "toolapi/ObjectSchema",
          "toolapi/PropertyDefinition",
          "toolapi/SafeParameterExtractor",
          "toolapi/StringSchema",
          "toolapi/ToolCallRequest",
          "toolapi/ToolFunction"
        ),
        "context-types-are-growth-safe-2380" -> List(
          "context/CompressionRule",
          "context/ContextStep",
          "context/ConversationWindow",
          "context/HistoryDigest",
          "context/ManagedConversation",
          "context/MessageTokenInfo",
          "context/PipelineStep",
          "context/SemanticBlock",
          "context/StructuredInfo",
          "context/TokenBreakdown",
          "context/TokenUsageInfo"
        ),
        "rerankproviderconfig-is-growth-safe-2478" -> List(
          "reranker/RerankProviderConfig"
        ),
        "config-model-and-util-types-are-growth-safe-2384" -> List(
          "config/Llm4sConfig.TextEmbeddingModelSettings",
          "config/ProvidersConfigModel.ProvidersConfig",
          "model/TransformationResult"
        )
      )
    byNote.flatMap { case (anchor, names) => names.map(n => s"org/llm4s/$n" -> Migration.guide10(anchor)) }.toMap
  }

  /**
   * Fields that were a `String` in 0.5.1 and are a newtype of `org.llm4s.types.ProviderModelTypes` now: a provider
   * config's `model`, and the model and base URL of an `EmbeddingProviderConfig` (#2805).
   */
  val newtypes: Map[(String, String), String] =
    Seq("CohereConfig", "DeepSeekConfig", "MistralConfig", "ZaiConfig", "AzureConfig", "GeminiConfig", "VertexAIConfig")
      .map(config => ("org/llm4s/llmconnect/config/" + config, "model") -> "ModelName")
      .toMap ++ Map(
      ("org/llm4s/llmconnect/config/EmbeddingProviderConfig", "model")   -> "ModelName",
      ("org/llm4s/llmconnect/config/EmbeddingProviderConfig", "baseUrl") -> "BaseUrl"
    )

  /** The `apiKey` fields that were a `String` (`true`: an `Option[String]`) in 0.5.1 and are a `Secret` now. */
  val secrets: Map[(String, String), Boolean] = Map(
    ("org/llm4s/llmconnect/config/DeepSeekConfig", "apiKey")          -> false,
    ("org/llm4s/llmconnect/config/ZaiConfig", "apiKey")               -> false,
    ("org/llm4s/llmconnect/config/MistralConfig", "apiKey")           -> false,
    ("org/llm4s/llmconnect/config/CohereConfig", "apiKey")            -> false,
    ("org/llm4s/llmconnect/config/GeminiConfig", "apiKey")            -> false,
    ("org/llm4s/llmconnect/config/AzureConfig", "apiKey")             -> false,
    ("org/llm4s/llmconnect/config/EmbeddingProviderConfig", "apiKey") -> false,
    ("org/llm4s/reranker/RerankProviderConfig", "apiKey")             -> false,
    ("org/llm4s/llmconnect/spi/EmbeddingProviderSection", "apiKey")   -> true
  )
}
