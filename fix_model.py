import os
import re

core = r'c:\Users\Acer\OneDrive\Desktop\llm4s\modules\core\src\main\scala\org\llm4s'

with open(os.path.join(core, 'config', 'ProvidersConfigModel.scala'), 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('      baseUrl.toRight(ConfigurationError("Configured provider is missing required field `baseUrl`"))\n\n    /**', 
                          '      baseUrl.toRight(ConfigurationError("Configured provider is missing required field `baseUrl`"))\n    }\n\n    /**')
content = content.replace('      baseUrl.getOrElse(BaseUrl(default))\n\n    /**', 
                          '      baseUrl.getOrElse(BaseUrl(default))\n    }\n\n    /**')
content = content.replace('      apiKey.toRight(ConfigurationError("Configured provider is missing required field `apiKey`"))\n\n  /**', 
                          '      apiKey.toRight(ConfigurationError("Configured provider is missing required field `apiKey`"))\n    }\n  }\n\n  /**')

if '}\n}\n' not in content[-10:]:
    content = content + '\n}\n'

with open(os.path.join(core, 'config', 'ProvidersConfigModel.scala'), 'w', encoding='utf-8') as f:
    f.write(content)

print('ProvidersConfigModel.scala fixed')
