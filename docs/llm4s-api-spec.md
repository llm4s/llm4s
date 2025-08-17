# LLM4S API Specification

## Overview

LLM4S provides a type-safe, functional, and idiomatic Scala interface for interacting with Large Language Models (LLMs). This specification defines a platform-agnostic API that abstracts away provider-specific details while maintaining the full capabilities of modern LLMs, including tool calling.

## Core Design Principles

- **Immutability**: All data structures are immutable
- **Type safety**: Leverage Scala's type system for compile-time safety
- **Functional approach**: Use monadic error handling with Either/Option
- **Platform agnosticism**: Abstract away provider-specific details
- **Idiomatic Scala**: Use case classes, pattern matching, and functional approaches
- **Compatibility**: Maintain compatibility with existing tool calling API where possible

## Core API Components

### 1. Message Types

```scala
sealed trait Message {
  def role: String
  def content: String
}

case class UserMessage(content: String) extends Message {
  val role = "user"
}

case class SystemMessage(content: String) extends Message {
  val role = "system"
}

case class AssistantMessage(
  content: String, 
  toolCalls: Seq[ToolCall] = Seq.empty
) extends Message {
  val role = "assistant"
}

case class ToolMessage(
  toolCallId: String,
  content: String
) extends Message {
  val role = "tool"
}
```

### 2. Conversation Model

```scala
case class Conversation(messages: Seq[Message]) {
  // Add a message and return a new Conversation
  def addMessage(message: Message): Conversation = 
    Conversation(messages :+ message)
    
  // Add multiple messages and return a new Conversation
  def addMessages(newMessages: Seq[Message]): Conversation = 
    Conversation(messages ++ newMessages)
}
```

### 3. Tool Calls

```scala
case class ToolCall(
  id: String,
  name: String,
  arguments: ujson.Value
)
```

### 4. Completion Results

```scala
case class Completion(
  id: String,
  created: Long,
  message: AssistantMessage,
  usage: Option[TokenUsage] = None
)

case class TokenUsage(
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int
)

case class StreamedChunk(
  id: String,
  content: Option[String],
  toolCall: Option[ToolCall] = None,
  finishReason: Option[String] = None
)
```

### 5. Completion Options

```scala
case class CompletionOptions(
  temperature: Double = 0.7,
  topP: Double = 1.0,
  maxTokens: Option[Int] = None,
  presencePenalty: Double = 0.0,
  frequencyPenalty: Double = 0.0,
  tools: Seq[ToolFunction[_, _]] = Seq.empty
)
```

### 6. Error Types

```scala
// Enhanced error hierarchy in org.llm4s.error
sealed trait LLMError {
  def message: String
  def formatted: String
}

object LLMError {
  case class AuthenticationError(
    message: String,
    provider: String,
    details: Map[String, String] = Map.empty
  ) extends LLMError
  
  case class RateLimitError(
    message: String,
    retryAfter: Option[Duration],
    provider: String,
    limit: Option[Int] = None,
    remaining: Option[Int] = None
  ) extends LLMError
  
  case class ServiceError(
    message: String,
    statusCode: Int,
    provider: String,
    requestId: Option[String] = None
  ) extends LLMError
  
  case class ValidationError(
    message: String,
    field: String,
    constraints: Map[String, String] = Map.empty
  ) extends LLMError
  
  case class NetworkError(
    message: String,
    cause: Option[Throwable],
    retryable: Boolean = true
  ) extends LLMError
  
  case class ConfigurationError(
    message: String,
    missingFields: List[String]
  ) extends LLMError
  
  case class UnknownError(
    message: String,
    cause: Option[Throwable] = None
  ) extends LLMError
  
  // Factory method for exceptions
  def fromThrowable(throwable: Throwable): LLMError = {
    UnknownError(throwable.getMessage, Some(throwable))
  }
}

// Result type alias for cleaner APIs
type Result[+A] = Either[LLMError, A]
```

## LLM Client Interface

```scala
import org.llm4s.types.Result

trait LLMClient {
  /** Complete a conversation and get a response */
  def complete(
    conversation: Conversation, 
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion]
  
  /** Stream a completion with callback for chunks */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion]
  
  /** Validate client configuration */
  def validate(): Result[Unit] = Result.success(())
  
  /** Close client and cleanup resources */
  def close(): Unit = ()
}
```

## Provider Configuration

```scala
sealed trait ProviderConfig {
  def model: String
}

case class OpenAIConfig(
  apiKey: String,
  model: String = "gpt-4o",
  organization: Option[String] = None,
  baseUrl: String = "https://api.openai.com/v1"
) extends ProviderConfig

case class AzureConfig(
  endpoint: String,
  apiKey: String,
  model: String,
  apiVersion: String = "2023-12-01-preview"
) extends ProviderConfig

case class AnthropicConfig(
  apiKey: String,
  model: String = "claude-3-opus-20240229",
  baseUrl: String = "https://api.anthropic.com"
) extends ProviderConfig
```

## Main LLM Factory

```scala
object LLM {
  /** Factory method for getting a client with the right configuration */
  def client(
    provider: LLMProvider,
    config: ProviderConfig
  ): LLMClient = provider match {
    case LLMProvider.OpenAI => new OpenAIClient(config.asInstanceOf[OpenAIConfig])
    case LLMProvider.Azure => new OpenAIClient(config.asInstanceOf[AzureConfig])  // OpenAIClient handles both
    case LLMProvider.Anthropic => new AnthropicClient(config.asInstanceOf[AnthropicConfig])
    // Other providers...
  }
  
  /** Convenience method for quick completion */
  def complete(
    messages: Seq[Message],
    provider: LLMProvider,
    config: ProviderConfig,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] = {
    val conversation = Conversation(messages)
    client(provider, config).complete(conversation, options)
  }
}

sealed trait LLMProvider
object LLMProvider {
  case object OpenAI extends LLMProvider
  case object Azure extends LLMProvider
  case object Anthropic extends LLMProvider
  // Add more as needed
}
```

## Integration with Existing Tool API

The existing tool calling API can be used with minimal changes. Provider-specific translation logic should be encapsulated within the provider implementations.

```scala
// Adapter to convert ToolFunction to provider-specific format
trait ToolAdapter[T] {
  def convertTools(tools: Seq[ToolFunction[_, _]]): T
}

// Example adapter for Azure OpenAI
class AzureToolAdapter extends ToolAdapter[ChatCompletionsOptions] {
  def convertTools(tools: Seq[ToolFunction[_, _]]): ChatCompletionsOptions = {
    val chatOptions = new ChatCompletionsOptions()
    AzureToolHelper.addToolsToOptions(new ToolRegistry(tools), chatOptions)
    chatOptions
  }
}
```

## Tool API (Compatible with Existing Implementation)

```scala
// Integrating with existing ToolFunction and ToolRegistry
trait LLMToolIntegration {
  /** Execute a tool call using the existing tool registry */
  def executeToolCall(
    toolCall: ToolCall, 
    toolRegistry: ToolRegistry
  ): Either[String, ujson.Value] = {
    val request = ToolCallRequest(toolCall.name, toolCall.arguments)
    toolRegistry.execute(request)
  }
  
  /** Process tool calls and get tool messages */
  def processToolCalls(
    toolCalls: Seq[ToolCall],
    toolRegistry: ToolRegistry
  ): Seq[ToolMessage] = {
    toolCalls.map { toolCall =>
      val result = executeToolCall(toolCall, toolRegistry)
      
      val content = result match {
        case Right(json) => json.render()
        case Left(error) => s"""{"error":"$error"}"""
      }
      
      ToolMessage(toolCall.id, content)
    }
  }
}
```

## Example Provider Implementation

### OpenAI Client Implementation

```scala
class OpenAIClient(config: OpenAIConfig) extends LLMClient {
  private val httpClient = HttpClient.newHttpClient()
  
  override def complete(
    conversation: Conversation, 
    options: CompletionOptions
  ): Result[Completion] = {
    try {
      // Convert to OpenAI format
      val requestBody = createRequestBody(conversation, options)
      
      // Make API call
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"${config.baseUrl}/chat/completions"))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer ${config.apiKey}")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
        .build()
      
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      
      // Handle response status
      response.statusCode() match {
        case 200 => 
          // Parse successful response
          val responseJson = ujson.read(response.body())
          Right(parseCompletion(responseJson))
          
        case 401 => Left(LLMError.AuthenticationError("Invalid API key", "openai"))
        case 429 => Left(LLMError.RateLimitError("Rate limit exceeded", None, "openai"))
        case status => Left(LLMError.ServiceError(s"OpenAI API error: ${response.body()}", status, "openai"))
      }
    } catch {
      case e: Exception => Left(LLMError.fromThrowable(e))
    }
  }
  
  // Implementation-specific helpers
  private def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    // Convert messages to OpenAI format...
    // Add tools if present...
    // Return formatted request body
  }
  
  private def parseCompletion(json: ujson.Value): Completion = {
    // Extract relevant fields and convert to our model
  }
  
  // ... Streaming implementation
}
```

### Unified OpenAI Client Implementation (handles both OpenAI and Azure)

```scala
class OpenAIClient private (private val model: String, private val client: AzureOpenAIClient) extends LLMClient {
  
  // Constructor for OpenAI
  def this(config: OpenAIConfig) = this(
    config.model,
    new OpenAIClientBuilder()
      .credential(new KeyCredential(config.apiKey))
      .endpoint(config.baseUrl)
      .buildClient()
  )
  
  // Constructor for Azure
  def this(config: AzureConfig) = this(
    config.model,
    new OpenAIClientBuilder()
      .credential(new AzureKeyCredential(config.apiKey))
      .endpoint(config.endpoint)
      .serviceVersion(OpenAIServiceVersion.valueOf(config.apiVersion))
      .buildClient()
  )
  
  override def complete(
    conversation: Conversation, 
    options: CompletionOptions
  ): Either[LLMError, Completion] = {
    try {
      // Convert conversation to Azure format
      val chatMessages = convertToAzureMessages(conversation)
      
      // Create chat options
      val chatOptions = new ChatCompletionsOptions(chatMessages)
      
      // Set options
      chatOptions.setTemperature(options.temperature)
      options.maxTokens.foreach(mt => chatOptions.setMaxTokens(mt))
      
      // Add tools if specified
      if (options.tools.nonEmpty) {
        val toolRegistry = new ToolRegistry(options.tools)
        AzureToolHelper.addToolsToOptions(toolRegistry, chatOptions)
      }
      
      // Make API call (model passed from constructor)
      val completions = client.getChatCompletions(model, chatOptions)
      
      // Convert response to our model
      Right(convertFromAzureCompletion(completions))
    } catch {
      case e: Exception => Left(UnknownError(e))
    }
  }
  
  // Convert our Conversation to Azure's message format
  private def convertToAzureMessages(conversation: Conversation): java.util.ArrayList[ChatRequestMessage] = {
    val messages = new java.util.ArrayList[ChatRequestMessage]()
    
    conversation.messages.foreach {
      case UserMessage(content) => 
        messages.add(new ChatRequestUserMessage(content))
      case SystemMessage(content) => 
        messages.add(new ChatRequestSystemMessage(content))
      case AssistantMessage(content, toolCalls) =>
        val msg = new ChatRequestAssistantMessage(content)
        // Add tool calls if needed
        messages.add(msg)
      case ToolMessage(toolCallId, content) =>
        messages.add(new ChatRequestToolMessage(content, toolCallId))
    }
    
    messages
  }
  
  // Convert Azure completion to our model
  private def convertFromAzureCompletion(completions: ChatCompletions): Completion = {
    val choice = completions.getChoices.get(0)
    val message = choice.getMessage
    
    // Extract tool calls if present
    val toolCalls = extractToolCalls(message)
    
    Completion(
      id = completions.getId,
      created = completions.getCreatedAt.toEpochSecond,
      message = AssistantMessage(
        content = message.getContent,
        toolCalls = toolCalls
      ),
      usage = Some(TokenUsage(
        promptTokens = completions.getUsage.getPromptTokens,
        completionTokens = completions.getUsage.getCompletionTokens,
        totalTokens = completions.getUsage.getTotalTokens
      ))
    )
  }
  
  private def extractToolCalls(message: ChatResponseMessage): Seq[ToolCall] = {
    import scala.jdk.CollectionConverters._
    
    Option(message.getToolCalls)
      .map(_.asScala.toSeq.collect {
        case ftc: ChatCompletionsFunctionToolCall =>
          ToolCall(
            id = ftc.getId,
            name = ftc.getFunction.getName,
            arguments = ujson.read(ftc.getFunction.getArguments)
          )
      })
      .getOrElse(Seq.empty)
  }
  
  // ... Streaming implementation
}
```

## Usage Examples

### Basic Conversation

```scala
// Configure a client
val client = LLM.client(
  provider = LLMProvider.OpenAI,
  config = OpenAIConfig(
    apiKey = "sk-..."
  )
)

// Create a conversation
val conversation = Conversation(Seq(
  SystemMessage("You are a helpful assistant. You will talk like a pirate."),
  UserMessage("Please write a scala function to add two integers")
))

// Get a completion
val result = client.complete(conversation)

result match {
  case Right(completion) => 
    println(s"Assistant: ${completion.message.content}")
    
  case Left(error) => 
    println(s"Error: $error")
}
```

### Tool Calling Example

```scala
// Create a conversation asking about weather
val weatherConversation = Conversation(Seq(
  UserMessage("What's the weather like in Paris?")
))

// Use the existing weather tool
val toolRegistry = new ToolRegistry(Seq(WeatherTool.tool))

// Get completion with tools
val weatherResult = client.complete(
  weatherConversation,
  CompletionOptions(tools = Seq(WeatherTool.tool))
)

// Handle the completion with potential tool calls
weatherResult match {
  case Right(completion) if completion.message.toolCalls.nonEmpty =>
    // Process tool calls with the existing tool registry
    val toolResponses = completion.message.toolCalls.map { toolCall =>
      val request = ToolCallRequest(toolCall.name, toolCall.arguments)
      val result = toolRegistry.execute(request)
      
      ToolMessage(
        toolCallId = toolCall.id,
        content = result.fold(
          error => s"""{"error":"$error"}""",
          success => success.render()
        )
      )
    }
    
    // Add the assistant message and tool responses to conversation
    val updatedConversation = weatherConversation
      .addMessage(completion.message)
      .addMessages(toolResponses)
      
    // Get final response with tool results
    val finalResult = client.complete(updatedConversation)
    
    // Display final response
    finalResult.foreach(fc => 
      println(s"Final response: ${fc.message.content}")
    )
    
  case Right(completion) =>
    // Normal response without tool calls
    println(s"Response: ${completion.message.content}")
    
  case Left(error) =>
    println(s"Error: $error")
}
```

### Streaming Example

```scala
client.streamComplete(
  conversation,
  CompletionOptions(),
  chunk => println(s"Chunk received: ${chunk.content.getOrElse("")}")
) match {
  case Right(finalCompletion) =>
    println("Stream completed successfully!")
  case Left(error) =>
    println(s"Stream error: $error")
}
```

## Migration from Existing API

For users of the current LLM4S API, a compatibility layer can be provided:

```scala
object LLM4SCompat {
  /** Convert the current API response to the new model */
  def convertFromAzureResponse(completions: ChatCompletions): Completion = {
    // Implementation similar to OpenAIClient.convertFromAzureCompletion
  }
  
  /** Convert new model conversation to current API messages */
  def convertToAzureMessages(conversation: Conversation): java.util.ArrayList[ChatRequestMessage] = {
    // Implementation similar to OpenAIClient.convertToAzureMessages
  }
  
  // More compatibility helpers...
}
```

## Conclusion

This API specification provides a clean, idiomatic Scala interface for LLM integration while maintaining compatibility with the existing tool calling implementation. The design emphasizes immutability, type safety, and a functional approach while abstracting away provider-specific details.

Provider-specific implementations handle the translation between our model and the provider's API format, ensuring a consistent experience regardless of the underlying LLM service being used.
