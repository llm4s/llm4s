import os
import re

core = r'c:\Users\Acer\OneDrive\Desktop\llm4s\modules\core\src\main\scala\org\llm4s'

with open(os.path.join(core, 'config', 'ProviderModelLister.scala'), 'r', encoding='utf-8') as f:
    content = f.read()

# fix object braces
content = re.sub(r'(object OpenAI extends ProviderModelLister \{\n.*?\n      )\n', r'\1  }\n\n', content, flags=re.DOTALL)
content = re.sub(r'(object OpenRouter extends ProviderModelLister \{\n.*?\n      )\n', r'\1  }\n\n', content, flags=re.DOTALL)
content = re.sub(r'(object Requesty extends ProviderModelLister \{\n.*?\n      )\n', r'\1  }\n\n', content, flags=re.DOTALL)
content = re.sub(r'(object Anthropic extends ProviderModelLister \{\n.*?\n      )\n', r'\1  }\n\n', content, flags=re.DOTALL)
content = re.sub(r'(object Gemini extends ProviderModelLister \{\n.*?\n      )\n', r'\1  }\n\n', content, flags=re.DOTALL)
content = re.sub(r'(object DeepSeek extends ProviderModelLister \{\n.*?\n      )\n', r'\1  }\n\n', content, flags=re.DOTALL)
content = re.sub(r'(object Mistral extends ProviderModelLister \{\n.*?\n      )\n', r'\1  }\n\n', content, flags=re.DOTALL)
content = re.sub(r'(object Ollama extends ProviderModelLister \{\n.*?)\n\n  private def', r'\1\n  }\n\n  private def', content, flags=re.DOTALL)
content = re.sub(r'(private\[llm4s\] object ProviderModelListers \{.*)\n\n$', r'\1\n}\n\n', content, flags=re.DOTALL)

# fix missing method braces
content = content.replace('private def parseAnthropicModels(json: ujson.Value): Result[List[DiscoveredModel]] =\n    val dataResult =', 'private def parseAnthropicModels(json: ujson.Value): Result[List[DiscoveredModel]] = {\n    val dataResult =')
content = content.replace('private def parseAnthropicModel(json: ujson.Value): Result[Option[DiscoveredModel]] =\n    val obj = json.obj\n    obj.get("type").flatMap(_.strOpt) match', 'private def parseAnthropicModel(json: ujson.Value): Result[Option[DiscoveredModel]] = {\n    val obj = json.obj\n    obj.get("type").flatMap(_.strOpt) match {')
content = content.replace('private def parseGeminiModels(json: ujson.Value): Result[List[DiscoveredModel]] =\n    val dataResult =', 'private def parseGeminiModels(json: ujson.Value): Result[List[DiscoveredModel]] = {\n    val dataResult =')
content = content.replace('private def parseGeminiModel(json: ujson.Value): Result[Option[DiscoveredModel]] =\n    val obj = json.obj\n    obj.get("name").flatMap(_.strOpt).filter(_.nonEmpty) match', 'private def parseGeminiModel(json: ujson.Value): Result[Option[DiscoveredModel]] = {\n    val obj = json.obj\n    obj.get("name").flatMap(_.strOpt).filter(_.nonEmpty) match {')
content = content.replace('private def parseMistralModels(json: ujson.Value): Result[List[DiscoveredModel]] =\n    val dataResult =', 'private def parseMistralModels(json: ujson.Value): Result[List[DiscoveredModel]] = {\n    val dataResult =')
content = content.replace('private def parseMistralModel(json: ujson.Value): Result[Option[DiscoveredModel]] =\n    val obj = json.obj\n    obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty) match', 'private def parseMistralModel(json: ujson.Value): Result[Option[DiscoveredModel]] = {\n    val obj = json.obj\n    obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty) match {')

# parseOptionalString
content = content.replace('private def parseOptionalString(json: ujson.Value, field: String): Result[Option[String]] =\n    Try(json(field).strOpt).toResult.left\n      .map', 'private def parseOptionalString(json: ujson.Value, field: String): Result[Option[String]] = {\n    Try(json(field).strOpt).toResult.left\n      .map')

content = content.replace('          )\n        )\n    }\n', '          )\n        )\n    }\n  }\n')
content = content.replace('          )\n    }\n', '          )\n    }\n  }\n')
# parseOptionalString end
content = content.replace('Err.message}"))', 'Err.message}"))\n  }')

with open(os.path.join(core, 'config', 'ProviderModelLister.scala'), 'w', encoding='utf-8') as f:
    f.write(content)

print('ProviderModelLister.scala fixed')

with open(os.path.join(core, 'config', 'Llm4sConfig.scala'), 'r', encoding='utf-8') as f:
    content = f.read()

# fix given ContextWindowResolver
content = content.replace('      service <- modelRegistryService()\n      given ContextWindowResolver = ContextWindowResolver(service)\n      config <- org.llm4s.config.NamedProviderLoader.load(ConfigSource.default, name)\n    } yield config', 
                          '      service <- modelRegistryService()\n      config <- {\n        implicit val resolver: ContextWindowResolver = ContextWindowResolver(service)\n        org.llm4s.config.NamedProviderLoader.load(ConfigSource.default, name)\n      }\n    } yield config')

content = content.replace('      service <- modelRegistryService()\n      given ContextWindowResolver = ContextWindowResolver(service)\n      result <- org.llm4s.config.NamedProviderLoader.loadProviderConfigs(ConfigSource.default)\n    } yield result', 
                          '      service <- modelRegistryService()\n      result <- {\n        implicit val resolver: ContextWindowResolver = ContextWindowResolver(service)\n        org.llm4s.config.NamedProviderLoader.loadProviderConfigs(ConfigSource.default)\n      }\n    } yield result')

content = content.replace('      service <- modelRegistryService(source)\n      given ContextWindowResolver = ContextWindowResolver(service)\n      config <- org.llm4s.config.NamedProviderLoader.load(source, name)\n    } yield config', 
                          '      service <- modelRegistryService(source)\n      config <- {\n        implicit val resolver: ContextWindowResolver = ContextWindowResolver(service)\n        org.llm4s.config.NamedProviderLoader.load(source, name)\n      }\n    } yield config')

content = content.replace('      service <- modelRegistryService()\n      given ContextWindowResolver = ContextWindowResolver(service)\n      name   <- defaultProviderName()\n      config <- org.llm4s.config.NamedProviderLoader.load(ConfigSource.default, name.asName)\n    } yield config', 
                          '      service <- modelRegistryService()\n      name   <- defaultProviderName()\n      config <- {\n        implicit val resolver: ContextWindowResolver = ContextWindowResolver(service)\n        org.llm4s.config.NamedProviderLoader.load(ConfigSource.default, name.asName)\n      }\n    } yield config')

content = content.replace('      service <- modelRegistryService(source)\n      given ContextWindowResolver = ContextWindowResolver(service)\n      name   <- defaultProviderName(source)\n      config <- org.llm4s.config.NamedProviderLoader.load(source, name.asName)\n    } yield config', 
                          '      service <- modelRegistryService(source)\n      name   <- defaultProviderName(source)\n      config <- {\n        implicit val resolver: ContextWindowResolver = ContextWindowResolver(service)\n        org.llm4s.config.NamedProviderLoader.load(source, name.asName)\n      }\n    } yield config')

content = content.replace('        given ContextWindowResolver = ContextWindowResolver(service)\n        org.llm4s.config.NamedProviderLoader.getProviderConfigs(map)', 
                          '        implicit val resolver: ContextWindowResolver = ContextWindowResolver(service)\n        org.llm4s.config.NamedProviderLoader.getProviderConfigs(map)')

with open(os.path.join(core, 'config', 'Llm4sConfig.scala'), 'w', encoding='utf-8') as f:
    f.write(content)

print('Llm4sConfig.scala fixed')
