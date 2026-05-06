# Contributing to jp4

Thanks for considering a contribution. This file is short on purpose —
the bar is "ship clear changes the maintainer can review", not a
governance document.

## Filing an issue

- **Bug.** Include the JDK version (`java -version`), how you started
  BMv2 (native binary or Docker, image / digest), the smallest jp4
  program that reproduces, and the full stack trace. If the failure is
  intermittent, include the reproduction rate.
- **Feature request.** Describe the controller pattern you're trying
  to write, what jp4 makes you do today vs what you'd prefer. Concrete
  shapes ("I'd like `sw.foo(...)` so I can write `…`") get traction
  faster than abstract requests.

If you're not sure whether something is a bug or a missing feature,
file it as a question — `[question]` in the title is fine.

## Submitting a PR

### Setup

```bash
git clone https://github.com/zhh2001/jp4.git
cd jp4
./gradlew build      # full unit + integration suite (Docker BMv2 required)
```

The full build runs the integration tests against either a native
`simple_switch_grpc` (set `JP4_BMV2_MODE=native`) or a Docker BMv2
image (default). For the native path you'll need
`simple_switch_grpc` on `PATH` and either a `veth0` or `bm0` interface
configured. For the Docker path you only need Docker.

### Code style

- **Java 21+ features welcome** — sealed types, exhaustive `switch`,
  records. Don't reach for older idioms when the modern shape is
  cleaner.
- **No reflection in main code.** Indirect deps (protobuf, grpc) use
  reflection internally; jp4's own code does not. If you find yourself
  reaching for `Class.forName`, stop and discuss in an issue.
- **No third-party reactive dependencies.** `java.util.concurrent.Flow`
  is the streaming surface; users adapt to Reactor / RxJava in one
  line.
- **JavaDoc** — public types and public methods get a class-level or
  method-level paragraph. No `@param` / `@return` blizzard for
  self-evident parameters; do explain bit-width contracts, exceptional
  conditions, and any behaviour that surprised you while writing the
  code.
- **Phase numbers don't appear in code or commit messages.** Describe
  *what* and *why*, not the project's internal narrative. Future
  readers should understand the change without needing the project's
  phase history.

### Tests

- **Unit tests** for pure logic — see `src/test/java/.../EntryValidatorTest.java`
  for the shape.
- **Integration tests** for anything that touches the wire. Run
  against real BMv2 (native locally, Docker in CI). See
  `src/test/java/.../integration/` for examples.
- **No `Thread.sleep` in test code.** Use Awaitility (already a test
  dep) or `CompletableFuture` synchronisation. The two pre-existing
  `Thread.sleep` calls in `testsupport/` exist to back off on
  ProcessBuilder bind retries — those are infrastructure, not test
  logic.
- **Concurrency-sensitive tests use `@RepeatedTest(3)`** so flakes show
  up locally before CI sees them.

### Commit messages

- **Imperative, present tense.** `Add the foo helper`, not `Added` or
  `Adds`.
- **First line under 70 characters**, full body wrapped at 80.
- **Body explains why**, not what — the diff already shows what.
  Subordinate paragraphs separated by blank lines.
- **No phase numbers, no internal milestone references.** Same as
  code-comment style.
- **No `Co-Authored-By` lines** unless the work was actually
  collaborative.

If your PR fixes a runtime quirk you discovered (BMv2 / target /
JVM behaviour), add a corresponding entry to `NOTES.md` in the same
commit so future maintainers don't re-derive it.

### Before opening the PR

- `./gradlew build` passes.
- `git diff main` is the change you intend to ship — no leftover debug
  prints or unrelated reformatting.
- If the change touches the public API surface, update the JavaDoc and
  `CHANGELOG.md` in the same commit.
- If your PR adds a new test, run it 5× consecutively on your machine
  to catch flakiness before CI sees it.

## Local development environment

jp4 development is tested on Linux (including WSL2 on Windows) and
macOS. Windows native development is not tested but should work for
Java contributions; BMv2 integration tests require Linux or macOS for
the native `simple_switch_grpc` binary, but the Docker-based
`DockerBackend` works on any platform with Docker.

For the native path, BMv2 needs `cap_net_admin,cap_net_raw=eip` on the
binary so tests don't require sudo:

```bash
sudo setcap 'cap_net_admin,cap_net_raw=eip' /usr/local/bin/simple_switch_grpc
sudo ip link add veth0 type veth peer name veth1
sudo ip link set veth0 up
sudo ip link set veth1 up
```

(One-time per host. The dummy-interface fallback is `bm0`; create with
`ip link add dev bm0 type dummy`.)

## License

By contributing you agree that your contribution will be licensed
under the [Apache License 2.0](LICENSE), the same as the rest of jp4.
