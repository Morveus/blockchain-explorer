import play.api._
import play.api.mvc._
import play.filters.gzip.GzipFilter

import scala.concurrent.ExecutionContext.Implicits.global

import models._

object Global extends WithFilters(new GzipFilter(shouldGzip = (request, response) =>
  response.headers.get("Content-Type").exists(_.startsWith("text/plain")))) with GlobalSettings {

  override def onStart(app: Application) {
    ApiLogs.info("BlockchainExplorer API started")

    // val testDB = new EmbeddedNeo4j
    // testDB.dropDb
    // testDB.createDb



    
    EmbeddedNeo4j2.dropDb
    EmbeddedNeo4j2.startService 

    val threadBlock = new Thread {
      override def run {
        Neo4jBlockchainIndexer.restart("ltc").map { response =>
          response match {
            case Right(s) => ApiLogs.debug("Neo4jBlockchainIndexer Block result : " + s)
            case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Block Exception : " + e.toString)
          }
        }
      }
    }
    threadBlock.start

    Thread.sleep(5000) 

    val threadTx1 = new Thread {
      override def run {
        Neo4jBlockchainIndexer.completeTransaction("ltc").map { response =>
          response match {
            case Right(s) => ApiLogs.debug("Neo4jBlockchainIndexer Transaction result : " + s)
            case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Transaction Exception : " + e.toString)
          }

          //EmbeddedNeo4j2.stopService
        }
      }
    }
    threadTx1.start
    
  }

  override def onStop(app: Application) {
    ApiLogs.info("BlockchainExplorer API shutting down...")
  }
}
