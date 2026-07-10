import os

core = r'c:\Users\Acer\OneDrive\Desktop\llm4s\modules\workspace'

# 1. RunnerMain.scala
fpath = os.path.join(core, 'workspaceRunner', 'src', 'main', 'scala', 'org', 'llm4s', 'runner', 'RunnerMain.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()

# restore pbEnv
c = c.replace('val _ = builder.environment()', 'val pbEnv = builder.environment()')
c = c.replace('env.foreach { case (k, v) => pbEnv.put(k, v) }', 'env.foreach { case (k, v) => pbEnv.put(k, v) }; val _ = pbEnv')

# restore captured
c = c.replace('val captured  = 0L', 'var captured  = 0L')
c = c.replace('captured += toCopy', 'captured += toCopy; val _ = captured')

with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# 2. CodeWorker.scala
fpath = os.path.join(core, 'workspaceClient', 'src', 'main', 'scala', 'org', 'llm4s', 'codegen', 'CodeWorker.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('    shutdown()\n', '    val _ = shutdown()\n')
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# 3. ContainerisedWorkspace.scala
fpath = os.path.join(core, 'workspaceClient', 'src', 'main', 'scala', 'org', 'llm4s', 'workspace', 'ContainerisedWorkspace.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('        future.complete(response)\n', '        val _ = future.complete(response)\n')
c = c.replace('        if (isComplete) streamingHandlers.remove(commandId)\n', '        if (isComplete) { val _ = streamingHandlers.remove(commandId) }\n')
c = c.replace('    executor.scheduleAtFixedRate(', '    val _ = executor.scheduleAtFixedRate(')
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

print("Fixed workspace client and runner")
