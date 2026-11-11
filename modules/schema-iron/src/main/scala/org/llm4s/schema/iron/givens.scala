package org.llm4s.schema.iron

import io.github.iltotore.iron.{ :|, refineEither, RuntimeConstraint }
import org.llm4s.schema.SchemaOf
import upickle.default.ReadWriter

/**
 * The schema of an Iron refinement type, `A :| C`: the schema of `A` with `C` written into it as JSON Schema
 * bounds, so the model is told the bound it must keep.
 *
 * {{{
 * import io.github.iltotore.iron.*
 * import io.github.iltotore.iron.constraint.all.*
 * import org.llm4s.schema.*
 * import org.llm4s.schema.iron.given
 *
 * case class Review(
 *   @description("Short headline") title: String :| (Not[Blank] & MaxLength[80]),
 *   @description("Stars") rating: Int :| Interval.Closed[1, 5]
 * ) derives SchemaOf, ReadWriter
 * }}}
 *
 * Described:
 *  - on `Int`, `Long`, `Double` and `Float`: `Greater`, `GreaterEqual`, `Less`, `LessEqual`, `StrictEqual`,
 *    `Positive`, `Positive0`, `Negative`, `Negative0` and every `Interval`, as `minimum` and `maximum` (and, on
 *    `Double` and `Float`, `exclusiveMinimum` and `exclusiveMaximum`). An integer's exclusive bound is written as the
 *    inclusive one it equals (`Greater[0]` as `minimum: 1`), which every provider accepts.
 *  - on `String`: `MinLength`, `MaxLength`, `FixedLength`, `Not[Empty]` and `Not[Blank]`, as `minLength` and
 *    `maxLength`. `Not[Blank]` is described as `minLength: 1`, which every non-blank string meets; that the string
 *    must not be only whitespace is checked when the reply is read.
 *  - on `List`, `Seq`, `Vector` and `Set`: `MinLength`, `MaxLength`, `FixedLength` and `Not[Empty]`, as `minItems`
 *    and `maxItems`.
 *  - a conjunction `C1 & C2` of these, as both bounds; two bounds of one kind keep the tighter.
 *
 * Any other constraint, `Match` among them (core's `StringSchema` has no `pattern` yet), and a constraint on any other
 * type, is refused at compile time with a message naming it, rather than left out of the schema without a word.
 *
 * The bounds are written into the schema `A`'s own `SchemaOf` builds, so the field keeps its description, and an
 * `Option[A :| C]`, a `List[A :| C]` or a case class holding one is derived as usual.
 *
 * @tparam A the refined type
 * @tparam C the constraint
 */
inline given refinedSchemaOf[A, C](using base: SchemaOf[A]): SchemaOf[A :| C] =
  IronSchemas.refined[A, C](base, IronMacros.bounds[A, C])

/**
 * The uPickle `ReadWriter` of `A :| C`: `A`'s, refined after reading. A value that breaks the constraint fails to
 * read with the constraint's message, so `completeStructured`, `completeStructuredOf` and `Tool.of` return a `Left`
 * naming the field and the bound rather than a value the type should have rejected.
 *
 * Iron's own `iron-upickle` module defines the same instance: import one of the two, not both, or the instances are
 * ambiguous.
 */
given refinedReadWriter[A, C](using base: ReadWriter[A], constraint: RuntimeConstraint[A, C]): ReadWriter[A :| C] =
  base.bimap[A :| C](
    refined => refined,
    value => value.refineEither[C].fold(message => throw upickle.core.Abort(message), identity)
  )
