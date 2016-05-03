package blockchains

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object EthereumBlockchainAPI extends BlockchainAPI {

  val config = play.Play.application.configuration

  private def connectionParameters(ticker:String) = {
    val ipNode = config.getString("coins."+ticker+".ipNode")
    val rpcPort = config.getString("coins."+ticker+".rpcPort")
    val rpcUser = config.getString("coins."+ticker+".rpcUser")
    val rpcPass = config.getString("coins."+ticker+".rpcPass")

    ("http://"+ipNode+":"+rpcPort, rpcUser, rpcPass)
  }  

  def getBlock(ticker: String, blockHeight: Long): Future[JsValue] = {
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "eth_getBlockByNumber",
                              "params" -> Json.arr(blockHeight, true))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def getUncle(ticker: String, blockHash:String, uncleIndex:Int): Future[JsValue] = {
    val index = Integer.toHexString(uncleIndex)
    
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "eth_getUncleByBlockHashAndIndex",
                              "params" -> Json.arr(blockHash, index))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }
}