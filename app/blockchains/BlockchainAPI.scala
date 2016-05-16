package blockchains

import scala.concurrent.Future
import play.api.mvc.RequestHeader
import play.api.libs.json._

trait BlockchainAPI {

    def getBlock(ticker: String, blockHash: String): Future[JsValue]

	def getBlockHash(ticker: String, blockHeight: Long): Future[JsValue]

    def getTransaction(ticker: String, txHash: String): Future[JsValue]
}