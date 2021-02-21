package helm
package http4s

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import cats.effect.{IO, Sync}
import cats.implicits._
import com.spotify.docker.client.{DefaultDockerClient, DockerClient}
import com.whisk.docker._
import com.whisk.docker.impl.spotify.SpotifyDockerFactory
import com.whisk.docker.scalatest._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s._
import org.http4s.client.blaze._
import org.scalatestplus.scalacheck.Checkers.check
//import org.scalacheck._
import org.scalatest.matchers.must.Matchers.{be, contain}
import org.scalatest.matchers.must.Matchers.{be, not}
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
//import org.scalatest._
import org.scalatest.BeforeAndAfterAll
//import org.scalatest.propspec.
//import org.scalatest.matchers.{BeMatcher, MatchResult}
import org.scalatest.flatspec.AsyncFlatSpec
//import org.scalatest.enablers.
import org.scalacheck.Prop._
import org.scalacheck._
import org.scalatest.prop.Whenever
import org.scalatestplus.scalacheck.CheckerAsserting


trait DockerConsulService extends DockerKit {
//  private[this] val logger = Slf4jLogger.getLoggerFromClass[F](this.getClass)

  private val client: DockerClient = DefaultDockerClient.fromEnv().build()
  override implicit val dockerFactory: DockerFactory = new SpotifyDockerFactory(client)

  val ConsulPort = 18512

  val consulContainer =
    DockerContainer("consul:1.8.5", name = Some("consul"))
      .withPorts(8500 -> Some(ConsulPort))
//      .withLogLineReceiver(LogLineReceiver(true, s => logger.debug(s"consul: $s")))
      .withLogLineReceiver(LogLineReceiver(true, s => println(s"consul: $s")))
      .withCommand("agent", "-dev", "-client", "0.0.0.0")
      .withReadyChecker(DockerReadyChecker
        .HttpResponseCode(8500, "/v1/kv/test", None, 404) // Returns 500 status until elected leader
        .looped(12, 10.seconds))

  abstract override def dockerContainers: List[DockerContainer] =
    consulContainer :: super.dockerContainers
}

class IntegrationSpec
    extends AsyncFlatSpec
    with BeforeAndAfterAll
    with DockerConsulService
    with DockerTestKit with Whenever {

  val baseUrl: Uri =
    Uri.fromString(s"http://${dockerExecutor.host}:${ConsulPort}").valueOr(throw _)

  val ec = ExecutionContext.global
  implicit val contextShift = IO.contextShift(ec)
  import IO.ioConcurrentEffect

  // Tests are executed later so we cannot wrap the helm.run calls in a resource.use(), lest the resources be freed before the Http4sConsulClient is called
  // so... this is not a great example of how to use this code in the real world
  val client = BlazeClientBuilder[IO](ec).resource.allocated.unsafeRunSync()._1

  val interpreter = new Http4sConsulClient(baseUrl, client)

  "consul" should "work" in check { (k: String, v: Array[Byte]) =>
    helm.run(interpreter, ConsulOp.kvSet(k, v, None)).unsafeRunSync
    // Equality comparison for Option[Array[Byte]] doesn't work properly. Since we expect the value to always be Some making a custom matcher doesn't seem worthwhile, so call .get on the Option
    // See https://github.com/scalatest/scalatest/issues/491
    helm.run(interpreter, ConsulOp.kvGetRaw(k, None, None)).unsafeRunSync.value.get should be (v)

    helm.run(interpreter, ConsulOp.kvListKeys("")).unsafeRunSync should contain (k)
    helm.run(interpreter, ConsulOp.kvDelete(k)).unsafeRunSync
    helm.run(interpreter, ConsulOp.kvListKeys("")).unsafeRunSync should not contain (k)
    true
  }(implicitly, implicitly, Arbitrary(Gen.alphaStr suchThat(_.nonEmpty)), implicitly, implicitly, Arbitrary(Gen.nonEmptyContainerOf[Array, Byte](Gen.chooseNum(Byte.MinValue, Byte.MaxValue))), implicitly, implicitly, implicitly, implicitly)
}
