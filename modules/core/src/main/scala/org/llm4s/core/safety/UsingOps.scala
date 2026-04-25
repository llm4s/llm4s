// scalafix:off
package org.llm4s.core.safety

/**
 * Resource management helpers for automatic cleanup
 * This file legitimately needs try-finally for low-level resource management
 */
object UsingOps {

  /**
   * Manages resources that implement AutoCloseable
   * Automatically closes the resource after use, even if an exception occurs
   *
   * @param resource The AutoCloseable resource to manage
   * @param f Function to apply to the resource
   * @return Result of applying the function
   */
  def using[A <: AutoCloseable, B](resource: A)(f: A => B): B =
    try
      f(resource)
    finally
      if (resource != null) resource.close()

  /**
   * Manages multiple resources with proper cleanup order
   * Resources are closed in reverse order of creation
   */
  def using2[A <: AutoCloseable, B <: AutoCloseable, C](resource1: A, resource2: B)(f: (A, B) => C): C =
    using(resource1)(r1 => using(resource2)(r2 => f(r1, r2)))
}
