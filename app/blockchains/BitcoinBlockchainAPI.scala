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
    // (ipNode, rpcPort, rpcUser, rpcPass) match {
    //   case (Some(ip), Some(port), Some(user), Some(pass)) => ("http://"+ip+":"+port, user, pass)
    //   case _ => throw new Exception("'coins."+ticker+".ipNode', 'coins."+ticker+".rpcPort', 'coins."+ticker+".rpcUser' or 'coins."+ticker+".rpcPass' are missing in application.conf")
    // }
    ("http://"+ipNode+":"+rpcPort, rpcUser, rpcPass)
  }  

  def getBlock(ticker: String, blockHash: String): Future[JsValue] = {
    val rpcRequest = Json.obj("jsonrpc" -> "1.0",
                              "method" -> "getblock",
                              "params" -> Json.arr(blockHash))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def getTransaction(ticker: String, txHash: String): Future[JsValue] = {
     val rpcRequest = Json.obj("jsonrpc" -> "1.0",
                              "method" -> "getrawtransaction",
                              "params" -> Json.arr(txHash, 1))

      val (url, user, pass) = this.connectionParameters(ticker)

      WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
        Json.parse(response.body)
      }
  }
}