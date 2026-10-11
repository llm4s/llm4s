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

  /**
   * Field instances that throw what `scala.util.Try` does not catch, so that a guard restored only after a `Try`
   * would be left on the thread. Each is a field of a derived product, whose build must still restore the guard.
   */
  object Throwing {
    private def throwing[A](name: String, error: () => Throwable): SchemaOf[A] = new SchemaOf[A] {
      def typeName: String                                                   = name
      def schema(description: String): org.llm4s.toolapi.SchemaDefinition[A] = throw error()
    }

    case class Linkage(value: Int)
    object Linkage {
      given SchemaOf[Linkage] = throwing("Linkage", () => new ExceptionInInitializerError("thrown by Linkage"))
    }
    case class HoldsLinkage(linkage: Linkage) derives SchemaOf

    /** Sets the thread's interrupt flag, as code that is interrupted and passes it on should, then throws. */
    case class Interrupted(value: Int)
    object Interrupted {
      given SchemaOf[Interrupted] = throwing(
        "Interrupted",
        () => {
          Thread.currentThread().interrupt()
          new InterruptedException("thrown by Interrupted")
        }
      )
    }
    case class HoldsInterrupted(interrupted: Interrupted) derives SchemaOf

    case class Control(value: Int)
    object Control {
      final class Thrown extends scala.util.control.ControlThrowable
      given SchemaOf[Control] = throwing("Control", () => new Thrown)
    }
    case class HoldsControl(control: Control) derives SchemaOf

    /** Its companion's initialiser throws, so summoning its given fails: first with ExceptionInInitializerError. */
    case class Boom(value: Int)
    object Boom {
      private def initialise(): Unit = throw new IllegalArgumentException("Boom's initialiser fails")
      initialise()
      given SchemaOf[Boom] = SchemaOf.string("a boom")
    }
    case class HoldsBoom(boom: Boom) derives SchemaOf
  }

  /** One generic class nested in itself: finite, but past 16 levels the run-time guard counts it as recursive. */
  object Nesting {
    case class W[A](a: A) derives SchemaOf
    type W16[A] = W[W[W[W[W[W[W[W[W[W[W[W[W[W[W[W[A]]]]]]]]]]]]]]]]
    type W17[A] = W[W[W[W[W[W[W[W[W[W[W[W[W[W[W[W[W[A]]]]]]]]]]]]]]]]]
    // the same nesting spelled out in a field's type, which the compile-time check walks (no `derives` here)
    case class Holds16(w: W16[Int])
    case class Holds17(w: W17[Int])
  }
}
