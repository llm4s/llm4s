package org.llm4s.prompt

import org.llm4s.annotation.Experimental

/**
 * A named, reusable prompt: a function from typed parameters to a [[Prompt]].
 *
 * {{{
 * case class TriageInput(ticket: Ticket, email: Untrusted[String])
 *
 * val triage = PromptTemplate[TriageInput] { p =>
 *   prompt"Ticket ${p.ticket} says: ${p.email}"
 * }
 *
 * triage(TriageInput(ticket, Untrusted.text(incomingEmail)))   // a Prompt
 * }}}
 *
 * The parameters are a case class or a named tuple, so a placeholder is a field access: one that names no
 * parameter (`${p.tikcet}`) is a compile error naming it, and a parameter whose type has no [[Promptable]] is one
 * too. Nothing is looked up by a string at run time.
 *
 * @tparam P the parameters.
 */
@Experimental
final class PromptTemplate[-P] private (render: P => Prompt) {

  /** The prompt for `params`. */
  def apply(params: P): Prompt = render(params)
}

/** Builds [[PromptTemplate]]s. */
object PromptTemplate {

  /** The template that renders its parameters with `render`. */
  def apply[P](render: P => Prompt): PromptTemplate[P] = new PromptTemplate(render)
}
