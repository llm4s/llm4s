package org.llm4s.testkit

import org.llm4s.llmconnect.model.{
  AssistantMessage,
  ContentPart,
  Conversation,
  Message,
  MessageRole,
  ToolCall,
  ToolMessage,
  UserMessage
}
import org.llm4s.media.{ MediaSource, MediaType }

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * One content part a chat client may or may not send, in the role it is sent in. A provider
 * module's spec names the cases its client supports to `ProviderModuleChecks.assertContentParts`,
 * which checks that each of those reaches the server and that every other case is refused with an
 * [[org.llm4s.error.UnsupportedContentPartError]] before anything is sent.
 */
enum ContentPartCase(val role: MessageRole):

  /** An image, by URL, in a user message. */
  case UserImageUrl extends ContentPartCase(MessageRole.User)

  /** An image, inline, in a user message. */
  case UserImageInline extends ContentPartCase(MessageRole.User)

  /** An image uploaded to the client's own provider, in a user message. */
  case UserImageFile extends ContentPartCase(MessageRole.User)

  /** A PDF, by URL, in a user message. */
  case UserPdfUrl extends ContentPartCase(MessageRole.User)

  /** A PDF, inline, in a user message. */
  case UserPdfInline extends ContentPartCase(MessageRole.User)

  /** A PDF uploaded to the client's own provider, in a user message. */
  case UserPdfFile extends ContentPartCase(MessageRole.User)

  /** A plain-text document, inline, in a user message. */
  case UserTextDocumentInline extends ContentPartCase(MessageRole.User)

  /** An image, inline, in a tool result. */
  case ToolImageInline extends ContentPartCase(MessageRole.Tool)

  /** A PDF, inline, in a tool result. */
  case ToolPdfInline extends ContentPartCase(MessageRole.Tool)

  /** The case's part, with `providerId` as the owner of a provider file. */
  def part(providerId: String): ContentPart =
    import ContentPartContract.*
    this match
      case UserImageUrl    => built(ContentPart.image(MediaSource.Url(ImageUrl), MediaType.Png))
      case UserImageInline => built(ContentPart.image(MediaSource.Inline(ImageBytes), MediaType.Png))
      case UserImageFile   => built(ContentPart.image(MediaSource.ProviderFile(providerId, ImageFileId), MediaType.Png))
      case UserPdfUrl      => built(ContentPart.document(MediaSource.Url(PdfUrl), MediaType.Pdf))
      case UserPdfInline   => built(ContentPart.document(MediaSource.Inline(PdfBytes), MediaType.Pdf))
      case UserPdfFile => built(ContentPart.document(MediaSource.ProviderFile(providerId, PdfFileId), MediaType.Pdf))
      case UserTextDocumentInline => built(ContentPart.document(MediaSource.Inline(TextBytes), MediaType.PlainText))
      case ToolImageInline        => built(ContentPart.image(MediaSource.Inline(ImageBytes), MediaType.Png))
      case ToolPdfInline          => built(ContentPart.document(MediaSource.Inline(PdfBytes), MediaType.Pdf))

  /**
   * What a request that carries the part contains, any one of: the URL, the file id, the bytes
   * in base64 - or, for the text document, its text, which a provider may send decoded.
   */
  def markers: Seq[String] =
    import ContentPartContract.*
    this match
      case UserImageUrl                      => Seq(ImageUrl.toString)
      case UserImageInline | ToolImageInline => Seq(base64(ImageBytes))
      case UserImageFile                     => Seq(ImageFileId)
      case UserPdfUrl                        => Seq(PdfUrl.toString)
      case UserPdfInline | ToolPdfInline     => Seq(base64(PdfBytes))
      case UserPdfFile                       => Seq(PdfFileId)
      case UserTextDocumentInline            => Seq(base64(TextBytes), TextDocument)

  /** A conversation that carries the part, with `providerId` as the owner of a provider file. */
  def conversation(providerId: String): Conversation =
    ContentPartContract.carrying(role, part(providerId))

/**
 * The content-part contract: a chat client sends every part its provider takes, and fails the
 * request with an [[org.llm4s.error.UnsupportedContentPartError]] naming any other, before
 * anything is sent - never dropping a part or sending it as text. `ProviderModuleChecks.assertContentParts`
 * runs it.
 */
object ContentPartContract:

  /** The text of the plain-text document case. */
  val TextDocument: String = "llm4s content-part contract: a plain text document"

  private[testkit] val ImageUrl: URI = URI.create("https://llm4s.example/contract/cat.png")
  private[testkit] val PdfUrl: URI   = URI.create("https://llm4s.example/contract/report.pdf")
  private[testkit] val ImageFileId   = "file-contract-image"
  private[testkit] val PdfFileId     = "file-contract-pdf"
  private[testkit] val ImageBytes    = IArray[Byte](-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
  private[testkit] val PdfBytes      = IArray.from("%PDF-1.4 llm4s contract".getBytes(StandardCharsets.US_ASCII))
  private[testkit] val TextBytes     = IArray.from(TextDocument.getBytes(StandardCharsets.UTF_8))
  private[testkit] def base64(bytes: IArray[Byte]): String =
    Base64.getEncoder.encodeToString(bytes.asInstanceOf[Array[Byte]])

  /** Two text parts every client must send, in a user message and in a tool result. */
  val FirstText: String  = "first text part"
  val SecondText: String = "second text part"

  /** A conversation whose user message, or tool result, has two text parts. */
  def multiText(role: MessageRole): Conversation =
    carrying(role, ContentPart.Text(SecondText))

  /** An image uploaded to a provider no client is: every client must refuse it. */
  val foreignFile: Conversation =
    carrying(
      MessageRole.User,
      built(ContentPart.image(MediaSource.ProviderFile("provider-testkit-foreign", "file-foreign"), MediaType.Png))
    )

  private[testkit] def built(part: Either[?, ContentPart]): ContentPart =
    part.fold(e => throw new IllegalStateException(s"contract part refused: $e"), identity)

  /** `part` after a text part, in a user message or in the result of the conversation's one tool call. */
  private[testkit] def carrying(role: MessageRole, part: ContentPart): Conversation =
    val parts = Vector(ContentPart.Text(FirstText), part)
    val messages: Seq[Message] =
      if role == MessageRole.Tool then
        Seq(
          UserMessage("Fetch it."),
          AssistantMessage(contentOpt = None, toolCalls = Seq(ToolCall("call_parts", "fetch", ujson.Obj()))),
          ToolMessage(parts, "call_parts")
        )
      else Seq(UserMessage(parts))
    Conversation(messages)
