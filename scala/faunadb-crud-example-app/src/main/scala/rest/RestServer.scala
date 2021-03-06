package rest

import akka.actor.{ActorSystem, CoordinatedShutdown, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1, Route}
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import faunadb.FaunaClient

import scala.util.{Failure, Success, Try}

/**
  * RestServer backed by Akka HTTP.
  *
  * @see [[https://doc.akka.io/docs/akka-http/current/ Akka HTTP]]
  */
object RestServer {

  private object BindFailure extends CoordinatedShutdown.Reason

  def start(restEndpoints: Seq[RestEndpoint] = Seq.empty)(implicit faunaClient: FaunaClient): Unit = {
    implicit val system = ActorSystem("rest-server")
    implicit val ec = system.dispatcher
    implicit val mat = ActorMaterializer()
    val log = system.log

    val settings = RestServerSettings(system)
    val (host, port) = (settings.host, settings.port)
    val routes = restEndpoints.map(_.routes).reduce(_ ~ _)
    val filteredRoutes = FaunaFilter.filter(routes)

    val shutdown = CoordinatedShutdown(system)

    Http()
      .bindAndHandle(filteredRoutes, host, port)
      .onComplete {
        case Failure(error) =>
          log.error(error, "Shutting down, because cannot bind to {}:{}!", host, port)
          shutdown.run(BindFailure)

        case Success(binding) =>
          log.info("Listening for HTTP connections on {}", binding.localAddress)
          shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "http-server.unbind") { () =>
            binding.unbind()
          }
      }
  }

}

// Settings
class RestServerSettings(config: Config) extends Extension {
  private val restServerConfig = config.getConfig("rest-server")
  val host = restServerConfig.getString("host")
  val port = restServerConfig.getInt("port")
}

object RestServerSettings extends ExtensionId[RestServerSettings] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): RestServerSettings =
    new RestServerSettings(system.settings.config)

  override def lookup(): ExtensionId[_ <: Extension] = RestServerSettings

}

// Filters
object FaunaFilter {
  val LastTxnTimeHeaderName = "X-Last-Txn-Time"

  def filter(routes: Route)(implicit faunaClient: FaunaClient): Route =
    // Extract lastXtnTime from Request
    extractLastTxnTime { lastXtnTime =>
      lastXtnTime.foreach { lastTxnTime =>
        faunaClient.syncLastTxnTime(lastTxnTime)
      }
      // Put back updated lastXtnTime into Response
      val lastTxnTime = faunaClient.lastTxnTime
      addLastTxnTime(lastTxnTime) {
        routes
      }
    }

  val extractLastTxnTime: Directive1[Option[Long]] = {
    def toLong(value: String): Option[Long] = Try(value.toLong).toOption
    optionalHeaderValueByName(LastTxnTimeHeaderName).map(_.flatMap(toLong))
  }

  def addLastTxnTime(lastTxnTime: Long): Directive0 =
    mapResponseHeaders { responseHeaders =>
      responseHeaders :+ RawHeader(LastTxnTimeHeaderName, lastTxnTime.toString)
    }

}
