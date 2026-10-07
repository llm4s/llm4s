package org.llm4s.toolapi.builtin

import com.typesafe.config.ConfigFactory
import org.llm4s.config.{ BraveSearchToolConfig, DuckDuckGoSearchToolConfig, ExaSearchToolConfig, ToolsConfigLoader }
import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.filesystem._
import org.llm4s.toolapi.builtin.http._
import org.llm4s.toolapi.builtin.search._
import org.llm4s.toolapi.builtin.shell._
import org.llm4s.toolapi.tools.WeatherTool
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.file.{ Files, Path }
import scala.concurrent.duration._

/**
 * Compiles and runs what `docs/guide/builtin-tools.md` says, so the guide cannot drift from the code.
 *
 * Each test below backs one statement of the guide: the bundle tables are asserted against the real
 * bundles, the parameter table against the real schemas, and the safety section against what the tools
 * return when they refuse. Nothing here touches the network or leaves the temporary directories it makes.
 */
class BuiltinToolsGuideSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  private def names(tools: Result[Seq[ToolFunction[_, _]]]): Set[String] = tools.value.map(_.name).toSet

  private def call(tools: Seq[ToolFunction[_, _]], tool: String, arguments: ujson.Obj) =
    new ToolRegistry(tools).execute(ToolCallRequest(tool, arguments))

  private def parameterNames(tool: ToolFunction[_, _]): Set[String] =
    tool.toOpenAITool(strict = false)("function")("parameters")("properties").obj.keys.toSet

  private def tempDir(): Path = Files.createTempDirectory("builtin-tools-guide")

  private def coreNames: Set[String] = Set("get_current_datetime", "calculator", "generate_uuid", "json_tool")

  // ---- the bundles

  "BuiltinTools.coreSafe" should "hold the four core utilities and nothing else" in {
    names(BuiltinTools.coreSafe) shouldBe coreNames
  }

  "BuiltinTools.withHttpSafe" should "add only the read-only HTTP tool, and no web search" in {
    names(BuiltinTools.withHttpSafe()) shouldBe coreNames + "http_request"
  }

  "BuiltinTools.withFilesSafe" should "add the three read-only file tools and still no write or shell" in {
    names(BuiltinTools.withFilesSafe()) shouldBe
      coreNames + "http_request" + "read_file" + "list_directory" + "file_info"
  }

  "BuiltinTools.developmentSafe" should "add file writing and the shell" in {
    names(BuiltinTools.developmentSafe()) shouldBe
      coreNames + "http_request" + "read_file" + "list_directory" + "file_info" + "write_file" + "shell_command"
  }

  "BuiltinTools.customSafe" should "include the core utilities and exactly the tools whose configuration is given" in {
    names(BuiltinTools.customSafe()) shouldBe coreNames
    names(BuiltinTools.customSafe(httpConfig = Some(HttpConfig()))) shouldBe coreNames + "http_request"
    names(BuiltinTools.customSafe(fileConfig = Some(FileConfig()))) shouldBe
      coreNames + "read_file" + "list_directory" + "file_info"
    names(BuiltinTools.customSafe(writeConfig = Some(WriteConfig(Seq("/tmp"))))) shouldBe coreNames + "write_file"
    names(BuiltinTools.customSafe(shellConfig = Some(ShellConfig.readOnly()))) shouldBe coreNames + "shell_command"
  }

  "Every bundle" should "register each tool once, under a distinct name" in {
    Seq(
      BuiltinTools.coreSafe,
      BuiltinTools.withHttpSafe(),
      BuiltinTools.withFilesSafe(),
      BuiltinTools.developmentSafe()
    )
      .foreach { bundle =>
        val all = bundle.value.map(_.name)
        all.distinct should have size all.size.toLong
      }
  }

  "The search tools and the weather tool" should "be in no bundle" in {
    val everything = names(BuiltinTools.developmentSafe())
    everything.filter(n => n.contains("search") || n.contains("weather")) shouldBe empty
  }

  // ---- the parameter table

  private def searchTools: Seq[ToolFunction[_, _]] = Seq(
    DuckDuckGoSearchTool.create(DuckDuckGoSearchToolConfig("https://api.duckduckgo.com/")).value,
    BraveSearchTool
      .create[BraveWebSearchResult](
        BraveSearchToolConfig("test-key", "https://api.search.brave.com/res/v1", 5, "moderate"),
        BraveSearchCategory.Web
      )
      .value,
    ExaSearchTool.create(ExaSearchToolConfig("test-key", "https://api.exa.ai", 10, "auto", 500)).value
  )

  "The parameter table of the guide" should "list the parameters each tool really declares" in {
    val expected = Map(
      "get_current_datetime" -> Set("timezone", "format"),
      "calculator"           -> Set("operation", "a", "b"),
      "generate_uuid"        -> Set("count", "format"),
      "json_tool"            -> Set("operation", "json", "path"),
      "http_request"         -> Set("url", "method", "headers", "body", "content_type"),
      "read_file"            -> Set("path", "max_lines", "encoding"),
      "list_directory"       -> Set("path", "max_entries", "include_hidden"),
      "file_info"            -> Set("path"),
      "write_file"           -> Set("path", "content", "append", "encoding"),
      "shell_command"        -> Set("command"),
      "duckduckgo_search"    -> Set("search_query"),
      "brave_web_search"     -> Set("search_query"),
      "exa_search"           -> Set("query")
    )
    val tools  = BuiltinTools.developmentSafe().value ++ searchTools
    val actual = tools.map(t => t.name -> parameterNames(t)).toMap
    actual shouldBe expected
  }

  // ---- registering the tools

  "The registration snippet" should "turn a bundle into a registry and run a tool" in {
    val registry = BuiltinTools.coreSafe.map(tools => new ToolRegistry(tools)).value

    registry.tools.map(_.name).toSet shouldBe coreNames
    registry.getOpenAITools().arr should have size 4

    val product = registry.execute(
      ToolCallRequest("calculator", ujson.Obj("operation" -> "multiply", "a" -> 6, "b" -> 7))
    )
    product.value("result").num shouldBe 42.0
  }

  "A refused call" should "come back as a Left, not an exception" in {
    val registry = new ToolRegistry(BuiltinTools.coreSafe.value)
    val divided = registry.execute(
      ToolCallRequest("calculator", ujson.Obj("operation" -> "divide", "a" -> 1, "b" -> 0))
    )
    divided.left.value.getFormattedMessage should include("Division by zero")
  }

  // ---- file tools

  "FileConfig" should "refuse a path outside allowedPaths, and read one inside" in {
    val allowed = tempDir()
    val other   = tempDir()
    val inside  = Files.writeString(allowed.resolve("notes.txt"), "hello")
    val outside = Files.writeString(other.resolve("secret.txt"), "secret")
    val tools = BuiltinTools
      .customSafe(fileConfig = Some(FileConfig(allowedPaths = Some(Seq(allowed.toString)), blockedPaths = Seq.empty)))
      .value

    call(tools, "read_file", ujson.Obj("path" -> inside.toString)).value("content").str shouldBe "hello"
    call(tools, "read_file", ujson.Obj("path" -> outside.toString)).left.value.getMessage should include("not allowed")
  }

  it should "block /etc, /var, /sys, /proc and /dev by default, even when they are allowed" in {
    assume(!isWindows, "the default blocklist is made of POSIX paths")
    FileConfig().blockedPaths shouldBe Seq("/etc", "/var", "/sys", "/proc", "/dev")
    val tools = BuiltinTools
      .customSafe(fileConfig = Some(FileConfig(allowedPaths = Some(Seq("/etc")))))
      .value
    call(tools, "read_file", ujson.Obj("path" -> "/etc/hosts")).left.value.getMessage should include("not allowed")
  }

  it should "allow any path outside the blocklist when allowedPaths is not set" in {
    val dir  = tempDir()
    val file = Files.writeString(dir.resolve("anywhere.txt"), "visible")
    FileConfig().allowedPaths shouldBe None
    val tools = BuiltinTools.customSafe(fileConfig = Some(FileConfig(blockedPaths = Seq.empty))).value
    call(tools, "read_file", ujson.Obj("path" -> file.toString)).value("content").str shouldBe "visible"
  }

  it should "refuse a file larger than maxFileSize" in {
    val dir  = tempDir()
    val file = Files.writeString(dir.resolve("big.txt"), "12345")
    val tools = BuiltinTools
      .customSafe(fileConfig = Some(FileConfig(maxFileSize = 4, blockedPaths = Seq.empty)))
      .value
    call(tools, "read_file", ujson.Obj("path" -> file.toString)).left.value.getMessage should include("File too large")
  }

  it should "treat /var as blocked, which on macOS includes the system temporary directory" in {
    assume(!isWindows, "the default blocklist is made of POSIX paths")
    FileConfig().isPathAllowed(Path.of("/var/folders/ab/cd/T/notes.txt")) shouldBe false
    FileConfig().isPathAllowed(Path.of("/tmp/notes.txt")) shouldBe true
  }

  "The development bundle" should "read anywhere outside the blocklist unless it is given a working directory" in {
    // Under target/, which is neither blocked by default nor tracked by git (the system temp directory is /var on macOS).
    val here =
      Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("user.dir"), "target")), "guide")
    val elsewhere = tempDir()
    val local     = Files.writeString(here.resolve("local.txt"), "local")
    val remote    = Files.writeString(elsewhere.resolve("remote.txt"), "remote")

    val unrestricted = BuiltinTools.developmentSafe().value
    call(unrestricted, "read_file", ujson.Obj("path" -> local.toString)).value("content").str shouldBe "local"

    val scoped = BuiltinTools.developmentSafe(workingDirectory = Some(here.toString)).value
    call(scoped, "read_file", ujson.Obj("path" -> local.toString)).value("content").str shouldBe "local"
    call(scoped, "read_file", ujson.Obj("path" -> remote.toString)).left.value.getMessage should include("not allowed")
  }

  it should "default to 1 MB reads and not following symbolic links" in {
    FileConfig().maxFileSize shouldBe 1024L * 1024L
    FileConfig().followSymlinks shouldBe false
  }

  it should "not read through a symbolic link unless followSymlinks is set" in {
    assume(!isWindows, "creating symbolic links needs a privilege on Windows")
    val dir    = tempDir()
    val target = Files.writeString(dir.resolve("target.txt"), "linked")
    val link   = Files.createSymbolicLink(dir.resolve("link.txt"), target)
    def readLink(follow: Boolean) =
      call(
        BuiltinTools
          .customSafe(fileConfig =
            Some(FileConfig(allowedPaths = Some(Seq(dir.toString)), blockedPaths = Seq.empty, followSymlinks = follow))
          )
          .value,
        "read_file",
        ujson.Obj("path" -> link.toString)
      )

    readLink(follow = false).left.value.getMessage should include("Not a regular file")
    readLink(follow = true).value("content").str shouldBe "linked"
  }

  "WriteConfig" should "write inside allowedPaths, refuse outside, and refuse to overwrite by default" in {
    val allowed = tempDir()
    val other   = tempDir()
    val target  = allowed.resolve("out.txt")
    val tools =
      BuiltinTools.customSafe(writeConfig = Some(WriteConfig(allowedPaths = Seq(allowed.toString)))).value

    call(tools, "write_file", ujson.Obj("path" -> target.toString, "content" -> "one")).isRight shouldBe true
    Files.readString(target) shouldBe "one"

    call(tools, "write_file", ujson.Obj("path" -> target.toString, "content" -> "two")).left.value.getMessage should
      include("overwrite is not allowed")
    Files.readString(target) shouldBe "one"

    call(
      tools,
      "write_file",
      ujson.Obj("path" -> other.resolve("x.txt").toString, "content" -> "x")
    ).left.value.getMessage should
      include("not in allowed paths")
    Files.exists(other.resolve("x.txt")) shouldBe false
  }

  it should "default to refusing overwrites, 10 MB files and creating missing directories being allowed" in {
    val config = WriteConfig(allowedPaths = Seq("/tmp"))
    config.allowOverwrite shouldBe false
    config.maxFileSize shouldBe 10L * 1024L * 1024L
    config.createDirectories shouldBe true
  }

  // ---- HTTP tool

  "HttpConfig" should "default to read-only, no redirects, a 30 second timeout and a 10 MB limit" in {
    val config = HttpConfig()
    config.allowedMethods shouldBe Seq("GET", "HEAD")
    config.followRedirects shouldBe false
    config.timeout shouldBe 30.seconds
    config.maxResponseSize shouldBe 10L * 1024L * 1024L
    config.blockInternalIPs shouldBe true
  }

  "The HTTP tool" should "refuse a method that is not allowed, before any request" in {
    val tools   = BuiltinTools.withHttpSafe().value
    val refused = call(tools, "http_request", ujson.Obj("url" -> "https://example.com/", "method" -> "POST"))
    refused.left.value.getMessage should include("HTTP method 'POST' is not allowed. Allowed: GET, HEAD")
  }

  it should "refuse localhost, loopback and the cloud metadata address, before any request" in {
    val tools = BuiltinTools.withHttpSafe().value
    Seq("http://localhost:8080/", "http://127.0.0.1/", "http://169.254.169.254/latest/meta-data/").foreach { url =>
      call(tools, "http_request", ujson.Obj("url" -> url)).left.value.getMessage should include("SSRF_BLOCKED")
    }
  }

  it should "refuse private network addresses, before any request" in {
    val tools = BuiltinTools.withHttpSafe().value
    Seq("http://10.0.0.1/", "http://192.168.1.1/", "http://172.16.0.1/").foreach { url =>
      call(tools, "http_request", ujson.Obj("url" -> url)).left.value.getMessage should include("SSRF_BLOCKED")
    }
  }

  it should "return the body cut at maxResponseSize and say so" in {
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange => {
        val body = ("x" * 5000).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
      }
    )
    server.start()
    // Loopback is refused by default; this test opts out of that on purpose to reach its own server.
    val config = HttpConfig(blockedDomains = Seq.empty, blockInternalIPs = false, maxResponseSize = 100)
    val attempt = scala.util.Try(
      call(
        BuiltinTools.customSafe(httpConfig = Some(config)).value,
        "http_request",
        ujson.Obj("url" -> s"http://127.0.0.1:${server.getAddress.getPort}/")
      )
    )
    server.stop(0)
    val reply = attempt.get

    reply.value("statusCode").num shouldBe 200.0
    (reply.value("body").str should have).length(100)
    reply.value("truncated").bool shouldBe true
  }

  it should "refuse a scheme other than http and https" in {
    val tools = BuiltinTools.withHttpSafe().value
    call(tools, "http_request", ujson.Obj("url" -> "file:///etc/hosts")).left.value.getMessage should
      include("UNSUPPORTED_PROTOCOL")
  }

  it should "refuse a domain outside allowedDomains" in {
    val tools = BuiltinTools.withHttpSafe(HttpConfig.restricted(Seq("api.example.com"))).value
    call(tools, "http_request", ujson.Obj("url" -> "https://other.example.org/")).left.value.getMessage should
      include("SSRF_BLOCKED")
  }

  it should "block localhost, loopback, any-address and the metadata addresses by name by default" in {
    (HttpConfig.DefaultBlockedDomains should contain).allOf(
      "localhost",
      "127.0.0.1",
      "0.0.0.0",
      "::1",
      "169.254.169.254"
    )
  }

  it should "only be as permissive as its configuration says" in {
    (HttpConfig.withWriteMethods().allowedMethods should contain).allOf("POST", "PUT", "DELETE")
    HttpConfig.unsafe.blockInternalIPs shouldBe false
    HttpConfig().withRedirectsEnabled.followRedirects shouldBe true
  }

  "The development bundle" should "give the model the read-only HTTP tool, not write methods" in {
    val tools   = BuiltinTools.developmentSafe().value
    val refused = call(tools, "http_request", ujson.Obj("url" -> "https://example.com/", "method" -> "DELETE"))
    refused.left.value.getMessage should include("is not allowed. Allowed: GET, HEAD")
  }

  // ---- shell tool

  "ShellConfig" should "list the read-only programs, without env, and the development programs with it" in {
    ShellConfig.readOnly().allowedCommands should contain theSameElementsAs
      Seq("ls", "cat", "head", "tail", "pwd", "echo", "wc", "date", "whoami", "which", "file")
    ShellConfig.readOnly().allowedCommands should not contain "env"
    (ShellConfig
      .development()
      .allowedCommands should contain).allOf("env", "git", "sbt", "make", "npm", "grep", "find", "cp", "mv", "rm")
    ShellConfig().allowedCommands shouldBe empty
    ShellConfig().timeout shouldBe 30.seconds
    ShellConfig().maxOutputSize shouldBe 100000
  }

  "The shell tool" should "run an allowed program and refuse any other" in {
    assume(!isWindows, "the allowed programs are POSIX programs")
    val tools = BuiltinTools.customSafe(shellConfig = Some(ShellConfig.readOnly())).value

    val ran = call(tools, "shell_command", ujson.Obj("command" -> "echo hello"))
    ran.value("exitCode").num shouldBe 0.0
    ran.value("stdout").str.trim shouldBe "hello"

    call(tools, "shell_command", ujson.Obj("command" -> "rm -rf /tmp/anything")).left.value.getMessage should
      include("Command 'rm' is not allowed")
  }

  it should "pass shell metacharacters to the program as plain text instead of running them" in {
    assume(!isWindows, "the allowed programs are POSIX programs")
    val file  = Files.createTempFile("builtin-tools-guide", ".tmp")
    val tools = BuiltinTools.customSafe(shellConfig = Some(ShellConfig.readOnly())).value

    val ran = call(tools, "shell_command", ujson.Obj("command" -> s"echo hi && rm ${file.toString}"))

    ran.isRight shouldBe true
    Files.exists(file) shouldBe true
    Files.deleteIfExists(file)
  }

  it should "run in the configured working directory" in {
    assume(!isWindows, "the allowed programs are POSIX programs")
    val dir   = tempDir().toRealPath()
    val tools = BuiltinTools.customSafe(shellConfig = Some(ShellConfig.readOnly(Some(dir.toString)))).value
    call(tools, "shell_command", ujson.Obj("command" -> "pwd")).value("stdout").str.trim shouldBe dir.toString
  }

  it should "hand the child process the environment of the process that runs the tool" in {
    assume(!isWindows, "printenv is a POSIX program")
    val tools =
      BuiltinTools.customSafe(shellConfig = Some(ShellConfig(allowedCommands = Seq("printenv")))).value
    val path = call(tools, "shell_command", ujson.Obj("command" -> "printenv PATH")).value("stdout").str.trim
    path should not be empty
  }

  it should "cut output at maxOutputSize and say so" in {
    assume(!isWindows, "the allowed programs are POSIX programs")
    val tools = BuiltinTools
      .customSafe(shellConfig = Some(ShellConfig(allowedCommands = Seq("echo"), maxOutputSize = 10)))
      .value
    val ran = call(tools, "shell_command", ujson.Obj("command" -> ("echo " + "x" * 200)))
    ran.value("truncated").bool shouldBe true
  }

  // ---- search tools and their configuration

  "The search tools" should "read their configuration from llm4s.tools.<name>" in {
    val config = ConfigFactory.parseString("""
      llm4s.tools {
        brave      { apiKey = "brave-key", apiUrl = "https://api.search.brave.com/res/v1", count = 3, safeSearch = "strict" }
        duckduckgo { apiUrl = "https://api.duckduckgo.com/" }
        exa        { apiKey = "exa-key", apiUrl = "https://api.exa.ai", numResults = 4, searchType = "neural", maxCharacters = 300 }
      }
    """)
    val source = ConfigSource.fromConfig(config)

    ToolsConfigLoader.loadBraveSearchTool(source).value shouldBe
      BraveSearchToolConfig("brave-key", "https://api.search.brave.com/res/v1", 3, "strict")
    ToolsConfigLoader.loadDuckDuckGoSearchTool(source).value shouldBe
      DuckDuckGoSearchToolConfig("https://api.duckduckgo.com/")
    ToolsConfigLoader.loadExaSearchTool(source).value shouldBe
      ExaSearchToolConfig("exa-key", "https://api.exa.ai", 4, "neural", 300)
  }

  it should "be built from the loaded configuration and registered next to a bundle" in {
    val source = ConfigSource.fromConfig(
      ConfigFactory.parseString("""llm4s.tools.duckduckgo { apiUrl = "https://api.duckduckgo.com/" }""")
    )
    val registry = (for {
      config <- ToolsConfigLoader.loadDuckDuckGoSearchTool(source)
      search <- DuckDuckGoSearchTool.create(config)
      core   <- BuiltinTools.coreSafe
    } yield new ToolRegistry(core :+ search)).value

    registry.tools.map(_.name).toSet shouldBe coreNames + "duckduckgo_search"
  }

  it should "name the tools brave_web_search, exa_search and duckduckgo_search" in {
    searchTools.map(_.name) shouldBe Seq("duckduckgo_search", "brave_web_search", "exa_search")
  }

  it should "refuse an empty Exa API key when the tool is built" in {
    ExaSearchTool.create(ExaSearchToolConfig("", "https://api.exa.ai", 10, "auto", 500)).isLeft shouldBe true
  }

  "A call with a missing parameter" should "come back as a Left that names the parameter" in {
    val failed = call(BuiltinTools.coreSafe.value, "calculator", ujson.Obj("operation" -> "add"))
    failed.isLeft shouldBe true
    failed.left.value.getMessage should include("required parameter 'a'")
    failed.left.value.getMessage should include("is missing")
  }

  "The development bundle" should "allow overwriting a file inside its working directory" in {
    val dir    = tempDir()
    val target = dir.resolve("again.txt")
    val tools  = BuiltinTools.developmentSafe(workingDirectory = Some(dir.toString)).value

    call(tools, "write_file", ujson.Obj("path" -> target.toString, "content" -> "one")).isRight shouldBe true
    call(tools, "write_file", ujson.Obj("path" -> target.toString, "content" -> "two")).isRight shouldBe true
    Files.readString(target) shouldBe "two"
  }

  // ---- the snippets of the guide that no test above already runs

  "The customSafe snippet of section 1" should "build the file and HTTP tools it configures" in {
    val tools = BuiltinTools.customSafe(
      fileConfig = Some(FileConfig(allowedPaths = Some(Seq("/srv/agent-data")))),
      httpConfig = Some(HttpConfig.restricted(Seq("api.example.com")))
    )

    names(tools) shouldBe coreNames + "http_request" + "read_file" + "list_directory" + "file_info"
  }

  "The registry snippet of section 2" should "print the product of the calculator call" in {
    val printed = scala.collection.mutable.ArrayBuffer.empty[String]
    BuiltinTools.coreSafe.foreach { tools =>
      val registry = new ToolRegistry(tools)

      registry.execute(ToolCallRequest("calculator", ujson.Obj("operation" -> "multiply", "a" -> 6, "b" -> 7))) match {
        case Right(json) => printed += json("result").toString
        case Left(error) => printed += error.getFormattedMessage
      }
    }

    printed.toSeq shouldBe Seq("42")
  }

  "The configuration snippet of section 5" should "build the read and write tools it configures" in {
    val tools = BuiltinTools.customSafe(
      fileConfig = Some(FileConfig(allowedPaths = Some(Seq("/srv/agent-data")), maxFileSize = 256 * 1024)),
      writeConfig = Some(WriteConfig(allowedPaths = Seq("/srv/agent-data/out")))
    )

    names(tools) shouldBe coreNames + "read_file" + "list_directory" + "file_info" + "write_file"
  }

  // ---- the weather tool

  "WeatherTool" should "return fixed demo data and call no service" in {
    val registry = new ToolRegistry(Seq(WeatherTool.toolSafe.value))
    val reply = registry
      .execute(ToolCallRequest("get_weather", ujson.Obj("location" -> "Oslo, Norway", "units" -> "celsius")))
      .value

    reply("conditions").str shouldBe "sunny"
    reply("temperature").num shouldBe 22.5
  }
}
