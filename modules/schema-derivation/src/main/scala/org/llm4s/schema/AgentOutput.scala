package org.llm4s.schema

import org.llm4s.agent.AgentBuilder
import org.llm4s.agent.graph.toolloop.OutputFormat

extension (builder: AgentBuilder) {

  /**
   * `AgentBuilder.withOutput` with the schema derived from `O` instead of written by hand:
   * `Agent.builder("extractor", client).withOutputOf[Invoice]` (design 4.26).
   *
   * `O` must be a case class: the agent's answer is a JSON object. Any other derived type, such as an enum, and a
   * schema that cannot be built - a recursion through a hand-written given that derives its type, which
   * `SchemaOf.definition` throws for - are refused by the agent's `build`, with a `Left`.
   *
   * `llm4s-agent` is a `Provided` dependency of this module: add it to your build to use this.
   *
   * @tparam O the answer's type; needs a [[SchemaOf]] (`derives SchemaOf`) and a uPickle `ReadWriter`
   */
  def withOutputOf[O](using schemaOf: SchemaOf[O], codec: upickle.default.ReadWriter[O]): AgentBuilder =
    builder.withOutputFormat(OutputFormat.deferred(schemaOf.definition))
}
