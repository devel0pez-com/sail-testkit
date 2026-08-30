package com.devel0pez.sail.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.util.control.NonFatal

/** What `versions.json` pins, read the same way `build.sbt` and `flake.nix` read it.
  *
  * A regex rather than a JSON parser, matching what `build.sbt` already does: the file is a handful
  * of flat string fields, and a test kit that ships no dependencies has no business pulling one in
  * to read them.
  */
object PinnedVersions {

  private lazy val json: Option[String] =
    try Some(new String(Files.readAllBytes(Paths.get("versions.json")), StandardCharsets.UTF_8))
    catch { case NonFatal(_) => None }

  private def field(key: String): Option[String] =
    json.flatMap(("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").r.findFirstMatchIn(_).map(_.group(1)))

  def pysail: Option[String] = field("pysail")
  def spark: Option[String] = field("spark")
  def corpusTag: Option[String] = field("sailCorpusTag")
}
