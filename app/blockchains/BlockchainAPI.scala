package blockchains

import scala.concurrent.Future
import play.api.mvc.RequestHeader
import play.api.libs.json._

trait BlockchainAPI {

    def getBlock(ticker: String, blockHash: String): Future[JsValue]

    def getBlockByHeight(ticker: String, blockHeight: Long): Future[JsValue]

    def getLatestBlock(ticker:String): Future[JsValue]

	def getUncle(ticker: String, blockHash: String, uncleIndex:Int): Future[JsValue]

    def pushTransaction(ticker: String, hex: String): Future[(Int, String)]
}