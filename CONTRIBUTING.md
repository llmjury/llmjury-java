# Contributing to the LLMJury Java SDK

Thanks for your interest in improving the SDK! This guide covers local setup, the quality bar,
and the one rule that is different from most projects: the frozen bucketing contract.

## Development setup

```bash
git clone https://github.com/llmjury/llmjury-java.git
cd llmjury-java
./gradlew build
```

Any JDK 11+ works for development; the build emits Java 11 bytecode and links only against the
Java 11 API regardless of the JDK running it. CI proves JDK 11, 17, and 21.

## Running checks

```bash
./gradlew test            # JUnit 5 suite
./gradlew spotlessCheck   # formatting (palantir-java-format)
./gradlew build           # everything, with -Werror
```

`./gradlew spotlessApply` fixes formatting in place.

## The frozen bucketing contract

`src/main/java/com/llmjury/sdk/Bucketing.java` implements the frozen, cross-language assignment
algorithm described in [`spec/bucketing.md`](spec/bucketing.md). The Python, TypeScript, and Java
SDKs must return the **same variant for the same input, bit for bit** — that determinism is a core
product guarantee.

- Do **not** change anything in `Bucketing.java` or `spec/` in a regular PR. Any behavioral change
  there is a breaking contract change and is coordinated across all SDKs and the backend by the
  maintainers.
- `BucketingTest` asserts every case in `spec/fixtures/bucketing-cases.json`. If your change
  breaks it, the change is wrong — not the fixture.

## SDK design rules

These invariants hold everywhere in the SDK; PRs that violate them will be asked to change:

1. **Never block the host app.** `assign` is pure local compute; `track` only appends to a deque.
2. **Never throw into the host app.** Network and I/O failures are logged and swallowed; the
   in-code `default` on `getPrompt` must always work.
3. **Minimal dependencies.** Gson is the only runtime dependency; HTTP is `java.net.http`.
4. **No real network in tests.** Use the in-process transports in `src/test/java`.

## Submitting changes

1. Fork and create a topic branch.
2. Add or update tests for anything you change.
3. Keep the public API backwards-compatible; deprecate before removing.
4. Update `CHANGELOG.md` under an `Unreleased` heading.
5. Open a PR with a clear description of the motivation and behavior change.

For anything non-trivial, open an issue first so we can agree on the approach before you invest
time in the code.

## Reporting issues

- Bugs and feature requests: [GitHub issues](https://github.com/llmjury/llmjury-java/issues)
- Security vulnerabilities: see [SECURITY.md](SECURITY.md) — please do not open public issues.
