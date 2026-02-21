package org.llm4s.samples.rag

import org.llm4s.rag.loader.s3.{ S3DocumentSource, S3Loader }
import org.llm4s.rag.{ EmbeddingProvider, RAG, RAGSearchResult }
import org.llm4s.rag.RAG.RAGConfigOps
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Example demonstrating S3 document loading and ingestion.
 *
 * This example shows how to:
 * - Load documents from an S3 bucket with full PDF/DOCX support
 * - Use S3Loader for simple integration with RAG.sync()
 * - Configure S3 access with credentials and region
 * - Set up LocalStack for local testing
 *
 * Prerequisites:
 * - AWS credentials configured (env vars, ~/.aws/credentials, or IAM role)
 * - An S3 bucket with documents to ingest
 * - For local testing: LocalStack running (`docker run -d -p 4566:4566 localstack/localstack`)
 *
 * Environment variables:
 * - AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY (or use IAM roles)
 * - OPENAI_API_KEY (for RAG embeddings)
 */
object S3LoaderExample {
  private val logger = LoggerFactory.getLogger(getClass)
  private def env(key: String): Option[String] =
    sys.props.get(key).orElse(sys.env.get(key))
  def main(args: Array[String]): Unit = {
    logger.info("=" * 60)
    logger.info("S3 Document Loader Example")
    logger.info("=" * 60)

    // Configuration - adjust these for your environment
    val bucket        = env("S3_BUCKET").getOrElse("my-documents")
    val prefix        = env("S3_PREFIX").getOrElse("docs/")
    val region        = env("AWS_REGION").getOrElse("us-east-1")
    val useLocalStack = env("USE_LOCALSTACK").getOrElse("false").toBoolean

    logger.info("Configuration:")
    logger.info("  Bucket: {}", bucket)
    logger.info("  Prefix: {}", prefix)
    logger.info("  Region: {}", region)
    logger.info("  LocalStack: {}", useLocalStack)

    // Create RAG pipeline using the builder API
    val ragResult = RAG
      .builder()
      .withEmbeddings(EmbeddingProvider.OpenAI)
      .build()

    ragResult match {
      case Left(err) =>
        logger.error("Failed to create RAG pipeline: {}", err.message)
        logger.error("Error: {}", err.message)
        logger.error("Make sure you have set:")
        logger.error("  - OPENAI_API_KEY")
        ()

      case Right(rag) =>
        try runExample(rag, bucket, prefix, region, useLocalStack)
        finally rag.close()
    }
  }

  def runExample(
    rag: RAG,
    bucket: String,
    prefix: String,
    region: String,
    useLocalStack: Boolean
  ): Unit = {
    logger.info("RAG pipeline created successfully.")

    // Create S3 document loader
    val loader = (if (useLocalStack) {
                    logger.info("Using LocalStack for local testing...")
                    logger.info("Make sure LocalStack is running: docker run -d -p 4566:4566 localstack/localstack")
                    S3Loader.forLocalStack(bucket, prefix)
                  } else {
                    S3Loader(
                      bucket = bucket,
                      prefix = prefix,
                      region = region
                    )
                  })
    .tap(l => logger.info("Loader created: {}", l.description))

    // Sync documents from S3
    logger.info("--- Syncing documents from S3 ---")
    rag.sync(loader) match {
      case Right(stats) =>
        logger.info("Sync completed successfully!")
        logger.info("  Documents added:     {}", stats.added)
        logger.info("  Documents updated:   {}", stats.updated)
        logger.info("  Documents deleted:   {}", stats.deleted)
        logger.info("  Documents unchanged: {}", stats.unchanged)
        logger.info("  Total:               {}", stats.total)

        if (stats.hasChanges) {
          logger.info("Documents were indexed. You can now query them.")
        } else {
          logger.info("No changes detected since last sync.")
        }

      case Left(err) =>
        logger.error("Sync failed: {}", err.message)
        logger.error("Common issues:")
        logger.error("  - Check AWS credentials are configured")
        logger.error("  - Verify the bucket exists and is accessible")
        logger.error("  - Check network connectivity to S3")
    }

    // Example query (if RAG has documents)
    logger.info("--- Example Query ---")
    rag.query("What topics are covered in the documents?", Some(3)) match {
      case Right(results) if results.nonEmpty =>
        logger.info("Found {} relevant chunks:", results.size)
        results.zipWithIndex.foreach { case (result: RAGSearchResult, idx: Int) =>
          logger.info("{}. Score: {}", idx + 1, result.score)
          logger.info("   {}...", result.content.take(200))
        }
      case Right(_) =>
        logger.info("No results found. The index may be empty.")
      case Left(err) =>
        logger.error("Query failed: {}", err.message)
    }

    logger.info("=" * 60)
    logger.info("Example complete")
    logger.info("=" * 60)
  }
}

/**
 * Advanced example showing custom S3 configuration.
 */
object S3LoaderAdvancedExample {
  private val logger = LoggerFactory.getLogger(getClass)
  private def env(key: String): Option[String] =
    sys.props.get(key).orElse(sys.env.get(key))
  def main(args: Array[String]): Unit = {
    logger.info("=" * 60)
    logger.info("S3 Document Loader - Advanced Configuration")
    logger.info("=" * 60)

    // Example 1: Custom file extensions
    logger.info("--- Example 1: Custom Extensions ---")
    Option(
      S3Loader(
        bucket = "my-bucket",
        prefix = "reports/",
        extensions = Set("pdf")
      ).tap(l => logger.info("PDF-only loader: {}", l.description))
    ).foreach(_ => ())

    // Example 2: Explicit credentials
    logger.info("--- Example 2: Explicit Credentials ---")
    Option(
      S3Loader
        .withCredentials(
          bucket = "private-bucket",
          accessKeyId = env("AWS_ACCESS_KEY_ID").getOrElse("test"),
          secretAccessKey = env("AWS_SECRET_ACCESS_KEY").getOrElse("test"),
          region = "eu-west-1"
        )
        .tap(l => logger.info("Credentials loader: {}", l.description))
    ).foreach(_ => ())

    // Example 3: Using the source directly for advanced configuration
    logger.info("--- Example 3: Advanced Source Configuration ---")
    Option(
      S3DocumentSource(
        bucket = "my-bucket",
        prefix = "docs/",
        extensions = Set("txt", "md", "pdf", "docx"),
        metadata = Map("environment" -> "production")
      ).withEndpoint("http://localhost:4566")
        .tap(s => logger.info("Advanced source: {}", s.description))
    ).foreach(_ => ())

    // Example 4: Combining loaders
    logger.info("--- Example 4: Multi-Source Loading ---")
    val docsLoader    = S3Loader("company-docs", prefix = "public/")
    val reportsLoader = S3Loader("company-docs", prefix = "reports/2024/")
    Option(
      (docsLoader ++ reportsLoader)
        .tap(l => logger.info("Combined loader: {}", l.description))
    ).foreach(_ => ())

    logger.info("=" * 60)
    logger.info("Advanced examples complete")
    logger.info("=" * 60)
  }
}
