package org.llm4s.javaapi

import org.llm4s.toolapi.ToolFunction

/**
 * An agent tool defined from Java (#1484), for [[Llm4s.createAgentWithTools]]. Build one from a lambda with
 * [[JTool.builder]], or from the [[Tool]]-annotated methods of an object with [[JTool.fromAnnotated]]:
 *
 * {{{
 * LlmResult<JTool> weather = JTool.builder("weather", "The current weather in a city")
 *     .param("city", String.class, "City name")
 *     .handler(args -> "Sunny in " + args.getString("city"))
 *     .build();
 * LlmResult<List<JTool>> annotated = JTool.fromAnnotated(new WeatherTools());
 * }}}
 *
 * A parameter is a `String`, an `int`, `long`, `short` or `byte` (boxed or not), a `double` or `float`, a `boolean`, or
 * an enum (the model passes a constant's name); its JSON Schema is derived from the type. The model's arguments are read
 * into those Java values before the handler runs, without a JSON library: a missing required argument, or one of the
 * wrong type, is a tool error the model sees, and the handler is not called. So is an exception the handler throws -
 * the agent keeps running.
 *
 * When an agent runs the tool, [[JToolArgs.idempotencyKey]] and [[JToolArgs.toolCallId]] describe the call (#1740): a
 * lambda reads them from its `args`, and an annotated method from a parameter of type [[JToolArgs]], which the model
 * does not see. They are empty outside an agent run.
 */
final class JTool private[javaapi] (token: FacadeToken, private[javaapi] val function: ToolFunction[?, ?]) {

  FacadeToken.check(token, "JTool")

  /** The tool's name, as the model calls it. */
  def name(): String = function.name

  /** What the tool does. */
  def description(): String = function.description

  override def toString: String = s"JTool(${function.name})"
}

object JTool {

  /** A builder for a tool called `name`, which `description` explains to the model. */
  def builder(name: String, description: String): Builder =
    new Builder(FacadeToken.INSTANCE, JTools.Spec(name, description, Vector.empty, None))

  /**
   * A tool for each public [[Tool]]-annotated method of `target`, sorted by name, each calling the method on `target`.
   * A method whose parameter has an unsupported type, or no name (no [[Param]] name, and not compiled with
   * `javac -parameters`), or a `target` with no such method, is a failed result naming it.
   */
  def fromAnnotated(target: Object): LlmResult[java.util.List[JTool]] = JTools.fromAnnotated(target)

  /** A tool under construction. Immutable: every method returns a new builder. */
  final class Builder private[javaapi] (token: FacadeToken, spec: JTools.Spec) {

    FacadeToken.check(token, "JTool.Builder")

    /** A required parameter `name` of `type`, which `description` explains to the model. */
    def param(name: String, `type`: Class[?], description: String): Builder =
      new Builder(FacadeToken.INSTANCE, spec.withParam(JTools.ParamSpec(name, `type`, description, required = true)))

    /** An optional parameter: the model may leave it out, and [[JToolArgs.has]] then says so. */
    def optionalParam(name: String, `type`: Class[?], description: String): Builder =
      new Builder(FacadeToken.INSTANCE, spec.withParam(JTools.ParamSpec(name, `type`, description, required = false)))

    /** What the tool does when called. */
    def handler(handler: JToolHandler): Builder = new Builder(FacadeToken.INSTANCE, spec.withHandler(handler))

    /**
     * The tool; a failed result for a blank name, a missing handler, a parameter of an unsupported type or a
     * parameter declared twice.
     */
    def build(): LlmResult[JTool] = FacadeInternals.result(JTools.build(spec))
  }
}
