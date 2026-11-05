package org.llm4s.samples.kafka

import org.llm4s.schema.{ description, SchemaOf }
import upickle.default.ReadWriter

/** A support ticket: the JSON value of a record on the input topic. */
final case class Ticket(id: String, text: String) derives ReadWriter

@description("The team that should handle a support ticket")
enum Category derives SchemaOf, ReadWriter {
  case Billing, Technical, Account, Other
}

@description("How urgently a support ticket needs an answer")
enum Priority derives SchemaOf, ReadWriter {
  case Low, Medium, High
}

/** What the model extracts from one ticket, with the schema derived from the type (`derives SchemaOf`). */
@description("The classification of one support ticket")
final case class TicketLabels(
  category: Category,
  priority: Priority,
  @description("The ticket in one sentence") summary: String
) derives SchemaOf,
      ReadWriter

/** The value written to the enriched topic: the ticket and its labels. */
final case class EnrichedTicket(id: String, text: String, labels: TicketLabels) derives ReadWriter

/**
 * The value written to the dead-letter topic: the input record as it was, where it came from, and why it failed.
 *
 * @param reason `decode` when the value is not a [[Ticket]], `llm` when the model call failed with an error that
 *               retrying does not fix
 * @param error  the error's type and message
 */
final case class DeadLetter(
  topic: String,
  partition: Int,
  offset: Long,
  key: String,
  value: String,
  reason: String,
  error: String
) derives ReadWriter
