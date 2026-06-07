package org.llm4s.samples.chat.tui

import org.llm4s.samples.chat.tui.ChatTuiModel.Model
import termflow.tui.*
import termflow.tui.TuiPrelude.*
import termflow.tui.widgets

/**
 * Renderer for the chat TUI. Three vertically stacked zones wrapped in
 * `Layout.border` so the transcript reflows when the terminal resizes.
 *
 *   - Top: title + help line
 *   - Center (Fill): `widgets.LogView` over the transcript lines
 *   - Bottom: status row (`widgets.StatusBar`) and prompt input
 *
 * The input is rendered as a separate `InputNode` carried on the
 * `RootNode`; layout doesn't own focused input.
 */
object ChatTuiView:

  /** `LogView` viewport rectangle — used by the update layer's mouse-wheel routing. */
  def transcriptViewport(model: Model): widgets.LogView.Viewport =
    widgets.LogView.Viewport(transcriptOrigin(model), transcriptWidth(model), transcriptHeight(model))

  def transcriptWidth(model: Model): Int =
    math.max(8, math.max(model.width, 40) - 2)

  def transcriptHeight(model: Model): Int =
    math.max(3, math.max(model.height, 12) - 5)

  def transcriptOrigin(@scala.annotation.unused model: Model): Coord =
    Coord(2.x, 4.y)

  def maxScroll(model: Model): Int =
    widgets.LogView.maxScroll(
      transcriptLines(model),
      transcriptWidth(model),
      transcriptHeight(model),
      wrap = true
    )

  /** Flatten the conversation entries into the LogView's line buffer. */
  def transcriptLines(model: Model): Vector[String] =
    if model.entries.isEmpty then Vector("(no messages yet — type below to start)")
    else
      model.entries
        .flatMap { entry =>
          val head = s"${entry.role.label}: ${entry.content}"
          val tool = entry.toolCall.toList.flatMap(renderToolCall)
          Vector(head) ++ tool ++ Vector("")
        }
        .dropRight(1)

  private def renderToolCall(summary: ToolCallSummary): List[String] =
    val args = if summary.args.length > 60 then summary.args.take(57) + "..." else summary.args
    val head = s"  ⚙ tool: ${summary.name}($args)"
    val tail = summary.outcome match {
      case Some(ToolOutcome.Ok(s))  => List(s"  ✓ $s")
      case Some(ToolOutcome.Err(m)) => List(s"  ✗ $m")
      case Some(ToolOutcome.Denied) => List("  ⊘ denied by user")
      case None                     => Nil
    }
    head :: tail

  def view(model: Model): RootNode =
    given Theme = model.theme

    val width  = math.max(model.width, 40)
    val height = math.max(model.height, 12)

    val streamingMarker = model.pending match {
      case PendingState.Streaming(_, _) => " · ● streaming"
      case _                            => ""
    }

    val title = s"termflow ✦ chat — ${model.config.modelName}$streamingMarker"

    val helpLine =
      "↑/↓ scroll · End tail · /help · Ctrl+T theme · Ctrl+C quit"

    val transcript = widgets.LogView(
      lines = transcriptLines(model),
      width = transcriptWidth(model),
      height = transcriptHeight(model),
      scrollOffset = model.scrollOffset,
      at = transcriptOrigin(model),
      wrap = true
    )

    val statusLeft = " " + (model.pending match {
      case PendingState.Idle                       => "ready"
      case PendingState.Streaming(_, _)            => "streaming"
      case PendingState.AwaitingToolApproval(_, _) => "awaiting tool approval"
      case PendingState.ExecutingTool(_, _)        => "running tool"
    }) + " "

    val statusCenter = s"${nonEmpty(model.status, "—")}"
    val statusRight  = if model.autoTail then " auto-tail " else " paused "

    val statusBar = widgets.StatusBar(
      left = statusLeft,
      center = statusCenter,
      right = statusRight,
      width = width,
      at = Coord(1.x, (height - 1).y)
    )

    val rendered = PromptHistory.renderWithPrefix(model.prompt, "> ")

    val titleNode = TextNode(
      1.x,
      1.y,
      List(Text(fixed(title, width), Style(fg = summon[Theme].primary, bold = true)))
    )
    val helpNode = TextNode(
      1.x,
      2.y,
      List(Text(fixed(helpLine, width), Style(fg = summon[Theme].secondary)))
    )

    val overlays: List[Overlay] = model.pending match {
      case PendingState.AwaitingToolApproval(call, _) =>
        List(toolApprovalDialog(call, model.config.workspaceRoot))
      case _ => Nil
    }

    val children: List[VNode] =
      titleNode :: helpNode :: transcript ::: List(statusBar)

    RootNode(
      width = width,
      height = height,
      children = children,
      input = Some(
        InputNode(
          1.x,
          height.y,
          rendered.text,
          Style(fg = summon[Theme].success),
          cursor = rendered.cursorIndex,
          lineWidth = math.max(1, width - 1),
          prefixLength = rendered.prefixLength
        )
      ),
      overlays = overlays
    )

  private def toolApprovalDialog(call: org.llm4s.llmconnect.model.ToolCall, root: java.nio.file.Path)(using
    Theme
  ): Overlay =
    val pathHint = call.arguments.objOpt
      .flatMap(_.get("path"))
      .flatMap(_.strOpt)
      .getOrElse("(unknown path)")
    val sizeHint = ChatTuiTool.probeSize(root, pathHint).map(formatBytes).getOrElse("?")
    val warn     = ChatTuiTool.probeSize(root, pathHint).exists(_ > ChatTuiTool.WarnBytes)
    val prompt =
      if warn then s"""Allow ${call.name} of "$pathHint" ($sizeHint — large)?"""
      else s"""Allow ${call.name} of "$pathHint" ($sizeHint)?"""
    Dialogs.confirm(
      prompt = prompt,
      yesFocused = !warn,
      title = "Tool call",
      yesLabel = "Allow",
      noLabel = "Deny"
    )

  private def fixed(s: String, width: Int): String =
    val w = math.max(0, width)
    if s.length >= w then s.take(w) else s + " " * (w - s.length)

  private def nonEmpty(s: String, fallback: String): String =
    if s == null || s.isEmpty then fallback else s

  private def formatBytes(bytes: Long): String =
    if bytes < 1024L then s"$bytes B"
    else if bytes < 1024L * 1024L then f"${bytes / 1024.0}%.1f KB"
    else f"${bytes / 1024.0 / 1024.0}%.1f MB"
