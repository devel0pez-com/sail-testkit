# sail-testkit

Run your Spark tests against [Sail](https://github.com/lakehq/sail) from the JVM.

Sail is a Rust engine that speaks Spark Connect, and it is fast. But it ships as
a Python wheel, so from Scala or Java there is nothing to bring a server up
with. This is that missing piece: it starts `sail spark server`, hands you the
url, and shuts it down when the suite ends.

```scala
libraryDependencies += "com.devel0pez" %% "sail-testkit" % "<version>" % Test
```

## Use it

Mix `SailSuite` into a ScalaTest suite and you get a `spark` that talks to Sail:

```scala
import com.devel0pez.sail.testkit.SailSuite
import org.scalatest.funsuite.AnyFunSuite

class MyEtlSpec extends AnyFunSuite with SailSuite {
  test("aggregates by key") {
    val out = MyEtl.transform(spark.read.parquet("src/test/resources/input"))
    assert(out.count() == 3)
  }
}
```

`sbt test` works with nothing running beforehand. One server per suite, started
in `beforeAll` and stopped in `afterAll`.

If you would rather manage the session yourself, use the launcher directly:

```scala
import com.devel0pez.sail.testkit.SailServer

SailServer.withServer { server =>
  val spark = SparkSession.builder().remote(server.url).getOrCreate()
  ...
}
```

## What will not run, and why

Pointing an existing suite at Sail works for far more than you would guess, but
there is one hard line: **anything that ships JVM bytecode to the engine**.

Spark Connect sends the query as a plan, and a closure is not an expression —
it travels as a `ScalaUDF` message carrying the serialised function. A regular
Connect server is a JVM, so it deserialises it and runs it. Sail is Rust and
has nothing to run it with, so it refuses from the server side:

```
SparkUnsupportedOperationException: Scala UDF is not supported yet   # udf(), groupByKey
SparkUnsupportedOperationException: wildcard with plan id            # map, typed filter
```

The second one is worth knowing by sight: it names neither UDFs nor closures,
because Sail gives up while resolving the plan rather than on the UDF itself.

In practice, on Sail:

| | |
| --- | --- |
| `df.as[T]`, `Seq[T].toDS()` | works — encoders are a client-side matter |
| `select`, `filter`, `join`, `groupBy` with **columns** | works |
| `insertInto`, `saveAsTable`, reading back as `Dataset[T]` | works |
| `map(x => ...)`, `filter(_.field > 0)`, `groupByKey` | **fails** |
| Scala UDFs | **fails** |
| RDDs | **fails** — no RDD API over Connect at all |

So a suite written against typed lambdas will not run, and that is worth
knowing before you spend an afternoon on it. A suite written with columns will,
types and all.

Worth saying plainly: this is not a Sail limitation you are working around. A
typed `map` has always been opaque to Catalyst — it cannot see through the
closure to push a filter or prune a column — so the column form is the one you
wanted anyway. Sail just makes the cost visible.

## Reporting what you find

The report groups failures **by cause**, and it does that on purpose. One
missing coercion can fail hundreds of scenarios, and hundreds of issues for one
bug helps nobody. File one issue per cause, with the smallest query that shows
it.

And check the pairing first: the corpus and the `pysail` binary must come from
the same Sail release. Running a newer corpus against an older binary reports
every fix that landed in between as though it were a bug — see
[docs/CORPUS.md](docs/CORPUS.md).

## Requirements

- **A Sail binary on the PATH**: `pip install pysail`, or point `SAIL_BIN` at it.
- **`pyspark` installed next to `pysail`**, matching your client version. Sail
  reads the Spark version from that module; without it `spark.version` fails
  with `ModuleNotFoundError: No module named 'pyspark'`.
- **Java 17+** and these JVM flags on the test JVM, or Arrow will fail with
  `sun.misc.Unsafe ... not available`. `spark-submit` sets them itself; sbt
  does not:

```scala
Test / fork := true,
Test / javaOptions ++= Seq(
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "-Dio.netty.tryReflectionSetAccessible=true"
)
```

The Spark Connect client and ScalaTest are `Provided`: this brings neither into
your build, so your own versions win.

## Sharing one server across a build

Starting a server per suite is fine locally. In CI it is cheaper to start one
and point everything at it — set `SPARK_REMOTE` and the kit connects to that
instead of spawning its own, and leaves it running when the suite ends:

```bash
sail spark server --port 50051 &
SPARK_REMOTE=sc://localhost:50051 sbt test
```

## Developing this

```bash
nix develop   # JDK 17, sbt, scalafmt, and a venv with the Sail server
t             # tests
f             # format
```

The devshell installs `pysail` and `pyspark` from `versions.json`, which
`build.sbt` reads too — client and server cannot drift apart.

Without Nix: Java 17, sbt, and `pip install pysail pyspark` yourself.

## Status

Early. It does one thing and the tests cover it, but it has not been through
much beyond that. Issues and PRs welcome.

Not affiliated with LakeSail. If a JVM-side kit ever lands upstream, this should
give way to it.

## License

Apache-2.0, matching Sail.
