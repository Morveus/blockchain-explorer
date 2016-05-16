import play.api._
import play.api.mvc._
import play.filters.gzip.GzipFilter

import scala.concurrent.ExecutionContext.Implicits.global

import models._

object Global extends WithFilters(new GzipFilter(shouldGzip = (request, response) =>
  response.headers.get("Content-Type").exists(_.startsWith("text/plain")))) with GlobalSettings {

  override def onStart(app: Application) {
    ApiLogs.info("BlockchainExplorer API started")

    EmbeddedNeo4j2.dropDb
    EmbeddedNeo4j2.startService 
    EmbeddedNeo4j2.cleanRedis
    
    Indexer.start()    
  }

  override def onStop(app: Application) {
    ApiLogs.info("BlockchainExplorer API shutting down...")
  }
}
