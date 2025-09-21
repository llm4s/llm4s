package org.llm4s

import cats.data.ValidatedNec
import org.llm4s.config.ConfigReader
import org.llm4s.core.safety.{ DefaultErrorMapper, ErrorMapper, Safety }
import org.llm4s.types.TryOps
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.{ AsyncResult, Result }
import org.slf4j.Logger
import upickle.default.{ readwriter, ReadWriter => RW }

import java.time.Instant
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

/**
 * Comprehensive type aliases and newtypes for the LLM4S library.
 *
 * Key Features:
 * - Core result types and error handling
 * - ID newtypes for type safety
 * - Async and streaming type aliases
 * - Configuration and metadata types
 * - Tool and MCP related types
 * - Future extensibility for various domains
 * - Consistent and clear type definitions
 * - Type safety without performance cost (AnyVal newtypes)
 * - Consistent naming patterns across the library
 * - Clear distinction between sync/async operations
 * - Extensible for future requirements
 * - Backwards compatibility with legacy types
 */
package object types {

  /**
   * Core result types and error handling.
   */

  /** Standard synchronous result type used throughout the library */
  type Result[+A] = Either[error.LLMError, A]

  /** Async result type for Future-based operations */
  type AsyncResult[+A] = Future[Result[A]]

  /** Try-based result for exception handling boundaries */
  type TryResult[+A] = Try[Result[A]]

  /** Optional result for operations that may not return a value */
  type OptionalResult[+A] = Result[Option[A]]

  /** Multi-value result for operations returning collections */
  type MultiResult[+A] = Result[List[A]]

  /** Validated result with accumulating errors (non-empty chain) */
  type ValidatedResult[+A] = ValidatedNec[error.LLMError, A]

  /** Paginated result for large datasets */
  type PaginatedResult[+A] = Result[(List[A], PaginationInfo)]

  /** Streaming result for real-time data */
  type StreamResult[+A] = Result[Iterator[A]]

  /**
   * Identifiers and newtypes for type safety.
   */

  /**
   * Type-safe wrapper for LLM model names
   */
  final case class ModelName(value: String) extends AnyVal {
    override def toString: String = value
    def isEmpty: Boolean          = value.trim.isEmpty
    def nonEmpty: Boolean         = value.trim.nonEmpty
  }

  /**
   * Type-safe wrapper for provider names
   */
  final case class ProviderName(value: String) extends AnyVal {
    override def toString: String = value
    def normalized: String        = value.toLowerCase.trim
  }

  /**
   * Type-safe wrapper for API keys (prevents accidental logging)
   */
  final case class ApiKey(private val value: String) extends AnyVal {
    override def toString: String = "ApiKey(***)"
    def reveal: String            = value
    def masked: String            = s"${value.take(4)}${"*" * (value.length - 4)}"
  }

  /**
   * Type-safe wrapper for conversation IDs
   */
  final case class ConversationId(value: String) extends AnyVal {
    override def toString: String = value
  }

  object ConversationId {

    /**
     * Generates a new unique conversation ID using UUID
     */
    def generate(): ConversationId =
      ConversationId(java.util.UUID.randomUUID().toString)

    /**
     * Creates a ConversationId with validation
     *
     * @param value The ID string to validate
     * @return Either a validation error or valid ConversationId
     */
    def create(value: String): Result[ConversationId] =
      if (value.trim.nonEmpty) {
        Right(ConversationId(value.trim))
      } else {
        Left(ValidationError("Conversation ID cannot be empty", "conversationId"))
      }
  }

  /**
   * Type-safe wrapper for completion IDs
   */
  final case class CompletionId(value: String) extends AnyVal {
    override def toString: String = value
  }

  object CompletionId {
    def generate(): CompletionId =
      CompletionId(java.util.UUID.randomUUID().toString)

    def create(value: String): Result[CompletionId] =
      if (value.trim.nonEmpty) {
        Right(CompletionId(value.trim))
      } else {
        Left(ValidationError("Completion ID cannot be empty", "completionId"))
      }
  }

  /**
   * Type-safe wrapper for message IDs
   */
  final case class MessageId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /**
   * Type-safe wrapper for tool names
   */
  final case class ToolName(value: String) extends AnyVal {
    override def toString: String = value
    def isValid: Boolean          = value.matches("[a-zA-Z0-9_-]+")
  }

  /**
   * Type-safe wrapper for tool call IDs
   */
  final case class ToolCallId(value: String) extends AnyVal {
    override def toString: String = value
  }

  object ToolCallId {
    def generate(): ToolCallId = ToolCallId(java.util.UUID.randomUUID().toString)

    def create(value: String): Result[ToolCallId] =
      if (value.trim.nonEmpty) {
        Right(ToolCallId(value.trim))
      } else {
        Left(ValidationError("Tool call ID cannot be empty", "toolCallId"))
      }
  }

  /**
   * Type-safe wrapper for workspace IDs
   */
  final case class WorkspaceId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /**
   * Type-safe wrapper for session IDs
   */
  final case class SessionId(value: String) extends AnyVal {
    override def toString: String = value
  }

  object SessionId {
    implicit val rw: RW[SessionId] =
      readwriter[String].bimap[SessionId](_.value, SessionId.apply)
  }

  /**
   * Type-safe wrapper for request IDs for tracing
   */
  final case class RequestId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /**
   * Type-safe wrapper for trace IDs for distributed tracing
   */
  final case class TraceId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /**
   * Type-safe wrapper for organization IDs
   */
  final case class OrganizationId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /**
   * Type-safe wrapper for project IDs
   */
  final case class ProjectId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /**
   * Streaming and async types for real-time data handling.
   * These types are used for streaming completions, callbacks, and async operations.
   */

  /** Type alias for streaming completion chunks */
  type CompletionStream = Iterator[StreamedChunk]

  /** Type alias for async streaming completions */
  type AsyncCompletionStream = Future[Result[CompletionStream]]

  /** Type alias for streaming with callback */
  type StreamingCallback[A] = A => Unit

  /** Type alias for completion streaming with callback */
  type CompletionCallback = StreamingCallback[StreamedChunk]

  /** Type alias for error handling callback */
  type ErrorCallback = StreamingCallback[error.LLMError]

  /** Type alias for progress callback */
  type ProgressCallback = StreamingCallback[Double] // 0.0 to 1.0

  /** Type alias for cancellation token */
  type CancellationToken = () => Boolean

  /**
   * Configuration and metadata types.
   * These types are used for environment configuration, secrets management,
   */

  /** Type alias for environment variable map */
  type EnvironmentConfig = Map[String, String]

  /** Type alias for configuration validation result */
  type ConfigValidation = ValidatedResult[Unit]

  /** Type alias for configuration loading result */
  type ConfigResult[A] = Result[A]

  /** Type alias for secrets map */
  type SecretsMap = Map[String, ApiKey]

  /** Type alias for configuration section */
  type ConfigSection = Map[String, Any]

  /**
   * HTTP and networking types.
   * These types are used for HTTP requests, responses, and endpoints.
   */

  /** Type alias for HTTP headers */
  type HttpHeaders = Map[String, String]

  /** Type alias for HTTP status code */
  type HttpStatus = Int

  /** Type alias for URL string with validation */
  final case class Url(value: String) extends AnyVal {
    override def toString: String = value
    def isValid: Boolean          = Try(new java.net.URI(value)).isSuccess
  }

  /** Type alias for endpoint configuration */
  type EndpointConfig = (Url, HttpHeaders)

  /**
   * Tool system types.
   * These types are used for defining and executing tools, including validation and execution contexts.
   */

  /** Type alias for tool parameter validation */
  type ParameterValidation = Either[List[String], ujson.Value]

  /** Type alias for tool execution result */
  type ToolExecutionResult = Result[ujson.Value]

  /** Type alias for tool schema definition */
  type ToolSchema = ujson.Value

  /** Type alias for tool registry */
  type ToolRegistryMap = Map[ToolName, ToolFunction[_, _]]

  /** Type alias for tool permissions */
  type ToolPermissions = Set[String]

  /** Type alias for tool execution context */
  type ToolExecutionContext = Map[String, Any]

  /**
   * Model context protocol (MCP) types.
   * These types are used for defining and managing model context protocols, including server and client names
   */

  /** Type-safe wrapper for MCP server names */
  final case class MCPServerName(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type-safe wrapper for MCP client names */
  final case class MCPClientName(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type-safe wrapper for MCP protocol version */
  final case class MCPProtocolVersion(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type alias for MCP capabilities */
  type MCPCapabilities = Map[String, ujson.Value]

  /** Type alias for MCP server configuration */
  type MCPServerConfig = Map[String, Any]

  /**
   * Code generation types.
   * These types are used for generating code from templates, including task IDs and template names.
   */

  /** Type-safe wrapper for code task IDs */
  final case class CodeTaskId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type-safe wrapper for template names */
  final case class TemplateName(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type alias for code generation context */
  type CodeGenContext = Map[String, Any]

  /** Type alias for template variables */
  type TemplateVariables = Map[String, String]

  /** Type alias for generated code result */
  type GeneratedCodeResult = Result[String]

  /**
   * Metrics and observability types.
   * These types are used for collecting and reporting metrics, including metric names, values, and tags.
   * They also include timing measurements for performance tracking.
   */

  /** Type alias for metric name */
  final case class MetricName(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type alias for metric value */
  type MetricValue = Double

  /** Type alias for metric tags */
  type MetricTags = Map[String, String]

  /** Type alias for metrics collection */
  type MetricsMap = Map[MetricName, (MetricValue, MetricTags)]

  /** Type alias for timing measurement */
  type TimingResult[A] = (A, Long) // (result, durationMillis)

  /**
   * Authentication and security types.
   * These types are used for authentication credentials, permissions, and roles.
   * They include type-safe wrappers for API keys, JWT tokens, and OAuth tokens.
   * They also define type aliases for authentication credentials and permission sets.
   */

  /** Type-safe wrapper for JWT tokens */
  final case class JwtToken(private val value: String) extends AnyVal {
    override def toString: String = "JwtToken(***)"
    def reveal: String            = value
  }

  /** Type-safe wrapper for OAuth tokens */
  final case class OAuthToken(private val value: String) extends AnyVal {
    override def toString: String = "OAuthToken(***)"
    def reveal: String            = value
  }

  /** Type alias for authentication credentials */
  type AuthCredentials = Either[ApiKey, JwtToken]

  /** Type alias for permission set */
  type Permissions = Set[String]

  /** Type alias for role definition */
  type Role = (String, Permissions)

  /**
   * File system and workspace types.
   * These types are used for file paths, directory paths, file content, and metadata.
   * They include type-safe wrappers for file and directory paths,
   * as well as type aliases for file content, text content, and file metadata.
   */

  /** Type-safe wrapper for file paths */
  final case class FilePath(value: String) extends AnyVal {
    override def toString: String = value
    def extension: Option[String] = {
      val lastDot = value.lastIndexOf('.')
      if (lastDot >= 0) Some(value.substring(lastDot + 1)) else None
    }
  }

  object FilePath {
    implicit val rw: RW[FilePath] =
      readwriter[String].bimap[FilePath](_.value, FilePath.apply)
  }

  /** Type-safe wrapper for directory paths */
  final case class DirectoryPath(value: String) extends AnyVal {
    override def toString: String = value
  }

  object DirectoryPath {
    implicit val rw: RW[DirectoryPath] =
      readwriter[String].bimap[DirectoryPath](_.value, DirectoryPath.apply)
  }

  /** Type alias for file content */
  type FileContent = Array[Byte]

  /** Type alias for text file content */
  type TextContent = String

  /** Type alias for file metadata */
  type FileMetadata = Map[String, String]

  /**
   * Pagination and search types.
   * These types are used for paginated results, search queries, and search filters.
   * They include type-safe wrappers for search queries and pagination information.
   * They also define type aliases for search results and search filters.
   */

  /** Pagination information for large result sets */
  final case class PaginationInfo(
    page: Int,
    pageSize: Int,
    totalItems: Long,
    totalPages: Int,
    hasNext: Boolean,
    hasPrevious: Boolean
  )

  /** Type alias for search query */
  final case class SearchQuery(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type alias for search filters */
  type SearchFilters = Map[String, List[String]]

  /** Type alias for search result */
  type SearchResult[A] = PaginatedResult[A]

  /**
   * Caching and metadata types.
   * These types are used for caching results, cache keys, and cache configurations.
   * They include type-safe wrappers for cache keys and type aliases for cached values and cache configurations.
   * They also define type aliases for cache TTL (time to live) and cached value metadata.
   * They are designed to be extensible for future caching requirements.
   */

  /** Type-safe wrapper for cache keys */
  final case class CacheKey(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type alias for cache TTL (time to live) in seconds */
  type CacheTTL = Long

  /** Type alias for cached value with metadata */
  type CachedValue[A] = (A, Instant, CacheTTL)

  /** Type alias for cache configuration */
  type CacheConfig = Map[String, Any]

  /**
   * Future extensibility types.
   * These types are placeholders for future extensibility.
   */

  // Image Generation Types (for future image generation support)
  final case class ImagePrompt(value: String) extends AnyVal {
    override def toString: String = value
  }

  final case class ImageId(value: String) extends AnyVal {
    override def toString: String = value
  }

  type ImageGenerationResult = Result[Array[Byte]]

  // Audio Processing Types (for future audio support)
  final case class AudioId(value: String) extends AnyVal {
    override def toString: String = value
  }

  type AudioContent             = Array[Byte]
  type AudioTranscriptionResult = Result[String]

  // Video Processing Types (for future video support)
  final case class VideoId(value: String) extends AnyVal {
    override def toString: String = value
  }

  type VideoContent        = Array[Byte]
  type VideoAnalysisResult = Result[String]

  // Embeddings Types (for future RAG/vector search support)
  final case class EmbeddingId(value: String) extends AnyVal {
    override def toString: String = value
  }

  type EmbeddingVector = Array[Float]
  type EmbeddingResult = Result[EmbeddingVector]

  // Fine-tuning Types (for future model training support)
  final case class FineTuningJobId(value: String) extends AnyVal {
    override def toString: String = value
  }

  type TrainingDataset  = List[String]
  type FineTuningResult = Result[ModelName]

  // Agents & Multi-Agent Types (for future agent support)
  final case class AgentId(value: String) extends AnyVal {
    override def toString: String = value
  }

  object AgentId {
    def generate(): AgentId = AgentId(java.util.UUID.randomUUID().toString)
  }

  final case class TeamId(value: String) extends AnyVal {
    override def toString: String = value
  }

  final case class PlanId(value: String) extends AnyVal {
    override def toString: String = value
  }

  object PlanId {
    def generate(): PlanId = PlanId(java.util.UUID.randomUUID().toString)
  }

  type AgentCapabilities   = Set[String]
  type AgentMemory         = Map[String, Any]
  type MultiAgentResult[A] = Result[Map[AgentId, A]]

  // Plugin System Types (for future plugin support)
  final case class PluginId(value: String) extends AnyVal {
    override def toString: String = value
  }

  final case class PluginVersion(value: String) extends AnyVal {
    override def toString: String = value
  }

  type PluginConfig   = Map[String, Any]
  type PluginRegistry = Map[PluginId, PluginConfig]

  // Workflow Types (for future workflow orchestration)
  final case class WorkflowId(value: String) extends AnyVal {
    override def toString: String = value
  }

  final case class StepId(value: String) extends AnyVal {
    override def toString: String = value
  }

  type WorkflowStep      = Map[String, Any]
  type WorkflowResult[A] = Result[Map[StepId, A]]

  // Context Management Types (for conversation context handling)
  final case class SemanticBlockId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type alias for externalization threshold in bytes */
  type ExternalizationThreshold = Long

  /** Type-safe wrapper for artifact store keys */
  final case class ArtifactKey(value: String) extends AnyVal {
    override def toString: String = value
  }

  object ArtifactKey {
    def generate(): ArtifactKey =
      ArtifactKey(java.util.UUID.randomUUID().toString)

    def fromContent(content: String): ArtifactKey = {
      val hash = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(content.getBytes("UTF-8"))
        .map("%02x".format(_))
        .mkString
      ArtifactKey(s"content_$hash")
    }
  }

  /** Information about externalized content */
  case class ExternalizedContent(
    key: ArtifactKey,
    originalSize: ByteCount,
    contentType: String,
    summary: String
  )

  object SemanticBlockId {
    def generate(): SemanticBlockId =
      SemanticBlockId(java.util.UUID.randomUUID().toString.take(8))
  }

  final case class ContextSummary(value: String) extends AnyVal {
    override def toString: String = value
    def tokenLength: Int          = (value.split("\\s+").length * 1.3).toInt // Rough approximation
  }

  /** Type alias for semantic block priority (higher = more important to preserve) */
  type BlockPriority = Int

  /** Type alias for compression ratio (0.0 to 1.0, lower = more compression) */
  type CompressionRatio = Double

  /** Type alias for context window size in semantic blocks */
  type ContextWindowSize = Int

  /** Type-safe wrapper for content size in bytes with validation */
  final case class ContentSize(bytes: Long) extends AnyVal {
    override def toString: String                                      = s"${bytes}B"
    def toKB: Double                                                   = bytes / 1024.0
    def toMB: Double                                                   = bytes / (1024.0 * 1024.0)
    def exceedsThreshold(threshold: ExternalizationThreshold): Boolean = bytes > threshold
  }

  object ContentSize {
    def fromString(content: String): ContentSize =
      ContentSize(content.getBytes("UTF-8").length.toLong)

    def fromBytes(bytes: Array[Byte]): ContentSize =
      ContentSize(bytes.length.toLong)
  }

  /** Type-safe wrapper for token estimates with accuracy indicators */
  final case class TokenEstimate(tokens: Int) extends AnyVal {
    def value: Int = tokens
  }

  object TokenEstimate {
    def withAccuracy(tokens: Int, accuracy: EstimationAccuracy): (TokenEstimate, EstimationAccuracy) =
      (TokenEstimate(tokens), accuracy)
  }

  sealed trait EstimationAccuracy
  object EstimationAccuracy {
    case object High   extends EstimationAccuracy // Exact tokenizer
    case object Medium extends EstimationAccuracy // Approximate but tested
    case object Low    extends EstimationAccuracy // Rough heuristic
  }

  /** Type-safe wrapper for compression target ratios with validation */
  final case class CompressionTarget(ratio: Double) extends AnyVal {
    def value: Double    = ratio
    def isValid: Boolean = ratio > 0.0 && ratio <= 1.0
  }

  object CompressionTarget {
    def create(ratio: Double): Result[CompressionTarget] =
      ratio match {
        case r if r > 0.0 && r <= 1.0 => Right(CompressionTarget(r))
        case r =>
          Left(error.ValidationError(s"Invalid compression ratio: $r. Must be between 0.0 and 1.0", "compressionRatio"))
      }

    val Minimal: CompressionTarget = CompressionTarget(0.95)
    val Light: CompressionTarget   = CompressionTarget(0.80)
    val Medium: CompressionTarget  = CompressionTarget(0.60)
    val Heavy: CompressionTarget   = CompressionTarget(0.40)
    val Maximum: CompressionTarget = CompressionTarget(0.20)
  }

  /**
   * Validation and smart constructors for type-safe IDs and names.
   * These objects provide methods to create and validate type-safe IDs and names,
   * ensuring they meet specific criteria.
   * They also provide common constants for frequently used models and providers.
   * These methods return `Result` types for error handling,
   * allowing users to create type-safe IDs and names
   * with confidence that they are valid.
   * This approach ensures type safety without runtime overhead,
   * and provides a clear and consistent API for creating and validating IDs and names.
   */

  object ModelName {

    private val validModelPattern = """^[a-zA-Z0-9\-_./:]+$""".r

    def apply(value: String): Result[ModelName] =
      if (validModelPattern.matches(value.trim)) Right(new ModelName(value.trim))
      else Left(error.ValidationError("modelName", "Invalid model name format"))

    def fromString(value: String): ModelName = new ModelName(value)

    def unsafe(value: String): ModelName = new ModelName(value)

    // Common model name constants
    val GPT_4: ModelName           = new ModelName("gpt-4")
    val GPT_4_TURBO: ModelName     = new ModelName("gpt-4-turbo")
    val GPT_3_5_TURBO: ModelName   = new ModelName("gpt-3.5-turbo")
    val CLAUDE_3_OPUS: ModelName   = new ModelName("claude-3-opus-20240229")
    val CLAUDE_3_SONNET: ModelName = new ModelName("claude-3-sonnet-20240229")
    val CLAUDE_3_HAIKU: ModelName  = new ModelName("claude-3-haiku-20240307")
  }

  object ProviderName {
    def create(value: String): Result[ProviderName] =
      if (value.trim.nonEmpty) Right(ProviderName(value.trim.toLowerCase))
      else Left(error.ValidationError("Provider name cannot be empty", "providerName"))

    // Common provider constants
    val OPENAI: ProviderName    = ProviderName("openai")
    val ANTHROPIC: ProviderName = ProviderName("anthropic")
    val AZURE: ProviderName     = ProviderName("azure")
    val GOOGLE: ProviderName    = ProviderName("google")
    val COHERE: ProviderName    = ProviderName("cohere")
  }

  object ApiKey {
    def apply(value: String): Result[ApiKey] =
      if (value.trim.nonEmpty && value.length >= 8) Right(new ApiKey(value))
      else Left(error.ValidationError("apiKey", "Must be at least 8 characters"))

    def fromEnvironment(envVar: String)(config: ConfigReader): Result[ApiKey] =
      config
        .get(envVar)
        .toRight(ConfigurationError(s"Environment variable '$envVar' not found", List(envVar)))
        .flatMap(apply)

    def unsafe(value: String): ApiKey = new ApiKey(value)
  }

  object ToolName {
    def create(value: String): Result[ToolName] = {
      val trimmed = value.trim
      if (trimmed.nonEmpty && trimmed.matches("[a-zA-Z0-9_-]+"))
        Right(ToolName(trimmed))
      else
        Left(
          error.ValidationError(
            "Tool name must be non-empty and contain only alphanumeric characters, underscores, and hyphens",
            "toolName"
          )
        )
    }
  }

  object Url {
    def create(value: String): Result[Url] =
      Try(new java.net.URI(value)) // Validate URL format
        .toEither.left
        .map(_ => error.ValidationError(s"Invalid URL: $value", "url"))
        .map(_ => Url(value))
  }

  /**
   * Utility types for common data structures.
   * These types provide type-safe wrappers for common data structures.
   */

  /** Type alias for JSON values */
  type Json = ujson.Value

  /** Type alias for JSON objects */
  type JsonObject = ujson.Obj

  /** Type alias for JSON arrays */
  type JsonArray = ujson.Arr

  /** Type alias for timestamp (Unix epoch milliseconds) */
  type Timestamp = Long

  /** Type alias for duration in milliseconds */
  type Duration = Long

  /** Type alias for timeout in milliseconds */
  type Timeout = Long

  /** Type alias for byte count */
  type ByteCount = Long

  /** Type alias for token count */
  type TokenCount = Int

  /** Type alias for token budget */
  type TokenBudget = Int

  /** Type alias for effective token budget (after applying headroom) */
  type EffectiveBudget = Int

  /** Type-safe wrapper for headroom percentage with validation */
  final case class HeadroomPercent(value: Double) extends AnyVal {
    override def toString: String = f"${value * 100}%.1f%%"
    def asRatio: Double           = value
    def isValid: Boolean          = value >= 0.0 && value < 1.0
  }

  object HeadroomPercent {
    def create(value: Double): Result[HeadroomPercent] =
      value match {
        case v if v >= 0.0 && v < 1.0 => Right(HeadroomPercent(v))
        case v => Left(error.ValidationError(s"Invalid headroom: $v. Must be between 0.0 and 1.0", "headroom"))
      }

    val None: HeadroomPercent         = HeadroomPercent(0.0)
    val Light: HeadroomPercent        = HeadroomPercent(0.05) // 5%
    val Standard: HeadroomPercent     = HeadroomPercent(0.08) // 8%
    val Conservative: HeadroomPercent = HeadroomPercent(0.15) // 15%
  }

  /** Type alias for system message token count */
  type SystemTokenCount = Int

  /** Type alias for temperature parameter (0.0 to 2.0) */
  type Temperature = Double

  /** Type alias for probability (0.0 to 1.0) */
  type Probability = Double

  /** Type alias for percentage (0.0 to 100.0) */
  type Percentage = Double

  /** Syntax: Try/Option/Future to Result conversions */
  implicit final class TryOps[A](val t: Try[A]) extends AnyVal {
    def toResult(implicit em: ErrorMapper = DefaultErrorMapper): Result[A] =
      t.fold(e => Left(em(e)), v => Right(v))
  }

  implicit final class OptionOps[A](val oa: Option[A]) extends AnyVal {
    def toResult(ifEmpty: => error.LLMError): Result[A] = oa.toRight(ifEmpty)
  }

  implicit final class FutureOps[A](val fa: Future[A]) extends AnyVal {
    def toResult(implicit ec: ExecutionContext, em: ErrorMapper = DefaultErrorMapper): Future[Result[A]] =
      Safety.future.fromFuture(fa)
  }

}

/**
 * Companion object for Result types.
 * Provides utility methods for creating and manipulating Result types.
 * Includes methods for success, failure, fromTry, fromOption, sequence, traverse, and combine.
 * These methods allow users to easily create and handle Result types,
 * providing a consistent and type-safe way to work with results in the LLM4S.
 * Provides a clear and concise API for error handling and result manipulation.
 * Provides methods for creating success and failure results,
 * converting from Try and Option types, sequencing and traversing lists of results,
 * and combining two results into a tuple.
 * This object is designed to be extensible for future requirements,
 * allowing users to add additional utility methods as needed.
 * It provides a consistent and type-safe way to handle results and errors in the LLM4S.
 */

object Result {
  val logger: Logger                                         = org.slf4j.LoggerFactory.getLogger(getClass)
  def success[A](value: A): Result[A]                        = Right(value)
  def failure[A](error: org.llm4s.error.LLMError): Result[A] = Left(error)

  /**
   * Converts a scala.util.Try[A] into a Result[A], while allowing a custom finallyBlock action.
   *
   * @param t       The Try[A] to wrap.
   * @param finallyBlock A user-provided finallyBlock function to run after processing.
   * @return A Result[A] representing success or failure.
   */
  @deprecated("Use Safety.fromTry or TryOps.toResult with ErrorMapper", since = "0.1.10")
  def fromTry[A](
    t: Try[A],
    finallyBlock: () => Unit = () => logger.info("Running default cleanup in fromTry")
  ): Result[A] = {
    val res: Result[A] = t match {
      case scala.util.Success(value)     => success(value)
      case scala.util.Failure(throwable) => failure(org.llm4s.error.ThrowableOps.RichThrowable(throwable).toLLMError)
    }
    Try(finallyBlock()) // do not throw; best-effort
    res
  }

  def fromOption[A](opt: Option[A], error: => org.llm4s.error.LLMError): Result[A] =
    opt.toRight(error)

  def sequence[A](results: List[Result[A]]): Result[List[A]] =
    results.foldRight(success(List.empty[A])) { (result, acc) =>
      for {
        value <- result
        list  <- acc
      } yield value :: list
    }

  def traverse[A, B](list: List[A])(f: A => Result[B]): Result[List[B]] =
    sequence(list.map(f))

  // Combinators for multiple Results
  def combine[A, B](ra: Result[A], rb: Result[B]): Result[(A, B)] =
    for {
      a <- ra
      b <- rb
    } yield (a, b)

  def combine[A, B, C](ra: Result[A], rb: Result[B], rc: Result[C]): Result[(A, B, C)] =
    for {
      a <- ra
      b <- rb
      c <- rc
    } yield (a, b, c)

  def safely[A](operation: => A): Result[A] = Try(operation).toResult

  // Async support
  def fromFuture[A](future: Future[A])(implicit ec: ExecutionContext): Future[Result[A]] =
    future.map(success).recover { case ex => failure(org.llm4s.error.ThrowableOps.RichThrowable(ex).toLLMError) }

  /**
   * Create Result from boolean condition
   */
  def fromBoolean(condition: Boolean, error: org.llm4s.error.LLMError): Result[Unit] =
    if (condition) success(()) else failure(error)

  /**
   * Create Result from boolean with custom success value
   */
  def fromBooleanWithValue[A](condition: Boolean, successValue: A, error: org.llm4s.error.LLMError): Result[A] =
    if (condition) success(successValue) else failure(error)

  // Validation that collects all errors
  def validateAll[A](items: List[A])(validator: A => Result[A]): Either[List[error.LLMError], List[A]] = {
    val results   = items.map(validator)
    val errors    = results.collect { case Left(error) => error }
    val successes = results.collect { case Right(value) => value }
    if (errors.nonEmpty) Left(errors) else Right(successes)
  }

  // Resource management: use scala.util.Using + types.TryOps#toResult
}

object AsyncResult {
  import scala.concurrent.ExecutionContext

  def success[A](value: A): AsyncResult[A]                        = Future.successful(Right(value))
  def failure[A](error: org.llm4s.error.LLMError): AsyncResult[A] = Future.successful(Left(error))

  def fromFuture[A](future: Future[A])(implicit ec: ExecutionContext): AsyncResult[A] =
    future.map(Right(_)).recover { case ex => Left(org.llm4s.error.ThrowableOps.RichThrowable(ex).toLLMError) }

  def fromResult[A](result: Result[A]): AsyncResult[A] =
    Future.successful(result)
}
