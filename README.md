# LLMJury Java SDK

[![CI](https://github.com/llmjury/llmjury-java/actions/workflows/ci.yml/badge.svg)](https://github.com/llmjury/llmjury-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.llmjury/llmjury-sdk)](https://central.sonatype.com/artifact/com.llmjury/llmjury-sdk)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

The official Java SDK for [LLMJury](https://llmjury.com) — run LLM experiments in production.

LLMJury lets you compare prompts and models on real traffic, with real users, and measure what
actually matters: your business outcomes. The SDK assigns each user to an experiment variant
deterministically, captures latency / tokens / errors from your LLM calls, and streams everything
to the LLMJury dashboard where the stats engine tells you which variant wins — and when you have
enough data to trust it.

**Why teams use it:**

- **Ship prompt and model changes like feature flags.** Roll a new prompt to 10% of traffic,
  watch the metrics, roll forward or back — no redeploy.
- **Decide on outcomes, not vibes.** Tie each variant to conversion, retention, resolution rate —
  whatever your business metric is — with statistical rigor built in.
- **Zero overhead on the hot path.** Variant assignment is pure local compute (no network call),
  and tracking never blocks and never throws into your app. A full LLMJury outage degrades to
  your in-code defaults.

## Installation

Maven:

```xml
<dependency>
  <groupId>com.llmjury</groupId>
  <artifactId>llmjury-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.llmjury:llmjury-sdk:0.1.0")
```

Java 11+. A single runtime dependency (Gson); HTTP uses the JDK's `java.net.http`.

## Authentication

Get your **publishable API key** from the LLMJury dashboard (**Settings → API keys**, it looks
like `llmj_pk_...`) and export it:

```bash
export LLMJURY_API_KEY=llmj_pk_...
```

The publishable key is write-only and rate-limited, so it is safe to ship in any environment.
The SDK reads it automatically via `LlmjuryClient.builder()`; you can also pass it explicitly
with `LlmjuryClient.builder("llmj_pk_...")`.

## Quick start

Create an experiment in the dashboard (say, `checkout-prompt`, with variants `control` and
`friendly`, each carrying a prompt). Then:

```java
import com.llmjury.sdk.LlmjuryClient;
import com.llmjury.sdk.PromptAssignment;
import java.util.Map;

// Once, at startup. Prefetching means the first assign resolves instantly.
try (LlmjuryClient client = LlmjuryClient.builder()
        .experiments("checkout-prompt")
        .build()) {

    // Per request: which variant is this user in, and what prompt does it carry?
    PromptAssignment p = client.getPrompt("checkout-prompt", userId,
            "You are a helpful assistant.");
    System.out.println(p.variant() + ": " + p.prompt());

    // When the user converts, record the outcome — this is what the experiment is measured on.
    client.track("business_event", Map.of(
            "experiment_id", "checkout-prompt",
            "user_id", userId,
            "variant", p.variant(),
            "business_metric", "conversion",
            "value", 1));
}
```

That's the whole loop: **assign → use the variant's prompt → track the outcome**. The dashboard
does the rest.

## Recommended production setup

Wrap your model call in `interceptModelCall` (or use a per-user `ExperimentSession`) and the SDK
also captures **latency, token usage, model name, and errors** — the call site stays a one-liner:

```java
PromptAssignment p = client.getPrompt("checkout-prompt", userId, DEFAULT_PROMPT);

String answer = client.interceptModelCall("checkout-prompt", userId, p.variant(),
        call -> {
            var response = anthropic.messages().create(/* system = p.prompt(), ... */);
            call.model("claude-sonnet-5")
                .tokensInput(response.usage().inputTokens())
                .tokensOutput(response.usage().outputTokens());
            return response.content();
        });

// Later, when the user converts — assign is deterministic, so re-deriving the
// variant is free and always consistent:
client.track("business_event", Map.of(
        "experiment_id", "checkout-prompt", "user_id", userId,
        "variant", client.assign("checkout-prompt", userId),
        "business_metric", "conversion", "value", 1));
```

## Core concepts

| Concept | What it is |
|---|---|
| **Experiment** | A named test with an ordered list of variants and traffic weights, defined in the dashboard. Address it by id or unique name. |
| **Variant** | One arm of the experiment. Carries a prompt and/or arbitrary variables (e.g. `model`, `temperature`). |
| **Assignment** | `assign(experiment, user)` → variant key. Deterministic: the same user always gets the same variant, in every SDK language, with no network call. |
| **User / session** | Any stable string id you choose — user id, session id, tenant id. It is hashed, never stored raw for assignment. |
| **Exposure** | "User X saw variant Y" — recorded automatically by interception, or track it yourself. |
| **Model call** | One LLM invocation: latency, tokens in/out, model, error — captured by the interceptor. |
| **Business event** | Your outcome metric (conversion, thumbs-up, resolution). The thing the experiment is judged on. |

### Comparing models

Variants can carry **variables**, so an experiment can vary the model (or temperature, or any
knob) instead of — or alongside — the prompt:

```java
VariantVariables v = client.getVariables("model-shootout", userId,
        Map.of("model", "claude-haiku-4-5", "temperature", "0.3"));
String model = v.get("model");
```

### Comparing prompts

`getPrompt` is the purpose-built path: each variant carries a prompt, and your in-code default is
the guaranteed fallback. Rich prompt workflows (versioning, edit history, per-variant prompt
text) are managed in the dashboard.

### Manual control

The low-level primitives are always available if you want full control:

```java
String variant = client.assign("checkout-prompt", userId); // variant key (or null pre-config)
client.track("exposure", Map.of(
        "experiment_id", "checkout-prompt", "user_id", userId, "variant", variant));
client.track("model_call", Map.of(
        "experiment_id", "checkout-prompt", "user_id", userId, "variant", variant,
        "latency_ms", 840, "tokens_input", 512, "tokens_output", 128,
        "model", "claude-sonnet-5"));
```

## Error handling & resilience

The SDK is engineered so that **LLMJury can never take your app down**:

- `assign` / `getPrompt` / `getVariables` never touch the network on the hot path — they compute
  against a locally cached config that refreshes in the background (60s polling, `ETag`/`304`).
- `track` appends to a bounded in-memory buffer and returns immediately. A daemon thread flushes
  every second (or every 1,000 events).
- Failed flushes retry a bounded number of times, then **spill to disk** (via `offlinePath`) and
  replay later with their original timestamps (bounded to 24h), or drop. They never throw into
  your code.
- Before the first config fetch completes, `assign` returns `null` and `getPrompt` returns your
  default — your app keeps working during a cold start with the network down.

There are no exceptions to catch (checked exceptions from *your* model call pass through
`interceptModelCall` unchanged). The one deliberate consequence: telemetry is best-effort — if
your process is killed without `close()`, buffered events from the last flush interval may be
lost. `LlmjuryClient` is `AutoCloseable`; use try-with-resources or call `close()` in a shutdown
hook.

## Configuration

```java
LlmjuryClient client = LlmjuryClient.builder()          // key from $LLMJURY_API_KEY
        .baseUrl("https://api.llmjury.com")              // default; $LLMJURY_BASE_URL also works
        .experiments("checkout-prompt")                  // prefetch at startup
        .flushInterval(Duration.ofSeconds(1))
        .flushSize(1000)
        .configPollInterval(Duration.ofSeconds(60))
        .requestTimeout(Duration.ofSeconds(5))
        .offlinePath("/var/tmp/llmjury-spill.jsonl")     // enables offline spill/replay
        .build();
```

Lifecycle: construct **one** `LlmjuryClient` per process and reuse it. Call `flush(timeout)` to
drain synchronously (e.g. at the end of a batch job) and `close()` at shutdown.

## Examples

[`Quickstart.java`](src/examples/java/com/llmjury/sdk/examples/Quickstart.java) is a runnable
end-to-end sample: `./gradlew runQuickstart` (set `LLMJURY_API_KEY`, or run it with no backend to
see the graceful-degradation behavior — it exits cleanly either way).

## The determinism guarantee

Assignment is a frozen, cross-language contract: MurmurHash3 over `salt:user:experiment` with
integer-only boundary arithmetic, specified in [`spec/bucketing.md`](spec/bucketing.md). The
[Python](https://github.com/llmjury/llmjury-python),
[TypeScript](https://github.com/llmjury/llmjury-typescript), and Java SDKs all assert the same
conformance fixture in CI, so a user gets the same variant no matter which service — in which
language — asks.

## Links

- [LLMJury docs](https://llmjury.com/docs) · [Dashboard](https://llmjury.com)
- [Python SDK](https://github.com/llmjury/llmjury-python) · [TypeScript SDK](https://github.com/llmjury/llmjury-typescript)
- [Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md) · [Changelog](CHANGELOG.md)

## License

[Apache 2.0](LICENSE)
