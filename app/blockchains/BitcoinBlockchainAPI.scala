package blockchains

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object BitcoinBlockchainAPI extends BlockchainAPI {

  val config = play.Play.application.configuration

  private def connectionParameters(ticker:String) = {
    val ipNode = config.getString("coins."+ticker+".ipNode")
    val rpcPort = config.getString("coins."+ticker+".rpcPort")
    val rpcUser = config.getString("coins."+ticker+".rpcUser")
    val rpcPass = config.getString("coins."+ticker+".rpcPass")
    
    ("http://"+ipNode+":"+rpcPort, rpcUser, rpcPass)
  }  

  def getBlock(ticker: String, blockHash: String): Future[JsValue] = {
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "getblock",
                              "params" -> Json.arr(blockHash))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def getBlockHash(ticker: String, blockHeight: Long): Future[JsValue] = {
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "getblockhash",
                              "params" -> Json.arr(blockHeight))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def getTransaction(ticker: String, txHash: String): Future[JsValue] = {
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "getrawtransaction",
                              "params" -> Json.arr(txHash, 1))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def pushTransaction(ticker: String, hex:String): Future[(Int, String)] = {
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "sendrawtransaction",
                              "params" -> Json.arr(hex))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      (response.status, response.body)
    }
  }
}