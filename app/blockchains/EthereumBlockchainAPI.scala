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
    val portNode = config.getString("coins."+ticker+".portNode")
    val userNode = config.getString("coins."+ticker+".userNode")
    val passNode = config.getString("coins."+ticker+".passNode")

    ("http://"+ipNode+":"+portNode, userNode, passNode)
  }  

  def getBlock(ticker: String, blockHash: String): Future[JsValue] = {
    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url+"/block/"+blockHash).withAuth(user, pass, WSAuthScheme.BASIC).get().map { response =>
      Json.parse(response.body)
    }
  }

  def getTransaction(ticker: String, txHash: String): Future[JsValue] = {
    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url+"/transaction/"+txHash).withAuth(user, pass, WSAuthScheme.BASIC).get().map { response =>
      Json.parse(response.body)
    }
  }
}