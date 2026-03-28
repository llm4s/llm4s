package org.llm4s.config

private[llm4s] trait ProviderCapabilities:
  def validator: NamedProviderValidator
  def modelLister: Option[ProviderModelLister] = None

private[llm4s] object ProviderCapabilities:

  object OpenAI extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.OpenAI
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.OpenAI)

  object OpenRouter extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.OpenRouter
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.OpenRouter)

  object Azure extends ProviderCapabilities:
    val validator: NamedProviderValidator = NamedProviderValidators.Azure

  object Anthropic extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.Anthropic
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Anthropic)

  object Ollama extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.Ollama
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Ollama)

  object Zai extends ProviderCapabilities:
    val validator: NamedProviderValidator = NamedProviderValidators.Zai

  object Gemini extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.Gemini
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Gemini)

  object DeepSeek extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.DeepSeek
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.DeepSeek)

  object Cohere extends ProviderCapabilities:
    val validator: NamedProviderValidator = NamedProviderValidators.Cohere

  object Mistral extends ProviderCapabilities:
    val validator: NamedProviderValidator                 = NamedProviderValidators.Mistral
    override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Mistral)
