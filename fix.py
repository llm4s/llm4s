import os
import re

def rep(file_path, replacements):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    orig = content
    for (old, new) in replacements:
        if isinstance(old, re.Pattern):
            content = old.sub(new, content)
        else:
            content = content.replace(old, new)
    if orig != content:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f'Fixed {file_path}')

core = r'c:\Users\Acer\OneDrive\Desktop\llm4s\modules\core\src\main\scala\org\llm4s'

rep(os.path.join(core, 'llmconnect', 'config', 'ProviderConfig.scala'), [
    (re.compile(r'(extends ProviderConfig \{\n  override val provider: ProviderKind = ProviderKind\.\w+\n  override def toString: String =\n.*?)(?=\n\nobject )', re.DOTALL), r'\1\n}')
])

rep(os.path.join(core, 'config', 'ProviderModelLister.scala'), [
    ('private def parseOllamaModels(json: ujson.Value): Result[List[DiscoveredModel]] =\n    val modelsResult =', 
     'private def parseOllamaModels(json: ujson.Value): Result[List[DiscoveredModel]] = {\n    val modelsResult ='),
    ('      }\n    }\n\n  private def parseOllamaModel', 
     '      }\n    }\n  }\n\n  private def parseOllamaModel'),
    ('private def parseOllamaModel(json: ujson.Value): Result[Option[DiscoveredModel]] =\n    val obj = json.obj',
     'private def parseOllamaModel(json: ujson.Value): Result[Option[DiscoveredModel]] = {\n    val obj = json.obj'),
    ('          )\n        )\n    }', 
     '          )\n        )\n    }\n  }')
])

rep(os.path.join(core, 'config', 'ProvidersConfigLoader.scala'), [
    ('private[config] object ProvidersConfigLoader:', 'private[config] object ProvidersConfigLoader {'),
    ('def validate(raw: RawProvidersConfig): Result[ProvidersConfig] =', 'def validate(raw: RawProvidersConfig): Result[ProvidersConfig] = {'),
    ('      ProvidersConfig(\n        selectedProvider = selected,\n        providers = namedProviders\n      )\n    }', 
     '      ProvidersConfig(\n        selectedProvider = selected,\n        providers = namedProviders\n      )\n    }\n  }'),
    ('private def validateNamedProviders(\n    providers: Map[String, ujson.Obj]\n  ): Result[Map[ProviderName, NamedProviderConfig]] =', 
     'private def validateNamedProviders(\n    providers: Map[String, ujson.Obj]\n  ): Result[Map[ProviderName, NamedProviderConfig]] = {'),
    ('      }\n    }', '      }\n    }\n  }'),
    ('private def validateSelectedProvider(\n    selected: Option[String],\n    available: Map[ProviderName, NamedProviderConfig]\n  ): Result[Option[ProviderName]] =', 
     'private def validateSelectedProvider(\n    selected: Option[String],\n    available: Map[ProviderName, NamedProviderConfig]\n  ): Result[Option[ProviderName]] = {'),
    ('      case None =>\n        Right(None)\n    }', '      case None =>\n        Right(None)\n    }\n  }'),
    (re.compile(r'Right\(None\)\n    \}\n  \}\n$'), r'Right(None)\n    }\n  }\n}\n')
])

rep(os.path.join(core, 'config', 'ProvidersConfigModel.scala'), [
    ('object ProvidersConfigModel:', 'object ProvidersConfigModel {'),
    ('  ):', '  ) {'),
    ('def requireBaseUrl: Result[BaseUrl] =', 'def requireBaseUrl: Result[BaseUrl] = {'),
    ('        Left(ConfigurationError(s"Missing required config value: baseUrl in provider $providerName"))\n    }', '        Left(ConfigurationError(s"Missing required config value: baseUrl in provider $providerName"))\n    }\n  }'),
    ('def baseUrlOrDefault(default: => String): BaseUrl =', 'def baseUrlOrDefault(default: => String): BaseUrl = {'),
    ('      case None          => BaseUrl(default)\n    }', '      case None          => BaseUrl(default)\n    }\n  }'),
    ('def requireApiKey: Result[ApiKey] =', 'def requireApiKey: Result[ApiKey] = {'),
    ('        Left(ConfigurationError(s"Missing required config value: apiKey in provider $providerName"))\n    }', '        Left(ConfigurationError(s"Missing required config value: apiKey in provider $providerName"))\n    }\n  }'),
    (re.compile(r'    \}\n  \}\n$'), r'    }\n  }\n}\n}\n')
])

rep(os.path.join(core, 'config', 'RawProvidersConfigLoader.scala'), [
    (re.compile(r'  \}\n\}\n$'), r'}\n')
])

rep(os.path.join(core, 'llmconnect', 'ProviderExchangeSink.scala'), [
    ('override def process(exchange: ProviderExchange): Unit =\n    val timestamp = timestampFormatter.format(startedAt)', 
     'override def process(exchange: ProviderExchange): Unit = {\n    val timestamp = timestampFormatter.format(startedAt)'),
    ('      logger.info(logMessage)\n    }', '      logger.info(logMessage)\n    }\n  }')
])

rep(os.path.join(core, 'llmconnect', 'config', 'ContextWindowResolver.scala'), [
    ('class ContextWindowResolver(service: ModelRegistryService):', 'class ContextWindowResolver(service: ModelRegistryService) {'),
    ('fallbackResolver: String => (Int, Int)\n  ): (Int, Int) =', 'fallbackResolver: String => (Int, Int)\n  ): (Int, Int) = {'),
    ('registryResult match\n      case Some(metadata) =>', 'registryResult match {\n      case Some(metadata) =>'),
    (r'      case None =>\n        logger.debug(s"Model \'$modelName\' not found in registry; using fallback resolver")\n        fallbackResolver(modelName)', 
     r'      case None =>\n        logger.debug(s"Model \'$modelName\' not found in registry; using fallback resolver")\n        fallbackResolver(modelName)\n    }\n  }'),
    ('object ContextWindowResolver:', 'object ContextWindowResolver {'),
    ('      ContextWindowResolver(registry)\n    }', '      ContextWindowResolver(registry)\n    }\n}')
])
