import os
import re

core = r'c:\Users\Acer\OneDrive\Desktop\llm4s\modules\core\src\main\scala\org\llm4s'

def append_unit_after_block(fpath, target_line, search_start):
    with open(fpath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    # We find the end of the block that starts around target_line
    # This might be tricky, so let's just append () after the closing brace of the block.
    pass # I'll do this carefully per file.

# VectorMemoryStore.scala
# 39: private def initializeSchema(): Unit = ...
# 416: def updateEmbedding ... { Using.resource ... }
fpath = os.path.join(core, 'agent', 'memory', 'VectorMemoryStore.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
# 1. initializeSchema
c = c.replace(
'''  private def initializeSchema(): Unit =
    Using.resource(connection.createStatement()) { stmt =>''',
'''  private def initializeSchema(): Unit = {
    Using.resource(connection.createStatement()) { stmt =>'''
)
c = c.replace(
'''        stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(content, id UNINDEXED)")
      }
    }''',
'''        stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(content, id UNINDEXED)")
      }
    }
    ()
  }'''
)
# 2. updateEmbedding
c = c.replace(
'''        stmt.executeUpdate()
      }
    }''',
'''        stmt.executeUpdate()
      }
      ()
    }'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# SQLiteVectorStore.scala
# 42: private def initializeSchema(): Unit = ...
fpath = os.path.join(core, 'vectorstore', 'SQLiteVectorStore.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace(
'''  private def initializeSchema(): Unit =
    Using.resource(connection.createStatement()) { stmt =>''',
'''  private def initializeSchema(): Unit = {
    Using.resource(connection.createStatement()) { stmt =>'''
)
c = c.replace(
'''        stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS vectors_fts USING fts5(content, id UNINDEXED)")
      }
    }''',
'''        stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS vectors_fts USING fts5(content, id UNINDEXED)")
      }
    }
    ()
  }'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)


# Policies.scala:160:40
fpath = os.path.join(core, 'agent', 'orchestration', 'Policies.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    lines = f.readlines()
# It was `timeoutPromise.trySuccess(` on line 160
# Let's replace the timeoutPromise.trySuccess(...) with { ...; () }
# Line 160:               timeoutPromise.trySuccess(
# Line 161:                 new CancellationException("Timeout exceeded")
# Line 162:               )
for i, line in enumerate(lines):
    if 'timeoutPromise.trySuccess(' in line and 'new CancellationException' in lines[i+1]:
        lines[i] = line.replace('timeoutPromise.trySuccess(', '{ timeoutPromise.trySuccess(')
        lines[i+2] = lines[i+2].replace(')', '); () }')
with open(fpath, 'w', encoding='utf-8') as f:
    f.writelines(lines)

# MCPTransport.scala:793
fpath = os.path.join(core, 'mcp', 'MCPTransport.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
# We need to add () after the recover block
c = c.replace(
'''    }.recover { case e =>
      logger.error(s"SSE request failed: ${e.getMessage}", e)
    }''',
'''    }.recover { case e =>
      logger.error(s"SSE request failed: ${e.getMessage}", e)
    }
    ()'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# PrometheusMetrics.scala
fpath = os.path.join(core, 'metrics', 'PrometheusMetrics.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
# There are multiple
c = c.replace(
'''    }.recover { case e: Exception =>
      logger.error("Failed to execute gauge block", e)
    }''',
'''    }.recover { case e: Exception =>
      logger.error("Failed to execute gauge block", e)
    }
    ()'''
)
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)


# InMemoryEmbeddingCache.scala
fpath = os.path.join(core, 'llmconnect', 'caching', 'InMemoryEmbeddingCache.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('store.put(key, CacheEntry(embedding, clock()))', '{ store.put(key, CacheEntry(embedding, clock())); () }')
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# AnthropicClient.scala
fpath = os.path.join(core, 'llmconnect', 'provider', 'AnthropicClient.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('builder.temperature(options.temperature.doubleValue())', '{ builder.temperature(options.temperature.doubleValue()); () }')
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

print('Fixed properly')
