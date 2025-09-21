package org.llm4s.imageprocessing

/**
 * Type-safe media types for image processing.
 */
sealed trait MediaType {
  def value: String
}

object MediaType {
  case object Jpeg extends MediaType { val value = "image/jpeg" }
  case object Png  extends MediaType { val value = "image/png"  }
  case object Gif  extends MediaType { val value = "image/gif"  }
  case object WebP extends MediaType { val value = "image/webp" }
  case object Bmp  extends MediaType { val value = "image/bmp"  }
  case object Tiff extends MediaType { val value = "image/tiff" }

  /**
   * Detect media type from file extension.
   */
  def fromExtension(extension: String): MediaType =
    extension.toLowerCase match {
      case "jpg" | "jpeg" => Jpeg
      case "png"          => Png
      case "gif"          => Gif
      case "webp"         => WebP
      case "bmp"          => Bmp
      case "tiff" | "tif" => Tiff
      case _              => Jpeg // Default fallback
    }

  /**
   * Detect media type from file path.
   */
  def fromPath(imagePath: String): MediaType = {
    val extension = imagePath.toLowerCase.split('.').lastOption.getOrElse("")
    fromExtension(extension)
  }
}
