package blockchains

import scala.concurrent.Future
import play.api.mvc.RequestHeader
import play.api.libs.json._

trait BlockchainAPI {

    def getBlock(ticker: String, blockHeight: Long): Future[JsValue]

    def getUncle(ticker: String, blockHash:String, uncleIndex:Int): Future[JsValue]
}