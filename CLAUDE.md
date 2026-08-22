# Project notes

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
- **A Scala template consuming the published artifact**, to exercise the API
  for real before pointing anyone at it.

## Context

Raised in the LakeSail Slack. Their answer: a separate repo is fine, a JVM
launcher "would be interesting", they cannot maintain it in-tree due to the
extra CI setup, but examples would help people who are interested.
