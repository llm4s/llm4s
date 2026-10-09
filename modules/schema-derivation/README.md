# llm4s-schema-derivation

The JSON schema for `completeStructured`, derived from a case class so that the type and its schema cannot drift apart.

**Experimental.** A new module, not part of the 1.0 frozen surface. It depends on `llm4s-core` only and adds no
dependency.

```scala
import org.llm4s.schema.*
import upickle.default.ReadWriter

@description("An invoice extracted from text")
case class Invoice(
  @description("Name of the vendor or supplier") vendor: String,
  @description("Total invoice amount as a decimal number") amount: Double,
  @description("ISO 4217 currency code, e.g. USD, EUR, GBP") currency: String
) derives SchemaOf, ReadWriter

val invoice = client.completeStructuredOf[Invoice](conversation)   // Result[Invoice]
```

## What it provides

| | |
|---|---|
| `SchemaOf[A]` | the schema of `A`; `derives SchemaOf` for case classes, enums and sealed hierarchies of singletons |
| `SchemaOf[A].toJsonSchema(strict)` | the JSON Schema document `completeStructured` sends |
| `@description("...")` | the description the model sees, on a field or a type |
| `client.completeStructuredOf[A](conversation, options)` | `completeStructured` with the derived schema |
| `SchemaOf.string`, `SchemaOf.stringEnum` | schemas for types you write as strings yourself |

## Supported and not

Supported: `String`, `Int`, `Long`, `Double`, `Float`, `Boolean`, `BigDecimal`, `BigInt`, `Option`, `List`, `Seq`,
`Vector`, `Set`, case classes (nested, generic, with `@upickle.implicits.key`), enums and sealed hierarchies of
singletons.

Refused at compile time, naming the type: `Map`, recursive types, and sealed hierarchies with fields. `llm4s-core`'s
schema model has a fixed set of properties and no `$ref` or `oneOf`, so it cannot express them.

The schema describes what uPickle's derived `ReadWriter` reads and writes, and the tests check that in both
directions. See the [guide](../../docs/guide/derived-schemas.md) for the details, the strict-mode behaviour and the
compile-time cost.

## Tests

`sbt schemaDerivation/test`. The golden specs pin the derived JSON for every shape, one of them against the hand-built
schema of `StructuredOutputExample`; `RoundTripSpec` validates what uPickle writes against the schema and reads
schema-conformant documents back; `CompileErrorsSpec` pins the refusals; `DerivedSchemasGuideSpec` compiles the guide.
