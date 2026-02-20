package org.llm4s.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModelCapabilitiesSpec extends AnyFlatSpec with Matchers {

  // ─────────────────────────────────────────────────────────────
  // ModelCapabilities.fromJson – boolean flags
  // ─────────────────────────────────────────────────────────────

  "ModelCapabilities.fromJson" should "parse all boolean capability flags as true" in {
    val json = ujson.Obj(
      "supports_function_calling"          -> true,
      "supports_parallel_function_calling" -> true,
      "supports_vision"                    -> true,
      "supports_prompt_caching"            -> true,
      "supports_reasoning"                 -> true,
      "supports_response_schema"           -> true,
      "supports_system_messages"           -> true,
      "supports_pdf_input"                 -> true,
      "supports_audio_input"               -> true,
      "supports_audio_output"              -> true,
      "supports_web_search"                -> true,
      "supports_computer_use"              -> true,
      "supports_assistant_prefill"         -> true,
      "supports_tool_choice"               -> true,
      "supports_native_streaming"          -> true
    )

    val caps = ModelCapabilities.fromJson(json)

    caps.supportsFunctionCalling shouldBe Some(true)
    caps.supportsParallelFunctionCalling shouldBe Some(true)
    caps.supportsVision shouldBe Some(true)
    caps.supportsPromptCaching shouldBe Some(true)
    caps.supportsReasoning shouldBe Some(true)
    caps.supportsResponseSchema shouldBe Some(true)
    caps.supportsSystemMessages shouldBe Some(true)
    caps.supportsPdfInput shouldBe Some(true)
    caps.supportsAudioInput shouldBe Some(true)
    caps.supportsAudioOutput shouldBe Some(true)
    caps.supportsWebSearch shouldBe Some(true)
    caps.supportsComputerUse shouldBe Some(true)
    caps.supportsAssistantPrefill shouldBe Some(true)
    caps.supportsToolChoice shouldBe Some(true)
    caps.supportsNativeStreaming shouldBe Some(true)
  }

  it should "parse all boolean capability flags as false" in {
    val json = ujson.Obj(
      "supports_function_calling"          -> false,
      "supports_parallel_function_calling" -> false,
      "supports_vision"                    -> false,
      "supports_prompt_caching"            -> false,
      "supports_reasoning"                 -> false,
      "supports_response_schema"           -> false,
      "supports_system_messages"           -> false,
      "supports_pdf_input"                 -> false,
      "supports_audio_input"               -> false,
      "supports_audio_output"              -> false,
      "supports_web_search"                -> false,
      "supports_computer_use"              -> false,
      "supports_assistant_prefill"         -> false,
      "supports_tool_choice"               -> false,
      "supports_native_streaming"          -> false
    )

    val caps = ModelCapabilities.fromJson(json)

    caps.supportsFunctionCalling shouldBe Some(false)
    caps.supportsParallelFunctionCalling shouldBe Some(false)
    caps.supportsVision shouldBe Some(false)
    caps.supportsPromptCaching shouldBe Some(false)
    caps.supportsReasoning shouldBe Some(false)
    caps.supportsResponseSchema shouldBe Some(false)
    caps.supportsSystemMessages shouldBe Some(false)
    caps.supportsPdfInput shouldBe Some(false)
    caps.supportsAudioInput shouldBe Some(false)
    caps.supportsAudioOutput shouldBe Some(false)
    caps.supportsWebSearch shouldBe Some(false)
    caps.supportsComputerUse shouldBe Some(false)
    caps.supportsAssistantPrefill shouldBe Some(false)
    caps.supportsToolChoice shouldBe Some(false)
    caps.supportsNativeStreaming shouldBe Some(false)
  }

  it should "return None for all flags when JSON fields are absent" in {
    val caps = ModelCapabilities.fromJson(ujson.Obj())

    caps.supportsFunctionCalling shouldBe None
    caps.supportsParallelFunctionCalling shouldBe None
    caps.supportsVision shouldBe None
    caps.supportsPromptCaching shouldBe None
    caps.supportsReasoning shouldBe None
    caps.supportsResponseSchema shouldBe None
    caps.supportsSystemMessages shouldBe None
    caps.supportsPdfInput shouldBe None
    caps.supportsAudioInput shouldBe None
    caps.supportsAudioOutput shouldBe None
    caps.supportsWebSearch shouldBe None
    caps.supportsComputerUse shouldBe None
    caps.supportsAssistantPrefill shouldBe None
    caps.supportsToolChoice shouldBe None
    caps.supportsNativeStreaming shouldBe None
  }

  it should "return None for all flags when JSON fields are null" in {
    val json = ujson.Obj(
      "supports_function_calling"          -> ujson.Null,
      "supports_parallel_function_calling" -> ujson.Null,
      "supports_vision"                    -> ujson.Null,
      "supports_prompt_caching"            -> ujson.Null,
      "supports_reasoning"                 -> ujson.Null
    )

    val caps = ModelCapabilities.fromJson(json)

    caps.supportsFunctionCalling shouldBe None
    caps.supportsParallelFunctionCalling shouldBe None
    caps.supportsVision shouldBe None
    caps.supportsPromptCaching shouldBe None
    caps.supportsReasoning shouldBe None
  }

  // ─────────────────────────────────────────────────────────────
  // ModelCapabilities.fromJson – complex fields
  // ─────────────────────────────────────────────────────────────

  it should "parse supported_regions list" in {
    val json = ujson.Obj(
      "supported_regions" -> ujson.Arr("us-east-1", "eu-west-1", "ap-southeast-1")
    )

    val caps = ModelCapabilities.fromJson(json)
    caps.supportedRegions shouldBe Some(List("us-east-1", "eu-west-1", "ap-southeast-1"))
  }

  it should "return None for supported_regions when field is absent" in {
    ModelCapabilities.fromJson(ujson.Obj()).supportedRegions shouldBe None
  }

  it should "return None for supported_regions when field is null" in {
    val json = ujson.Obj("supported_regions" -> ujson.Null)
    ModelCapabilities.fromJson(json).supportedRegions shouldBe None
  }

  it should "parse disallowed_params set" in {
    val json = ujson.Obj(
      "disallowed_params" -> ujson.Arr("temperature", "top_p")
    )

    val caps = ModelCapabilities.fromJson(json)
    caps.disallowedParams shouldBe Some(Set("temperature", "top_p"))
  }

  it should "return None for disallowed_params when field is absent" in {
    ModelCapabilities.fromJson(ujson.Obj()).disallowedParams shouldBe None
  }

  it should "parse temperature_constraint range" in {
    val json = ujson.Obj(
      "temperature_constraint" -> ujson.Arr(0.0, 1.0)
    )

    val caps = ModelCapabilities.fromJson(json)
    caps.temperatureConstraint shouldBe Some((0.0, 1.0))
  }

  it should "return None for temperature_constraint when field is absent" in {
    ModelCapabilities.fromJson(ujson.Obj()).temperatureConstraint shouldBe None
  }

  it should "return None for temperature_constraint when field is null" in {
    val json = ujson.Obj("temperature_constraint" -> ujson.Null)
    ModelCapabilities.fromJson(json).temperatureConstraint shouldBe None
  }

  // ─────────────────────────────────────────────────────────────
  // ModelMetadata.supports() – all capability keys and aliases
  // ─────────────────────────────────────────────────────────────

  private def metaWith(caps: ModelCapabilities): ModelMetadata =
    ModelMetadata(
      modelId = "test-model",
      provider = "test",
      mode = ModelMode.Chat,
      maxInputTokens = None,
      maxOutputTokens = None,
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = caps,
      pricing = ModelPricing(),
      deprecationDate = None
    )

  "ModelMetadata.supports" should "return true for function_calling and its 'tools' alias" in {
    val meta = metaWith(ModelCapabilities(supportsFunctionCalling = Some(true)))
    meta.supports("function_calling") shouldBe true
    meta.supports("tools") shouldBe true
  }

  it should "return true for parallel_function_calling" in {
    val meta = metaWith(ModelCapabilities(supportsParallelFunctionCalling = Some(true)))
    meta.supports("parallel_function_calling") shouldBe true
  }

  it should "return true for vision and its 'images' alias" in {
    val meta = metaWith(ModelCapabilities(supportsVision = Some(true)))
    meta.supports("vision") shouldBe true
    meta.supports("images") shouldBe true
  }

  it should "return true for prompt_caching and its 'caching' alias" in {
    val meta = metaWith(ModelCapabilities(supportsPromptCaching = Some(true)))
    meta.supports("prompt_caching") shouldBe true
    meta.supports("caching") shouldBe true
  }

  it should "return true for reasoning" in {
    val meta = metaWith(ModelCapabilities(supportsReasoning = Some(true)))
    meta.supports("reasoning") shouldBe true
  }

  it should "return true for response_schema and its 'structured' alias" in {
    val meta = metaWith(ModelCapabilities(supportsResponseSchema = Some(true)))
    meta.supports("response_schema") shouldBe true
    meta.supports("structured") shouldBe true
  }

  it should "return true for system_messages" in {
    val meta = metaWith(ModelCapabilities(supportsSystemMessages = Some(true)))
    meta.supports("system_messages") shouldBe true
  }

  it should "return true for pdf_input and its 'pdf' alias" in {
    val meta = metaWith(ModelCapabilities(supportsPdfInput = Some(true)))
    meta.supports("pdf_input") shouldBe true
    meta.supports("pdf") shouldBe true
  }

  it should "return true for audio_input" in {
    val meta = metaWith(ModelCapabilities(supportsAudioInput = Some(true)))
    meta.supports("audio_input") shouldBe true
  }

  it should "return true for audio_output" in {
    val meta = metaWith(ModelCapabilities(supportsAudioOutput = Some(true)))
    meta.supports("audio_output") shouldBe true
  }

  it should "return true for web_search" in {
    val meta = metaWith(ModelCapabilities(supportsWebSearch = Some(true)))
    meta.supports("web_search") shouldBe true
  }

  it should "return true for computer_use" in {
    val meta = metaWith(ModelCapabilities(supportsComputerUse = Some(true)))
    meta.supports("computer_use") shouldBe true
  }

  it should "return true for assistant_prefill and its 'prefill' alias" in {
    val meta = metaWith(ModelCapabilities(supportsAssistantPrefill = Some(true)))
    meta.supports("assistant_prefill") shouldBe true
    meta.supports("prefill") shouldBe true
  }

  it should "return true for tool_choice" in {
    val meta = metaWith(ModelCapabilities(supportsToolChoice = Some(true)))
    meta.supports("tool_choice") shouldBe true
  }

  it should "return true for native_streaming and its 'streaming' alias" in {
    val meta = metaWith(ModelCapabilities(supportsNativeStreaming = Some(true)))
    meta.supports("native_streaming") shouldBe true
    meta.supports("streaming") shouldBe true
  }

  it should "default native_streaming to true when capability is None" in {
    // Per implementation: supportsNativeStreaming.getOrElse(true)
    val meta = metaWith(ModelCapabilities(supportsNativeStreaming = None))
    meta.supports("native_streaming") shouldBe true
    meta.supports("streaming") shouldBe true
  }

  it should "return false for known capabilities explicitly set to false" in {
    val allFalse = ModelCapabilities(
      supportsFunctionCalling = Some(false),
      supportsVision = Some(false),
      supportsPromptCaching = Some(false),
      supportsReasoning = Some(false),
      supportsResponseSchema = Some(false),
      supportsSystemMessages = Some(false),
      supportsPdfInput = Some(false),
      supportsAudioInput = Some(false),
      supportsAudioOutput = Some(false),
      supportsWebSearch = Some(false),
      supportsComputerUse = Some(false),
      supportsAssistantPrefill = Some(false),
      supportsToolChoice = Some(false),
      supportsNativeStreaming = Some(false)
    )
    val meta = metaWith(allFalse)

    meta.supports("function_calling") shouldBe false
    meta.supports("vision") shouldBe false
    meta.supports("caching") shouldBe false
    meta.supports("reasoning") shouldBe false
    meta.supports("response_schema") shouldBe false
    meta.supports("system_messages") shouldBe false
    meta.supports("pdf_input") shouldBe false
    meta.supports("audio_input") shouldBe false
    meta.supports("audio_output") shouldBe false
    meta.supports("web_search") shouldBe false
    meta.supports("computer_use") shouldBe false
    meta.supports("assistant_prefill") shouldBe false
    meta.supports("tool_choice") shouldBe false
    meta.supports("native_streaming") shouldBe false
  }

  it should "return false for all capabilities when all are None (except streaming)" in {
    val meta = metaWith(ModelCapabilities())

    meta.supports("function_calling") shouldBe false
    meta.supports("parallel_function_calling") shouldBe false
    meta.supports("vision") shouldBe false
    meta.supports("prompt_caching") shouldBe false
    meta.supports("reasoning") shouldBe false
    meta.supports("response_schema") shouldBe false
    meta.supports("system_messages") shouldBe false
    meta.supports("pdf_input") shouldBe false
    meta.supports("audio_input") shouldBe false
    meta.supports("audio_output") shouldBe false
    meta.supports("web_search") shouldBe false
    meta.supports("computer_use") shouldBe false
    meta.supports("assistant_prefill") shouldBe false
    meta.supports("tool_choice") shouldBe false
    meta.supports("native_streaming") shouldBe true // defaults to true
  }

  it should "return false for an unrecognised capability key" in {
    val meta = metaWith(ModelCapabilities())
    meta.supports("telekinesis") shouldBe false
    meta.supports("") shouldBe false
  }

  it should "dispatch capability lookup case-insensitively" in {
    val meta = metaWith(ModelCapabilities(supportsFunctionCalling = Some(true)))
    meta.supports("FUNCTION_CALLING") shouldBe true
    meta.supports("Function_Calling") shouldBe true
    meta.supports("VISION") shouldBe false // not set in this meta
  }
}
