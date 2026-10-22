package org.llm4s.kotlin

import kotlinx.coroutines.runBlocking
import org.llm4s.javaapi.JTool
import org.llm4s.javaapi.JToolArgs
import org.llm4s.javaapi.JToolHandler
import kotlin.reflect.KClass

/**
 * Marks the receivers of the Kotlin DSL ([tool], [tools], [completionOptions], the client blocks of [Llm4s]), so a
 * block nested in another cannot call the outer one's members by accident.
 */
@DslMarker
annotation class Llm4sDsl

/**
 * Marks a reified inline function for JaCoCo, which leaves out a method annotated with an annotation whose name contains
 * `Generated`. Such a function's own body never runs - every call is inlined where it is written, and counted there - so
 * without the mark the module's 100% line-coverage rule (the 'Kotlin API' CI job) could not pass. Keep each one a
 * single call to a non-inline function that tests cover.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
internal annotation class ReifiedInlineGenerated

/**
 * An agent tool declared in Kotlin (#1495), for [Llm4s.createAgent]: a [JTool] of the Java facade, built with
 * `JTool.builder`, so it behaves exactly as one built from Java.
 *
 * ```kotlin
 * val add = tool("add", "Adds two numbers") {
 *     param<Long>("a", "First")
 *     param<Long>("b", "Second")
 *     handler { args -> args.get<Long>("a") + args.get<Long>("b") }
 * }
 * ```
 *
 * Throws [LLMException] when the tool cannot be built: a blank name, no handler, a parameter of an unsupported type or
 * one declared twice.
 */
fun tool(name: String, description: String, configure: ToolSpec.() -> Unit): JTool =
    ToolSpec(name, description).apply(configure).build()

/**
 * Several tools, each declared with [ToolsSpec.tool] or added with [ToolsSpec.add], in order.
 *
 * ```kotlin
 * val agent = Llm4s.createAgent(client, tools {
 *     tool("weather", "The current weather in a city") {
 *         param<String>("city", "City name")
 *         handler { args -> "Sunny in ${args.get<String>("city")}" }
 *     }
 * })
 * ```
 */
fun tools(configure: ToolsSpec.() -> Unit): List<JTool> = ToolsSpec().apply(configure).tools.toList()

/** The receiver of [tools]. */
@Llm4sDsl
class ToolsSpec internal constructor() {
    internal val tools = mutableListOf<JTool>()

    /** Declares a tool, as the top-level [org.llm4s.kotlin.tool] does, and adds it. */
    fun tool(name: String, description: String, configure: ToolSpec.() -> Unit) {
        tools += org.llm4s.kotlin.tool(name, description, configure)
    }

    /** Adds a tool built elsewhere: with [org.llm4s.kotlin.tool], `JTool.builder` or `JTool.fromAnnotated`. */
    fun add(tool: JTool) {
        tools += tool
    }
}

/**
 * The receiver of [tool]: its parameters and its handler.
 *
 * A parameter's type is a `String`, an `Int`, `Long`, `Short` or `Byte`, a `Double` or `Float`, a `Boolean`, or an
 * enum (the model passes a constant's name), as for `JTool.builder`; its JSON Schema is derived from the type, and the
 * model's arguments are read into those values before the handler runs. A missing or mistyped argument is a tool error
 * the model sees, and the handler is not called.
 */
@Llm4sDsl
class ToolSpec internal constructor(name: String, description: String) {
    private var builder: JTool.Builder = JTool.builder(name, description)

    // Overloads rather than a default description: a default argument adds a `$default` method JaCoCo would count.

    /** A required parameter [name] of type [T], which [description] explains to the model. */
    @ReifiedInlineGenerated
    inline fun <reified T : Any> param(name: String, description: String) = param(name, T::class, description)

    /** A required parameter [name] of type [T], with no description. */
    @ReifiedInlineGenerated
    inline fun <reified T : Any> param(name: String) = param(name, T::class, "")

    /** An optional parameter: the model may leave it out, and [ToolArgs.has] then says so. */
    @ReifiedInlineGenerated
    inline fun <reified T : Any> optionalParam(name: String, description: String) =
        optionalParam(name, T::class, description)

    /** An optional parameter with no description. */
    @ReifiedInlineGenerated
    inline fun <reified T : Any> optionalParam(name: String) = optionalParam(name, T::class, "")

    /** A required parameter [name] of [type]; the reified [param] is the usual way to write it. */
    fun <T : Any> param(name: String, type: KClass<T>, description: String = "") {
        builder = builder.param(name, type.javaObjectType, description)
    }

    /** An optional parameter [name] of [type]; the reified [optionalParam] is the usual way to write it. */
    fun <T : Any> optionalParam(name: String, type: KClass<T>, description: String = "") {
        builder = builder.optionalParam(name, type.javaObjectType, description)
    }

    /**
     * What the tool does when called: [handle] receives the call's [ToolArgs] and returns the result the model sees. A
     * `String` is passed as it is; a number, a boolean, an enum (its name), a `Map` or a `Collection` of these become
     * JSON; anything else is passed as its `toString()`. An exception it throws does not fail the agent: the model
     * receives a tool error with its message and may try again.
     *
     * [handle] is a suspend function. The agent runs a tool on its own thread, which waits for the handler in a
     * coroutine of its own ([runBlocking]); when the coroutine that runs the turn is cancelled - [AgentKt.run]'s caller,
     * a collector of [AgentKt.stream] - the agent cancels the turn, which interrupts that thread and so cancels the
     * handler: a suspended `delay`, `withContext` or other cancellable call throws `CancellationException`.
     */
    fun handler(handle: suspend (ToolArgs) -> Any?) {
        builder = builder.handler(JToolHandler { args -> runBlocking { handle(ToolArgs(args)) } })
    }

    internal fun build(): JTool = builder.build().unwrap("Invalid tool", cancellation = false)
}

/**
 * The arguments of one tool call, as [ToolSpec.handler] receives them, over the Java facade's [JToolArgs]: each
 * declared parameter the model passed, read with [get] as the type it was declared with, and the call being run.
 *
 * When an agent runs the tool, [toolCallId] and [idempotencyKey] describe the call (#1740). The key is the same in every
 * run of one call - a retry, an approval, an answered question, a `recover` - so a tool with an external side effect
 * passes it to the system it calls, which then does the work once. Both are `null` when the tool runs outside an agent.
 */
class ToolArgs internal constructor(
    /** The Java facade's arguments, for what this class does not wrap. */
    val java: JToolArgs,
) {
    /** The id the model gave this call, or `null` outside an agent run. */
    val toolCallId: String? get() = java.toolCallId().orElse(null)

    /**
     * The call's idempotency key, the same in every run of this call and different for every other call, or `null`
     * outside an agent run. Pass it to the system a side effect reaches (an `Idempotency-Key` header, a unique column).
     */
    val idempotencyKey: String? get() = java.idempotencyKey().orElse(null)

    /** Whether the model passed [name]: always for a required parameter, and for an optional one it did not leave out. */
    fun has(name: String): Boolean = java.has(name)

    /**
     * The argument [name] as [T], the type it was declared with. Throws [NoSuchElementException] when the model left an
     * optional parameter out (read it with [getOrNull]), and [ClassCastException] for a type it was not declared with.
     */
    @ReifiedInlineGenerated
    inline fun <reified T : Any> get(name: String): T = get(name, T::class)

    /** The argument [name] as [T], or `null` when the model left it out. */
    @ReifiedInlineGenerated
    inline fun <reified T : Any> getOrNull(name: String): T? = getOrNull(name, T::class)

    /** The argument [name] as [type]; the reified [get] is the usual way to write it. */
    fun <T : Any> get(name: String, type: KClass<T>): T =
        getOrNull(name, type) ?: throw NoSuchElementException("the model did not pass '$name'")

    /** The argument [name] as [type], or `null`; the reified [getOrNull] is the usual way to write it. */
    fun <T : Any> getOrNull(name: String, type: KClass<T>): T? = java.get(name, type.javaObjectType)

    /** Every argument the model passed, by name. */
    fun asMap(): Map<String, Any> = java.asMap()

    override fun toString(): String = "ToolArgs(${java.asMap()})"
}
