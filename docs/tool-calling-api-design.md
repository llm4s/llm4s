---
layout: page
title: Tool Calling API Design
parent: API Reference
---

# Scala Tool Calling API Design

This document outlines the design for a type-safe Scala API for defining, validating, and executing tool calls for LLMs. It provides a clean, functional approach to tool function definitions with JSON Schema validation.

## Core Components

### 1. Schema Definitions

The design uses a hierarchy of schema classes to define parameter types and constraints:

```scala
sealed trait SchemaDefinition[T] {
  def toJsonSchema: ujson.Value
}
```

#### String Schema

```scala
case class StringSchema(
  description: String,
  enumValues: Option[Seq[String]] = None,
  pattern: Option[String] = None,
  minLength: Option[Int] = None,
  maxLength: Option[Int] = None
) extends SchemaDefinition[String]
```

#### Number Schema

```scala
case class NumberSchema(
  description: String,
  isInteger: Boolean = false,
  minimum: Option[Double] = None,
  maximum: Option[Double] = None,
  exclusiveMinimum: Option[Double] = None,
  exclusiveMaximum: Option[Double] = None,
  multipleOf: Option[Double] = None
) extends SchemaDefinition[Double]
```

#### Integer Schema

```scala
case class IntegerSchema(
  description: String,
  minimum: Option[Int] = None,
  maximum: Option[Int] = None,
  exclusiveMinimum: Option[Int] = None,
  exclusiveMaximum: Option[Int] = None,
  multipleOf: Option[Int] = None
) extends SchemaDefinition[Int]
```

#### Boolean Schema

```scala
case class BooleanSchema(
  description: String
) extends SchemaDefinition[Boolean]
```

#### Array Schema

```scala
case class ArraySchema[A](
  description: String,
  itemSchema: SchemaDefinition[A],
  minItems: Option[Int] = None,
  maxItems: Option[Int] = None,
  uniqueItems: Boolean = false
) extends SchemaDefinition[Seq[A]]
```

#### Object Schema

```scala
case class PropertyDefinition[T](
  name: String,
  schema: SchemaDefinition[T],
  required: Boolean = true
)

case class ObjectSchema[T](
  description: String,
  properties: Seq[PropertyDefinition[_]],
  additionalProperties: Boolean = false
) extends SchemaDefinition[T]
```

#### Nullable Schema

```scala
case class NullableSchema[T](
  underlying: SchemaDefinition[T]
) extends SchemaDefinition[Option[T]]
```

### 2. Safe Parameter Extraction

To ensure type-safe parameter extraction:

```scala
case class SafeParameterExtractor(params: ujson.Value) {
  def getString(path: String): Either[String, String]
  def getInt(path: String): Either[String, Int]
  def getDouble(path: String): Either[String, Double]
  def getBoolean(path: String): Either[String, Boolean]
  def getArray(path: String): Either[String, ujson.Arr]
  def getObject(path: String): Either[String, ujson.Obj]
}
```

### 3. Tool Function Definition

```scala
case class ToolFunction[T, R: ReadWriter](
  name: String,
  description: String,
  schema: SchemaDefinition[T],
  handler: SafeParameterExtractor => Either[String, R]
) {
  def toOpenAITool(strict: Boolean = true): ujson.Value
  def execute(args: ujson.Value): Either[ToolCallError, R]
}
```

### 4. Tool Builder (Fluent API)

```scala
class ToolBuilder[T, R: ReadWriter] private (
  name: String,
  description: String,
  schema: SchemaDefinition[T],
  handler: Option[SafeParameterExtractor => Either[String, R]] = None
) {
  def withHandler(handler: SafeParameterExtractor => Either[String, R]): ToolBuilder[T, R]
  def build(): ToolFunction[T, R]
}
```

### 5. Tool Registry

```scala
class ToolRegistry(tools: Seq[ToolFunction[_, _]]) {
  def getTool(name: String): Option[ToolFunction[_, _]]
  def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value]
  def getOpenAITools(strict: Boolean = true): ujson.Arr
  def getToolDefinitions(provider: String): ujson.Value
}
```

### 6. Error Handling

```scala
sealed trait ToolCallError
object ToolCallError {
  case class UnknownFunction(name: String) extends ToolCallError
  case class InvalidArguments(errors: List[String]) extends ToolCallError
  case class ExecutionError(cause: Throwable) extends ToolCallError
}
```

## Schema Builder API

A fluent API for building schemas:

```scala
object Schema {
  // String schemas
  def string(description: String): StringSchema
  
  // Number schemas
  def number(description: String): NumberSchema
  def integer(description: String): IntegerSchema
  
  // Boolean schemas
  def boolean(description: String): BooleanSchema
  
  // Array schemas
  def array[A](description: String, itemSchema: SchemaDefinition[A]): ArraySchema[A]
  
  // Object schemas
  def `object`[T](description: String): ObjectSchema[T]
  
  // Nullable schemas
  def nullable[T](schema: SchemaDefinition[T]): NullableSchema[T]
  
  // Properties
  def property[T](name: String, schema: SchemaDefinition[T], required: Boolean = true): PropertyDefinition[T]
}
```

## Complete Implementation

```scala
import upickle.default._
import ujson._

/**
 * Safe parameter extraction
 */
case class SafeParameterExtractor(params: ujson.Value) {
  def getString(path: String): Either[String, String] = 
    extract(path, _.strOpt, "string")
  
  def getInt(path: String): Either[String, Int] = 
    extract(path, _.numOpt.map(_.toInt), "integer")
  
  def getDouble(path: String): Either[String, Double] = 
    extract(path, _.numOpt, "number")
  
  def getBoolean(path: String): Either[String, Boolean] = 
    extract(path, _.boolOpt, "boolean")
  
  def getArray(path: String): Either[String, ujson.Arr] = 
    extract(path, v => Option(v).collect { case arr: ujson.Arr => arr }, "array")
  
  def getObject(path: String): Either[String, ujson.Obj] = 
    extract(path, v => Option(v).collect { case obj: ujson.Obj => obj }, "object")
  
  // Generic extractor with type validation
  private def extract[T](path: String, extractor: ujson.Value => Option[T], expectedType: String): Either[String, T] = {
    try {
      val pathParts = path.split('.')
      var current = params
      
      // Navigate through nested path
      for (part <- pathParts.dropRight(1)) {
        current.obj.get(part) match {
          case Some(value) => current = value
          case None => return Left(s"Path '$path' not found: missing '$part' segment")
        }
      }
      
      // Extract the final value
      val finalPart = pathParts.last
      current.obj.get(finalPart) match {
        case Some(value) => 
          extractor(value) match {
            case Some(result) => Right(result)
            case None => Left(s"Value at '$path' is not of expected type '$expectedType'")
          }
        case None => Left(s"Parameter '$finalPart' not found")
      }
    } catch {
      case e: Exception => Left(s"Error extracting parameter at '$path': ${e.getMessage}")
    }
  }
}

/**
 * Core model for tool function definitions
 */
case class ToolFunction[T, R: ReadWriter](
  name: String,
  description: String,
  schema: SchemaDefinition[T],
  handler: SafeParameterExtractor => Either[String, R]
) {
  /**
   * Converts the tool definition to the format expected by OpenAI's API
   */
  def toOpenAITool(strict: Boolean = true): ujson.Value = {
    ujson.Obj(
      "type" -> ujson.Str("function"),
      "function" -> ujson.Obj(
        "name" -> ujson.Str(name),
        "description" -> ujson.Str(description),
        "parameters" -> schema.toJsonSchema,
        "strict" -> ujson.Bool(strict)
      )
    )
  }
  
  /**
   * Executes the tool with the given arguments
   */
  def execute(args: ujson.Value): Either[ToolCallError, R] = {
    val extractor = SafeParameterExtractor(args)
    handler(extractor) match {
      case Right(result) => Right(result)
      case Left(error) => Left(ToolCallError.InvalidArguments(List(error)))
    }
  }
}

/**
 * Schema definitions
 */
sealed trait SchemaDefinition[T] {
  def toJsonSchema: ujson.Value
}

/**
 * Schema building blocks
 */
case class StringSchema(
  description: String,
  enumValues: Option[Seq[String]] = None,
  pattern: Option[String] = None,
  minLength: Option[Int] = None,
  maxLength: Option[Int] = None
) extends SchemaDefinition[String] {
  def toJsonSchema: ujson.Value = {
    val base = ujson.Obj(
      "type" -> ujson.Str("string"),
      "description" -> ujson.Str(description)
    )
    
    enumValues.foreach(values => base("enum") = ujson.Arr.from(values.map(ujson.Str(_))))
    pattern.foreach(p => base("pattern") = ujson.Str(p))
    minLength.foreach(min => base("minLength") = ujson.Num(min))
    maxLength.foreach(max => base("maxLength") = ujson.Num(max))
    
    base
  }
  
  def withEnum(values: Seq[String]): StringSchema = copy(enumValues = Some(values))
  def withPattern(regex: String): StringSchema = copy(pattern = Some(regex))
  def withLengthConstraints(min: Option[Int] = None, max: Option[Int] = None): StringSchema = 
    copy(minLength = min, maxLength = max)
}

case class NumberSchema(
  description: String,
  isInteger: Boolean = false,
  minimum: Option[Double] = None,
  maximum: Option[Double] = None,
  exclusiveMinimum: Option[Double] = None,
  exclusiveMaximum: Option[Double] = None,
  multipleOf: Option[Double] = None
) extends SchemaDefinition[Double] {
  def toJsonSchema: ujson.Value = {
    val base = ujson.Obj(
      "type" -> ujson.Str(if (isInteger) "integer" else "number"),
      "description" -> ujson.Str(description)
    )
    
    minimum.foreach(min => base("minimum") = ujson.Num(min))
    maximum.foreach(max => base("maximum") = ujson.Num(max))
    exclusiveMinimum.foreach(min => base("exclusiveMinimum") = ujson.Num(min))
    exclusiveMaximum.foreach(max => base("exclusiveMaximum") = ujson.Num(max))
    multipleOf.foreach(multiple => base("multipleOf") = ujson.Num(multiple))
    
    base
  }
  
  def withRange(min: Option[Double] = None, max: Option[Double] = None): NumberSchema = 
    copy(minimum = min, maximum = max)
    
  def withExclusiveRange(min: Option[Double] = None, max: Option[Double] = None): NumberSchema = 
    copy(exclusiveMinimum = min, exclusiveMaximum = max)
    
  def withMultipleOf(multiple: Double): NumberSchema = 
    copy(multipleOf = Some(multiple))
}

case class IntegerSchema(
  description: String,
  minimum: Option[Int] = None,
  maximum: Option[Int] = None,
  exclusiveMinimum: Option[Int] = None,
  exclusiveMaximum: Option[Int] = None,
  multipleOf: Option[Int] = None
) extends SchemaDefinition[Int] {
  def toJsonSchema: ujson.Value = {
    val base = ujson.Obj(
      "type" -> ujson.Str("integer"),
      "description" -> ujson.Str(description)
    )
    
    minimum.foreach(min => base("minimum") = ujson.Num(min))
    maximum.foreach(max => base("maximum") = ujson.Num(max))
    exclusiveMinimum.foreach(min => base("exclusiveMinimum") = ujson.Num(min))
    exclusiveMaximum.foreach(max => base("exclusiveMaximum") = ujson.Num(max))
    multipleOf.foreach(multiple => base("multipleOf") = ujson.Num(multiple))
    
    base
  }
  
  def withRange(min: Option[Int] = None, max: Option[Int] = None): IntegerSchema = 
    copy(minimum = min, maximum = max)
    
  def withExclusiveRange(min: Option[Int] = None, max: Option[Int] = None): IntegerSchema = 
    copy(exclusiveMinimum = min, exclusiveMaximum = max)
    
  def withMultipleOf(multiple: Int): IntegerSchema = 
    copy(multipleOf = Some(multiple))
}

case class BooleanSchema(
  description: String
) extends SchemaDefinition[Boolean] {
  def toJsonSchema: ujson.Value = {
    ujson.Obj(
      "type" -> ujson.Str("boolean"),
      "description" -> ujson.Str(description)
    )
  }
}

case class ArraySchema[A](
  description: String,
  itemSchema: SchemaDefinition[A],
  minItems: Option[Int] = None,
  maxItems: Option[Int] = None,
  uniqueItems: Boolean = false
) extends SchemaDefinition[Seq[A]] {
  def toJsonSchema: ujson.Value = {
    val base = ujson.Obj(
      "type" -> ujson.Str("array"),
      "description" -> ujson.Str(description),
      "items" -> itemSchema.toJsonSchema
    )
    
    minItems.foreach(min => base("minItems") = ujson.Num(min))
    maxItems.foreach(max => base("maxItems") = ujson.Num(max))
    if (uniqueItems) base("uniqueItems") = ujson.Bool(true)
    
    base
  }
  
  def withSizeConstraints(min: Option[Int] = None, max: Option[Int] = None): ArraySchema[A] =
    copy(minItems = min, maxItems = max)
    
  def withUniqueItems(unique: Boolean = true): ArraySchema[A] =
    copy(uniqueItems = unique)
}

case class PropertyDefinition[T](
  name: String,
  schema: SchemaDefinition[T],
  required: Boolean = true
)

case class ObjectSchema[T](
  description: String,
  properties: Seq[PropertyDefinition[_]],
  additionalProperties: Boolean = false
) extends SchemaDefinition[T] {
  def toJsonSchema: ujson.Value = {
    val props = ujson.Obj()
    val required = properties.filter(_.required).map(_.name)
    
    properties.foreach { prop =>
      props(prop.name) = prop.schema.toJsonSchema
    }
    
    ujson.Obj(
      "type" -> ujson.Str("object"),
      "description" -> ujson.Str(description),
      "properties" -> props,
      "required" -> ujson.Arr.from(required.map(ujson.Str(_))),
      "additionalProperties" -> ujson.Bool(additionalProperties)
    )
  }
  
  def withProperty[P](property: PropertyDefinition[P]): ObjectSchema[T] = {
    copy(properties = properties :+ property)
  }
}

case class NullableSchema[T](
  underlying: SchemaDefinition[T]
) extends SchemaDefinition[Option[T]] {
  def toJsonSchema: ujson.Value = {
    val schema = underlying.toJsonSchema.obj
    val typeField = schema.get("type")
    
    typeField match {
      case Some(ujson.Str(typeValue)) =>
        // Replace type field with array of types
        schema("type") = ujson.Arr(ujson.Str(typeValue), ujson.Str("null"))
      case Some(arr: ujson.Arr) =>
        // Add null to existing type array
        schema("type") = arr.value :+ ujson.Str("null")
      case _ =>
        // Create new type array if none exists
        schema("type") = ujson.Arr(ujson.Str("null"))
    }
    
    ujson.Obj.from(schema)
  }
}

/**
 * Schema builder - fluent API
 */
object Schema {
  // String schemas
  def string(description: String): StringSchema = StringSchema(description)
  
  // Number schemas
  def number(description: String): NumberSchema = NumberSchema(description)
  def integer(description: String): IntegerSchema = IntegerSchema(description)
  
  // Boolean schemas
  def boolean(description: String): BooleanSchema = BooleanSchema(description)
  
  // Array schemas
  def array[A](description: String, itemSchema: SchemaDefinition[A]): ArraySchema[A] = 
    ArraySchema(description, itemSchema)
  
  // Object schemas
  def `object`[T](description: String): ObjectSchema[T] = 
    ObjectSchema[T](description, Seq.empty)
  
  // Nullable schemas
  def nullable[T](schema: SchemaDefinition[T]): NullableSchema[T] = 
    NullableSchema(schema)
  
  // Properties
  def property[T](name: String, schema: SchemaDefinition[T], required: Boolean = true): PropertyDefinition[T] =
    PropertyDefinition(name, schema, required)
}

/**
 * Builder for tool definitions
 */
class ToolBuilder[T, R: ReadWriter] private (
  name: String,
  description: String,
  schema: SchemaDefinition[T],
  handler: Option[SafeParameterExtractor => Either[String, R]] = None
) {
  def withHandler(handler: SafeParameterExtractor => Either[String, R]): ToolBuilder[T, R] = 
    new ToolBuilder(name, description, schema, Some(handler))
    
  def build(): ToolFunction[T, R] = handler match {
    case Some(h) => ToolFunction(name, description, schema, h)
    case None => throw new IllegalStateException("Handler not defined")
  }
}

object ToolBuilder {
  def apply[T, R: ReadWriter](name: String, description: String, schema: SchemaDefinition[T]): ToolBuilder[T, R] =
    new ToolBuilder(name, description, schema)
}

/**
 * Request/Response handling
 */
case class ToolCallRequest(
  functionName: String,
  arguments: ujson.Value
)

sealed trait ToolCallError
object ToolCallError {
  case class UnknownFunction(name: String) extends ToolCallError
  case class InvalidArguments(errors: List[String]) extends ToolCallError
  case class ExecutionError(cause: Throwable) extends ToolCallError
}

/**
 * Tool registry and executor
 */
class ToolRegistry(tools: Seq[ToolFunction[_, _]]) {
  // Get a specific tool by name
  def getTool(name: String): Option[ToolFunction[_, _]] = tools.find(_.name == name)
  
  // Execute a tool call
  def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] = {
    tools.find(_.name == request.functionName) match {
      case Some(tool) => 
        try {
          tool.execute(request.arguments) match {
            case Right(result) => Right(writeJs(result))
            case Left(error) => Left(error)
          }
        } catch {
          case e: Exception => Left(ToolCallError.ExecutionError(e))
        }
        
      case None => Left(ToolCallError.UnknownFunction(request.functionName))
    }
  }
  
  // Generate OpenAI tool definitions for all tools
  def getOpenAITools(strict: Boolean = true): ujson.Arr = {
    ujson.Arr.from(tools.map(_.toOpenAITool(strict)))
  }
  
  // Generate a specific format of tool definitions for a particular LLM provider
  def getToolDefinitions(provider: String): ujson.Value = provider.toLowerCase match {
    case "openai" => getOpenAITools()
    case "anthropic" => getOpenAITools() // Currently using the same format
    case "gemini" => getOpenAITools() // May need adjustment for Google's format
    case _ => throw new IllegalArgumentException(s"Unsupported LLM provider: $provider")
  }
}
```

## Usage Example

Here's an example of how to use the library to define and execute tools:

```scala
// Define result type
case class WeatherResult(
  location: String,
  temperature: Double,
  units: String,
  conditions: String
)

// Provide implicit reader/writer
implicit val weatherResultRW: ReadWriter[WeatherResult] = macroRW

// Define weather parameter schema
val weatherParamsSchema = Schema.`object`[Map[String, Any]]("Weather request parameters")
  .withProperty(Schema.property(
    "location", 
    Schema.string("City and country e.g. Bogotá, Colombia")
      .withPattern("^[A-Za-z\\s,]+$") // Pattern for city, country format
  ))
  .withProperty(Schema.property(
    "units", 
    Schema.string("Units the temperature will be returned in.")
      .withEnum(Seq("celsius", "fahrenheit"))
  ))

// Define type-safe handler function
def weatherHandler(params: SafeParameterExtractor): Either[String, WeatherResult] = {
  for {
    location <- params.getString("location")
    units <- params.getString("units")
  } yield {
    // In a real implementation, this would call an actual weather service
    WeatherResult(
      location = location,
      temperature = 22.5,
      units = units,
      conditions = "sunny"
    )
  }
}

// Build the weather tool
val weatherTool = ToolBuilder[Map[String, Any], WeatherResult](
  "get_weather", 
  "Retrieves current weather for the given location.", 
  weatherParamsSchema
).withHandler(weatherHandler).build()

// Define a complex nested schema
val addressSchema = Schema.`object`[Map[String, Any]]("Address information")
  .withProperty(Schema.property("street", Schema.string("Street address")))
  .withProperty(Schema.property("city", Schema.string("City name")))
  .withProperty(Schema.property(
    "zipcode",
    Schema.string("ZIP/Postal code").withPattern("^\\d{5}(-\\d{4})?$")
  ))
  .withProperty(Schema.property("country", Schema.string("Country name")))

// Create a tool registry with the weather tool
val toolRegistry = new ToolRegistry(Seq(weatherTool))

// Example execution
val weatherRequest = ToolCallRequest(
  functionName = "get_weather",
  arguments = ujson.Obj("location" -> "London, UK", "units" -> "celsius")
)

// Execute the tool call
val result = toolRegistry.execute(weatherRequest)

// Generate tool definitions for OpenAI
val openaiTools = toolRegistry.getToolDefinitions("openai")

// Use in an API request
val openaiRequest = ujson.Obj(
  "model" -> "gpt-4-turbo",
  "messages" -> ujson.Arr(
    ujson.Obj(
      "role" -> "user",
      "content" -> "What's the weather in Paris?"
    )
  ),
  "tools" -> openaiTools
)
```

## Key Advantages

1. **Type Safety**: Both schema definitions and return types are fully type-safe
2. **Safe Parameter Extraction**: Path-based parameter extraction with type checking
3. **Pattern Validation**: Comprehensive support for validation constraints
4. **Composable Design**: Schemas can be built from reusable components
5. **Error Handling**: Clear, detailed error messages for invalid parameters
6. **LLM Provider Support**: Easy generation of tool definitions for different LLM APIs
7. **Functional Style**: Uses Scala's functional features like Either for error handling

## Dependencies

- `upickle` library for JSON serialization/deserialization
- `ujson` for working with JSON data structures

## Implementation Notes

The code uses the following Scala patterns:
- Case classes for immutable data models
- Builder pattern for fluent schema construction
- Composition over inheritance for schema building
- Functional error handling with Either
- Path-based parameter extraction for nested objects
