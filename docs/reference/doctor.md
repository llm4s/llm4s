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

```text
llm4s doctor

  PASS  JDK               Java 21 (LLM4S needs 21 or newer)
  PASS  Configuration     readable; llm4s.providers has 1 section(s): ollama-local
  PASS  Default provider  'ollama-local'
  PASS  Provider module   'ollama' is registered
  PASS  API key           'ollama' needs no API key
  PASS  Provider config   section 'ollama-local' is valid (model llama3:latest)
  FAIL  Local server      nothing usable answered at http://localhost:11434: GET http://localhost:11434/api/tags: connection failed: ConnectException
                          fix: start it with `ollama serve`, and check llm4s.providers baseUrl / OLLAMA_BASE_URL
  SKIP  Live call         not requested: --live sends one small request, which a hosted provider bills

Result: 6 passed, 0 warnings, 1 failed, 1 skipped
```

## Running it

The doctor is part of the repository's `llm4s-config-policy` module, which is not published, so today you run it from
a checkout of the repository:

```bash
# Check the configuration the module itself would load
sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli"

# Check your own application's configuration file
sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config /path/to/application.conf"
```

`--config` layers your file over every provider module's `reference.conf`, exactly as your application would see it,
so a key that a module binds to a vendor variable (for example `OPENAI_API_KEY`) is found.

| Option | What it does |
|---|---|
| `--config <file>` | Check this file instead of the application's own `application.conf`. |
| `--offline` | Do not contact a local model server either. |
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
| Provider module | the section's `provider` is registered, which means its module is on the classpath | names the registered providers and says to add the dependency that supplies it |
| API key | the provider needs none, or a key is set | gives the exact setting, for example `set OPENAI_API_KEY, or set apiKey under llm4s.providers.main` |
| Provider config | the section validates and the provider can build its config | shows the validation error for the section |
| Local server | a server on this machine answers and has the configured model | says to start it, or the command to pull the model |
| Live call | (only with `--live`) the provider answers one request | maps the error to advice: bad key, rate limit, unknown model, network |

A step that cannot run because an earlier one failed is reported as skipped, not as failed, so the first failure is
the one to fix.

### What it contacts

By default the doctor sends nothing to a hosted provider. If the provider's base URL is on the loopback interface
(`localhost`, `127.0.0.1` or `::1`, which is where an Ollama normally runs), it asks that server which models it has,
because a stopped server or an unpulled model is the most common first-run problem. Use `--offline` to skip even that.

`--live` makes one real request of a few tokens through the same client your application would use. For a hosted
provider that request is billed. A rate limit is reported as a warning rather than a failure, since the setup itself
is fine.

## Your key is never shown

The report shows where a key was found, as a configuration path such as `llm4s.providers.main.apiKey` or
`llm4s.credentials.openai.apiKey`, and never its value. Every message that reaches the report is also scrubbed twice:
the values of every API key in the configuration are replaced by `***` wherever they appear (a provider's error
message that quotes your key, for example), and the text goes through the same redaction as the exchange logs.

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
- It checks the default provider only, not every section. Use the config policy checks (`CheckPolicies`) to gate a
  whole file in CI.
- It does not change anything.

See also [Troubleshooting / FAQ](troubleshooting) and the [configuration guide](../getting-started/configuration).
