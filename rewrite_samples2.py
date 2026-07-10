import os
import glob
import re

samples_dir = r'c:\Users\Acer\OneDrive\Desktop\llm4s\modules\samples\src\main\scala\org\llm4s\samples\basic'
files = glob.glob(samples_dir + '/*.scala')

def add_braces(fpath):
    with open(fpath, 'r', encoding='utf-8') as f:
        content = f.read()

    # We replaced `object Foo:` with `object Foo {`
    # Now we must append `}` at the end of the file if it contains `object Foo {`
    
    # We replaced `@main def ...` with `object ... { def main ... {`
    # We must append `} }` at the end
    
    lines = content.split('\n')
    
    new_lines = []
    
    for i, line in enumerate(lines):
        # for \n foo <- bar \n yield
        if line.strip() == 'for':
            line = line.replace('for', 'for {')
        if line.strip() == 'yield':
            # this means the previous line was the last generator. We need to close } before yield
            # Actually, `yield` usually comes after the generator.
            # A simple fix: replace `yield` with `} yield`
            line = line.replace('yield', '} yield')
        
        # Future { ... }
        if line.strip() == 'Future {':
            pass # we need to close it. This requires indentation tracking!
            
        new_lines.append(line)
        
    content = '\n'.join(new_lines)
    
    if 'object ' in content and '{\n' in content:
        # Just count { and } and add missing ones at the EOF
        open_braces = content.count('{')
        close_braces = content.count('}')
        if open_braces > close_braces:
            content += '\n' + '}' * (open_braces - close_braces) + '\n'

    with open(fpath, 'w', encoding='utf-8') as f:
        f.write(content)

for fpath in files:
    add_braces(fpath)

print("Pass 2 complete")
