package helm

import argonaut._
import Argonaut._
import cats.effect.IO
import org.scalactic.TypeCheckedTripleEquals
import ConsulOp._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, equal}

class ConsulOpTests extends AsyncFlatSpec with TypeCheckedTripleEquals {
  val I: Interpreter.Functions[ConsulOp, IO] = Interpreter.prepare[ConsulOp, IO]

  "getJson" should "return none right when get returns None" in {
    val interp = for {
      _ <- I.expectU[QueryResponse[Option[Array[Byte]]]] {
        case ConsulOp.KVGetRaw("foo", None, None) => IO.pure(QueryResponse(None, -1, true, -1))
      }
    } yield ()
    interp.run(kvGetJson[Json]("foo", None, None)).unsafeRunSync should equal(Right(QueryResponse(None, -1, true, -1)))
  }

  it should "return a value when get returns a decodeable value" in {
    val interp = for {
      _ <- I.expectU[QueryResponse[Option[Array[Byte]]]] {
        case ConsulOp.KVGetRaw("foo", None, None) => IO.pure(QueryResponse(Some("42".getBytes), -1, true, -1))
      }
    } yield ()
    interp.run(kvGetJson[Json]("foo", None, None)).unsafeRunSync should equal(Right(QueryResponse(Some(jNumber(42)), -1, true, -1)))
  }

  it should "return an error when get returns a non-decodeable value" in {
    val interp = for {
      _ <- I.expectU[QueryResponse[Option[Array[Byte]]]] {
        case ConsulOp.KVGetRaw("foo", None, None) => IO.pure(QueryResponse(Some("{".getBytes), -1, true, -1))
      }
    } yield ()
    interp.run(kvGetJson[Json]("foo", None, None)).unsafeRunSync should equal(Left("JSON terminates unexpectedly."))
  }
}
