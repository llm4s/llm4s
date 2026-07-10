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

# 1. ProviderModelLister.scala
rep(os.path.join(core, 'config', 'ProviderModelLister.scala'), [
    # add missing braces to objects
    (re.compile(r'(object OpenAI extends ProviderModelLister \{\n.*?\n      )\n', re.DOTALL), r'\1  }\n\n'),
    (re.compile(r'(object OpenRouter extends ProviderModelLister \{\n.*?\n      )\n', re.DOTALL), r'\1  }\n\n'),
    (re.compile(r'(object Requesty extends ProviderModelLister \{\n.*?\n      )\n', re.DOTALL), r'\1  }\n\n'),
    (re.compile(r'(object Anthropic extends ProviderModelLister \{\n.*?\n      )\n', re.DOTALL), r'\1  }\n\n'),
    (re.compile(r'(object Gemini extends ProviderModelLister \{\n.*?\n      )\n', re.DOTALL), r'\1  }\n\n'),
    (re.compile(r'(object DeepSeek extends ProviderModelLister \{\n.*?\n      )\n', re.DOTALL), r'\1  }\n\n'),
    (re.compile(r'(object Mistral extends ProviderModelLister \{\n.*?\n      )\n', re.DOTALL), r'\1  }\n\n'),
    (re.compile(r'(object Ollama extends ProviderModelLister \{\n.*?)\n\n  private def', re.DOTALL), r'\1\n  }\n\n  private def'),
    
    # fix method body braces
    ('private def parseAnthropicModels(json: ujson.Value): Result[List[DiscoveredModel]] =\n    val dataResult =', 'private def parseAnthropicModels(json: ujson.Value): Result[List[DiscoveredModel]] = {\n    val dataResult ='),
    ('private def parseAnthropicModel(json: ujson.Value): Result[Option[DiscoveredModel]] =\n    val obj = json.obj\n    obj.get("type").flatMap(_.strOpt) match', 'private def parseAnthropicModel(json: ujson.Value): Result[Option[DiscoveredModel]] = {\n    val obj = json.obj\n    obj.get("type").flatMap(_.strOpt) match {'),
    ('private def parseGeminiModels(json: ujson.Value): Result[List[DiscoveredModel]] =\n    val dataResult =', 'private def parseGeminiModels(json: ujson.Value): Result[List[DiscoveredModel]] = {\n    val dataResult ='),
    ('private def parseGeminiModel(json: ujson.Value): Result[Option[DiscoveredModel]] =\n    val obj = json.obj\n    obj.get("name").flatMap(_.strOpt).filter(_.nonEmpty) match', 'private def parseGeminiModel(json: ujson.Value): Result[Option[DiscoveredModel]] = {\n    val obj = json.obj\n    obj.get("name").flatMap(_.strOpt).filter(_.nonEmpty) match {'),
    ('private def parseMistralModels(json: ujson.Value): Result[List[DiscoveredModel]] =\n    val dataResult =', 'private def parseMistralModels(json: ujson.Value): Result[List[DiscoveredModel]] = {\n    val dataResult ='),
    ('private def parseMistralModel(json: ujson.Value): Result[Option[DiscoveredModel]] =\n    val obj = json.obj\n    obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty) match', 'private def parseMistralModel(json: ujson.Value): Result[Option[DiscoveredModel]] = {\n    val obj = json.obj\n    obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty) match {'),
    ('private def parseOptionalString(json: ujson.Value, field: String): Result[Option[String]] =\n    Try(json(field).strOpt).toResult.left\n      .map(err => ValidationError(field, s"Invalid format for $field: ${err.message}"))', 
     'private def parseOptionalString(json: ujson.Value, field: String): Result[Option[String]] = {\n    Try(json(field).strOpt).toResult.left\n      .map(err => ValidationError(field, s"Invalid format for $field: ${err.message}"))\n  }'),
    # add missing closing braces for match blocks and methods
    (re.compile(r'          )\n        )\n    }\n'), r'          )\n        )\n    }\n  }\n'),
    (re.compile(r'          )\n    }\n'), r'          )\n    }\n  }\n'),
])

# 2. ProvidersConfigLoader.scala
rep(os.path.join(core, 'config', 'ProvidersConfigLoader.scala'), [
    ('for\n', 'for {\n'),
    ('    yield ProvidersConfig(\n', '    } yield ProvidersConfig(\n'),
    ('        providers = namedProviders\n      )\n    }\n  }', '        providers = namedProviders\n      )\n  }') # fix the previous replace
])

# 3. NamedProviderLoader.scala
rep(os.path.join(core, 'config', 'NamedProviderLoader.scala'), [
    ('    for\n', '    for {\n'),
    ('    yield ', '    } yield ')
])

# 4. ProvidersConfigModel.scala
rep(os.path.join(core, 'config', 'ProvidersConfigModel.scala'), [
    ('export org.llm4s.types.ProviderModelTypes.*', ''),
    ('if provider == expected then', 'if (provider == expected)'),
    ('if provider == ProviderKind.OpenRouter then', 'if (provider == ProviderKind.OpenRouter)'),
    ('if provider == ProviderKind.Requesty then', 'if (provider == ProviderKind.Requesty)'),
    ('if provider == ProviderKind.VertexAI then', 'if (provider == ProviderKind.VertexAI)'),
    ('if provider == ProviderKind.Azure then', 'if (provider == ProviderKind.Azure)')
])
