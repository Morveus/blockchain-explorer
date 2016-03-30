import play.api._
import play.api.mvc._
import play.filters.gzip.GzipFilter

import scala.concurrent.ExecutionContext.Implicits.global

import models._

object Global extends WithFilters(new GzipFilter(shouldGzip = (request, response) =>
  response.headers.get("Content-Type").exists(_.startsWith("text/plain")))) with GlobalSettings {

  override def onStart(app: Application) {
    Logger.info("BlockchainExplorer API started")

    Neo4jBlockchainIndexer.resume("ltc", true).map { response =>
      response match {
        case Right(s) => Logger.debug("Neo4jBlockchainIndexer result : " + s)
        case Left(e) => Logger.error("Neo4jBlockchainIndexer Exception : " + e.toString)
      }
    }
  }

  override def onStop(app: Application) {
    Logger.info("BlockchainExplorer API shutting down...")
  }
}
