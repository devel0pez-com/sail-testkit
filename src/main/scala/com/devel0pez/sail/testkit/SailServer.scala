package com.devel0pez.sail.testkit

import java.io.IOException
import java.net.{ConnectException, InetSocketAddress, ServerSocket, Socket}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.util.control.NonFatal

/** A running Sail Spark Connect server, owned by the caller.
  *
  * Sail ships as a Python wheel, so there is nothing on the JVM side that starts one. This wraps
  * `sail spark server` so a test can ask for a server without knowing any of that.
  *
  * @param url
  *   the `sc://host:port` address to hand to `SparkSession.builder().remote(...)`
  */
final class SailServer private (process: Option[Process], val url: String) extends AutoCloseable {

  /** True when this instance started the process and is responsible for it. */
  def owned: Boolean = process.isDefined

  /** Stops the server, if this instance started it. Idempotent. */
  override def close(): Unit = process.foreach { p =>
    p.destroy()
    if (!p.waitFor(SailServer.ShutdownTimeout.toSeconds, TimeUnit.SECONDS)) p.destroyForcibly()
  }
}

object SailServer {

  /** The Sail binary. Comes from the `pysail` wheel; override with `SAIL_BIN`. */
  def binary: String = sys.env.getOrElse("SAIL_BIN", "sail")

  /** Address of an already-running server. Set it in CI to share one server. */
  val RemoteEnvVar = "SPARK_REMOTE"

  val StartTimeout: FiniteDuration = 30.seconds
  val ShutdownTimeout: FiniteDuration = 10.seconds

  /** Returns a server ready to accept connections.
    *
    * If `SPARK_REMOTE` is set, connects to that one and starts nothing: in CI it is cheaper to
    * start a single server for the whole build. Otherwise spawns one on a free port.
    */
  def start(): SailServer = sys.env.get(RemoteEnvVar).filter(_.trim.nonEmpty) match {
    case Some(remote) => new SailServer(None, remote.trim)
    case None         => spawn()
  }

  /** Starts a server, runs `body` against its url and always stops it after. */
  def withServer[A](body: SailServer => A): A = {
    val server = start()
    try body(server)
    finally server.close()
  }

  private def spawn(): SailServer = {
    val port = freePort()
    val process =
      try {
        new ProcessBuilder(binary, "spark", "server", "--ip", "127.0.0.1", "--port", port.toString)
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start()
      } catch {
        case e: IOException =>
          throw new IllegalStateException(
            s"Could not run '$binary'. Install it with `pip install pysail` and make sure it is " +
              s"on the PATH, or point SAIL_BIN at the binary. Cause: ${e.getMessage}",
            e
          )
      }

    awaitPort(port, process)
    new SailServer(Some(process), s"sc://127.0.0.1:$port")
  }

  /** Asks the OS for a port and releases it, for Sail to take on startup.
    *
    * There is a race between releasing it and Sail binding it, but it is the usual way to avoid a
    * hardcoded port, which would clash between concurrent runs.
    */
  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  private def awaitPort(port: Int, process: Process): Unit = {
    val deadline = System.nanoTime() + StartTimeout.toNanos
    while (System.nanoTime() < deadline) {
      if (!process.isAlive) {
        throw new IllegalStateException(
          s"The Sail server exited while starting up (code ${process.exitValue()})"
        )
      }
      if (accepts(port)) return
      Thread.sleep(100)
    }
    process.destroyForcibly()
    throw new IllegalStateException(s"Sail is not listening on port $port after $StartTimeout")
  }

  private def accepts(port: Int): Boolean = {
    val socket = new Socket()
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 200)
      true
    } catch {
      case _: ConnectException => false
      case NonFatal(_)         => false
    } finally socket.close()
  }
}
