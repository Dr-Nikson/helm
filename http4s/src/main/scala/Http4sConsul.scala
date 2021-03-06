package helm
package http4s

import java.util.UUID

import argonaut.Json
import argonaut.Json.jEmptyObject
import argonaut.StringWrap.StringToStringWrap
import cats.data.NonEmptyList
import cats.effect.Effect
import cats.~>
import cats.implicits._
import journal.Logger
import org.http4s.Method.PUT
import org.http4s._
import argonaut._
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers.Authorization
import org.http4s.Status.Successful
import org.http4s.syntax.string.http4sStringSyntax

final class Http4sConsulClient[F[_]](
  baseUri: Uri,
  client: Client[F],
  accessToken: Option[String] = None,
  credentials: Option[(String,String)] = None)
  (implicit F: Effect[F]) extends (ConsulOp ~> F) {

  private[this] val dsl = new Http4sClientDsl[F]{}
  import dsl._

  private implicit val keysDecoder: EntityDecoder[F, List[String]] = jsonOf[F, List[String]]
  private implicit val listKvGetResultDecoder: EntityDecoder[F, List[KVGetResult]] = jsonOf[F, List[KVGetResult]]
  private implicit val listServicesDecoder: EntityDecoder[F, Map[String, ServiceResponse]] = jsonOf[F, Map[String, ServiceResponse]]
  private implicit val listHealthChecksDecoder: EntityDecoder[F, List[HealthCheckResponse]] = jsonOf[F, List[HealthCheckResponse]]
  private implicit val listHealthNodesForServiceResponseDecoder: EntityDecoder[F, List[HealthNodesForServiceResponse]] =
    jsonOf[F, List[HealthNodesForServiceResponse]]
  private implicit val sessionCreateResponseDecoder: EntityDecoder[F, SessionCreateResponse] = jsonOf[F, SessionCreateResponse]
  private implicit val sessionInfoResponseDecoder: EntityDecoder[F, List[SessionInfoResponse]] = jsonOf[F, List[SessionInfoResponse]]

  private val log = Logger[this.type]

  def apply[A](op: ConsulOp[A]): F[A] = op match {
    case ConsulOp.SessionCreate(datacenter, lockDelay, node, name, checks, behavior, ttl) =>
      sessionCreate(datacenter, lockDelay, node, name, checks, behavior, ttl)
    case ConsulOp.SessionDestroy(uuid: UUID) => sessionDestroy(uuid)
    case ConsulOp.SessionInfo(uuid: UUID)    => sessionInfo(uuid)
    case ConsulOp.KVGet(key, recurse, datacenter, separator, index, wait) =>
      kvGet(key, recurse, datacenter, separator, index, wait)
    case ConsulOp.KVGetRaw(key, index, wait) => kvGetRaw(key, index, wait)
    case ConsulOp.KVSet(key, value, lockOperation) => kvSet(key, value, lockOperation)
    case ConsulOp.KVListKeys(prefix) => kvList(prefix)
    case ConsulOp.KVDelete(key)      => kvDelete(key)
    case ConsulOp.HealthListChecksForService(service, datacenter, near, nodeMeta, index, wait) =>
      healthChecksForService(service, datacenter, near, nodeMeta, index, wait)
    case ConsulOp.HealthListChecksForNode(node, datacenter, index, wait) =>
      healthChecksForNode(node, datacenter, index, wait)
    case ConsulOp.HealthListChecksInState(state, datacenter, near, nodeMeta, index, wait) =>
      healthChecksInState(state, datacenter, near, nodeMeta, index, wait)
    case ConsulOp.HealthListNodesForService(service, datacenter, near, nodeMeta, tag, passingOnly, index, wait) =>
      healthNodesForService(service, datacenter, near, nodeMeta, tag, passingOnly, index, wait)
    case ConsulOp.AgentRegisterService(service, id, tags, address, port, enableTagOverride, check, checks) =>
      agentRegisterService(service, id, tags, address, port, enableTagOverride, check, checks)
    case ConsulOp.AgentDeregisterService(service) => agentDeregisterService(service)
    case ConsulOp.AgentListServices               => agentListServices()
    case ConsulOp.AgentEnableMaintenanceMode(id, enable, reason) =>
      agentEnableMaintenanceMode(id, enable, reason)
  }

  private def addConsulToken(req: Request[F]): Request[F] =
    accessToken.fold(req)(tok => req.putHeaders(Header("X-Consul-Token", tok)))

  private def addCreds(req: Request[F]): Request[F] =
    credentials.fold(req){case (un,pw) => req.putHeaders(Authorization(BasicCredentials(un,pw)))}

  private val addHeaders: Request[F] => Request[F] =
    addConsulToken _ andThen addCreds _

  private def addLockOperation(uri: Uri, lockOperation: Option[LockOperation]) =
    lockOperation.fold(uri)( op => uri.+?(LockOperation.LockOperationType.toString(op.operation), op.id.show))
  /** A nice place to store the Consul response headers so we can pass them around */
  private case class ConsulHeaders(
    index:       Long,
    lastContact: Long,
    knownLeader: Boolean
  )

  /** Helper function to get the value of a header out of a Response. Only used by extractConsulHeaders */
  private def extractHeaderValue(header: String, response: Response[F]): F[String] = {
    response.headers.get(header.ci) match {
      case Some(header) => F.pure(header.value)
      case None         => F.raiseError(new NoSuchElementException(s"Header not present in response: $header"))
    }
  }

  /** Helper function to get Consul GET request metadata from response headers */
  private def extractConsulHeaders(response: Response[F]): F[ConsulHeaders] = {
    for {
      index       <- extractHeaderValue("X-Consul-Index", response).map(_.toLong)
      lastContact <- extractHeaderValue("X-Consul-LastContact", response).map(_.toLong)
      knownLeader <- extractHeaderValue("X-Consul-KnownLeader", response).map(_.toBoolean)
    } yield ConsulHeaders(index, lastContact, knownLeader)
  }

  /**
    * Encapsulates the functionality for parsing out the Consul headers from the HTTP response and decoding the JSON body.
    * Note: these headers are only present for a portion of the API.
    */
  private def extractQueryResponse[A](response: Response[F])(implicit d: EntityDecoder[F, A]): F[QueryResponse[A]] = response match {
    case Successful(_) =>
      for {
        headers     <- extractConsulHeaders(response)
        decodedBody <- response.as[A]
      } yield {
        QueryResponse(decodedBody, headers.index, headers.knownLeader, headers.lastContact)
      }
    case failedResponse =>
      handleConsulErrorResponse(failedResponse).flatMap(F.raiseError)
  }

  private def handleConsulErrorResponse(response: Response[F]): F[Throwable] = {
    response.as[String].map(errorMsg => new RuntimeException("Got error response from Consul: " + errorMsg))
  }

  def kvGet(
    key:        Key,
    recurse:    Option[Boolean],
    datacenter: Option[String],
    separator:  Option[String],
    index:      Option[Long],
    wait:       Option[Interval]
  ): F[QueryResponse[List[KVGetResult]]] = {
    for {
      _ <- F.delay(log.debug(s"fetching consul key $key"))
      req = addHeaders(
        Request(
          uri =
            (baseUri / "v1" / "kv" / key)
              .+??("recurse", recurse.filter(identity))
              .+??("dc", datacenter)
              .+??("separator", separator)
              .+??("index", index)
              .+??("wait", wait.map(Interval.toString))))
      response <- client.fetch[QueryResponse[List[KVGetResult]]](req) { response: Response[F] =>
        response.status match {
          case status@(Status.Ok|Status.NotFound) =>
            for {
              headers <- extractConsulHeaders(response)
              value   <- if (status == Status.Ok) response.as[List[KVGetResult]] else F.pure(List.empty)
            } yield {
              QueryResponse(value, headers.index, headers.knownLeader, headers.lastContact)
            }
          case _ =>
            handleConsulErrorResponse(response).flatMap(F.raiseError)
        }
      }
    } yield {
      log.debug(s"consul response for key get $key was $response")
      response
    }
  }

  def kvGetRaw(
    key:   Key,
    index: Option[Long],
    wait:  Option[Interval]
  ): F[QueryResponse[Option[Array[Byte]]]] = {
    for {
      _ <- F.delay(log.debug(s"fetching consul key $key"))
      req = addHeaders(
        Request(
          uri =
            (baseUri / "v1" / "kv" / key)
              .+?("raw")
              .+??("index", index)
              .+??("wait", wait.map(Interval.toString))))
      response <- client.fetch[QueryResponse[Option[Array[Byte]]]](req) { response: Response[F] =>
        response.status match {
          case status@(Status.Ok|Status.NotFound) =>
            for {
              headers <- extractConsulHeaders(response)
              value   <- if (status == Status.Ok) {
                response.body.compile.to[Array].map {
                  case Array() => None
                  case nonEmpty => Some(nonEmpty)
                }
              } else F.pure(None)
            } yield {
              QueryResponse(value, headers.index, headers.knownLeader, headers.lastContact)
            }
          case _ =>
            handleConsulErrorResponse(response).flatMap(F.raiseError)
        }
      }
    } yield {
      log.debug(s"consul response for raw key get $key is $response")
      response
    }
  }

  def kvSet(key: Key, value: Array[Byte], lockOperation: Option[LockOperation]): F[Boolean] =
    for {
      _ <- F.delay(log.debug(s"setting consul key $key to $value"))
      req <- PUT(value, addLockOperation(baseUri / "v1" / "kv" / key, lockOperation)).map(addHeaders)
      response <- client.expectOr[String](req)(handleConsulErrorResponse)
      bool <- F.delay(java.lang.Boolean.valueOf(response))
    } yield {
      log.debug(s"setting consul key $key resulted in response $response")
      bool
    }

  def kvList(prefix: Key): F[Set[Key]] = {
    val req = addHeaders(Request(uri = (baseUri / "v1" / "kv" / prefix).withQueryParam(QueryParam.fromKey("keys"))))

    for {
      _ <- F.delay(log.debug(s"listing key consul with the prefix: $prefix"))
      response <- client.expectOr[List[String]](req)(handleConsulErrorResponse)
    } yield {
      log.debug(s"listing of keys: $response")
      response.toSet
    }
  }

  def kvDelete(key: Key): F[Unit] = {
    val req = addHeaders(Request(Method.DELETE, uri = (baseUri / "v1" / "kv" / key)))

    for {
      _ <- F.delay(log.debug(s"deleting $key from the consul KV store"))
      response <- client.expectOr[String](req)(handleConsulErrorResponse)
    } yield log.debug(s"response from delete: $response")
  }

  def healthChecksForService(
    service:    String,
    datacenter: Option[String],
    near:       Option[String],
    nodeMeta:   Option[String],
    index:      Option[Long],
    wait:       Option[Interval]
  ): F[QueryResponse[List[HealthCheckResponse]]] = {
    for {
      _ <- F.delay(log.debug(s"fetching health checks for service $service"))
      req = addHeaders(
        Request(
          uri =
            (baseUri / "v1" / "health" / "checks" / service)
              .+??("dc", datacenter)
              .+??("near", near)
              .+??("node-meta", nodeMeta)
              .+??("index", index)
              .+??("wait", wait.map(Interval.toString))))
      response <- client.fetch[QueryResponse[List[HealthCheckResponse]]](req)(extractQueryResponse)
    } yield {
      log.debug(s"health check response: " + response)
      response
    }
  }

  def healthChecksForNode(
    node:       String,
    datacenter: Option[String],
    index:      Option[Long],
    wait:       Option[Interval]
  ): F[QueryResponse[List[HealthCheckResponse]]] = {
    for {
      _ <- F.delay(log.debug(s"fetching health checks for node $node"))
      req = addHeaders(
        Request(
          uri =
            (baseUri / "v1" / "health" / "node" / node)
              .+??("dc", datacenter)
              .+??("index", index)
              .+??("wait", wait.map(Interval.toString))))
      response <- client.fetch[QueryResponse[List[HealthCheckResponse]]](req)(extractQueryResponse)
    } yield {
      log.debug(s"health checks for node response: $response")
      response
    }
  }

  def healthChecksInState(
    state:      HealthStatus,
    datacenter: Option[String],
    near:       Option[String],
    nodeMeta:   Option[String],
    index:      Option[Long],
    wait:       Option[Interval]
  ): F[QueryResponse[List[HealthCheckResponse]]] = {
    for {
      _ <- F.delay(log.debug(s"fetching health checks for service ${HealthStatus.toString(state)}"))
      req = addHeaders(
        Request(
          uri =
            (baseUri / "v1" / "health" / "state" / HealthStatus.toString(state))
              .+??("dc", datacenter)
              .+??("near", near)
              .+??("node-meta", nodeMeta)
              .+??("index", index)
              .+??("wait", wait.map(Interval.toString))))
      response <- client.fetch[QueryResponse[List[HealthCheckResponse]]](req)(extractQueryResponse)
    } yield {
      log.debug(s"health checks in state response: $response")
      response
    }
  }

  def healthNodesForService(
    service:     String,
    datacenter:  Option[String],
    near:        Option[String],
    nodeMeta:    Option[String],
    tag:         Option[String],
    passingOnly: Option[Boolean],
    index:       Option[Long],
    wait:        Option[Interval]
  ): F[QueryResponse[List[HealthNodesForServiceResponse]]] = {
    for {
      _ <- F.delay(log.debug(s"fetching nodes for service $service from health API"))
      req = addHeaders(
        Request(
          uri =
            (baseUri / "v1" / "health" / "service" / service)
              .+??("dc", datacenter)
              .+??("near", near)
              .+??("node-meta", nodeMeta)
              .+??("tag", tag)
              .+??("passing", passingOnly.filter(identity)) // all values of passing parameter are treated the same by Consul
              .+??("index", index)
              .+??("wait", wait.map(Interval.toString))))

      response <- client.fetch[QueryResponse[List[HealthNodesForServiceResponse]]](req)(extractQueryResponse)
    } yield {
      log.debug(s"health nodes for service response: $response")
      response
    }
  }

  def agentRegisterService(
    service:           String,
    id:                Option[String],
    tags:              Option[NonEmptyList[String]],
    address:           Option[String],
    port:              Option[Int],
    enableTagOverride: Option[Boolean],
    check:             Option[HealthCheckParameter],
    checks:            Option[NonEmptyList[HealthCheckParameter]]
  ): F[Unit] = {
    val json: Json =
      ("Name"              :=  service)              ->:
      ("ID"                :=? id)                   ->?:
      ("Tags"              :=? tags.map(_.toList))   ->?:
      ("Address"           :=? address)              ->?:
      ("Port"              :=? port)                 ->?:
      ("EnableTagOverride" :=? enableTagOverride)    ->?:
      ("Check"             :=? check)                ->?:
      ("Checks"            :=? checks.map(_.toList)) ->?:
      jEmptyObject

    for {
      _ <- F.delay(log.debug(s"registering $service with json: ${json.toString}"))
      req <- PUT(json, baseUri / "v1" / "agent" / "service" / "register").map(addHeaders)
      response <- client.expectOr[String](req)(handleConsulErrorResponse)
    } yield log.debug(s"registering service $service resulted in response $response")
  }

  def agentDeregisterService(id: String): F[Unit] = {
    val req = addHeaders(Request(Method.PUT, uri = (baseUri / "v1" / "agent" / "service" / "deregister" / id)))
    for {
      _ <- F.delay(log.debug(s"deregistering service with id $id"))
      response <- client.expectOr[String](req)(handleConsulErrorResponse)
    } yield log.debug(s"response from deregister: " + response)
  }

  def agentListServices(): F[Map[String, ServiceResponse]] = {
    for {
      _ <- F.delay(log.debug(s"listing services registered with local agent"))
      req = addHeaders(Request(uri = (baseUri / "v1" / "agent" / "services")))
      services <- client.expectOr[Map[String, ServiceResponse]](req)(handleConsulErrorResponse)
    } yield {
      log.debug(s"got services: $services")
      services
    }
  }

  def agentEnableMaintenanceMode(id: String, enable: Boolean, reason: Option[String]): F[Unit] = {
    for {
      _ <- F.delay(log.debug(s"setting service with id $id maintenance mode to $enable"))
      req = addHeaders(
        Request(Method.PUT,
          uri = (baseUri / "v1" / "agent" / "service" / "maintenance" / id).+?("enable", enable).+??("reason", reason)))
      response  <- client.expectOr[String](req)(handleConsulErrorResponse)
    } yield log.debug(s"setting maintenance mode for service $id to $enable resulted in $response")
  }

  def sessionCreate(
    datacenter: Option[String],
    lockDelay:  Option[String],
    node:       Option[String],
    name:       Option[String],
    checks:     Option[NonEmptyList[String]],
    behavior:   Option[Behavior],
    ttl:        Option[Interval]
  ): F[SessionCreateResponse] = {

    val json: Json =
      ("LockDelay"                 :=? lockDelay)              ->?:
        ("Node"                      :=? node)                 ->?:
        ("Name"                      :=? name)                 ->?:
        ("Checks"                    :=? checks.map(_.toList)) ->?:
        ("Behavior"                  :=? behavior)             ->?:
        ("TTL"                       :=? ttl)                  ->?:
        jEmptyObject

    for {
      _        <- F.delay(log.debug(s"creating session with json: ${json.toString}"))
      req      <- PUT(json, (baseUri / "v1" / "session" / "create").+??("dc", datacenter)).map(addHeaders)
      response <- client.expectOr[SessionCreateResponse](req)(handleConsulErrorResponse)
    } yield {
      log.debug(s"creating session resulted in consul response $response")
      response
    }
  }

  def sessionDestroy(uuid: UUID): F[Unit] =
    for {
      _        <- F.delay(log.debug(s"destroying $uuid session"))
      req      =  addHeaders(Request(Method.PUT, uri = (baseUri / "v1" / "session" / "destroy" / uuid.show)))
      response <- client.expectOr[String](req)(handleConsulErrorResponse)
    } yield log.debug(s"response from delete: $response")


  def sessionInfo(uuid: UUID): F[QueryResponse[List[SessionInfoResponse]]] =
    for {
      _        <- F.delay(log.debug(s"fetching $uuid session info"))
      req      =  addHeaders(Request(uri = (baseUri / "v1" / "session" / "info" / uuid.show)))
      response <- client.fetch[QueryResponse[List[SessionInfoResponse]]](req){ response: Response[F] =>
        response.status match {
          case status@(Status.Ok|Status.NotFound) =>
            for {
              headers <- extractConsulHeaders(response)
              value   <- if (status == Status.Ok) response.as[List[SessionInfoResponse]] else F.pure(List.empty)
            } yield {
              QueryResponse(value, headers.index, headers.knownLeader, headers.lastContact)
            }
          case _ =>
            handleConsulErrorResponse(response).flatMap(F.raiseError)
        }
      }
    } yield {
      log.debug(s"response for session $uuid was $response")
      response
    }
}
