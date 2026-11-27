package org.llm4s.knowledgegraph.storage

import java.nio.file.Files

/** [[GraphStoreContract]] against [[InMemoryGraphStore]]. */
class InMemoryGraphStoreContractSpec extends GraphStoreContract {
  override protected def storeName: String                         = "InMemoryGraphStore"
  override protected def withStore(body: GraphStore => Unit): Unit = body(new InMemoryGraphStore())
}

/** [[GraphStoreContract]] against [[JsonGraphStore]], each test on its own file. */
class JsonGraphStoreContractSpec extends GraphStoreContract {
  override protected def storeName: String = "JsonGraphStore"
  override protected def withStore(body: GraphStore => Unit): Unit = {
    val dir      = Files.createTempDirectory("llm4s-graph-contract-")
    val jsonPath = dir.resolve("test.json")
    try body(new JsonGraphStore(jsonPath))
    finally {
      Files.deleteIfExists(jsonPath)
      Files.deleteIfExists(dir)
    }
  }
}
