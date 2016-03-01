package models

import play.api.Play.current
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global

import com.ning.http.client.Realm.AuthScheme


object BlockchainParser {

  val config = play.api.Play.configuration

  private def connectionParameters(ticker:String) = {
    val ipNode = config.getString("coins."+ticker+".ipNode")
    val rpcPort = config.getString("coins."+ticker+".rpcPort")
    val rpcUser = config.getString("coins."+ticker+".rpcUser")
    val rpcPass = config.getString("coins."+ticker+".rpcPass")
    (ipNode, rpcPort, rpcUser, rpcPass) match {
      case (Some(ip), Some(port), Some(user), Some(pass)) => ("http://"+ip+":"+port, user, pass)
      case _ => throw new Exception("'coins."+ticker+".ipNode', 'coins."+ticker+".rpcPort', 'coins."+ticker+".rpcUser' or 'coins."+ticker+".rpcPass' are missing in application.conf")
    }
  }

  private def block(ticker:String, blockHash:String) = {
    val rpcRequest = Json.obj("jsonrpc" -> "1.0",
                              "method" -> "getblock",
                              "params" -> Json.arr(blockHash))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      response.body
    }
  }

  private def transaction(ticker:String, txHash:String) = {

  }

  def resume(ticker:String) = {
    //on reprend la suite de l'indexation à partir du dernier block stocké dans notre bdd
    val genesisBlock = config.getString("coins."+ticker+".genesisBlock").get
    this.block(ticker, genesisBlock)
  }

}