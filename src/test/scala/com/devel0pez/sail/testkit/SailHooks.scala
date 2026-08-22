package com.devel0pez.sail.testkit

import io.cucumber.scala.{EN, ScalaDsl}

/** Tears the server down when the feature run ends, not when the JVM exits.
  *
  * An `object`, not a `class`: cucumber-scala requires BeforeAll/AfterAll to live in a static
  * context and refuses to start otherwise.
  */
object SailHooks extends ScalaDsl with EN {

  AfterAll { SailWorld.shutdown() }
}
