import play.api._
import play.api.mvc._
import play.filters.gzip.GzipFilter
import play.api.libs.concurrent._
import play.api.Play.current

import scala.concurrent.ExecutionContext.Implicits.global

import actors._
import akka.actor.ActorSystem
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask

import models._

object Global extends WithFilters(new GzipFilter(shouldGzip = (request, response) =>
  response.headers.get("Content-Type").exists(_.startsWith("text/plain")))) with GlobalSettings {

  override def onStart(app: Application) {
    ApiLogs.info("BlockchainExplorer API started")

    Akka.system.actorOf(Props[WebSocketActor], name = "blockchain-explorer")

    Indexer.start()
  }

  override def onStop(app: Application) {
    ApiLogs.info("BlockchainExplorer API shutting down...")
  }
}
