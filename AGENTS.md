# Notes for agents (and for whoever reads this next)

What a newcomer — human or otherwise — needs to know before changing anything
here. Most of it is not derivable from the code: it is what was measured, what
was decided, and what was tried and rejected.

Launcher that starts a [Sail](https://github.com/lakehq/sail) Spark Connect
server from JVM tests. Sail is a Rust engine shipped as a Python wheel, so
there is nothing on the JVM side that brings one up; this fills that gap.

Published as `com.devel0pez:sail-testkit_2.13`, hosted at
`github.com/devel0pez-com/sail-testkit`.

## Verified facts

Measured against Sail 0.7.0 + `spark-connect-client-jvm` 4.2.0, not assumed.
Re-check before changing anything that depends on them.

- **The server needs `pyspark` installed next to `pysail`.** Sail resolves the
  Spark version from that module. Without it, `spark.version` fails with
  `ModuleNotFoundError: No module named 'pyspark'` and expressions break. This
  is why `versions.json` pins both and `flake.nix` installs them together.
- **The client JVM needs `--add-opens=java.base/java.nio=ALL-UNNAMED` and
  `-Dio.netty.tryReflectionSetAccessible=true`**, or Arrow fails with
  `sun.misc.Unsafe or java.nio.DirectByteBuffer.<init>(long, int) not
  available`. `spark-submit` passes these itself; sbt does not.
- **Typed Dataset API boundary.** Works: `as[T]`, `Seq[T].toDS()`, and
  column-based `select`/`filter` on a `Dataset[T]`. Fails: anything that ships
  JVM code — `groupByKey` and Scala UDFs return `Scala UDF is not supported
  yet`, typed lambdas (`filter(_.field > 2)`, `map`) fail with `wildcard with
  plan ID`. Expected: the engine is Rust, there is no JVM to run a closure.
  Note a case class declared inside a test class breaks encoders by reflection
  — that failure is the test's fault, not Sail's. Declare them at file level.
- **sbt forks with the JVM that launched it**, not the one `JAVA_HOME` points
  at. Nix packages sbt with its own JDK, so `build.sbt` sets `javaHome` from
  `JAVA_HOME` to make the shell win.

## Decisions

- **Scala, not Java.** Spark 4 only publishes for Scala 2.13, so the audience
  already builds against it, and Scala buys the `SailSuite` ScalaTest trait,
  which is the actual value over a bare launcher. A Java core would have
  avoided the `_2.13` suffix but for an audience that barely exists here.
- **Dependencies are `Provided`, never `Test`.** A test kit must not drag a
  Spark client or a test framework into anyone's build; the consumer's versions
  have to win. sbt puts `Provided` on the test classpath anyway, so this
  project's own tests still run. Declaring them `Test` as well publishes them
  in `test` scope, which is wrong.
- **groupId `com.devel0pez`, repo under `devel0pez-com`.** They do not have to
  match: Sonatype verifies the namespace via a DNS TXT record on the domain,
  and the GitHub URL is only POM metadata. `io.github.*` would have tied them.
- **Apache-2.0**, matching Sail. Sail requires no CLA or DCO, so a future
  donation is a repo transfer and nothing more.
- **The Sail server comes from `pip`, not from nixpkgs.** `pysail` has been in
  nixpkgs since 0.7.0, and there is a top-level `sail` too, so this is a choice
  and not a gap. Three reasons, heaviest first. The wheel is the binary Sail's
  users actually run; nixpkgs builds from the release tarball instead, and a
  divergence found in an engine we compiled ourselves is one LakeSail cannot
  reproduce — which is the entire premise of the corpus. It is also absent from
  `cache.nixos.org` for `aarch64-darwin`, so `nix develop` would compile
  DataFusion and Sail on the spot instead of fetching a 48 MB wheel. And nixpkgs
  carries `pyspark` 4.1.2 while `versions.json` pins 4.2.0: since Sail reads its
  Spark version from that module, an all-nix shell would run a server that
  believes it is 4.1.2 against a 4.2.0 client.

  Underneath all three: with nixpkgs the *channel* decides the Sail version. A
  `flake.lock` bump could move it away from the corpus submodule pinned at
  `v0.7.0`, which is the desync that produced 96 phantom divergences.
  `versions.json` has to keep that authority.

  The price paid is an impure devshell — `pip install` reaches the network at
  startup, and `flake.nix` carries stamp logic to catch a half-finished install.
  If that ever becomes the bigger problem, the answer is a derivation in this
  flake built from the PyPI **wheel** at the version in `versions.json`: pure
  and hash-pinned, without ceding the version or changing the binary. Not
  nixpkgs.
- **English throughout.** Unlike the sibling templates, this is public-facing
  to the Sail community.

## Working on it

```bash
nix develop   # JDK 17, sbt, scalafmt, and a venv with the Sail server
t             # tests
f             # format
```

The devshell installs `pysail` and `pyspark` from `versions.json`, which
`build.sbt` reads too, so client and server cannot drift apart.

`versions.json` is the single source of truth. No bot knows about it: Scala
Steward reads `build.sbt`, where the version sits behind a function. Bumping
Spark is a manual edit, which is exactly what forces bumping pysail with it.

## Before reporting anything

A corpus run against Sail reports hundreds of failures and almost none of them
are news. Read them in this order or you will file bugs that do not exist —
this happened twice in one afternoon.

**1. Spark is the oracle, Sail is the subject.** The expected values were
captured from real Spark, so a scenario that fails against *Spark* is a bug in
this harness, not in anything else. Run the baseline first:

```bash
# start a reference Spark Connect server on a port nobody else uses
$(python -c 'import pyspark,os;print(os.path.dirname(pyspark.__file__))')/bin/spark-submit \
  --class org.apache.spark.sql.connect.service.SparkConnectServer \
  --conf spark.connect.grpc.binding.port=15731 --conf spark.ui.enabled=false &

SPARK_REMOTE=sc://localhost:15731 sbt -Dcucumber.filter.tags='not @sail-only' corpus
```

It should sit at ~99%. Whatever fails there is ours. Note the tag filter:
`@sail-only` scenarios are Sail extensions and Spark has no such thing, while
`@sail-bug` ones are expected to **pass** against Spark.

**2. Check the version pairing.** The corpus submodule and the `pysail` binary
must come from the same Sail release. HEAD's corpus against 0.7.0 produced 96
"divergences" that were fixes landed after the release — see
[docs/CORPUS.md](docs/CORPUS.md).

**3. Grep their corpus for the thing, comments included.** `@sail-bug` is not
the only way they record what they know. The `decimal(18,2) * 2` widening
difference looks like a fresh find and is not: `transform.feature` carries a
plain comment calling it *"the pre-existing decimal×int coercion gap"*. The
report classifies by tag and will never see prose.

**4. Reproduce it by hand, against both engines.** Same client, one query, both
servers. Every finding that survived this was real; every one that did not,
was not.

## Comparing types across the whole corpus

The corpus asserts a type only where a scenario says `query schema` — 901 of
its 4.972. Everywhere else it compares rows as text, so `decimal(29,2)` and
`decimal(20,2)` render identically and pass. To cover the rest:

```bash
sbt -Dsail.schemaDump=/tmp/sail.tsv corpus
SPARK_REMOTE=sc://localhost:15731 sbt -Dsail.schemaDump=/tmp/spark.tsv \
  -Dcucumber.filter.tags='not @sail-only' corpus
# then diff the two by SQL
```

No expected values needed: the same query yielding two schemas *is* the
divergence. Last run: 4.639 queries in common, 4.599 identical, 40 different,
falling into about six families (`bigint`/`int`, `timestamp_ntz`/`timestamp`,
decimal precision). Those 40 are unreviewed — step 3 above still applies.

## Harness bugs that looked like engine bugs

Kept because each one cost hours and each produced hundreds of false failures:

- **`query result` reads the table the server renders with `show()`**, it does
  not `collect()`. Collecting sends every value through the Arrow deserializer,
  so a type the JVM client cannot decode fails a scenario that was never about
  decoding. Worth 292 failures.
- **`config` must be restored after each scenario.** One scenario setting
  `America/New_York` shifted every later result by hours. Worth 113.
- **An empty Gherkin cell is an empty string, not NULL.**
- **Cucumber unescapes `\\` in Examples tables; pytest-bdd does not**, so a
  regex arrives with its backslashes eaten. `repairEscapes` reads the row back
  from the file. Worth 10.

## Failures that are nobody's bug

They stay red on purpose; they belong in release notes, not in an issue
tracker:

- `uniform` with a fixed seed expects a specific random sequence, which depends
  on how the server partitions.
- `hll_sketch_agg` is approximate: 996 against 995.
- Plan snapshots are keyed by the pytest test name that produced them and hold
  Sail's own physical plans, so they can never be reused from here.

## Operational

Use a **dedicated port** for the reference Spark server and kill it by PID.
A broad `pkill -f SparkConnectServer` will take down a run started by another
terminal, and the symptom is a client retrying with a 60-second backoff against
a dead server, which reads like a hang.

Only one sbt can hold `target/` at a time. If a run seems stuck on nothing,
check whether another sbt is already in this directory.

## Pending

- **Publishing.** `sbt ci-release` needs `PGP_SECRET`, `PGP_PASSPHRASE`,
  `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` as repo secrets, plus registering
  the `com.devel0pez` namespace on the Central Portal (DNS TXT on Cloudflare).
  Untested end to end: verify against current sbt-ci-release docs, since that
  part of the ecosystem moves.
- **Docs PR to `lakehq/sail`** — "Using Sail from the JVM": server startup, the
  Arrow flags, the `pyspark` requirement, the typed API table, one Scala test
  example. Docs only, no CI: LakeSail said they lack the bandwidth to maintain
  a JVM component in-tree, but that examples would help.
- **The 40 schema divergences above**, unreviewed. Step 3 first: some are
  likely already recorded in their corpus.

Done since: the sibling Scala template consumes the artifact from
`publishLocal`, which is what turned up the `configureSession` hook — the kit
owns the lifecycle, the project owns the configuration.

## Context

Raised in the LakeSail Slack. Their answer: a separate repo is fine, a JVM
launcher "would be interesting", they cannot maintain it in-tree due to the
extra CI setup, but examples would help people who are interested.
