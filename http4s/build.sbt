
val http4sOrg = "org.http4s"
val http4sVersion = "0.21.8"
val dockeritVersion = "0.9.9"
val logForCatsVersion = "1.1.1"



libraryDependencies ++= Seq(
  "io.chrisdavenport" %% "log4cats-slf4j"    % logForCatsVersion,
  http4sOrg            %% "http4s-blaze-client"             % http4sVersion,
  http4sOrg            %% "http4s-argonaut"                 % http4sVersion,
  "com.whisk"          %% "docker-testkit-scalatest"        % dockeritVersion % "test",
  "com.whisk"          %% "docker-testkit-impl-spotify"     % dockeritVersion % "test"
)

(initialCommands in console) := """
import helm.http4s.Http4sConsulClient

import cats.effect.IO
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, Duration}

import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.headers.{AgentProduct, `User-Agent`}
import org.http4s.util.threads
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.{Executors, ExecutorService}
import javax.net.ssl.SSLContext

val clientEC = {
  val maxThreads = math.max(4, (Runtime.getRuntime.availableProcessors * 1.5).ceil.toInt)
  val threadFactory = threads.threadFactory(name = (i => s"http4s-blaze-client-$i"), daemon = true)
  ExecutionContext.fromExecutor(Executors.newFixedThreadPool(maxThreads, threadFactory))
}

implicit val contextShift = IO.contextShift(clientEC)

val http = {
  BlazeClientBuilder[IO](clientEC)
  .withMaxTotalConnections(10)
  .withIdleTimeout(60.seconds)
  .withBufferSize(8 * 1024)
  .withUserAgent(`User-Agent`(AgentProduct("http4s-blaze", Some(org.http4s.BuildInfo.version))))
  .resource.allocated.unsafeRunSync()._1
}

val c = new Http4sConsulClient(Uri.uri("http://localhost:8500"), http)
"""
