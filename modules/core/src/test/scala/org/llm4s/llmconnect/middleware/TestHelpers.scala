package org.llm4s.llmconnect.middleware

import org.slf4j.Logger
import scala.collection.mutable.ArrayBuffer

object TestHelpers {

  class FakeLogger extends Logger {
    val debugs = ArrayBuffer[String]()
    val warns  = ArrayBuffer[String]()
    val traces = ArrayBuffer[String]()
    val infos  = ArrayBuffer[String]()
    val errors = ArrayBuffer[String]()

    override def getName: String          = "FakeLogger"
    override def isDebugEnabled: Boolean  = true
    override def debug(msg: String): Unit = debugs += msg
    override def isWarnEnabled: Boolean   = true
    override def warn(msg: String): Unit  = warns += msg
    override def isTraceEnabled: Boolean  = true
    override def trace(msg: String): Unit = traces += msg
    override def isInfoEnabled: Boolean   = true
    override def info(msg: String): Unit  = infos += msg
    override def isErrorEnabled: Boolean  = true
    override def error(msg: String): Unit = errors += msg

    // Boilerplate for other methods
    override def debug(format: String, arg: Any): Unit                                       = ()
    override def debug(format: String, arg1: Any, arg2: Any): Unit                           = ()
    override def debug(format: String, arguments: Any*): Unit                                = ()
    override def debug(msg: String, t: Throwable): Unit                                      = ()
    override def info(format: String, arg: Any): Unit                                        = ()
    override def info(format: String, arg1: Any, arg2: Any): Unit                            = ()
    override def info(format: String, arguments: Any*): Unit                                 = ()
    override def info(msg: String, t: Throwable): Unit                                       = ()
    override def warn(format: String, arg: Any): Unit                                        = ()
    override def warn(format: String, arguments: Any*): Unit                                 = ()
    override def warn(format: String, arg1: Any, arg2: Any): Unit                            = ()
    override def warn(msg: String, t: Throwable): Unit                                       = ()
    override def error(format: String, arg: Any): Unit                                       = ()
    override def error(format: String, arg1: Any, arg2: Any): Unit                           = ()
    override def error(format: String, arguments: Any*): Unit                                = ()
    override def error(msg: String, t: Throwable): Unit                                      = ()
    override def trace(format: String, arg: Any): Unit                                       = ()
    override def trace(format: String, arg1: Any, arg2: Any): Unit                           = ()
    override def trace(format: String, arguments: Any*): Unit                                = ()
    override def trace(msg: String, t: Throwable): Unit                                      = ()
    override def isDebugEnabled(marker: org.slf4j.Marker): Boolean                           = false
    override def debug(marker: org.slf4j.Marker, msg: String): Unit                          = ()
    override def debug(marker: org.slf4j.Marker, format: String, arg: Any): Unit             = ()
    override def debug(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit = ()
    override def debug(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit      = ()
    override def debug(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit            = ()
    override def isInfoEnabled(marker: org.slf4j.Marker): Boolean                            = false
    override def info(marker: org.slf4j.Marker, msg: String): Unit                           = ()
    override def info(marker: org.slf4j.Marker, format: String, arg: Any): Unit              = ()
    override def info(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit  = ()
    override def info(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit       = ()
    override def info(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit             = ()
    override def isWarnEnabled(marker: org.slf4j.Marker): Boolean                            = false
    override def warn(marker: org.slf4j.Marker, msg: String): Unit                           = ()
    override def warn(marker: org.slf4j.Marker, format: String, arg: Any): Unit              = ()
    override def warn(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit  = ()
    override def warn(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit       = ()
    override def warn(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit             = ()
    override def isErrorEnabled(marker: org.slf4j.Marker): Boolean                           = false
    override def error(marker: org.slf4j.Marker, msg: String): Unit                          = ()
    override def error(marker: org.slf4j.Marker, format: String, arg: Any): Unit             = ()
    override def error(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit = ()
    override def error(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit      = ()
    override def error(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit            = ()
    override def isTraceEnabled(marker: org.slf4j.Marker): Boolean                           = false
    override def trace(marker: org.slf4j.Marker, msg: String): Unit                          = ()
    override def trace(marker: org.slf4j.Marker, format: String, arg: Any): Unit             = ()
    override def trace(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit = ()
    override def trace(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit      = ()
    override def trace(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit            = ()
  }
}
