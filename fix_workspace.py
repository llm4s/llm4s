import os
import re

core = r'c:\Users\Acer\OneDrive\Desktop\llm4s\modules'

# 1. ConfigCatalog.scala
fpath = os.path.join(core, 'config-policy', 'src', 'main', 'scala', 'org', 'llm4s', 'configpolicy', 'ConfigCatalog.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
# Replace:
# enum CatalogEnvironment:
#   case Dev, Staging, Prod
c = c.replace(
'''enum CatalogEnvironment:
  case Dev, Staging, Prod''',
'''sealed trait CatalogEnvironment
object CatalogEnvironment {
  case object Dev extends CatalogEnvironment
  case object Staging extends CatalogEnvironment
  case object Prod extends CatalogEnvironment
}'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)


# 2. Neo4jGraphStore.scala
fpath = os.path.join(core, 'knowledgegraph-neo4j', 'src', 'main', 'scala', 'org', 'llm4s', 'knowledgegraph', 'neo4j', 'Neo4jGraphStore.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace(
'''          params.put("filterPropValue", value)''',
'''          { params.put("filterPropValue", value); () }'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)


# 3. KeywordIndexBenchmark.scala
fpath = os.path.join(core, 'benchmarks', 'src', 'main', 'scala', 'org', 'llm4s', 'benchmarks', 'KeywordIndexBenchmark.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace(
'''    index.indexBatch(BenchmarkFixtures.makeDocuments(1000))''',
'''    { index.indexBatch(BenchmarkFixtures.makeDocuments(1000)); () }'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)


# 4. CodeGenExample.scala
fpath = os.path.join(core, 'workspace', 'workspaceClient', 'src', 'main', 'scala', 'org', 'llm4s', 'codegen', 'CodeGenExample.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace(
'''      given org.llm4s.model.ModelRegistryService = registryService''',
'''      implicit val _registryService: org.llm4s.model.ModelRegistryService = registryService'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)


# 5. RunnerMain.scala
fpath = os.path.join(core, 'workspace', 'workspaceRunner', 'src', 'main', 'scala', 'org', 'llm4s', 'runner', 'RunnerMain.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace(
'''          connections.remove(channel)''',
'''          { connections.remove(channel); () }'''
)
c = c.replace(
'''    }(ec)''',
'''    }(ec)
    ()'''
)
c = c.replace(
'''                  }(ec)''',
'''                  }(ec)
                  ()'''
)
c = c.replace(
'''                      done.trySuccess(())''',
'''                      { done.trySuccess(()); () }'''
)
c = c.replace(
'''                      exitDone.trySuccess(())''',
'''                      { exitDone.trySuccess(()); () }'''
)
c = c.replace(
'''    heartbeatExecutor.scheduleAtFixedRate(''',
'''    val _ = heartbeatExecutor.scheduleAtFixedRate('''
)
c = c.replace(
'''    }.recover { case _: InterruptedException => executor.shutdownNow() }''',
'''    }.recover { case _: InterruptedException => { executor.shutdownNow(); () } }
    ()'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

print('Fixed workspace errors')
