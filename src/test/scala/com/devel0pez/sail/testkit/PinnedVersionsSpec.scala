package com.devel0pez.sail.testkit

import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** That `versions.json` is readable, and that the environment actually honours it. */
final class PinnedVersionsSpec extends AnyFunSuite with Matchers with OptionValues {

  test("reads the pins out of versions.json") {
    PinnedVersions.pysail.value should fullyMatch regex """\d+\.\d+\.\d+"""
    PinnedVersions.spark.value should fullyMatch regex """\d+\.\d+\.\d+"""
    PinnedVersions.corpusTag.value should startWith("v")
  }

  /** The corpus tag and the binary have to name the same release.
    *
    * `SailHooks` refuses a corpus run when they disagree, but that check only fires when someone
    * runs the corpus — and it does not gate the build. This one runs in `sbt test`, so a
    * half-finished bump is caught by CI on the pull request rather than by a report that quietly
    * blames Sail for changes between two releases.
    */
  test("the corpus tag, the pin and the installed binary all name the same release") {
    val pinned = PinnedVersions.pysail.value
    PinnedVersions.corpusTag.value shouldBe s"v$pinned"
    SailServer.version.value shouldBe pinned
  }
}
