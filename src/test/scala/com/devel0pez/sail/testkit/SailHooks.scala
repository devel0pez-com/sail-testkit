package com.devel0pez.sail.testkit

import io.cucumber.scala.{EN, ScalaDsl}

/** Guards the version pairing before the run, and tears the server down after it.
  *
  * An `object`, not a `class`: cucumber-scala requires BeforeAll/AfterAll to live in a static
  * context and refuses to start otherwise.
  */
object SailHooks extends ScalaDsl with EN {

  /** Refuses to run a corpus that does not match the binary under it.
    *
    * The expected values come from one Sail release, so running them against another reports every
    * change in between as a divergence. That is not hypothetical: it produced 96 phantom findings
    * once, and it is invisible — the report looks perfectly normal and is entirely wrong.
    *
    * `versions.json` pins both halves and the devshell installs from it, but nothing guaranteed
    * they agreed at the moment of the run: a stale direnv cache silently reinstalled the previous
    * release, `SAIL_BIN` can point anywhere, and outside Nix the binary is whatever `pip` last
    * installed. Documenting the rule was not enough; this checks it.
    *
    * Only the corpus checks this. `SailSuite` and `SailServer` are published and must work against
    * whatever Sail a consumer runs — they have no opinion about this file.
    */
  BeforeAll {
    (PinnedVersions.pysail, SailServer.version) match {
      case (Some(pinned), Some(running)) if pinned != running =>
        throw new IllegalStateException(
          s"""Version mismatch: the corpus is pinned to Sail $pinned, the binary is $running.
             |
             |Every difference between those two releases would be reported as a divergence.
             |Fix the pairing before reading anything into a run:
             |
             |  - inside the devshell:  direnv reload   (or exit and re-enter)
             |  - elsewhere:            pip install pysail==$pinned
             |  - or point SAIL_BIN at the matching binary
             |
             |Bumping Sail means moving versions.json AND the sail-features submodule together —
             |see docs/CORPUS.md.""".stripMargin
        )
      // A version we cannot read is not a mismatch. Saying nothing beats blocking a run over a
      // binary that answers `--version` in a way we did not anticipate.
      case _ => ()
    }
  }

  AfterAll { SailWorld.shutdown() }
}
