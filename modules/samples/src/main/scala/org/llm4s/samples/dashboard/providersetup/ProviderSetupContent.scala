package org.llm4s.samples.dashboard.providersetup

import org.llm4s.samples.dashboard.providersetup.ProviderSetupDemoApp.{ ProviderDoc, SetupTabDocIds }

private[providersetup] object ProviderSetupContent:

  val docs: Vector[ProviderDoc] = Vector(
    ProviderDoc(
      id = SetupTabDocIds.Overview,
      title = "Overview",
      summary = "This sample helps first-time users understand how llm4s expects provider configuration.",
      highlights = List(
        "llm4s can read config from env vars, application.conf, or Java properties.",
        "For a first pass, env vars are the simplest and safest path.",
        "This app validates the current machine using real llm4s config loading."
      ),
      requiredVars = List("LLM_MODEL"),
      optionalVars = List("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "AZURE_API_KEY", "AZURE_API_BASE", "OLLAMA_HOST"),
      recommendedModels = List("ollama/llama3:latest", "openai/gpt-4o-mini"),
      setupSteps = List(
        "Use the default provider tab for the fastest first pass, especially with a local model.",
        "Open Providers to browse the rest of the supported hosted and local connectors.",
        "Run `status` or `reload` here to verify detection."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Default,
      title = "Default",
      summary = "Focused view of the currently configured default named provider.",
      highlights = List(
        "This tab follows whichever named provider is currently marked as the default.",
        "Use it to inspect the active default provider without browsing the full provider list.",
        "The exact required settings depend on that configured provider kind."
      ),
      requiredVars = List("Depends on the configured default provider"),
      optionalVars = List("Any session-only overrides you want for this app run"),
      recommendedModels = List("Uses the models discovered from the configured default provider"),
      setupSteps = List(
        "Mark one named provider as the default in llm4s config.",
        "Run `reload` after changing config.",
        "Highlight a model in the middle panel and press Enter to choose it.",
        "Type `use` to open chat with that default provider."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Providers,
      title = "Providers",
      summary = "Browse the wider provider set supported by llm4s and compare what each one needs.",
      highlights = List(
        "Use the middle panel to browse provider options.",
        "The detail panel updates as you move through the provider list.",
        "The default provider tab gives one focused path while this tab exposes the wider provider set."
      ),
      requiredVars = List("Provider-specific credentials and model selection"),
      optionalVars = List("Base URLs, org ids, and local endpoint overrides vary by provider"),
      recommendedModels = List("openai/gpt-4o-mini", "anthropic/claude-3-5-haiku-latest"),
      setupSteps = List(
        "Move focus into the body, then use Left/Right to reach the provider list.",
        "Use Up/Down to browse providers.",
        "Run `use` only after the matching provider is actually configured in llm4s."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Compare,
      title = "Compare",
      summary = "Build a small compare set of named providers and run the same prompt against them in parallel.",
      highlights = List(
        "Each compare entry is one named provider plus one selected model.",
        "The same named provider appears only once in the compare set.",
        "Results arrive independently so you can see fast and slow providers side by side."
      ),
      requiredVars = List("At least two configured named providers with working credentials"),
      optionalVars =
        List("Use another named provider if you want the same provider kind with a different default model"),
      recommendedModels = List("openai/gpt-4o-mini", "ollama/qwen3:8b"),
      setupSteps = List(
        "Move focus into the body and highlight an available provider.",
        "Open that provider's discovered models and choose one to add it to the compare set.",
        "Repeat until the compare set looks right, then type `use`.",
        "Enter one prompt and review the provider tabs as responses arrive."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Config,
      title = "Config Paths",
      summary = "llm4s supports multiple config entry points. Start simple and keep secrets local.",
      highlights = List(
        "Env vars are the easiest for first-run and demos.",
        "application.conf is useful when you want durable local defaults.",
        "Java properties are handy for scripted or IDE-driven launches."
      ),
      requiredVars = List("Same logical keys regardless of config source"),
      optionalVars = List("Use local private files only if you intentionally want persistence"),
      recommendedModels = List("Prefer one known-good model per provider during setup"),
      setupSteps = List(
        "Start with env vars to remove ambiguity.",
        "If the workflow stabilizes later, move selected values into local config.",
        "Keep secrets out of git-tracked files."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Status,
      title = "Status",
      summary = "This tab reflects the current machine state using llm4s configuration loading.",
      highlights = List(
        "Configured means llm4s resolved a provider successfully.",
        "Not configured means llm4s could not load a valid provider yet.",
        "Use `reload` after changing env vars or config files."
      ),
      requiredVars = List("Depends on the chosen provider"),
      optionalVars = List("Additional provider-specific tuning settings"),
      recommendedModels = List("ollama/llama3:latest", "openai/gpt-4o-mini"),
      setupSteps = List(
        "Change config outside the app.",
        "Run `reload` here.",
        "Once status is healthy, the next slice can launch chat or agent views."
      )
    )
  )

  val providerDocs: Vector[ProviderDoc] = Vector(
    ProviderDoc(
      id = SetupTabDocIds.Ollama,
      title = "Ollama",
      summary = "Local or self-hosted provider path for Ollama models through llm4s.",
      highlights = List(
        "Does not require an API key for the usual local or reachable Ollama setup.",
        "Useful when you want a local-first workflow or your own reachable Ollama host.",
        "Model discovery depends on the configured host being reachable."
      ),
      requiredVars = List("LLM_MODEL=ollama/<model-name>"),
      optionalVars = List("OLLAMA_HOST", "OPENAI_BASE_URL"),
      recommendedModels = List("ollama/llama3:latest", "ollama/qwen2.5:3b"),
      setupSteps = List(
        "Install and start Ollama on the local machine or another reachable host.",
        "Pull one model, for example `ollama pull llama3:latest`.",
        "If needed, set `OLLAMA_HOST` to the target host and port.",
        "Run `reload` here to confirm llm4s can resolve and list models."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.OpenAI,
      title = "OpenAI",
      summary = "Hosted API path when you want access to OpenAI models from llm4s.",
      highlights = List(
        "Requires an OpenAI API key with billing enabled in the OpenAI platform account.",
        "Use a small model first while iterating to keep costs predictable.",
        "The key should normally stay in env vars rather than a committed file."
      ),
      requiredVars = List("LLM_MODEL=openai/<model-name>", "OPENAI_API_KEY=<your-api-key>"),
      optionalVars = List("OPENAI_BASE_URL", "OPENAI_ORG_ID"),
      recommendedModels = List("openai/gpt-4o-mini", "openai/gpt-4.1-mini"),
      setupSteps = List(
        "Create an API key in the OpenAI platform dashboard.",
        "Export `OPENAI_API_KEY` in your shell.",
        "Export `LLM_MODEL=openai/gpt-4o-mini`.",
        "Use `reload` in this app to validate the setup."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Azure,
      title = "Azure OpenAI",
      summary = "Azure-hosted OpenAI deployment path for teams already using Azure AI services.",
      highlights = List(
        "Requires Azure resource credentials and a deployed model name.",
        "Useful when your organization standardizes on Azure networking and identity.",
        "Deployment names can differ from the underlying model family."
      ),
      requiredVars =
        List("LLM_MODEL=azure/<deployment-name>", "AZURE_API_KEY=<your-api-key>", "AZURE_API_BASE=<endpoint>"),
      optionalVars = List("AZURE_API_VERSION"),
      recommendedModels = List("azure/gpt-4o-mini", "azure/gpt-4.1-mini"),
      setupSteps = List(
        "Create or reuse an Azure OpenAI resource.",
        "Deploy a model and note the deployment name.",
        "Export the Azure key, base URL, and `LLM_MODEL`.",
        "Run `reload` here to verify llm4s can resolve the provider."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Anthropic,
      title = "Anthropic",
      summary = "Hosted API path for Claude models through Anthropic.",
      highlights = List(
        "Requires an Anthropic API key.",
        "A good option when you want Claude models and their message API.",
        "Keep the key in env vars or local private config only."
      ),
      requiredVars = List("LLM_MODEL=anthropic/<model-name>", "ANTHROPIC_API_KEY=<your-api-key>"),
      optionalVars = List("ANTHROPIC_BASE_URL"),
      recommendedModels = List("anthropic/claude-3-5-haiku-latest", "anthropic/claude-3-7-sonnet-latest"),
      setupSteps = List(
        "Create an Anthropic API key.",
        "Export `ANTHROPIC_API_KEY`.",
        "Export a Claude model in `LLM_MODEL`.",
        "Use `reload` to confirm the setup."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Gemini,
      title = "Gemini",
      summary = "Google Gemini provider path for hosted multimodal and text models.",
      highlights = List(
        "Requires a Gemini or Google AI Studio style API key.",
        "Useful for Gemini model families inside llm4s.",
        "Model naming can change, so keep one known-good default while iterating."
      ),
      requiredVars = List("LLM_MODEL=gemini/<model-name>", "GEMINI_API_KEY=<your-api-key>"),
      optionalVars = List("GEMINI_BASE_URL"),
      recommendedModels = List("gemini/gemini-2.0-flash", "gemini/gemini-1.5-pro"),
      setupSteps = List(
        "Create or retrieve a Gemini API key.",
        "Export `GEMINI_API_KEY`.",
        "Pick one working Gemini model in `LLM_MODEL`.",
        "Run `reload` to verify detection."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.DeepSeek,
      title = "DeepSeek",
      summary = "OpenAI-compatible hosted path for DeepSeek models.",
      highlights = List(
        "Requires a DeepSeek API key.",
        "Shares a similar setup shape to OpenAI-compatible providers.",
        "A useful path when you want reasoning-oriented models through llm4s."
      ),
      requiredVars = List("LLM_MODEL=deepseek/<model-name>", "DEEPSEEK_API_KEY=<your-api-key>"),
      optionalVars = List("DEEPSEEK_BASE_URL"),
      recommendedModels = List("deepseek/deepseek-chat", "deepseek/deepseek-reasoner"),
      setupSteps = List(
        "Create a DeepSeek API key.",
        "Export `DEEPSEEK_API_KEY`.",
        "Set `LLM_MODEL` to a known DeepSeek model.",
        "Use `reload` to validate the configuration."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Cohere,
      title = "Cohere",
      summary = "Hosted API path for Cohere command models.",
      highlights = List(
        "Requires a Cohere API key.",
        "Useful when you want Cohere models or embeddings in the same ecosystem.",
        "Keep the first setup to one working command model."
      ),
      requiredVars = List("LLM_MODEL=cohere/<model-name>", "COHERE_API_KEY=<your-api-key>"),
      optionalVars = List("COHERE_BASE_URL"),
      recommendedModels = List("cohere/command-r", "cohere/command-r-plus"),
      setupSteps = List(
        "Create a Cohere API key.",
        "Export `COHERE_API_KEY`.",
        "Set `LLM_MODEL` to a supported command model.",
        "Run `reload` here to check resolution."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Mistral,
      title = "Mistral",
      summary = "Hosted API path for Mistral models through llm4s.",
      highlights = List(
        "Requires a Mistral API key.",
        "Good when you want Mistral-hosted text models.",
        "Model families differ, so start with a smaller known-good choice."
      ),
      requiredVars = List("LLM_MODEL=mistral/<model-name>", "MISTRAL_API_KEY=<your-api-key>"),
      optionalVars = List("MISTRAL_BASE_URL"),
      recommendedModels = List("mistral/mistral-small-latest", "mistral/open-mistral-nemo"),
      setupSteps = List(
        "Create a Mistral API key.",
        "Export `MISTRAL_API_KEY`.",
        "Export a supported model in `LLM_MODEL`.",
        "Use `reload` to confirm llm4s can load it."
      )
    ),
    ProviderDoc(
      id = SetupTabDocIds.Zai,
      title = "Z.ai",
      summary = "Hosted provider path for Z.ai models.",
      highlights = List(
        "Requires a Z.ai API key.",
        "Follows a provider-specific hosted setup similar to the other API-backed connectors.",
        "Keep a single working model configured first."
      ),
      requiredVars = List("LLM_MODEL=zai/<model-name>", "ZAI_API_KEY=<your-api-key>"),
      optionalVars = List("ZAI_BASE_URL"),
      recommendedModels = List("zai/glm-4.5-air", "zai/glm-4.5"),
      setupSteps = List(
        "Create a Z.ai API key.",
        "Export `ZAI_API_KEY`.",
        "Set `LLM_MODEL` to a supported Z.ai model.",
        "Use `reload` to validate the setup."
      )
    )
  )
