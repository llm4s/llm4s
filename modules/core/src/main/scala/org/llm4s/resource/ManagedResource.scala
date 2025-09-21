package org.llm4s.resource

import org.llm4s.types.Result
import scala.util.Try
import org.llm4s.types.TryOps

/**
 * Managed resource abstraction for safe resource handling.
 * Builds on the existing bracket functionality in types.package.
 */
trait ManagedResource[R] {

  /**
   * Acquire the resource
   */
  def acquire(): Result[R]

  /**
   * Release the resource safely
   */
  def release(resource: R): Result[Unit]

  /**
   * Use the resource with automatic cleanup
   */
  def use[A](f: R => Result[A]): Result[A] =
    acquire().flatMap { resource =>
      val result = f(resource)
      release(resource).flatMap(_ => result)
    }
}

object ManagedResource {

  /**
   * Create a managed resource from acquire/release functions
   */
  def apply[R](
    acquireF: () => Result[R],
    releaseF: R => Result[Unit]
  ): ManagedResource[R] = new ManagedResource[R] {
    def acquire(): Result[R]               = acquireF()
    def release(resource: R): Result[Unit] = releaseF(resource)
  }

  /**
   * Create a managed resource from acquire/release functions with Try
   */
  def fromTry[R](
    acquireF: () => Try[R],
    releaseF: R => Try[Unit]
  ): ManagedResource[R] = new ManagedResource[R] {
    def acquire(): Result[R]               = acquireF().toResult
    def release(resource: R): Result[Unit] = releaseF(resource).toResult
  }

  /**
   * Managed FileInputStream
   */
  def fileInputStream(path: java.nio.file.Path): ManagedResource[java.io.FileInputStream] =
    fromTry(
      () => Try(new java.io.FileInputStream(path.toFile)),
      (fis: java.io.FileInputStream) => Try(fis.close())
    )

  /**
   * Managed FileOutputStream
   */
  def fileOutputStream(path: java.nio.file.Path): ManagedResource[java.io.FileOutputStream] =
    fromTry(
      () => Try(new java.io.FileOutputStream(path.toFile)),
      (fos: java.io.FileOutputStream) => Try(fos.close())
    )

  /**
   * Managed DataOutputStream
   */
  def dataOutputStream(path: java.nio.file.Path): ManagedResource[java.io.DataOutputStream] =
    fromTry(
      () => Try(new java.io.DataOutputStream(new java.io.FileOutputStream(path.toFile))),
      (dos: java.io.DataOutputStream) => Try(dos.close())
    )

  /**
   * Managed ByteArrayInputStream
   */
  def byteArrayInputStream(data: Array[Byte]): ManagedResource[java.io.ByteArrayInputStream] =
    fromTry(
      () => Try(new java.io.ByteArrayInputStream(data)),
      (bais: java.io.ByteArrayInputStream) => Try(bais.close())
    )

  /**
   * Managed AudioInputStream for speech processing
   */
  def audioInputStream(
    data: Array[Byte],
    format: javax.sound.sampled.AudioFormat
  ): ManagedResource[javax.sound.sampled.AudioInputStream] =
    fromTry(
      () =>
        Try {
          val bais = new java.io.ByteArrayInputStream(data)
          new javax.sound.sampled.AudioInputStream(bais, format, data.length / format.getFrameSize)
        },
      (ais: javax.sound.sampled.AudioInputStream) => Try(ais.close())
    )

  /**
   * Managed temporary file that gets deleted on release
   */
  def tempFile(prefix: String, suffix: String): ManagedResource[java.nio.file.Path] =
    fromTry(
      () => Try(java.nio.file.Files.createTempFile(prefix, suffix)),
      (path: java.nio.file.Path) => Try(java.nio.file.Files.deleteIfExists(path)).map(_ => ())
    )

  /**
   * Extension methods for easier resource composition
   */
  implicit class ManagedResourceOps[R](resource: ManagedResource[R]) {

    /**
     * Map over the resource
     */
    def map[S](f: R => S): ManagedResource[S] = new ManagedResource[S] {
      def acquire(): Result[S]        = resource.acquire().map(f)
      def release(s: S): Result[Unit] = Right(()) // Mapped resources don't need explicit release
    }

    /**
     * FlatMap for resource composition
     */
    def flatMap[S](f: R => ManagedResource[S]): ManagedResource[S] = new ManagedResource[S] {
      def acquire(): Result[S]        = resource.acquire().flatMap(r => f(r).acquire())
      def release(s: S): Result[Unit] = Right(()) // Composed resources handle their own cleanup
    }
  }
}
