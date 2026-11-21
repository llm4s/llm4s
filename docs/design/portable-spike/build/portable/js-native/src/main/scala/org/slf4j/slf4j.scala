package org.slf4j

// slf4j-api is a Java library with no Scala.js or Scala Native build. Two of the portable files use it:
// `LLMError.correlationId` reads `MDC.get("correlationId")` and `KnownSecrets` logs a warning. These stand-ins
// cover exactly those calls, so the spike compiles core's files unchanged; a real portable module would route
// both through a small platform hook instead (see the design record).

/** No mapped diagnostic context off the JVM: every key is absent, as slf4j's NOP adapter answers. */
object MDC {
  def get(key: String): String = null
}

/** The subset of slf4j's `Logger` the portable files call. */
trait Logger {
  def debug(msg: String): Unit
  def warn(msg: String): Unit
}

/** Debug is dropped; a warning goes to stderr (the browser console on Scala.js). */
object LoggerFactory {
  def getLogger(cls: Class[?]): Logger = new Logger {
    def debug(msg: String): Unit = ()
    def warn(msg: String): Unit  = System.err.println(s"[warn] ${cls.getName}: $msg")
  }
}
