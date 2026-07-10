import os
import re

core = r'c:\Users\Acer\OneDrive\Desktop\llm4s'

# 1. Dependencies.scala
fpath = os.path.join(core, 'project', 'Dependencies.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace(
'''  val termflow                = "org.llm4s" % "termflow_3" % Versions.termflow''',
'''  val termflow                = ("org.llm4s" % "termflow_3" % Versions.termflow).exclude("com.github.pureconfig", "pureconfig-core_3")'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# 2. CodeGenExample.scala
fpath = os.path.join(core, 'modules', 'workspace', 'workspaceClient', 'src', 'main', 'scala', 'org', 'llm4s', 'codegen', 'CodeGenExample.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace(
'''      implicit val _registryService: org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)''',
'''      client <- {
        implicit val _registryService: org.llm4s.model.ModelRegistryService = registryService
        LLMConnect.getClient(providerCfg)
      }'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# 3. ConfigCatalog.scala
fpath = os.path.join(core, 'modules', 'config-policy', 'src', 'main', 'scala', 'org', 'llm4s', 'configpolicy', 'ConfigCatalog.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace(
'''sealed trait CatalogEnvironment
object CatalogEnvironment {
  case object Dev extends CatalogEnvironment
  case object Staging extends CatalogEnvironment
  case object Prod extends CatalogEnvironment
}

/**
 * Scala 3 `enum` syntax is intentional: if this module ever needs Scala 2.13
 * cross-compilation, replace with a sealed trait + case objects.
 */
object CatalogEnvironment {
  def fromString(value: String): CatalogEnvironment =''',
'''sealed trait CatalogEnvironment
object CatalogEnvironment {
  case object Dev extends CatalogEnvironment
  case object Staging extends CatalogEnvironment
  case object Prod extends CatalogEnvironment

  def fromString(value: String): CatalogEnvironment ='''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# 4. RunnerMain.scala
fpath = os.path.join(core, 'modules', 'workspace', 'workspaceRunner', 'src', 'main', 'scala', 'org', 'llm4s', 'runner', 'RunnerMain.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()

# Replace block { ... ; () } with val _ = ...
c = c.replace('{ connections.remove(channel); () }', 'val _ = connections.remove(channel)')
c = c.replace('{ done.trySuccess(()); () }', 'val _ = done.trySuccess(())')
c = c.replace('{ exitDone.trySuccess(()); () }', 'val _ = exitDone.trySuccess(())')

# Fix unused privates
c = c.replace('private val effectiveSandboxConfig', 'private[runner] val effectiveSandboxConfig')
c = c.replace('private val HeartbeatCheckIntervalSeconds', 'private[runner] val HeartbeatCheckIntervalSeconds')
c = c.replace('private def handleWebSocketMessage', 'private[runner] def handleWebSocketMessage')
c = c.replace('private def sendCommandFailure', 'private[runner] def sendCommandFailure')
c = c.replace('private def resolveWorkingDirectory', 'private[runner] def resolveWorkingDirectory')

# Fix unused local val
c = c.replace('val pbEnv = builder.environment()', 'val _ = builder.environment()')

# Fix var to val
c = c.replace('var captured  = 0L', 'val captured  = 0L')

with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

print("Applied fixes")
