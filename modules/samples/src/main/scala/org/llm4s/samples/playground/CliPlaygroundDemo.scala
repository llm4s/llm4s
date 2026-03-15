package org.llm4s.samples.playground

import org.llm4s.trace.AnsiColors
import org.llm4s.trace.AnsiColors._

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * CLI Playground Demo for new users and workshop attendees.
 *
 * Runs entirely offline — no API keys or network access required.
 * It replays pre-recorded scenarios with full ANSI-coloured console
 * output so that workshop participants can explore what LLM4S looks
 * like in action before wiring up a real provider.
 *
 * After the pre-recorded scenarios, an interactive prompt loop lets
 * you type your own queries and see simulated traced responses in real
 * time — no provider needed.
 *
 * == How to run ==
 * {{{
 * sbt "samples/runMain org.llm4s.samples.playground.CliPlaygroundDemo"
 * }}}
 *
 * == Scenarios ==
 *   1. Simple text completion
 *   2. Multi-turn conversation
 *   3. Tool calling
 *   4. Agent pipeline
 *   5. Error handling and recovery
 *   6. Token usage awareness
 *   7. Interactive prompt loop (try it yourself!)
 *
 * == Connecting to a real provider ==
 * Once you have an API key, swap `CliPlaygroundDemo` for
 * `BasicLLMCallingExample` and set the environment variables shown at
 * the end of this demo.
 */
object CliPlaygroundDemo extends App {

  //  helpers

  private def header(title: String): Unit = {
    println()
    println(boldColor(AnsiColors.separator(), CYAN))
    println(boldColor(s"  $title", CYAN))
    println(boldColor(AnsiColors.separator(), CYAN))
  }

  private def step(label: String): Unit =
    println(s"${GRAY}   $label$RESET")

  private def userSays(text: String): Unit =
    println(s"  ${boldColor("You:", GREEN)}  $text")

  private def llmSays(text: String): Unit =
    println(s"  ${boldColor("LLM:", BLUE)}  $text")

  private def toolCall(name: String, args: String): Unit =
    println(s"  ${boldColor("Tool call:", YELLOW)} $name($args)")

  private def toolResult(output: String): Unit =
    println(s"  ${boldColor("Tool result:", MAGENTA)} $output")

  private def errorLine(msg: String): Unit =
    println(s"  ${boldColor("Error:", RED)} $msg")

  private def info(msg: String): Unit =
    println(s"  ${GRAY}$msg$RESET")

  private def pause(): Unit = Thread.sleep(120)

  /** Returns the date of the next occurrence of the given weekday as an ISO string. */
  private def nextWeekday(day: DayOfWeek): String =
    LocalDate.now().`with`(TemporalAdjusters.next(day)).toString

  //  welcome

  private def printWelcome(): Unit = {
    println()
    println(boldColor(AnsiColors.separator('*'), CYAN))
    println(boldColor("  Welcome to the LLM4S Playground!", CYAN))
    println(boldColor("  A hands-on demo for new users and workshop attendees", CYAN))
    println(boldColor(AnsiColors.separator('*'), CYAN))
    println()
    println(s"  ${GREEN}This demo runs offline — no API key is needed.$RESET")
    println(s"  ${GRAY}Follow along to see what LLM4S can do, then connect a real provider.$RESET")
  }

  //  scenario 1: simple completion

  private def scenario1(): Unit = {
    header("Scenario 1 — Simple Text Completion")
    step("Sending a single message to the LLM")
    userSays("Explain what a monad is in one sentence.")
    pause()
    llmSays("A monad is a design pattern that chains together a sequence of")
    llmSays("computations while automatically handling context such as")
    llmSays("optionality, failure, or side-effects.")
    info("completion id: cmpl-demo-001  model: gpt-4o  tokens: 42")
  }

  //  scenario 2: multi-turn conversation

  private def scenario2(): Unit = {
    header("Scenario 2 — Multi-Turn Conversation")
    step("Building a conversation history across several messages")
    userSays("My name is Alice. What is 2 + 2?")
    pause()
    llmSays("Hi Alice! 2 + 2 equals 4.")
    userSays("What was my name again?")
    pause()
    llmSays("Your name is Alice — you told me just a moment ago!")
    info("LLM4S keeps the full message history so the model remembers context.")
  }

  //  scenario 3: tool calling

  private def scenario3(): Unit = {
    header("Scenario 3 — Tool Calling")
    step("The LLM decides to call a registered tool")
    userSays("What is the current weather in London?")
    pause()
    toolCall("get_weather", """{"city": "London", "unit": "celsius"}""")
    pause()
    toolResult("""{"temperature": 14, "condition": "Partly cloudy", "humidity": "72%"}""")
    pause()
    llmSays("The current weather in London is 14 C and partly cloudy, with")
    llmSays("72 % humidity.")
    info("Tool calls are structured JSON — LLM4S validates arguments automatically.")
  }

  //  scenario 4: agent pipeline

  private def scenario4(): Unit = {
    header("Scenario 4 — Agent Pipeline")
    step("An agent breaks a complex task into tool calls and reasoning steps")
    userSays("Summarise the three most-viewed Wikipedia articles from last week.")
    pause()
    toolCall("search_wikipedia_trending", """{"period": "last_week", "limit": 3}""")
    pause()
    toolResult("""["ChatGPT", "Academy Awards", "FIFA World Cup"]""")
    pause()
    toolCall("fetch_wikipedia_summary", """{"title": "ChatGPT"}""")
    pause()
    toolResult(""""ChatGPT is an AI chatbot developed by OpenAI """")
    pause()
    toolCall("fetch_wikipedia_summary", """{"title": "Academy Awards"}""")
    pause()
    toolResult(""""The Academy Awards, or Oscars, are presented by AMPAS """")
    pause()
    toolCall("fetch_wikipedia_summary", """{"title": "FIFA World Cup"}""")
    pause()
    toolResult(""""The FIFA World Cup is an international association football tournament """")
    pause()
    llmSays("Here are last week's three most-viewed articles:")
    llmSays("1. ChatGPT — OpenAI's conversational AI assistant.")
    llmSays("2. Academy Awards — the prestigious Oscars ceremony.")
    llmSays("3. FIFA World Cup — the global football championship.")
    info("The agent orchestrated 4 tool calls automatically.")
  }

  //  scenario 5: error handling

  private def scenario5(): Unit = {
    header("Scenario 5 — Error Handling and Recovery")
    step("Demonstrating graceful recovery when a tool call fails")
    userSays("Book a flight from Zurich to Tokyo for next Monday.")
    pause()
    val monday  = nextWeekday(DayOfWeek.MONDAY)
    val tuesday = nextWeekday(DayOfWeek.TUESDAY)
    toolCall("book_flight", s"""{"origin": "ZRH", "destination": "TYO", "date": "$monday"}""")
    pause()
    errorLine(s"ToolExecutionError: No seats available on ZRHTYO for $monday")
    pause()
    toolCall("book_flight", s"""{"origin": "ZRH", "destination": "TYO", "date": "$tuesday"}""")
    pause()
    toolResult("""{"booking_id": "BA-98321", "seat": "23A", "price": "CHF 1 420"}""")
    pause()
    llmSays("Monday was fully booked, so I moved the reservation to Tuesday.")
    llmSays("Your booking ID is BA-98321, seat 23 A, at CHF 1 420.")
    info("LLM4S surfaces ToolCallError details so the LLM can retry or explain.")
  }

  //  scenario 6: token usage

  private def scenario6(): Unit = {
    header("Scenario 6 — Token Usage Awareness")
    step("Inspecting token counts returned with each completion")
    userSays("Write a haiku about functional programming.")
    pause()
    llmSays("Pure functions compose,")
    llmSays("Side-effects wrapped in monads —")
    llmSays("Referential calm.")
    println()
    println(s"  ${boldColor("Token usage", YELLOW)}")
    println(s"    prompt tokens:     ${colorize("18", GREEN)}")
    println(s"    completion tokens: ${colorize("21", GREEN)}")
    println(s"    total tokens:      ${colorize("39", CYAN)}")
    info("Track cumulative usage across requests to stay within budget.")
  }

  //  scenario 7: interactive prompt loop

  private def scenario7(): Unit = {
    header("Scenario 7 — Interactive Playground (try it yourself!)")
    step("Type your own prompt and see a simulated traced response")
    println(s"  ${GRAY}(Press Enter with no text or type 'exit' to quit the loop)$RESET")
    var continue = true
    while (continue) {
      print(s"\n  ${boldColor("You:", GREEN)}  ")
      val raw   = scala.io.StdIn.readLine()
      val input = if (raw == null) "" else raw.trim
      if (input.isEmpty || input.equalsIgnoreCase("exit")) {
        continue = false
      } else {
        println(s"  ${GRAY}   Sending to offline playground $RESET")
        pause()
        pause()
        llmSays(s"[Offline simulation] You asked: \"$input\"")
        llmSays("In a live session your provider would return a real response here.")
        info(s"completion id: cmpl-live  model: (your provider)  tokens: ~${input.length / 4 + 10}")
      }
    }
    info("Interactive session ended.")
  }

  //  goodbye

  private def printGoodbye(): Unit = {
    println()
    println(boldColor(AnsiColors.separator('*'), GREEN))
    println(boldColor("  Demo complete!", GREEN))
    println(boldColor(AnsiColors.separator('*'), GREEN))
    println()
    println(s"  ${GREEN}Next steps:$RESET")
    println(s"  ${GRAY}1. Set your provider:  export LLM_MODEL=openai/gpt-4o$RESET")
    println(s"  ${GRAY}2. Set your API key:   export OPENAI_API_KEY=sk-...$RESET")
    println(s"  ${GRAY}3. Run a real example:$RESET")
    println(s"  ${CYAN}     sbt \"samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample\"$RESET")
    println()
    println(s"  ${GRAY}Full documentation: https://llm4s.org$RESET")
    println()
  }

  //  entry point

  printWelcome()
  scenario1()
  scenario2()
  scenario3()
  scenario4()
  scenario5()
  scenario6()
  scenario7()
  printGoodbye()
}
