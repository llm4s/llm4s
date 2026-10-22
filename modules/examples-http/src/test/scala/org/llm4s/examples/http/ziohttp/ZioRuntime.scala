package org.llm4s.examples.http.ziohttp

import zio.{ Runtime, Scope, Unsafe, ZIO }

/** Runs a ZIO effect from a ScalaTest spec, in its own scope. */
private[ziohttp] object ZioRuntime {

  def unsafeRun[E, A](effect: ZIO[Scope, E, A]): A =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(ZIO.scoped(effect)).getOrThrowFiberFailure())
}
