package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.error.NotFoundError

/**
 * Factory for creating VectorStore instances.
 *
 * Supports creating stores from configuration or explicit parameters.
 * Backend selection is based on the provider name.
 *
 * Currently supported backends:
 * - "sqlite" - SQLite-based storage (default)
 * - "pgvector" - PostgreSQL with pgvector extension
 * - "qdrant" - Qdrant vector database
 *
 * Future backends (roadmap):
 * - "milvus" - Milvus vector database
 * - "pinecone" - Pinecone cloud service
 */
object VectorStoreFactory {

  /**
   * Supported vector store backends.
   */
  sealed trait Backend {
    def name: String
  }

  object Backend {
    case object SQLite   extends Backend { val name = "sqlite"   }
    case object PgVector extends Backend { val name = "pgvector" }
    case object Qdrant   extends Backend { val name = "qdrant"   }
    // Future backends:
    // case object Milvus extends Backend { val name = "milvus" }
    // case object Pinecone extends Backend { val name = "pinecone" }

    def fromString(s: String): Option[Backend] = s.toLowerCase match {
      case "sqlite"   => Some(SQLite)
      case "pgvector" => Some(PgVector)
      case "qdrant"   => Some(Qdrant)
      case _          => None
    }

    val all: Seq[Backend] = Seq(SQLite, PgVector, Qdrant)
  }

  /**
   * Configuration for creating a vector store.
   *
   * @param backend The storage backend to use
   * @param path Path to database file (for file-based backends)
   * @param connectionString Connection string (for remote backends)
   * @param options Additional backend-specific options
   */
  final case class Config(
    backend: Backend = Backend.SQLite,
    path: Option[String] = None,
    connectionString: Option[String] = None,
    options: Map[String, String] = Map.empty
  ) {

    /**
     * Create with SQLite backend.
     */
    def withSQLite(path: String): Config =
      copy(backend = Backend.SQLite, path = Some(path))

    /**
     * Create with pgvector backend.
     */
    def withPgVector(connectionString: String): Config =
      copy(backend = Backend.PgVector, connectionString = Some(connectionString))

    /**
     * Create with Qdrant backend.
     */
    def withQdrant(url: String): Config =
      copy(backend = Backend.Qdrant, connectionString = Some(url))

    /**
     * Create in-memory store (SQLite).
     */
    def inMemory: Config =
      copy(backend = Backend.SQLite, path = None)

    /**
     * Add a configuration option.
     */
    def withOption(key: String, value: String): Config =
      copy(options = options + (key -> value))
  }

  object Config {

    /**
     * Default configuration (in-memory SQLite).
     */
    val default: Config = Config()

    /**
     * Configuration for file-based SQLite.
     */
    def sqlite(path: String): Config = Config(Backend.SQLite, Some(path))

    /**
     * Configuration for in-memory SQLite.
     */
    val inMemory: Config = Config(Backend.SQLite, None)

    /**
     * Configuration for pgvector.
     *
     * @param connectionString JDBC connection string (jdbc:postgresql://...)
     * @param tableName Optional table name (default: "vectors")
     */
    def pgvector(
      connectionString: String,
      tableName: String = "vectors",
      user: Option[String] = None,
      password: Option[String] = None
    ): Config = {
      val baseOptions = Map("tableName" -> tableName)

      // ++ appends to the map.
      // .map transforms the Option into a key-value pair IF it exists.
      val finalOptions = baseOptions ++
        user.map("user" -> _) ++
        password.map("password" -> _)

      Config(Backend.PgVector, Some(connectionString), options = finalOptions)
    }

    /**
     * Configuration for local Qdrant.
     *
     * @param collectionName Collection name (default: "vectors")
     * @param port Qdrant port (default: 6333)
     */
    def qdrant(collectionName: String = "vectors", port: Int = 6333): Config =
      Config(
        Backend.Qdrant,
        connectionString = Some(s"http://localhost:$port"),
        options = Map("collectionName" -> collectionName)
      )

    /**
     * Configuration for Qdrant Cloud.
     *
     * @param cloudUrl Qdrant cloud URL (e.g., https://xxx.qdrant.io)
     * @param apiKey Qdrant API key
     * @param collectionName Collection name (default: "vectors")
     */
    def qdrantCloud(cloudUrl: String, apiKey: String, collectionName: String = "vectors"): Config =
      Config(
        Backend.Qdrant,
        connectionString = Some(cloudUrl),
        options = Map("collectionName" -> collectionName, "apiKey" -> apiKey)
      )
  }

  /**
   * Create a vector store from configuration.
   *
   * @param config The store configuration
   * @return The vector store or error
   */
  def create(config: Config): Result[VectorStore] = config.backend match {
    case Backend.SQLite =>
      config.path match {
        case Some(path) => SQLiteVectorStore(path)
        case None       => SQLiteVectorStore.inMemory()
      }

    case Backend.PgVector =>
      val tableName = config.options.getOrElse("tableName", "vectors")
      config.connectionString match {
        case Some(connStr) =>
          val user     = config.options.getOrElse("user", "postgres")
          val password = config.options.getOrElse("password", "")
          PgVectorStore(connStr, user, password, tableName)
        case None =>
          // Use default local connection
          PgVectorStore.local(tableName)
      }

    case Backend.Qdrant =>
      val collectionName = config.options.getOrElse("collectionName", "vectors")
      val apiKey         = config.options.get("apiKey")
      config.connectionString match {
        case Some(url) =>
          apiKey match {
            case Some(key) => QdrantVectorStore.cloud(url, key, collectionName)
            case None      => QdrantVectorStore(url, collectionName)
          }
        case None =>
          QdrantVectorStore.local(collectionName)
      }

    // Future backends would be handled here:
    // case Backend.Milvus => MilvusVectorStore(config.connectionString.getOrElse(...))
  }

  /**
   * Create a vector store from a provider name.
   *
   * @param provider The provider name (e.g., "sqlite", "pgvector")
   * @param path Optional path for file-based backends
   * @param connectionString Optional connection string for remote backends
   * @return The vector store or error
   */
  def create(
    provider: String,
    path: Option[String] = None,
    connectionString: Option[String] = None
  ): Result[VectorStore] =
    Backend.fromString(provider) match {
      case Some(backend) =>
        create(Config(backend, path, connectionString))
      case None =>
        Left(
          NotFoundError(
            s"Unknown vector store provider: $provider. Supported: ${Backend.all.map(_.name).mkString(", ")}",
            provider
          )
        )
    }

  /**
   * Create an in-memory vector store.
   *
   * Useful for testing or temporary storage.
   *
   * @return The vector store or error
   */
  def inMemory(): Result[VectorStore] =
    SQLiteVectorStore.inMemory()

  /**
   * Create a file-based SQLite vector store.
   *
   * @param path Path to the database file
   * @return The vector store or error
   */
  def sqlite(path: String): Result[VectorStore] =
    SQLiteVectorStore(path)

  /**
   * Create a pgvector store with default local settings.
   *
   * Connects to localhost:5432/postgres with user postgres.
   *
   * @param tableName Table name for vectors (default: "vectors")
   * @return The vector store or error
   */
  def pgvector(tableName: String = "vectors"): Result[VectorStore] =
    PgVectorStore.local(tableName)

  /**
   * Create a pgvector store with explicit connection settings.
   *
   * @param connectionString JDBC connection string (jdbc:postgresql://...)
   * @param user Database user
   * @param password Database password
   * @param tableName Table name for vectors
   * @return The vector store or error
   */
  def pgvector(
    connectionString: String,
    user: String,
    password: String,
    tableName: String
  ): Result[VectorStore] =
    PgVectorStore(connectionString, user, password, tableName)

  /**
   * Create a Qdrant store with default local settings.
   *
   * Connects to localhost:6333.
   *
   * @param collectionName Collection name (default: "vectors")
   * @return The vector store or error
   */
  def qdrant(collectionName: String = "vectors"): Result[VectorStore] =
    QdrantVectorStore.local(collectionName)

  /**
   * Create a Qdrant store with explicit URL.
   *
   * @param url Qdrant server URL (e.g., http://localhost:6333)
   * @param collectionName Collection name
   * @return The vector store or error
   */
  def qdrant(url: String, collectionName: String): Result[VectorStore] =
    QdrantVectorStore(url, collectionName)

  /**
   * Create a Qdrant Cloud store.
   *
   * @param cloudUrl Qdrant cloud URL (e.g., https://xxx.qdrant.io)
   * @param apiKey Qdrant API key
   * @param collectionName Collection name (default: "vectors")
   * @return The vector store or error
   */
  def qdrantCloud(
    cloudUrl: String,
    apiKey: String,
    collectionName: String = "vectors"
  ): Result[VectorStore] =
    QdrantVectorStore.cloud(cloudUrl, apiKey, collectionName)
}
