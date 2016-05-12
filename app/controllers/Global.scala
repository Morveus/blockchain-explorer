import play.api._
import play.api.mvc._
import play.filters.gzip.GzipFilter

import scala.concurrent.ExecutionContext.Implicits.global

import models._

object Global extends WithFilters(new GzipFilter(shouldGzip = (request, response) =>
  response.headers.get("Content-Type").exists(_.startsWith("text/plain")))) with GlobalSettings {

  override def onStart(app: Application) {
    ApiLogs.info("BlockchainExplorer API started")

    EmbeddedNeo4j2.dropDbBatch
    EmbeddedNeo4j2.startServiceBatch 
    
    Indexer.start("eth")    
  }

  override def onStop(app: Application) {
    ApiLogs.info("BlockchainExplorer API shutting down...")
  }
}
