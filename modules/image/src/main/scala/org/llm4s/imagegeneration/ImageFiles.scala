package org.llm4s.imagegeneration

import java.nio.file.{ AtomicMoveNotSupportedException, Files, Path, StandardCopyOption }
import scala.util.Try

/** Writing an image file without truncating one in place (#3140); shared by generation and processing. */
private[llm4s] object ImageFiles {

  /**
   * Writes `bytes` to a temporary file beside `path` and moves it into place, so a failed write leaves no
   * partial file at `path`. Without `overwrite` an existing `path` is a `FileAlreadyExistsException` and is
   * left untouched; with it the move replaces the file, atomically where the file system can.
   */
  def writeAtomically(path: Path, bytes: Array[Byte], overwrite: Boolean): Either[Throwable, Unit] = {
    val target = path.toAbsolutePath.normalize()
    Try(Files.createTempFile(target.getParent, s".${target.getFileName}.", ".tmp")).toEither.flatMap { temp =>
      val moved = Try(Files.write(temp, bytes)).flatMap(_ => move(temp, target, overwrite)).toEither
      if (moved.isLeft) Try(Files.deleteIfExists(temp))
      moved
    }
  }

  private def move(temp: Path, target: Path, overwrite: Boolean): Try[Unit] =
    if (!overwrite) Try(Files.move(temp, target)).map(_ => ())
    else
      Try(Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
        .recoverWith { case _: AtomicMoveNotSupportedException =>
          Try(Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING))
        }
        .map(_ => ())
}
