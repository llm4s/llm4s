import os

core = r'c:\Users\Acer\OneDrive\Desktop\llm4s\modules\core\src\main\scala\org\llm4s'

# SQLiteMemoryStore.scala
fpath = os.path.join(core, 'agent', 'memory', 'SQLiteMemoryStore.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('stmt.executeUpdate()\n    }\n  }', 'stmt.executeUpdate()\n      ()\n    }\n  }')
c = c.replace('stmt.executeUpdate()\n    }\n    // Insert new entry', 'stmt.executeUpdate()\n      ()\n    }\n    // Insert new entry')
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# VectorMemoryStore.scala
fpath = os.path.join(core, 'agent', 'memory', 'VectorMemoryStore.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('Using.resource(connection.createStatement()) { stmt =>\n      stmt.execute(createTableSql)\n    }', 'Using.resource(connection.createStatement()) { stmt =>\n      stmt.execute(createTableSql)\n      ()\n    }')
c = c.replace('Using.resource(connection.prepareStatement("INSERT INTO memories_fts (id, content) VALUES (?, ?)")) { stmt =>\n      stmt.setString(1, memory.id.value)\n      stmt.setString(2, memory.content)\n      stmt.executeUpdate()\n    }', 'Using.resource(connection.prepareStatement("INSERT INTO memories_fts (id, content) VALUES (?, ?)")) { stmt =>\n      stmt.setString(1, memory.id.value)\n      stmt.setString(2, memory.content)\n      stmt.executeUpdate()\n      ()\n    }')
c = c.replace('Using.resource(connection.prepareStatement(updateSql)) { stmt =>\n        stmt.setString(1, memory.content)\n        stmt.setString(2, memory.id.value)\n        stmt.executeUpdate()\n      }', 'Using.resource(connection.prepareStatement(updateSql)) { stmt =>\n        stmt.setString(1, memory.content)\n        stmt.setString(2, memory.id.value)\n        stmt.executeUpdate()\n        ()\n      }')
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# Policies.scala
fpath = os.path.join(core, 'agent', 'orchestration', 'Policies.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('timeoutPromise.trySuccess(\n                  new CancellationException("Timeout exceeded")\n                )', '{ timeoutPromise.trySuccess(\n                  new CancellationException("Timeout exceeded")\n                ); () }')
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

# MCPTransport.scala
fpath = os.path.join(core, 'mcp', 'MCPTransport.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('}.recover { case e =>\n      logger.error(s"SSE request failed: ${e.getMessage}", e)\n    }', '}.recover { case e =>\n      logger.error(s"SSE request failed: ${e.getMessage}", e)\n    }\n    ()')
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# PrometheusMetrics.scala
fpath = os.path.join(core, 'metrics', 'PrometheusMetrics.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('logger.error("Failed to execute gauge block", e)\n    }', 'logger.error("Failed to execute gauge block", e)\n    }\n    ()')
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

# SQLiteVectorStore.scala
fpath = os.path.join(core, 'vectorstore', 'SQLiteVectorStore.scala')
with open(fpath, 'r', encoding='utf-8') as f:
    c = f.read()
c = c.replace('stmt.execute(createTableSql)\n    }', 'stmt.execute(createTableSql)\n      ()\n    }')
c = c.replace('stmt.executeUpdate()\n    }', 'stmt.executeUpdate()\n      ()\n    }')
with open(fpath, 'w', encoding='utf-8') as f:
    f.write(c)

print('Fixed again')
