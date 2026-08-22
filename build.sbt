// Versions live in versions.json, which flake.nix reads too: the Sail server
// resolves its Spark version from the `pyspark` module installed next to it,
// so client and server have to move together.
def versionOf(key: String): String = {
  val json = IO.read(file("versions.json"))
  val re = ("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").r
  re.findFirstMatchIn(json).map(_.group(1)).getOrElse(sys.error(s"versions.json has no '$key'"))
}

// Spark 4 only publishes for Scala 2.13, so that is the only version worth
// cross-building: anyone who could use this already builds against 2.13.
ThisBuild / scalaVersion := versionOf("scala")
ThisBuild / organization := "com.devel0pez"
ThisBuild / organizationName := "devel0pez.com"
ThisBuild / homepage := Some(url("https://github.com/devel0pez-com/sail-testkit"))
ThisBuild / licenses := Seq(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")
)
// Goes into the POM, where a consumer's build tool reads it to decide whether a
// version bump it resolved is a compatible one or a break it should complain
// about. Left unset, sbt warns on every publish and downstream eviction checks
// have nothing to go on.
//
// `early-semver`, not `semver-spec`: under strict semver every 0.x release is
// allowed to break everything, so a `0.1.0` -> `0.1.1` bump would carry no
// promise at all. early-semver keeps the patch digit meaningful before 1.0.0 —
// `0.1.z` stays compatible, `0.2.0` is where things may move — which is the
// promise this project can actually keep while the API is still settling.
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/devel0pez-com/sail-testkit"),
    "scm:git:https://github.com/devel0pez-com/sail-testkit.git",
    Some("scm:git:git@github.com:devel0pez-com/sail-testkit.git")
  )
)
// Publishing goes through the Central Portal. It is not the plugin's default:
// it still points at oss.sonatype.org, the legacy OSSRH, which stopped taking
// new namespaces when it was sunset. Left unset, `sbt ci-release` stages into a
// host that will never publish this and says little about why.
// Credentials go to the Central Portal, not to the legacy OSSRH the plugin
// still defaults to.
ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost

// sbt-ci-release ends a tagged release by running `sonaRelease`, a command no
// published version of sbt-sonatype defines: without this the build signs and
// stages correctly and then dies on the last step with "Not a valid command".
// Both Central Portal commands upload the same bundle directory ci-release
// staged into; they differ in what happens next.
//
// `sonatypeCentralUpload`, not `sonatypeCentralRelease`, on purpose: it stops
// the deployment at VALIDATED, so signatures, sources, javadoc and the POM can
// be read in the Portal before anything reaches Maven Central. From there it is
// one click to publish, or Drop and it never existed. `sonatypeCentralRelease`
// publishes as soon as validation passes, and Maven Central is immutable — a
// version that goes out cannot be replaced or withdrawn. The reversible path is
// the better default; a release that should skip the hold can override the
// whole command for that run with `CI_SONATYPE_RELEASE=sonatypeCentralRelease`.
addCommandAlias("sonaRelease", "sonatypeCentralUpload")

ThisBuild / developers := List(
  Developer(
    id = "davidlghellin",
    name = "David Lopez",
    email = "hola@devel0pez.com",
    url = url("https://github.com/davidlghellin")
  )
)

// sbt forks with the JVM that launched it, not the one JAVA_HOME points at.
// Nix packages sbt with its own JDK, so this makes the shell win.
ThisBuild / javaHome := sys.env.get("JAVA_HOME").map(file)

val sparkVersion = versionOf("spark")
val scalaTestVersion = "3.2.19"

// Spark and Arrow reach into JDK internals that are closed off since Java 17.
// `spark-submit` passes these itself; from sbt they have to be set by hand, or
// the first collect() dies with "sun.misc.Unsafe ... not available".
val jvmOptions = Seq(
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  // Spark's date conversion reaches into sun.util.calendar.ZoneInfo; without
  // this every date column fails with IllegalAccessException.
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
  "-Dio.netty.tryReflectionSetAccessible=true",
  // The session time zone only decides what the server computes. Rendering
  // happens here, and `java.sql.Timestamp.toString` uses the JVM default zone,
  // so without this every timestamp is off by the developer's offset — and the
  // report would blame Sail for the machine's locale.
  "-Duser.timezone=UTC"
)

val corpus = taskKey[Unit]("Run Sail's feature corpus and write a compatibility report")

lazy val root = (project in file("."))
  .settings(
    name := "sail-testkit",
    libraryDependencies ++= Seq(
      // Provided, not Test: a test kit must not drag a Spark client or a test
      // framework into anyone's build, and the consumer's own versions have to
      // win. sbt puts Provided on the test classpath too, so this project's
      // own tests still compile and run against them.
      "org.apache.spark" %% "spark-connect-client-jvm" % sparkVersion % Provided,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Provided,
      // Test scope only: none of this reaches the published artifact. Sail's
      // own `.feature` files are run through this kit as validation — if the
      // launcher works, their corpus passes over a JVM client the same way it
      // does over the Python one.
      "io.cucumber" %% "cucumber-scala" % "8.39.7" % Test,
      "io.cucumber" % "cucumber-junit" % "7.34.7" % Test,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test
    ),
    // The corpus is a report, not a gate: it runs Sail's own feature files
    // through a JVM client and says what came out. It is excluded from `test`
    // so an upstream failure nobody can fix here cannot block a release, and
    // `corpus` swallows the result for the same reason.
    // Scoped to the `test` task, not the whole Test config: scoping it to the
    // config would hide the class from `testOnly` too, and `corpus` would have
    // nothing left to run.
    Test / test / testOptions += Tests.Exclude(Seq("com.devel0pez.sail.testkit.SailCorpusTest")),
    corpus := {
      val _ = (Test / testOnly).toTask(" com.devel0pez.sail.testkit.SailCorpusTest").result.value
      streams.value.log.info("corpus report: target/corpus-report.{html,json}")
    },
    // Every suite starts a real server on its own port; running them at once
    // would put several Sail processes and their Arrow buffers in flight
    // together for no gain.
    Test / parallelExecution := false,
    Test / fork := true,
    Test / javaOptions ++= jvmOptions,
    // Forwarded to the forked test JVM so the corpus can be filtered from the
    // command line. What a tag means depends on the server: against Spark the
    // run needs `not @sail-only`, against Sail it needs no filter at all.
    Test / javaOptions ++= sys.props
      .get("cucumber.filter.tags")
      .map(v => s"-Dcucumber.filter.tags=$v")
      .toSeq,
    Test / javaOptions ++= sys.props
      .get("sail.schemaDump")
      .map(v => s"-Dsail.schemaDump=$v")
      .toSeq,
    // Same forwarding, for running one slice of the corpus instead of all of
    // it. Cucumber lets `cucumber.features` override the `features` in the
    // annotation, but only if the property reaches the JVM that runs it.
    Test / javaOptions ++= sys.props
      .get("cucumber.features")
      .map(v => s"-Dcucumber.features=$v")
      .toSeq,
    Test / baseDirectory := (ThisBuild / baseDirectory).value,
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlint")
  )
