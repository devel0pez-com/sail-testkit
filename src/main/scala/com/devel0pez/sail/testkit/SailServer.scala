package com.devel0pez.sail.testkit

import java.io.{BufferedReader, IOException, InputStreamReader}
import java.net.{ConnectException, InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
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
final class SailServer private (
    process: Option[Process],
    private val tail: SailServer.OutputTail,
    val url: String
) extends AutoCloseable {

  /** True when this instance started the process and is responsible for it. */
  def owned: Boolean = process.isDefined

  /** The last few lines the server printed, oldest first.
    *
    * Sail logs to stderr, which nothing on this side would otherwise show. When a query behaves
    * oddly the server usually said why, and this is where to look. Empty for a server this instance
    * did not start.
    */
  def recentOutput: Seq[String] = tail.snapshot

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

  /** How many lines of server output to keep for diagnostics. */
  private val TailLines = 20

  /** Returns a server ready to accept connections.
    *
    * If `SPARK_REMOTE` is set, connects to that one and starts nothing: in CI it is cheaper to
    * start a single server for the whole build. Otherwise spawns one on a free port.
    */
  def start(): SailServer = sys.env.get(RemoteEnvVar).filter(_.trim.nonEmpty) match {
    case Some(remote) => new SailServer(None, new OutputTail(0), remote.trim)
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
          .start()
      } catch {
        case e: IOException =>
          throw new IllegalStateException(
            s"Could not run '$binary'. Install it with `pip install pysail` and make sure it is " +
              s"on the PATH, or point SAIL_BIN at the binary. Cause: ${e.getMessage}",
            e
          )
      }

    // The pipe has to be drained for as long as the server runs. Without a
    // reader it fills up and Sail blocks on its own logging, which looks like a
    // hang with no cause anywhere. Discarding the output would avoid that too,
    // but then a server that dies takes the reason with it.
    val tail = new OutputTail(TailLines)
    val reader = drain(process, tail)

    awaitPort(port, process, tail, reader)
    new SailServer(Some(process), tail, s"sc://127.0.0.1:$port")
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

  private def drain(process: Process, tail: OutputTail): Thread = {
    val thread = new Thread(
      () => {
        val reader =
          new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
        try {
          var line = reader.readLine()
          while (line != null) {
            tail.add(line)
            line = reader.readLine()
          }
        } catch {
          case NonFatal(_) => () // the process went away mid-read; nothing to salvage
        } finally reader.close()
      },
      "sail-server-output"
    )
    // A daemon thread: a test kit must never be the reason a JVM refuses to exit.
    thread.setDaemon(true)
    thread.start()
    thread
  }

  private def awaitPort(port: Int, process: Process, tail: OutputTail, reader: Thread): Unit = {
    val deadline = System.nanoTime() + StartTimeout.toNanos
    while (System.nanoTime() < deadline) {
      if (!process.isAlive) {
        // The exit is visible before the last lines have been read: give the
        // reader a moment, or the message arrives without the part that
        // explains it.
        reader.join(2000)
        throw new IllegalStateException(
          explain(s"The Sail server exited while starting up (code ${process.exitValue()})", tail)
        )
      }
      if (accepts(port)) return
      Thread.sleep(100)
    }
    process.destroyForcibly()
    throw new IllegalStateException(
      explain(s"Sail is not listening on port $port after $StartTimeout", tail)
    )
  }

  /** Puts what the server said into the failure, instead of leaving it on a dead pipe. */
  private def explain(problem: String, tail: OutputTail): String = tail.snapshot match {
    case Nil   => s"$problem. It printed nothing before that."
    case lines => s"$problem. It printed:\n${lines.map("  " + _).mkString("\n")}"
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

  /** The last `maxLines` lines of output, kept so a failure can quote them.
    *
    * Bounded on purpose: a server that runs for an hour must not turn its log into a leak.
    */
  private final class OutputTail(maxLines: Int) {
    private val lines = new ArrayDeque[String](math.max(maxLines, 1))

    def add(line: String): Unit = if (maxLines > 0) lines.synchronized {
      if (lines.size == maxLines) lines.removeFirst()
      lines.addLast(line)
    }

    def snapshot: List[String] = lines.synchronized {
      lines.toArray(Array.empty[String]).toList
    }
  }
}
