import play.api._
import play.api.mvc._
import play.filters.gzip.GzipFilter

import scala.concurrent.ExecutionContext.Implicits.global

import models._

object Global extends WithFilters(new GzipFilter(shouldGzip = (request, response) =>
  response.headers.get("Content-Type").exists(_.startsWith("text/plain")))) with GlobalSettings {

  override def onStart(app: Application) {
    ApiLogs.info("BlockchainExplorer API started")

    //EmbeddedNeo4j2.dropDb
    EmbeddedNeo4j2.startService 
    
    val threadBlock = new Thread {
      override def run {
        Neo4jBlockchainIndexer.resume("ltc", true).map { response =>
          response match {
            case Right(s) => ApiLogs.debug("Neo4jBlockchainIndexer Block result : " + s)
            case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Block Exception : " + e.toString)
          }
        }
      }
    }
    threadBlock.start
    TransactionIndexer.start("ltc")    
  }

  override def onStop(app: Application) {
    ApiLogs.info("BlockchainExplorer API shutting down...")
  }
}
