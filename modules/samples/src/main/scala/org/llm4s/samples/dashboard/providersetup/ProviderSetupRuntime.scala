package org.llm4s.samples.dashboard.providersetup

import org.llm4s.config.{ DefaultConfig, DiscoveredModel, ProvidersConfigModel }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.config.*
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, SystemMessage, UserMessage }
import org.llm4s.llmconnect.{ LLMClient, LLMConnect, LlmClientOptions, ProviderExchangeLogging }
import org.llm4s.model.ModelRegistryService
import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.samples.dashboard.shared.DashboardSupport
import org.llm4s.types.ProviderModelTypes.{ ProviderId, ProviderName }
import org.llm4s.types.Result
import termflow.tui.{ Cmd, RuntimeCtx }

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try
import scala.util.Using

private[providersetup] object ProviderSetupRuntime:
  private def contextWindowResolver(using ModelRegistryService): ContextWindowResolver =
    ContextWindowResolver(summon[ModelRegistryService])

  def detectConfigStatus(
    providersCfg: ProvidersConfigModel.ProvidersConfig,
    discoveredModels: Map[ProviderName, List[DiscoveredModel]]
  ): ConfigStatus =
    val defaultName = providersCfg.defaultProviderName.toOption.map(_.asName)
    val configuredProviders =
      providersCfg.namedProviders.toVector
        .sortBy(_._1.asName)
        .map { case (name, cfg) =>
          val listedModels = discoveredModels(name)
          val (models, detail) = (
            listedModels.map(_.name.asString).toVector.sorted,
            s"Discovered ${listedModels.size} model(s)."
          )

          ConfiguredProvider(
            name = name.asName,
            providerId = cfg.provider.asString,
            modelName = cfg.model.asString,
            discoveredModels = models,
            discoveryDetail = detail,
            isDefault = defaultName.contains(name.asName)
          )
        }

    val selectedDefault = configuredProviders.find(_.isDefault).orElse(configuredProviders.headOption)

    ConfigStatus(
      headline = selectedDefault
        .map(p => s"configured: ${p.name} / ${p.providerId} / ${p.modelName}")
        .getOrElse("configured: named providers"),
      detail = selectedDefault
        .map(p => s"Loaded ${configuredProviders.size} named provider(s). Default: ${p.name}.")
        .getOrElse(s"Loaded ${configuredProviders.size} named provider(s)."),
      providerId = selectedDefault.map(_.providerId),
      modelName = selectedDefault.map(_.modelName),
      providerName = selectedDefault.map(_.name),
      namedProviders = configuredProviders
    )

  def refreshStatusCmd(
    providersCfg: ProvidersConfigModel.ProvidersConfig,
    discoveredModels: Map[ProviderName, List[DiscoveredModel]]
  ): Cmd[Msg] =
    Cmd.FCmd(
      task = Future {
        Msg.Global(
          GlobalMsg.StatusRefreshed(
            configStatus = detectConfigStatus(providersCfg, discoveredModels)
          )
        )
      },
      toCmd = Cmd.GCmd.apply
    )

  def resolveSession(
    providerConfigs: Map[ProviderName, ProviderConfig],
    defaultProvider: ProviderConfig,
    isDefaultProviderTab: Boolean,
    activeTab: SetupTabId,
    activeDocId: SetupTabDocId,
    selectedProviderId: Option[ProviderId],
    selectedConfiguredProvider: Option[ConfiguredProvider],
    sessionInput: Option[ProviderSessionInput]
  )(using ModelRegistryService): Either[String, ActiveSession] =
    val overrideInput = sessionInput.filter(_.hasAnyValue)
    selectedConfiguredProvider match
      case Some(configured) =>
        val configuredProvider = providerConfigs(ProviderName(configured.name))
        resolveConfiguredNamedSession(isDefaultProviderTab, configured, configuredProvider, overrideInput)
      case None =>
        val providerIdResult =
          selectedProviderId.map[Result[ProviderId]](Right(_)).getOrElse(providerIdFromDocId(activeDocId))

        providerIdResult.left.map(_.formatted).flatMap { providerId =>
          overrideInput.flatMap(input => buildOverrideSession(providerId, input).toOption) match
            case Some(session) =>
              Right(session)
            case None =>
              overrideInput match
                case Some(input) =>
                  buildOverrideSession(providerId, input)
                case None =>
                  resolveConfiguredSession(
                    providerConfigs,
                    defaultProvider,
                    activeTab,
                    selectedProviderId,
                    selectedConfiguredProvider
                  )
        }

  private def resolveConfiguredNamedSession(
    isDefaultProviderTab: Boolean,
    configured: ConfiguredProvider,
    providerConfig: ProviderConfig,
    overrideInput: Option[ProviderSessionInput]
  ): Either[String, ActiveSession] =
    val sessionConfig = applyConfiguredSessionOverride(providerConfig, overrideInput)
    val note =
      if isDefaultProviderTab then
        "Using the selected model for the configured default provider in this app session only."
      else if overrideInput.exists(_.hasAnyValue) then
        "Using the selected model for the configured named provider in this app session only."
      else "Using the selected configured named provider for this app session."
    Right(
      ActiveSession(
        config = sessionConfig,
        label = s"${configured.name} / ${sessionConfig.model}",
        note = note
      )
    )

  private def resolveConfiguredSession(
    providerConfigs: Map[ProviderName, ProviderConfig],
    defaultProvider: ProviderConfig,
    activeTab: SetupTabId,
    selectedProviderId: Option[ProviderId],
    selectedConfiguredProvider: Option[ConfiguredProvider]
  ): Either[String, ActiveSession] =
    selectedConfiguredProvider match
      case Some(namedProvider) if activeTab == SetupTabId.Providers =>
        val config = providerConfigs(ProviderName(namedProvider.name))
        Right(
          ActiveSession(
            config = config,
            label = s"${namedProvider.name} / ${config.model}",
            note = "Using the selected configured named provider for this app session."
          )
        )

      case _ =>
        val configuredProvider = defaultProvider.providerId.asString
        selectedProviderId match
          case Some(requested) if activeTab == SetupTabId.Providers && configuredProvider != requested.asString =>
            Left(
              s"The selected provider is ${requested.asString}, but llm4s is currently configured for $configuredProvider. Change your config and run reload first."
            )
          case _ =>
            Right(
              ActiveSession(
                config = defaultProvider,
                label = s"$configuredProvider / ${defaultProvider.model}",
                note = "Using the provider currently resolved by llm4s config loading for this app session."
              )
            )

  private def buildOverrideSession(
    providerId: ProviderId,
    input: ProviderSessionInput
  )(using ModelRegistryService): Either[String, ActiveSession] =
    given ContextWindowResolver = contextWindowResolver
    providerId.asString match
      case "ollama" =>
        for
          model <- requiredModel(
            input,
            "Session override for the default provider needs `set model <name>` or a chosen model from the picker."
          )
          baseUrl <- requiredBaseUrl(
            input,
            "Session override for the default provider needs `set base-url <url>`."
          )
        yield activeSession(
          providerId = "ollama",
          model = model,
          config = OllamaConfig.fromValues(model, baseUrl),
          note = s"Using session override values for the default provider in this app run only. Base URL: $baseUrl"
        )
      case "openai" =>
        for
          model  <- requiredModel(input, "Session override for OpenAI needs `set model <model>`.")
          apiKey <- requiredApiKey(input, "Session override for OpenAI needs `set api-key <key>`.")
        yield activeSession(
          providerId = "openai",
          model = model,
          config = OpenAIConfig.fromValues(
            model,
            apiKey,
            input.organization,
            input.baseUrl.getOrElse(DefaultConfig.DEFAULT_OPENAI_BASE_URL)
          ),
          note = "Using session override values for OpenAI in this app run only."
        )
      case "azure" =>
        for
          model    <- requiredModel(input, "Session override for Azure OpenAI needs `set model <deployment>`.")
          endpoint <- requiredEndpoint(input, "Session override for Azure OpenAI needs `set endpoint <url>`.")
          apiKey   <- requiredApiKey(input, "Session override for Azure OpenAI needs `set api-key <key>`.")
        yield activeSession(
          providerId = "azure",
          model = model,
          config = AzureConfig.fromValues(
            model,
            endpoint,
            apiKey,
            input.apiVersion.getOrElse(DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW)
          ),
          note = "Using session override values for Azure OpenAI in this app run only."
        )
      case "anthropic" =>
        for
          model  <- requiredModel(input, "Session override for Anthropic needs `set model <model>`.")
          apiKey <- requiredApiKey(input, "Session override for Anthropic needs `set api-key <key>`.")
        yield activeSession(
          providerId = "anthropic",
          model = model,
          config = AnthropicConfig
            .fromValues(model, apiKey, input.baseUrl.getOrElse(DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL)),
          note = "Using session override values for Anthropic in this app run only."
        )
      case "gemini" =>
        for
          model  <- requiredModel(input, "Session override for Gemini needs `set model <model>`.")
          apiKey <- requiredApiKey(input, "Session override for Gemini needs `set api-key <key>`.")
        yield activeSession(
          providerId = "gemini",
          model = model,
          config =
            GeminiConfig.fromValues(model, apiKey, input.baseUrl.getOrElse(DefaultConfig.DEFAULT_GEMINI_BASE_URL)),
          note = "Using session override values for Gemini in this app run only."
        )
      case "deepseek" =>
        for
          model  <- requiredModel(input, "Session override for DeepSeek needs `set model <model>`.")
          apiKey <- requiredApiKey(input, "Session override for DeepSeek needs `set api-key <key>`.")
        yield activeSession(
          providerId = "deepseek",
          model = model,
          config = DeepSeekConfig.fromValues(model, apiKey, input.baseUrl.getOrElse(DeepSeekConfig.DEFAULT_BASE_URL)),
          note = "Using session override values for DeepSeek in this app run only."
        )
      case "cohere" =>
        for
          model  <- requiredModel(input, "Session override for Cohere needs `set model <model>`.")
          apiKey <- requiredApiKey(input, "Session override for Cohere needs `set api-key <key>`.")
        yield activeSession(
          providerId = "cohere",
          model = model,
          config = CohereConfig.fromValues(model, apiKey, input.baseUrl.getOrElse(CohereConfig.DEFAULT_BASE_URL)),
          note = "Using session override values for Cohere in this app run only."
        )
      case "mistral" =>
        for
          model  <- requiredModel(input, "Session override for Mistral needs `set model <model>`.")
          apiKey <- requiredApiKey(input, "Session override for Mistral needs `set api-key <key>`.")
        yield activeSession(
          providerId = "mistral",
          model = model,
          config = MistralConfig.fromValues(model, apiKey, input.baseUrl.getOrElse(MistralConfig.DEFAULT_BASE_URL)),
          note = "Using session override values for Mistral in this app run only."
        )
      case "zai" =>
        for
          model  <- requiredModel(input, "Session override for Z.ai needs `set model <model>`.")
          apiKey <- requiredApiKey(input, "Session override for Z.ai needs `set api-key <key>`.")
        yield activeSession(
          providerId = "zai",
          model = model,
          config = ZaiConfig.fromValues(model, apiKey, input.baseUrl.getOrElse(ZaiConfig.DEFAULT_BASE_URL)),
          note = "Using session override values for Z.ai in this app run only."
        )
      case other =>
        Left(s"Session override is not implemented for provider: $other")

  private def activeSession(
    providerId: String,
    model: String,
    config: ProviderConfig,
    note: String
  ): ActiveSession =
    ActiveSession(
      config = config,
      label = s"$providerId / $model",
      note = note
    )

  private def requiredModel(input: ProviderSessionInput, message: String): Either[String, String] =
    input.model.toRight(message)

  private def requiredApiKey(input: ProviderSessionInput, message: String): Either[String, String] =
    input.apiKey.toRight(message)

  private def requiredBaseUrl(input: ProviderSessionInput, message: String): Either[String, String] =
    input.baseUrl.toRight(message)

  private def requiredEndpoint(input: ProviderSessionInput, message: String): Either[String, String] =
    input.endpoint.toRight(message)

  // The one remaining per-provider match in this file, and the one that does not collapse:
  // it edits provider-specific fields (Azure's apiVersion, Vertex's projectId), not just the
  // model. Left as-is deliberately - the fix is for the dashboard to edit the HOCON config
  // *section* and rebuild the config through the provider's descriptor, rather than
  // pattern-matching the already-built config. That needs the SPI, so it lands with #1131 PR 2.
  private def applyConfiguredSessionOverride(
    provider: ProviderConfig,
    input: Option[ProviderSessionInput]
  ): ProviderConfig =
    (provider: @unchecked) match
      case cfg: OpenAIConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          apiKey = input.flatMap(_.apiKey).getOrElse(cfg.apiKey),
          organization = input.flatMap(_.organization).orElse(cfg.organization),
          baseUrl = input.flatMap(_.baseUrl).getOrElse(cfg.baseUrl)
        )
      case cfg: AzureConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          endpoint = input.flatMap(_.endpoint).getOrElse(cfg.endpoint),
          apiKey = input.flatMap(_.apiKey).getOrElse(cfg.apiKey),
          apiVersion = input.flatMap(_.apiVersion).getOrElse(cfg.apiVersion)
        )
      case cfg: AnthropicConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          apiKey = input.flatMap(_.apiKey).getOrElse(cfg.apiKey),
          baseUrl = input.flatMap(_.baseUrl).getOrElse(cfg.baseUrl)
        )
      case cfg: OllamaConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          baseUrl = input.flatMap(_.baseUrl).getOrElse(cfg.baseUrl)
        )
      case cfg: GeminiConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          apiKey = input.flatMap(_.apiKey).getOrElse(cfg.apiKey),
          baseUrl = input.flatMap(_.baseUrl).getOrElse(cfg.baseUrl)
        )
      case cfg: DeepSeekConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          apiKey = input.flatMap(_.apiKey).getOrElse(cfg.apiKey),
          baseUrl = input.flatMap(_.baseUrl).getOrElse(cfg.baseUrl)
        )
      case cfg: CohereConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          apiKey = input.flatMap(_.apiKey).getOrElse(cfg.apiKey),
          baseUrl = input.flatMap(_.baseUrl).getOrElse(cfg.baseUrl)
        )
      case cfg: MistralConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          apiKey = input.flatMap(_.apiKey).getOrElse(cfg.apiKey),
          baseUrl = input.flatMap(_.baseUrl).getOrElse(cfg.baseUrl)
        )
      case cfg: ZaiConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          apiKey = input.flatMap(_.apiKey).getOrElse(cfg.apiKey),
          baseUrl = input.flatMap(_.baseUrl).getOrElse(cfg.baseUrl)
        )
      case cfg: VertexAIConfig =>
        cfg.copy(
          model = input.flatMap(_.model).getOrElse(cfg.model),
          projectId = input.flatMap(_.endpoint).getOrElse(cfg.projectId),
          location = input.flatMap(_.organization).getOrElse(cfg.location),
          credentialFilePath = input.flatMap(_.apiKey).orElse(cfg.credentialFilePath)
        )

  def demoCompletionCmd(
    providerConfig: ProviderConfig,
    entries: Vector[DemoEntry],
    demoConfig: ProviderSetupDemoConfig,
    exchangeLogging: ProviderExchangeLogging,
    ctx: RuntimeCtx[Msg]
  )(using ModelRegistryService): Cmd[Msg] =
    Cmd.FCmd(
      task = Future {
        Msg.Global(
          GlobalMsg.DemoResponseReceived(runDemoCompletion(providerConfig, entries, demoConfig, exchangeLogging, ctx))
        )
      },
      toCmd = Cmd.GCmd.apply
    )

  def resolveCompareSession(
    selection: CompareSelection,
    demoAppconfigs: DemoAppConfigs
  ): Either[String, ActiveSession] =
    val config        = demoAppconfigs.providerConfigs(ProviderName(selection.providerName))
    val sessionConfig = overrideModel(config, selection.selectedModel)
    Right(
      ActiveSession(
        config = sessionConfig,
        label = s"${selection.providerName} / ${selection.selectedModel}",
        note = s"Parallel compare session for ${selection.providerName}."
      )
    )

  def compareCompletionStartCmd(
    demoAppconfigs: DemoAppConfigs,
    selections: Vector[CompareSelection],
    entries: Vector[DemoEntry],
    demoConfig: ProviderSetupDemoConfig,
    exchangeLogging: ProviderExchangeLogging,
    ctx: RuntimeCtx[Msg]
  )(using ModelRegistryService): Cmd[Msg] =
    Cmd.FCmd(
      task = Future {
        selections.foreach { selection =>
          Future {
            val startedAt = Instant.now()
            val result =
              resolveCompareSession(selection, demoAppconfigs)
                .flatMap(session =>
                  runDemoCompletion(
                    session.config,
                    entries,
                    demoConfig.copy(streamingEnabled = false),
                    exchangeLogging,
                    ctx
                  )
                )
            val latencyMs = java.time.Duration.between(startedAt, Instant.now()).toMillis
            ctx.publish(
              Cmd.GCmd(Msg.Global(GlobalMsg.CompareResponseReceived(selection.providerName, latencyMs, result)))
            )
          }
        }
        Msg.Global(GlobalMsg.NoOp)
      },
      toCmd = Cmd.GCmd.apply
    )

  private def runDemoCompletion(
    config: ProviderConfig,
    entries: Vector[DemoEntry],
    demoConfig: ProviderSetupDemoConfig,
    exchangeLogging: ProviderExchangeLogging,
    ctx: RuntimeCtx[Msg]
  )(using ModelRegistryService): Either[String, String] =
    val clientResult: Result[LLMClient] = LLMConnect.getClient(
      config,
      LlmClientOptions(
        exchangeLogging = exchangeLogging
      )
    )

    clientResult match
      case Left(error) =>
        ProviderSetupDebugLog.append(
          demoConfig.debugLog,
          "demo-client-error",
          List(
            s"provider=${providerName(config)}",
            s"model=${config.model}",
            s"error=${error.formatted}"
          )
        )
        Left(error.formatted)
      case Right(client) =>
        Using.resource(client) { scopedClient =>
          Try {
            val conversation = demoConversation(entries)
            val useStreaming = demoConfig.streamingEnabled && providerSupportsStreaming(config)
            ProviderSetupDebugLog.append(
              demoConfig.debugLog,
              "demo-request",
              List(
                s"provider=${providerName(config)}",
                s"model=${config.model}",
                s"streamingRequested=${demoConfig.streamingEnabled}",
                s"streamingUsed=$useStreaming",
                s"messages=${conversation.messages.length}"
              ) ++ conversation.messages.zipWithIndex.map { case (message, idx) =>
                s"[$idx] ${message.role}: ${message.content}"
              }
            )
            val completionResult =
              if useStreaming then
                scopedClient.streamComplete(
                  conversation,
                  onChunk = chunk =>
                    chunk.content
                      .filter(_.nonEmpty)
                      .foreach(text => ctx.publish(Cmd.GCmd(Msg.Global(GlobalMsg.DemoChunkReceived(text)))))
                )
              else scopedClient.complete(conversation)

            completionResult
              .fold(
                error =>
                  ProviderSetupDebugLog.append(
                    demoConfig.debugLog,
                    "demo-response-error",
                    List(
                      s"provider=${providerName(config)}",
                      s"model=${config.model}",
                      s"error=${error.formatted}"
                    )
                  )
                  Left(error.formatted)
                ,
                completion =>
                  val text = completion.message.content.trim
                  ProviderSetupDebugLog.append(
                    demoConfig.debugLog,
                    "demo-response",
                    List(
                      s"provider=${providerName(config)}",
                      s"model=${completion.model}",
                      s"id=${completion.id}",
                      s"responseLength=${text.length}",
                      "response:",
                      text
                    )
                  )
                  Right(text)
              )
          }.toEither.fold(
            e =>
              val message = DashboardSupport.safeMessage(e)
              ProviderSetupDebugLog.append(
                demoConfig.debugLog,
                "demo-exception",
                List(
                  s"provider=${providerName(config)}",
                  s"model=${config.model}",
                  s"error=$message"
                )
              )
              Left(message)
            ,
            identity
          )
        }

  private def demoConversation(entries: Vector[DemoEntry]): Conversation =
    val systemPrompt =
      SystemMessage(
        "You are a helpful demo assistant inside the llm4s provider setup app. Keep answers concise, practical, and suitable for a terminal UI."
      )
    val history = entries.takeRight(10).flatMap {
      case DemoEntry(DemoRole.System, content) if !content.startsWith("Demo ready with ") =>
        Some(SystemMessage(content))
      case DemoEntry(DemoRole.User, content)      => Some(UserMessage(content))
      case DemoEntry(DemoRole.Assistant, content) => Some(AssistantMessage(content))
      case _                                      => None
    }
    Conversation(systemPrompt +: history)

  private def providerName(provider: ProviderConfig): String =
    provider.providerId.asString

  private def providerIdFromDocId(docId: SetupTabDocId): Result[ProviderId] =
    ProviderSetupContent.providerDocs
      .find(_.id.value == docId.value)
      .map(doc => ProviderId(doc.id.value))
      .toRight(ValidationError("providerDocId", s"Expected provider doc id but got: ${docId.value}"))

  // Hard-coded because there is nothing to ask yet. `ProviderFeatures` on the descriptor is the
  // replacement (#1131 PR 2), which is also what makes the gap in #925 un-hideable.
  private def providerSupportsStreaming(provider: ProviderConfig): Boolean =
    provider match
      case _: MistralConfig => false
      case _: CohereConfig  => false
      case _                => true

  private def overrideModel(provider: ProviderConfig, modelName: String): ProviderConfig =
    provider.withModel(modelName)
