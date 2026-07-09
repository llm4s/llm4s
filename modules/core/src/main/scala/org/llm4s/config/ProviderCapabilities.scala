package org.llm4s.config

/** Describes the validation and model-listing capabilities available for a given provider. */
private[llm4s] trait ProviderCapabilities:
  /** The validator used to check raw provider configuration sections. */
  def validator: NamedProviderValidator

  /** An optional `ProviderModelLister` for discovering models from this provider's API. */
  def modelLister: Option[ProviderModelLister] = None

/** Per-provider `ProviderCapabilities` implementations for each supported provider. */
private[llm4s] object ProviderCapabilities:

  /** Capabilities for the OpenAI provider. */
  object OpenAI extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.OpenAI
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.OpenAI)

  /** Capabilities for the OpenRouter provider. */
  object OpenRouter extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.OpenRouter
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.OpenRouter)

  /** Capabilities for the Requesty provider. */
  object Requesty extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.Requesty
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Requesty)

  /** Capabilities for the Azure provider. */
  object Azure extends ProviderCapabilities:
    val validator: NamedProviderValidator = NamedProviderValidators.Azure

  /** Capabilities for the Anthropic provider. */
  object Anthropic extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.Anthropic
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Anthropic)

  /** Capabilities for the Ollama provider. */
  object Ollama extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.Ollama
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Ollama)

  /** Capabilities for the Zai provider. */
  object Zai extends ProviderCapabilities:
    val validator: NamedProviderValidator = NamedProviderValidators.Zai

  /** Capabilities for the Gemini provider. */
  object Gemini extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.Gemini
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Gemini)

  /** Capabilities for the DeepSeek provider. */
  object DeepSeek extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.DeepSeek
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.DeepSeek)

  /** Capabilities for the Cohere provider. */
  object Cohere extends ProviderCapabilities:
    val validator: NamedProviderValidator = NamedProviderValidators.Cohere

  /** Capabilities for the Mistral provider. */
  object Mistral extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.Mistral
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Mistral)
