import play.api._
import play.api.mvc._
import play.filters.gzip.GzipFilter

import models._

object Global extends WithFilters(new GzipFilter(shouldGzip = (request, response) =>
  response.headers.get("Content-Type").exists(_.startsWith("text/plain")))) with GlobalSettings {

  override def onStart(app: Application) {
    Logger.info("BlockchainExplorer API started")

    BlockchainParser.resume("ltc", true)
  }

  override def onStop(app: Application) {
    Logger.info("BlockchainExplorer API shutting down...")
  }
}
