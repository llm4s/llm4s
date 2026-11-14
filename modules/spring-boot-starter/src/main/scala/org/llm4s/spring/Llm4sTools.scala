package org.llm4s.spring

import org.llm4s.javaapi.{ JTool, Tool }
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.util.ClassUtils

import scala.jdk.CollectionConverters.*

/**
 * The agent tools the application's beans declare (#2279): each public method annotated with llm4s's `@Tool`
 * (`org.llm4s.javaapi.Tool`) on a singleton bean, read as `JTool.fromAnnotated` reads it. The `llm4sAgent` bean offers
 * them to its model; inject this bean to give them to an agent of your own.
 *
 * {{{
 * @Component
 * public class WeatherTools {
 *     @Tool(description = "The current weather in a city")
 *     public String weather(@Param(name = "city", description = "City name") String city) { ... }
 * }
 * }}}
 */
final class Llm4sTools(declared: java.util.List[JTool]) {

  private val all: java.util.List[JTool] = java.util.List.copyOf(declared)

  /** The tools, in the order their beans were defined, and in each bean by method name. */
  def tools(): java.util.List[JTool] = all

  override def toString: String = all.asScala.map(_.name()).mkString("Llm4sTools(", ", ", ")")
}

object Llm4sTools {

  /**
   * The tools of every singleton bean whose class has a `@Tool` method; such a bean is created here. Fails, naming
   * the bean, when its tools cannot be read, when two tools share a name, or when the bean is an AOP proxy: a proxy's
   * class does not carry the annotations, and calling the target directly would skip its advice (`@Transactional`,
   * security), so a tool bean must not be proxied.
   */
  private[spring] def collect(beans: ConfigurableListableBeanFactory): Llm4sTools = {
    val declared = beans
      .getBeanNamesForType(classOf[Object], false, false)
      .toVector
      .filter(name => Option(beans.getType(name, false)).map(ClassUtils.getUserClass).exists(hasTools))
    val read = declared.foldLeft[Either[String, Vector[(String, JTool)]]](Right(Vector.empty)) { (acc, name) =>
      acc.flatMap(done => toolsOf(name, beans.getBean(name)).map(ts => done ++ ts.map(name -> _)))
    }
    read
      .flatMap { named =>
        named
          .groupBy(_._2.name())
          .collectFirst { case (tool, same) if same.size > 1 => (tool, same.map(_._1)) }
          .toLeft(named.map(_._2))
          .left
          .map { case (tool, owners) => s"two @Tool methods are named '$tool', on beans ${owners.mkString(", ")}" }
      }
      .fold(message => throw new IllegalStateException(message), tools => new Llm4sTools(tools.asJava))
  }

  private def hasTools(c: Class[?]): Boolean = c.getMethods.exists(_.isAnnotationPresent(classOf[Tool]))

  private def toolsOf(name: String, bean: AnyRef): Either[String, Vector[JTool]] =
    if (AopUtils.isAopProxy(bean))
      Left(
        s"bean '$name' (${AopUtils.getTargetClass(bean).getName}) has @Tool methods but is an AOP proxy; " +
          "move the tools to a bean that is not proxied"
      )
    else {
      val result = JTool.fromAnnotated(bean)
      if (result.isSuccess) Right(result.get().asScala.toVector)
      else Left(s"the @Tool methods of bean '$name': ${result.getError().getMessage}")
    }
}
