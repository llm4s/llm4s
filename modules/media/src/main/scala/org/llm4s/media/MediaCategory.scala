package org.llm4s.media

/**
 * The broad kind of a media type - the part of a MIME type before the slash.
 *
 * This is the discriminator multimodal code branches on: extraction decides whether to decode
 * a file as pixels, samples or text; a provider decides whether it can accept the payload at
 * all. Modelling it as a type rather than as `mimeType.startsWith("image/")` keeps that
 * decision exhaustive and testable.
 */
sealed trait MediaCategory extends Product with Serializable {

  /** The MIME top-level type, e.g. `"image"`. */
  def name: String
}

object MediaCategory {
  case object Image       extends MediaCategory { val name = "image"       }
  case object Audio       extends MediaCategory { val name = "audio"       }
  case object Video       extends MediaCategory { val name = "video"       }
  case object Text        extends MediaCategory { val name = "text"        }
  case object Application extends MediaCategory { val name = "application" }

  /** Every category, in declaration order. */
  val all: Seq[MediaCategory] = Seq(Image, Audio, Video, Text, Application)

  /**
   * The category of a MIME type string, e.g. `"image/png"` -> [[Image]].
   *
   * Returns `None` for a MIME type whose top-level type is not one of [[all]] - including
   * malformed input - so callers must decide what an unrecognised payload means rather than
   * being handed a wrong answer.
   */
  def fromMimeType(mimeType: String): Option[MediaCategory] = {
    val normalised = mimeType.trim.toLowerCase
    val slash      = normalised.indexOf('/')
    // A bare top-level type with no subtype is not a MIME type; refuse it rather than
    // reporting a category for a string that never named one.
    if (slash <= 0) None else all.find(_.name == normalised.substring(0, slash))
  }
}
