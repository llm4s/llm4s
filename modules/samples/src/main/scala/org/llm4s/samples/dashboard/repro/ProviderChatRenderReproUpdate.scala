package org.llm4s.samples.dashboard.repro

import org.llm4s.samples.dashboard.repro.ProviderChatRenderReproApp.App.toMsg
import org.llm4s.samples.dashboard.repro.ProviderChatRenderReproApp.Msg.{
  ConsoleInputError,
  ConsoleInputKey,
  ExitRequested,
  Resize,
  RunCommand,
  ScrollBy,
  ScrollToEnd
}
import org.llm4s.samples.dashboard.repro.ProviderChatRenderReproApp.{ Entry, Model, Msg, Role }
import termflow.tui.Tui.*
import termflow.tui.{ Cmd, KeyDecoder, PromptHistory, RuntimeCtx, Tui }
import termflow.tui.widgets

import scala.annotation.unused

private def ProviderChatRenderReproUpdate(model: Model, msg: Msg, @unused ctx: RuntimeCtx[Msg]) =
  msg match
    case Resize(width, height) =>
      retailIfFollowing(model.copy(terminalWidth = width, terminalHeight = height)).tui

    case ConsoleInputError(error) =>
      model.copy(status = s"input error: ${Option(error.getMessage).getOrElse("unknown")}").tui

    case ExitRequested =>
      Tui(model, Cmd.Exit)

    case RunCommand(command) =>
      handleCommand(model, command)

    case ScrollBy(delta) =>
      scrollBy(model, delta).tui

    case ScrollToEnd =>
      val mx = transcriptMaxScroll(model)
      model.copy(scrollOffset = mx, autoTail = true).tui

    case ConsoleInputKey(key) =>
      handleConsoleInputKey(model, key)

private def handleConsoleInputKey(model: Model, key: KeyDecoder.InputKey): Tui[Model, Msg] =
  val capacity = transcriptHeightFor(model)
  key match
    case KeyDecoder.InputKey.PageUp =>
      scrollBy(model, -capacity).tui
    case KeyDecoder.InputKey.PageDown =>
      scrollBy(model, capacity).tui
    case KeyDecoder.InputKey.End =>
      val mx = transcriptMaxScroll(model)
      model.copy(scrollOffset = mx, autoTail = true).tui
    case KeyDecoder.InputKey.Mouse(ev) =>
      // Modern (termflow 0.4): wheel scrolls only when the cursor is over the
      // transcript viewport rect.
      widgets.LogView.scrollDelta(ev, transcriptViewport(model)) match
        case Some(d) => scrollBy(model, d).tui
        case None    => model.tui
    case _ =>
      val (nextPrompt, maybeCmd) = PromptHistory.handleKey[Msg](model.prompt, key)(toMsg)
      maybeCmd match
        case Some(cmd) => Tui(model.copy(prompt = nextPrompt), cmd)
        case None      => model.copy(prompt = nextPrompt).tui

private def retailIfFollowing(model: Model): Model =
  val mx = transcriptMaxScroll(model)
  if model.autoTail then model.copy(scrollOffset = mx)
  else model.copy(scrollOffset = math.min(model.scrollOffset, mx))

private def scrollBy(model: Model, delta: Int): Model =
  val mx   = transcriptMaxScroll(model)
  val next = math.max(0, math.min(mx, model.scrollOffset + delta))
  model.copy(scrollOffset = next, autoTail = next >= mx)

private def handleCommand(model: Model, command: String): Tui[Model, Msg] =
  command.trim match
    case "help" =>
      model.copy(status = "Commands: seed, clear, help, quit. Any other text appends a fake user+assistant turn.").tui
    case "seed" =>
      val nextBurst   = seedBurst(model.seedRound)
      val nextEntries = model.entries ++ nextBurst
      retailIfFollowing(
        model.copy(
          entries = nextEntries,
          seedRound = model.seedRound + 1,
          status = s"Added ${nextBurst.length} seeded transcript entries in round ${model.seedRound + 1}."
        )
      ).tui
    case "clear" =>
      model
        .copy(entries = Vector.empty, seedRound = 0, scrollOffset = 0, autoTail = true, status = "Transcript cleared.")
        .tui
    case other =>
      val nextEntries = model.entries ++ Vector(
        Entry(Role.User, other),
        Entry(
          Role.Assistant,
          s"This is a synthetic assistant reply for render debugging. It intentionally wraps across multiple lines and grows the transcript without involving any llm call. Echoed input: $other"
        )
      )
      retailIfFollowing(
        model.copy(
          entries = nextEntries,
          status = s"Added a fake turn. Transcript entries=${nextEntries.length}."
        )
      ).tui

private val seedBursts: Vector[Vector[Entry]] =
  Vector(
    Vector(
      Entry(Role.User, "ok"),
      Entry(Role.Assistant, "Short reply."),
      Entry(Role.User, "Give me one medium answer about storms in three sentences."),
      Entry(
        Role.Assistant,
        "Storms gather when warm and cold air masses collide, creating unstable rising currents. Pressure shifts and moisture feed clouds until rain bands organize. In a terminal view, this kind of medium response is useful because it wraps a little without dominating the panel."
      ),
      Entry(Role.User, "Now something much longer about clocks, thunder, and lanterns."),
      Entry(
        Role.Assistant,
        "The watchmaker kept three lanterns in the workshop window, one for clear weather, one for rain, and one for the kind of thunderstorm that made the brass tools hum before the first strike arrived. Every apprentice knew the ritual: close the shutters halfway, cover the smallest gears, and count the seconds between lightning and sound. This synthetic paragraph is deliberately long so it crosses multiple wrap boundaries and makes the transcript viewport behave more like a real conversation where answer lengths are uneven and not neatly predictable."
      )
    ),
    Vector(
      Entry(Role.User, "Tell me a story about a watchmaker in a storm."),
      Entry(
        Role.Assistant,
        "A long synthetic answer follows. It contains enough wrapped text to stress transcript rendering and repaint behavior in the sample app without involving network calls or provider configuration."
      ),
      Entry(Role.User, "Now make it a logic puzzle."),
      Entry(
        Role.Assistant,
        "Another long synthetic answer follows. It intentionally adds more wrapped content so the visible transcript must clip older lines while the prompt remains active at the bottom of the screen."
      ),
      Entry(Role.User, "Thanks, one more variation."),
      Entry(
        Role.Assistant,
        "A third long synthetic answer continues the same pattern for redraw testing. The goal is not semantic quality but predictable, repeatable transcript growth."
      )
    ),
    Vector(
      Entry(Role.User, "One sentence only."),
      Entry(Role.Assistant, "Tiny synthetic reply for short-line variance."),
      Entry(Role.User, "Explain repaint artifacts in a medium paragraph."),
      Entry(
        Role.Assistant,
        "Repaint artifacts often show up when the same region is redrawn with slightly different node shapes from frame to frame. Even if the content is correct, small differences in line count, padding, or cursor placement can make terminal output feel unstable."
      ),
      Entry(Role.User, "Now add a very long answer with awkward wrapping near the panel width."),
      Entry(
        Role.Assistant,
        "This extra-long synthetic block is designed to create wrapping that lands near the right edge of the transcript area, where clipped words, narrow gaps, and mixed sentence lengths can expose redraw issues that a more regular transcript would hide. It also varies rhythm by mixing short clauses with longer ones, which helps us avoid a steady-state pattern where every batch looks the same to the renderer."
      )
    )
  )

private def seedBurst(round: Int): Vector[Entry] =
  val offset = math.floorMod(round, seedBursts.length)
  (seedBursts.drop(offset) ++ seedBursts.take(offset)).flatten
