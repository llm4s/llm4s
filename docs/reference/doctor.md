---
layout: page
title: Doctor (setup check)
parent: Reference
nav_order: 19
---

# Doctor: check your whole setup in one run

The first run of an LLM4S application has several parts that can each fail on their own: a JDK that is too old, an
`application.conf` without a provider section, a missing API key, a provider module that is not on the classpath, a
local model server that is not running. The doctor checks all of them in order and, for each one that fails, says
what to change.

This is the report for an application whose `application.conf` names a local Ollama, on a machine where Ollama is not
running, run with `--local`:

```text
llm4s doctor

  PASS  JDK               Java 21 (LLM4S needs 21 or newer)
  PASS  Configuration     readable; llm4s.providers has 1 section(s): ollama-local
  PASS  Default provider  'ollama-local'
  PASS  Provider module   'ollama' is registered by org.llm4s.llmconnect.provider.Llm4sOllamaModule (llm4s-ollama_3-{{ site.data.project.latest_release }}.jar) on this classpath
  PASS  API key           'ollama' needs no API key
  PASS  Provider config   section 'ollama-local' is valid (model llama3:latest)
  FAIL  Local server      nothing usable answered at http://localhost:11434: GET http://localhost:11434/api/tags: connection failed: ConnectException
                          fix: start it with `ollama serve`, and check llm4s.providers baseUrl / OLLAMA_BASE_URL
  SKIP  Live call         not requested: --live sends one small request, which a hosted provider bills
  SKIP  Other sections    there is no section besides the default

Result: 6 passed, 0 warnings, 1 failed, 2 skipped
```

## Running it

The doctor is `llm4s-doctor`, published from the first release after 0.5.1. It depends on `llm4s-core` only. Run it
from your own project, so it runs on your project's classpath: it reads the `application.conf` your application loads
and finds only the provider modules your build depends on. A provider your build lacks is reported as missing, not
found on someone else's classpath.

### sbt

Add it in test scope, so it never reaches your application's runtime classpath:

```scala
// build.sbt: same version as llm4s-core; test scope only
libraryDependencies += "org.llm4s" %% "llm4s-doctor" % llm4sVersion % Test
```

```bash
# Check the application.conf in src/main/resources
sbt "Test/runMain org.llm4s.doctor.DoctorCli"

# Also ask a local Ollama whether it is running and has the model
sbt "Test/runMain org.llm4s.doctor.DoctorCli --local"
```

The test classpath has your main resources, so the `application.conf` checked is your application's; an
`application.conf` in `src/test/resources` takes precedence, as in your tests. `DoctorCli` exits the JVM it runs in with
its exit code, so run it as a batch command as above, or set `Test / run / fork := true` to run it from the sbt shell.

### No project yet: Coursier

With [Coursier](https://get-coursier.io), name the doctor and the provider module you mean to use, and the file to check:

```bash
cs launch org.llm4s:llm4s-doctor_3:{{ site.data.project.latest_release }} \
          org.llm4s:llm4s-openai_3:{{ site.data.project.latest_release }} \
          -M org.llm4s.doctor.DoctorCli -- --config application.conf
```

Only the provider modules you list are on that classpath, so this also tells you which module your build needs.

### Maven

Add `llm4s-doctor_3` in test scope, then run it on the test classpath with the exec plugin:

```xml
<dependency>
  <groupId>org.llm4s</groupId>
  <artifactId>llm4s-doctor_3</artifactId>
  <version>{{ site.data.project.latest_release }}</version>
  <scope>test</scope>
</dependency>
```

```bash
mvn -q test-compile exec:java -Dexec.mainClass=org.llm4s.doctor.DoctorCli -Dexec.classpathScope=test
# with options:
mvn -q test-compile exec:java -Dexec.mainClass=org.llm4s.doctor.DoctorCli -Dexec.classpathScope=test -Dexec.args="--local"
```

### Gradle

Add it to the test runtime classpath and register a `JavaExec` task (Kotlin DSL):

```kotlin
dependencies {
    testRuntimeOnly("org.llm4s:llm4s-doctor_3:{{ site.data.project.latest_release }}")
}

tasks.register<JavaExec>("llm4sDoctor") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.llm4s.doctor.DoctorCli")
}
```

```bash
gradle llm4sDoctor
gradle llm4sDoctor --args="--local"
```

A failed step makes the task fail, which is what a CI step wants.

### From code

`Doctor.run` makes the same checks against the configuration and provider modules of the running application and
returns the report, so an application can log it at startup, or a test can fail on it. It is `@Experimental`; the
command line's options, text and JSON output are the stable contract.

```scala
import org.llm4s.doctor.{ Doctor, DoctorOptions }

val report = Doctor.run(DoctorOptions())
println(report.renderText)
if report.exitCode == 2 then println("the setup has a problem: see the report above")
```

`DoctorOptions().withLocal(true)` and `.withLive(true)` are `--local` and `--live`.

### From a checkout of this repository

`sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli"` runs the same doctor with every built-in provider module
on the classpath.

### Options

`--config` layers your file over the `reference.conf` of every module on the classpath, so a key that a provider
module binds to a vendor variable (for example `OPENAI_API_KEY`) is found.

`--classpath` checks another application's runtime classpath from wherever the doctor runs. Only the provider modules
on it are then registered, and the configuration is layered as that application would load it: `-D` system
properties, over your `--config` file (or, without one, the `application.conf` on that classpath), over the
`reference.conf` files on that classpath - so a third-party provider module's credential binding is found too. The
doctor's own `reference.conf` files come last, only to fill in what the classpath leaves out. Running the doctor from
your project, as above, makes it unnecessary:

```bash
# In your application's project: print its runtime classpath (the last line of the output)
sbt --error "export Runtime/fullClasspath"

# Wherever the doctor runs
cs launch org.llm4s:llm4s-doctor_3:{{ site.data.project.latest_release }} -M org.llm4s.doctor.DoctorCli -- --classpath <that classpath>
```

| Option | What it does |
|---|---|
| `--config <file>` | Check this file instead of the application's own `application.conf`. |
| `--classpath <path>` | Check the provider modules, `reference.conf` files and (without `--config`) `application.conf` on this classpath (entries separated by `:`, or `;` on Windows) instead of the one the doctor runs on. |
| `--local` | Ask a model server on this machine whether it is running and has the configured model. |
| `--live` | Make one small request to the configured provider (see below). |
| `--timeout <seconds>` | How long to wait for that request. The default is 30. |
| `--json` | Print the report as JSON, for CI. |

The exit code is `0` when every step passed or was skipped, `1` when there are warnings only, and `2` when any step
failed.

## What it checks

| Step | Passes when | When it fails |
|---|---|---|
| JDK | the running JDK is 21 or newer | names the version it found and says to set `JAVA_HOME` |
| Configuration | the configuration can be read and has at least one section under `llm4s.providers` | shows the parse problem, or an example section to add |
| Default provider | `llm4s.providers.provider` names a section that exists | lists the sections you have |
| Provider module | the section's `provider` is registered, which means its module is on the classpath the doctor runs on (or the `--classpath` given); names the module and the jar it came from | names the registered providers and says to add the dependency that supplies it |
| API key | the provider needs none, or a key is set | gives the exact setting, for example `set OPENAI_API_KEY, or set apiKey under llm4s.providers.main` |
| Provider config | the section validates and the provider can build its config | shows the validation error for the section |
| Local server | (only with `--local`) a server on this machine answers and has the configured model | says to start it, or the command to pull the model |
| Live call | (only with `--live`) the provider answers one request | maps the error to advice: bad key, rate limit, unknown model, network |
| Other sections | every section besides the default loads, as `Llm4sConfig.provider(name)` would load it | a warning naming each section and its error: the default works, but code that loads that section by name would fail |

A step that cannot run because an earlier one failed is reported as skipped, not as failed, so the first failure is
the one to fix.

### What it contacts

By default the doctor contacts nothing, not even a server on your own machine: it reads the configuration and the
classpath and stops there. With `--local`, if the provider's base URL is on the loopback interface (`localhost`,
`127.0.0.1` or `::1`, which is where an Ollama normally runs), it asks that server which models it has, because a
stopped server or an unpulled model is the most common first-run problem. A base URL on another host is never
contacted by `--local`.

`--live` makes one real request of a few tokens through the same client your application would use. For a hosted
provider that request is billed. A rate limit is reported as a warning rather than a failure, since the setup itself
is fine.

## How it keeps secrets out of the report

The report shows where a key was found, as a configuration path such as `llm4s.providers.main.apiKey` or
`llm4s.credentials.openai.apiKey` (the shared key a provider module binds to its vendor's variable); the doctor never
puts a key's value in a message itself. A URL is always printed as `scheme://host:port/path`, without the user
info, query or fragment where a base URL can carry a password, a signature or a token:
`https://alice:secret@gw.example.com/v1?sig=...` is shown as `https://gw.example.com/v1`, in the doctor's own
messages and in any error message that quotes the URL. A password that holds a `/` is removed whole too:
everything up to the last `@` before any `?` or `#` is user info, so `http://u:pa/ss@gw.example.com/v1` is shown as
`http://gw.example.com/v1`.

A configuration file that cannot be parsed is reported by where the problem is and what kind it is, for example
`application.conf:5: ConfigException.WrongType (a value has the wrong type, ...)`, never by the parser's own
message: Typesafe Config's messages quote the values next to the problem (`Quoted("...")`,
`SimpleConfigObject({...})`, `SimpleConfigList([...])`), and an API key is often one of them. Open the file at that
line to see the values. As the file could not be parsed, its keys cannot be told apart from other values, so every
quoted value of 8 characters or more in it is also hidden by value.

Every message that reaches the report is also scrubbed twice, because a provider's or a gateway's error can echo
what it was sent. First, these values from the configuration are replaced by `***` wherever they appear: every
shared `llm4s.credentials` key and, from every section under `llm4s.providers` (including one that cannot be read),
its `apiKey`, each of its `headers` values (and each word of one, so the token of `Bearer <token>` is hidden on its
own) and the user info, query and query values of its `baseUrl` (the user info and password both as written and
URL-decoded, since an error can print either). Then the text goes through the same shape-based
redaction as the exchange logs.

That scrubbing has limits worth knowing:

- A value shorter than 8 characters is not replaced by value, because hiding a three-letter value would mangle
  ordinary words in the report. A key, header value or password that short is hidden only if its shape is recognised
  by the redaction, so do not rely on the report to hide it.
- A value is hidden only where it appears whole. A provider that echoes part of a key (`sk-abc...xyz`, the last four
  characters) is not matched by value; vendors that do this usually show too little of the key to be useful, and
  the known key shapes are caught by the redaction.

## JSON output

`--json` prints one object:

```json
{
  "verdict": "fail",
  "exitCode": 2,
  "checks": [
    { "name": "API key", "status": "fail", "detail": "no API key for section 'main'", "fix": "set OPENAI_API_KEY, or set apiKey under llm4s.providers.main in application.conf" }
  ]
}
```

`status` is one of `pass`, `warn`, `fail` and `skip`; `fix` is `null` when there is nothing to change.

## Limits

- It does not check tracing or observability settings.
- It checks the default provider step by step; the other sections are only loaded, and a problem there is a warning.
  Use the config policy checks (`CheckPolicies`) to gate a whole file against policies in CI.
- It does not change anything.

See also [Troubleshooting / FAQ](troubleshooting) and the [configuration guide](../getting-started/configuration).
