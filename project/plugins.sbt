// Publishing to Maven Central: signing, staging and releasing from CI.
//
// 1.12.x is the first line that targets the Central Portal. Earlier ones stage
// through oss.sonatype.org, the legacy OSSRH, which no longer accepts new
// namespaces — with 1.9.2 this project resolved `publishTo` to a host that
// would never have published it.
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")

// sbt-ci-release no longer depends on this, but its release step still calls a
// `sonaRelease` command, and nothing it ships defines one. This provides the
// Central Portal commands that `sonaRelease` is aliased to in build.sbt.
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.12.2")
