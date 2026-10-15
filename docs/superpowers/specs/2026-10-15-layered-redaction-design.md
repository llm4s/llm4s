# Layered redaction: design

- **Status:** proposed.
- **Issue:** [#1932](https://github.com/llm4s/llm4s/issues/1932).
- **Supersedes:** PR [#1959](https://github.com/llm4s/llm4s/pull/1959), which will be closed without merging.

## Why the single-pass tokenizer (#1959) did not converge

`org.llm4s.util.Redaction` set itself this goal: *in any text, in any syntax, find the value of every sensitive key, mask exactly that value, and leave everything else readable, keeping JSON valid*. #1959 turned the old 51-regex implementation into one linear pass over a tokenizer. That fixed running time, which is now linear by construction. Correctness still failed:

- **Every review round found a new class of leak.** Codex rounds 1–3 each raised new P1s:
  - a call value spanning several lines;
  - an escape depth past the cap;
  - whitespace before a bare key's colon;
  - a value that only *starts* with the placeholder;
  - a quoted value followed by `:`.
- **Each fix was an "is this the value?" exit.** The audit after round 3 found 32 such exits and 14 of them leaked.
- **The scale was larger than the review rounds showed.** A property test over 8,240 key, value and shape combinations leaked on 5,620 of them before the round-4 fixes.

The cause is the goal, not the implementation. "The value of a key" in arbitrary text has no single meaning: JSON, Python repr, YAML, query strings, headers, prose and nested escapes disagree about it. The approach also fails open, because whatever the parser does not recognise as a value is shown. Every rule written to keep output readable is a leak waiting to be found. No implementation of that goal can be complete.

## What the codebase shows

Audit of `main` at `e04bfed8c` and later:

- **Callers.** There are about 50 non-test callers.
  - Most pass a *remote response or error body* to `safeBody`: every provider, embedder, reranker, image client, MCP transport and Langfuse.
  - Some pass a *request or response dump that llm4s built itself*: `ProviderExchangeSink` and the clients' debug logs.
  - Some pass *stderr or model output*.
- **Known-secret scrubbing is ad hoc.** A few clients assemble their own `secrets` list and call `scrubRemote`, `remoteBody` or `scrub`: OpenAI, Anthropic, OpenAI-compatible, watsonx and `TokenExchange`. The common path, `Llm4sHttpClient.ensureSuccess` → `safeBody`, receives no secrets, and neither do the embedders, rerankers, image clients, MCP or Langfuse.
- **No type marks a credential.** `ApiKey` is `opaque type ApiKey = String`, and every config masks its own `toString` by hand.
- **Structure is discarded before redaction.** `ProviderExchange` holds `String` bodies, although every client had a ujson value or an SDK object first. Redaction then has to recover the structure from text.
- **Traces are never redacted.** `TraceEvent` carries queries, content, tool input and output, and stack traces, and sends them to Langfuse and OpenTelemetry unredacted.

## Guarantees

Redaction separates secrets llm4s **knows** from secrets it **does not know**:

| Layer | Applies to | Method | Promise |
|---|---|---|---|
| **A. Known secrets** | All diagnostic text, and trace content | One linear exact-match scan for every registered `Secret`, in each standard encoding | **Guaranteed.** llm4s never emits a credential it holds, whatever the syntax around it. |
| **B1. Structure** | What llm4s holds as structure (dumps, headers, URLs), and remote text that parses as JSON | Walk the tree and mask the value under each sensitive key, whether a scalar or a whole subtree | **Exact** for that structure; output stays valid. |
| **B2. Free text** | Untrusted text that is not JSON: remote bodies, stderr, exception messages, model output | Vendor-token detectors, plus a coarse key rule (see the B2 contract) | **Best effort, failing toward masking.** Output may over-mask and may not be valid JSON. |
| **Cap** | Text placed in error messages | `safeBody`'s size bound, unchanged | Bounded output. |

**Order.** A always runs first. The text then goes to B1 if it parses as JSON, and to B2 otherwise. A is the only guarantee, and it never depends on parsing.

**Out of scope:**
- Heuristic redaction of trace content. Traces get layer A only, because prompts and tool output are the user data tracing exists to show.
- PII, which stays with the agent guardrails.

### The B2 contract

These are the only promises B2 makes.

1. **Sensitive keys.** A value that follows a sensitive key name is masked up to the end of its segment. The key vocabulary is the existing `isSensitiveKey` / `isCredentialName`.
   - A segment ends at a newline, at the end of the enclosing string literal, or at `&`, `;` or `,` outside quotes.
   - The rule applies at every escape level up to the depth cap.
   - Beyond the cap, the rest of the literal is masked. If the top level is beyond the cap, the rest of the text is masked.
2. **Token detectors.** Every match of a `SecretPatterns` detector is masked (`sk-…`, `sk-ant-…`, `AIza…`, `pa-…`, Langfuse keys, `AKIA…`, JWT, PEM headers).
3. **Nothing else is promised.**

### Review rule

Every PR in this programme states this rule in its description and in its Codex trigger comment:

- A finding against A or B1 is a defect.
- A finding against B2 is a defect only if it breaks clause 1 or clause 2.
- Any other B2 shape is a candidate for a new detector or a documentation note, not a blocker.

## Components

All components live in `llm4s-core`. No new dependency is added.

### `Secret` (public, `org.llm4s.security`)

- **Representation.** An opaque type. `toString` is `***`, and the raw value is reached only through an explicit `reveal`.
- **Registration.** Constructing a `Secret` registers it with `KnownSecrets`.
- **Derived forms.** Builders register the derived form too:
  - `Secret.basic(user, password)` registers the base64 of `user:password`;
  - `Secret.bearer(token)` registers the token.
- **`ApiKey` becomes a `Secret`.** This is a pre-1.0 break, recorded in the CHANGELOG and the migration guide.
- **Other credentials become `Secret`s:**
  - Langfuse public and secret keys;
  - `llm4s.credentials.*`;
  - workload-identity subject tokens and exchanged tokens;
  - Vertex OAuth access tokens, refresh tokens and JWT assertions;
  - watsonx IAM API keys and tokens;
  - Azure keys;
  - search-tool keys.
- **Config `toString` methods** stop calling `Redaction.secret` / `secretOpt` by hand.
- **Not covered:** Bedrock credentials stay inside the AWS SDK. llm4s never holds the signed header, so `BedrockClient`'s exception-message redaction remains B2.

### `KnownSecrets` (`private[llm4s]`)

- **Contents.** A process-wide set of values.
- **Matcher.** A multi-pattern exact matcher (Aho–Corasick), rebuilt copy-on-write when the set changes. A scan reads one volatile reference and takes no lock. It is linear in the text.
- **Encodings.** Each value is registered raw, JSON-escaped and URL-encoded.
- **Minimum length.** A value under 8 characters is refused and logged once at DEBUG, so that short common strings are never masked.
- **Lifecycle.**
  - A token source calls `forget` on rotation.
  - The set has a cap. When it is reached, the oldest token-kind entries are evicted first; configured keys are never evicted.
- **Masking.** A match becomes `[REDACTED]`.
- **Multi-tenant processes.** One tenant's secret may be masked in another tenant's output. This is harmless over-masking and is documented.
- **Tests.** `KnownSecrets.isolated { … }` is a test hook that scopes registrations.

### `StructuredRedaction` (B1, `private[llm4s]`)

- `redact(ujson.Value)` masks the value under each sensitive key, scalar or subtree, at any depth. Output stays valid with the same shape.
- `redactHeaders(Map[String, String])` masks credential-named headers.
- `redactUrl(String)` masks userinfo passwords and credential-named query parameters. This is the existing `Redaction.url` behaviour.

### `TextRedaction` (B2, `private[llm4s]`)

- **Lexer.** It recognises string literals and decodes escapes one level at a time, with a fail-closed depth cap. It is salvaged from #1959's `RedactionScanner`.
- **Rules.** The coarse key rule (contract clause 1) runs at each decoded level. The `SecretPatterns` detectors (clause 2) then run over the result.
- **Exits.** It has no "is this really the value?" exits beyond the segment terminators named in clause 1.
- **Cost.** Time is linear in input length × the depth cap.

### `Redaction` (facade)

| Member | Behaviour |
|---|---|
| `redact(text)` | A, then B1 if the text parses as JSON, otherwise B2 |
| `redact(json)` | A over B1 |
| `safeBody(text, cap)` | `redact`, then cap |
| `scrubKnown(text)` | A only (traces) |
| `url`, `isCredentialName` | Kept |

**Removed:** `redactForLogging`, which duplicates `safeBody`; its callers in openai-compatible, gemini, mcp `PayloadLog` and agent-tools switch to `safeBody`. Also removed: `remoteBody`, `scrubRemote`, `scrub(secrets)` and `identifiers`. The registry replaces them, and their callers become plain `safeBody` / `redact` calls. `secret` / `secretOpt` are also removed once `Secret` replaces their uses.

### Wiring

- **`ProviderExchange`.** Its bodies become `Payload = Json(ujson.Value) | Text(String)`. `ProviderExchangeRecorder` and each client's `recordExchange` pass the structure they already hold: the OpenAI SDK params go through Jackson to ujson, Gemini and Vertex are already ujson, and the rest are built by hand. `ProviderExchangeSink` redacts with B1.
- **Debug request and response logs** in the clients redact the ujson value before it is rendered.
- **Traces.** A single wrapper in the core tracing contract (`Tracing`/`TracingComposer`) applies `scrubKnown` to every string field of a `TraceEvent` before any backend sees it.
- **`ShellTool` output** is unchanged. It is model-facing, not diagnostic.

## What is kept from #1959

- **Property generators** (`RedactionPropertySpec`: `genSecret`, `genDoc`, `genWs`, `genDeep`, and the rest), pointed at the new facade:
  - "no planted secret survives" becomes a hard property for A and B1, and a contract property for B2;
  - "never throws" is kept.
- **Lexer pieces** of `RedactionScanner`: string-literal recognition, decoding escapes one level at a time, and the fail-closed depth cap.
- **`RedactionScalingSpec`** (the timing backstop) and the `SecretPatterns` nested-quantifier check from `RedactionPatternsSpec`.
- **`RedactionOpenIssuesSpec` reproducers.** Each is re-run against the new contract, and its issue is closed or re-scoped.
- **The round-4 early-exit audit**, which records which kinds of exit leak. B2 is designed to have none of them.
- **The differential corpus harness.** It is rebuilt in the repo as an on-demand tool, `sbt redactionCorpus`, that reports leaks per layer. It is not a CI gate.

**Dropped:** the precise value-boundary logic: call, container and repr walks, header-in-bracket rules, and the goals of keeping JSON valid and staying idempotent on malformed input. That is most of the scanner's 1,741 lines.

## Delivery

Four PRs, in order. Until PR 4, `main` keeps its existing heuristics with layer A on top, so no step makes things worse.

1. **Layer A.**
   - Add `Secret` and `KnownSecrets`.
   - Convert `ApiKey` and every credential and token source to `Secret`.
   - Every existing `Redaction` entry point runs A first.
   - Delete the per-caller secret APIs.
   - End-to-end leak tests for each client.
2. **Traces.** The `scrubKnown` wrapper on `Tracing`, with tests for each backend.
3. **Layer B1.**
   - `StructuredRedaction` and `Payload` in `ProviderExchange`.
   - Clients pass structure.
   - `redact(text)` tries JSON first.
4. **Layer B2.**
   - `TextRedaction` replaces the old heuristic body of `Redaction` (about 4,000 lines on `main`).
   - The salvaged tests and the corpus tool move in.
   - Each of #1657, #1689, #1752, #1765, #1777, #1782, #1788, #1799, #1800 and #1844 is closed or re-scoped against the B2 contract.

## Testing

**Layer A:**
- **Registration.** For each credential entry point there is a test that the value is registered: config keys, `credentials.*`, the Vertex, watsonx and workload-identity tokens, Langfuse, and Basic auth.
- **Leak property.** A generated `Secret` is embedded raw, JSON-escaped, URL-encoded or as Basic base64. The embedding text is generated JSON, repr, a query string, prose or stderr. The text is passed through every facade method and the trace wrapper, and the secret must never survive.
- **End to end, for each client.** Using `LocalProviderTestServer`, the provider echoes the request's key or Bearer token in its error body. The `LLMError` message, the exchange log and the trace must not contain it.
- **Lifecycle.** A rotated token is forgotten; the cap evicts tokens before keys; short values are refused; a register racing a scan is safe.

**Layer B1:** a property over generated JSON. No value under a sensitive key survives, and the output is valid JSON with the same shape.

**Layer B2:** one property per contract clause, including a depth beyond the cap.

**Global:**
- Linear time, checked by the scaling backstop under `sbt testPerformance`.
- Redaction never throws.
- No regex with a nested quantifier.

## Acceptance

- All four PRs are merged.
- Every caller of a removed API is migrated.
- `sbt redactionCorpus` reports zero A leaks and zero B1 leaks.
- The ten shape issues are each closed or re-scoped.
- `docs/reference/security.md` and the design record state the three guarantees and the B2 contract.
