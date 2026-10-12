package org.llm4s.kotlin

import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Kotlin API's signatures as the JVM sees them, as `JavaInteropSpec` checks the Java facade's (#1666): no Scala
 * library or `ujson` type in a public method or constructor. Kotlin's `internal` is public in bytecode, so the
 * `internal` constructors of [AgentKt] and [LLMClientKt] are checked too: they take only facade types.
 */
class JvmSurfaceTest {

    private val classes: List<Class<*>> = listOf(
        Llm4s::class.java,
        AgentKt::class.java,
        AgentKt.Companion::class.java,
        LLMClientKt::class.java,
        AgentStreamItem::class.java,
        AgentStreamItem.Event::class.java,
        AgentStreamItem.Done::class.java,
        LLMException::class.java,
    )

    private fun mentioned(t: Type): List<Class<*>> = when (t) {
        is Class<*> -> if (t.isArray) mentioned(t.componentType) else listOf(t)
        is ParameterizedType -> mentioned(t.rawType) + t.actualTypeArguments.flatMap { mentioned(it) }
        is WildcardType -> (t.upperBounds + t.lowerBounds).flatMap { mentioned(it) }
        is GenericArrayType -> mentioned(t.genericComponentType)
        else -> emptyList()
    }

    @Test
    fun `no public method or constructor of the Kotlin API takes or returns a Scala type`() {
        val offenders = classes.flatMap { cls ->
            val methods = cls.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.map {
                "${cls.simpleName}.${it.name}" to (listOf(it.genericReturnType) + it.genericParameterTypes)
            }
            val constructors = cls.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }.map {
                "${cls.simpleName}.<init>" to it.genericParameterTypes.toList()
            }
            (methods + constructors).flatMap { (name, types) ->
                types.flatMap { mentioned(it) }
                    .filter { it.name.startsWith("scala.") || it.name.startsWith("ujson.") }
                    .map { "$name: ${it.name}" }
            }
        }
        assertEquals(emptyList(), offenders)
    }
}
