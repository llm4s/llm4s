package org.llm4s.schema

/**
 * Mutually recursive types whose recursion passes through a hand-written given that itself derives its type. The
 * compile-time check does not look inside a hand-written given, so all of these compile; building their schema
 * must fail at once with a message, never hang (eager field summoning used to deadlock their lazy givens).
 */
object RecursionFixtures {

  /** Both givens hand-written, each deriving its type: `given ps = SchemaOf.derived[P]`. */
  object BothGivens {
    case class P(q: Option[Q])
    case class Q(p: List[P])
    object Gs {
      given ps: SchemaOf[P] = SchemaOf.derived[P]
      given qs: SchemaOf[Q] = SchemaOf.derived[Q]
    }
  }

  /** One side `derives SchemaOf`, the other's given derives through a helper, so it is not named `derived$`. */
  object DerivesAndHelper {
    case class P(q: Option[Q]) derives SchemaOf
    case class Q(p: List[P])
    object Q {
      private def make: SchemaOf[Q] = SchemaOf.derived[Q]
      given SchemaOf[Q]             = make
    }
  }

  /** The instances are plain `val`s, initialised in order, which the givens forward to. */
  object PlainVals {
    case class P(q: Option[Q])
    case class Q(p: List[P])
    object Gs {
      val pSchema: SchemaOf[P] = SchemaOf.derived[P]
      val qSchema: SchemaOf[Q] = SchemaOf.derived[Q]
      given SchemaOf[P]        = pSchema
      given SchemaOf[Q]        = qSchema
    }
  }

  /** The givens are `def`s, so every summon makes a new instance and no instance is ever met twice. */
  object DefGivens {
    case class P(q: Option[Q])
    case class Q(p: List[P])
    object Gs {
      given ps(using DummyImplicit): SchemaOf[P] = SchemaOf.derived[P]
      given qs(using DummyImplicit): SchemaOf[Q] = SchemaOf.derived[Q]
    }
  }

  /** The same shape, but the given that closes the loop describes `Q` by hand: finite, and must keep working. */
  object ClosedByHand {
    case class P(q: Option[Q]) derives SchemaOf
    case class Q(p: List[P])
    object Q {
      given SchemaOf[Q] = SchemaOf.string("the Q, as text")
    }
  }
}
