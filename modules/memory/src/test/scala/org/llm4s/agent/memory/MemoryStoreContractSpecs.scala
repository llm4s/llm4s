package org.llm4s.agent.memory

import java.nio.file.Files

// `MemoryStoreContract` over this module's stores. The contract has a file of its own because `modules/it` compiles
// that one file into its own tests for `PostgresMemoryStore` (#3217).

/** The contract over [[InMemoryStore]], a keyword store. */
class InMemoryStoreContractSpec extends MemoryStoreContract {
  protected def storeName: String                                                     = "InMemoryStore"
  protected def semantic: Boolean                                                     = false
  protected def withStore[A](embeddings: EmbeddingService)(body: MemoryStore => A): A = body(InMemoryStore.empty)
}

/** The contract over [[EmbeddingMemoryStore]], an `InMemoryStore` searched by embedding. */
class EmbeddingMemoryStoreContractSpec extends MemoryStoreContract {
  protected def storeName: String = "EmbeddingMemoryStore"
  protected def semantic: Boolean = true
  protected def withStore[A](embeddings: EmbeddingService)(body: MemoryStore => A): A =
    body(EmbeddingMemoryStore.empty(embeddings))
}

/** The contract over [[SQLiteMemoryStore]], in memory. */
class SqliteMemoryStoreContractSpec extends MemoryStoreContract {
  protected def storeName: String = "SQLiteMemoryStore"
  protected def semantic: Boolean = false
  protected def withStore[A](embeddings: EmbeddingService)(body: MemoryStore => A): A = {
    val store = SQLiteMemoryStore.inMemory().fold(e => fail(e.message), identity)
    MemoryStoreContract.after(store.close())(body(store))
  }
}

/**
 * The contract over [[SQLiteMemoryStore]] on a file: a store that writes in place, where a transaction that is not one
 * would show. `memory` is a whole module of `project/PlatformTests.scala`, so it runs on Windows.
 */
class SqliteFileMemoryStoreContractSpec extends MemoryStoreContract {
  protected def storeName: String = "SQLiteMemoryStore (file)"
  protected def semantic: Boolean = false
  protected def withStore[A](embeddings: EmbeddingService)(body: MemoryStore => A): A = {
    val dir   = Files.createTempDirectory("llm4s-contract-sqlite")
    val file  = dir.resolve("memories.db")
    val store = SQLiteMemoryStore(file.toString).fold(e => fail(e.message), identity)
    MemoryStoreContract.after { store.close(); Files.deleteIfExists(file); Files.deleteIfExists(dir): Unit }(
      body(store)
    )
  }
}

/** The contract over [[VectorMemoryStore]], in memory. */
class VectorMemoryStoreContractSpec extends MemoryStoreContract {
  protected def storeName: String = "VectorMemoryStore"
  protected def semantic: Boolean = true
  protected def withStore[A](embeddings: EmbeddingService)(body: MemoryStore => A): A = {
    val store = VectorMemoryStore.inMemory(embeddings).fold(e => fail(e.message), identity)
    MemoryStoreContract.after(store.close())(body(store))
  }
}

/** The contract over [[VectorMemoryStore]] on a file; it runs on Windows, as every suite of `memory` does. */
class VectorFileMemoryStoreContractSpec extends MemoryStoreContract {
  protected def storeName: String = "VectorMemoryStore (file)"
  protected def semantic: Boolean = true
  protected def withStore[A](embeddings: EmbeddingService)(body: MemoryStore => A): A = {
    val dir   = Files.createTempDirectory("llm4s-contract-vector")
    val file  = dir.resolve("memories.db")
    val store = VectorMemoryStore(file.toString, embeddings).fold(e => fail(e.message), identity)
    MemoryStoreContract.after { store.close(); Files.deleteIfExists(file); Files.deleteIfExists(dir): Unit }(
      body(store)
    )
  }
}
