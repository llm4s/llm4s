package org.llm4s.media

import java.net.URI

/**
 * Where the bytes of a piece of media are: at a URL the consumer fetches, inline, or in a file
 * already uploaded to a provider.
 *
 * Pure data, like the rest of `llm4s-media`: nothing here fetches, reads or checks anything.
 * `llm4s-core`'s `ContentPart` builds parts from a source and checks it there (an inline source's
 * size, a URL's scheme), because a check returns core's `Result`.
 */
sealed trait MediaSource extends Product with Serializable {

  /** What kind of source this is, for errors and logs: `url`, `inline` or `provider file <provider>`. */
  def kind: String
}

object MediaSource {

  /** Media at `uri`, which the consumer (a provider, usually) fetches. */
  final case class Url(uri: URI) extends MediaSource {
    def kind: String = "url"
  }

  /**
   * The media's bytes themselves. Two are equal when their bytes are, and `toString` prints the
   * length, never the bytes.
   */
  final case class Inline(bytes: IArray[Byte]) extends MediaSource {
    def kind: String = "inline"

    /** The number of bytes. */
    def length: Int = bytes.length

    override def equals(other: Any): Boolean = other match {
      case that: Inline => java.util.Arrays.equals(bytes.asInstanceOf[Array[Byte]], that.bytes.asInstanceOf[Array[Byte]])
      case _            => false
    }

    override def hashCode: Int = java.util.Arrays.hashCode(bytes.asInstanceOf[Array[Byte]])

    override def toString: String = s"Inline($length bytes)"
  }

  /** A file already uploaded to the provider whose id is `provider` (`openai`, `gemini`), by that provider's `id`. */
  final case class ProviderFile(provider: String, id: String) extends MediaSource {
    def kind: String = s"provider file $provider"
  }
}
