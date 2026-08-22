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
// `sonatypeCentralRelease` is the Central Portal equivalent, and it uploads the
// same bundle directory that ci-release staged into.
addCommandAlias("sonaRelease", "sonatypeCentralRelease")

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
