import os
import glob
import re

samples_dir = r'c:\Users\Acer\OneDrive\Desktop\llm4s\modules\samples\src\main\scala\org\llm4s\samples\basic'
files = glob.glob(samples_dir + '/*.scala')

def process_file(fpath):
    with open(fpath, 'r', encoding='utf-8') as f:
        content = f.read()

    # 1. replace object Foo: -> object Foo {
    content = re.sub(r'object\s+(\w+):', r'object \1 {', content)
    
    # 2. replace @main def Foo(): Unit = ... -> object Foo { def main(args: Array[String]): Unit = ... }
    # This is tricky, let's just do it manually for ProviderKeyValidationMain
    if "ProviderKeyValidationMain" in content:
        content = content.replace('@main\ndef ProviderKeyValidationMain(): Unit =', 'object ProviderKeyValidationMain {\n  def main(args: Array[String]): Unit = {')
        # we'll add } at the end
    
    # 3. replace given ExecutionContext = ... -> implicit val _ec: ExecutionContext = ...
    content = content.replace('private given ExecutionContext =', 'private implicit val _ec: ExecutionContext =')
    content = content.replace('given ExecutionContext =', 'implicit val _ec: ExecutionContext =')
    content = content.replace('given org.llm4s.model.ModelRegistryService =', 'implicit val _registryService: org.llm4s.model.ModelRegistryService =')

    # 4. Future: -> Future {
    content = re.sub(r'Future:\s*$', 'Future {', content, flags=re.MULTILINE)
    
    # 5. for ... yield -> we can leave it if it's already using braces, but if it's indentation based:
    # Actually, let's just convert all of them. I'll print the ones that have `for` without `{`
    
    with open(fpath, 'w', encoding='utf-8') as f:
        f.write(content)

for fpath in files:
    process_file(fpath)

print("Pass 1 complete")
