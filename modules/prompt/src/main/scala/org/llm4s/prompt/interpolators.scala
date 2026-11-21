package org.llm4s.prompt

// `transparent` expands the macro while the call site is type-checked, so a `given Promptable` it finds counts as used
// under `-Wunused`; expanded later, the given would be reported as an unused local definition.
extension (inline sc: StringContext) {

  /**
   * A user prompt: `prompt"Classify ticket ${ticket.id}: $email"`.
   *
   * Each value is rendered by its [[Promptable]], found at compile time: a value whose type has none is a compile
   * error naming the type. An [[Untrusted]] value is fenced. Escapes (`\n`, `\t`, ...) are processed as `s"..."`
   * does; [[Prompt.stripMargin]] strips a `|` margin from the template's own text.
   */
  transparent inline def prompt(inline args: Any*): Prompt = ${ PromptMacros.interpolate('sc, 'args, system = false) }

  /** A system prompt, built as [[prompt]] builds a user prompt: `system"You are a support triage assistant."`. */
  transparent inline def system(inline args: Any*): Prompt = ${ PromptMacros.interpolate('sc, 'args, system = true) }
}
