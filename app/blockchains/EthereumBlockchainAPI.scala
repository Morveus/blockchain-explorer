package blockchains

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

object EthereumBlockchainAPI extends BlockchainAPI {

  val config = play.Play.application.configuration

  private def connectionParameters(ticker:String) = {
    val ipNode = config.getString("coins."+ticker+".ipNode")
    val rpcPort = config.getString("coins."+ticker+".rpcPort")
    val rpcUser = config.getString("coins."+ticker+".rpcUser")
    val rpcPass = config.getString("coins."+ticker+".rpcPass")
    
    ("http://"+ipNode+":"+rpcPort, rpcUser, rpcPass)
  }  

  def getBlock(ticker: String, blockHash: String): Future[JsValue] = {
    val rpcRequestId = Random.nextInt(10000000)
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "eth_getBlockByHash",
                              "id" -> rpcRequestId,
                              "params" -> Json.arr(blockHash, true))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def getBlockByHeight(ticker: String, blockHeight: Long): Future[JsValue] = {
    val height = Integer.toHexString(blockHeight.toInt)

    val rpcRequestId = Random.nextInt(10000000)
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "eth_getBlockByNumber",
                              "id" -> rpcRequestId,
                              "params" -> Json.arr(blockHeight, true))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def getLatestBlock(ticker: String): Future[JsValue] = {
    val rpcRequestId = Random.nextInt(10000000)
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "eth_getBlockByNumber",
                              "id" -> rpcRequestId,
                              "params" -> Json.arr("latest", true))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def getUncle(ticker: String, blockHash:String, uncleIndex:Int): Future[JsValue] = {
    val index = Integer.toHexString(uncleIndex)

    val rpcRequestId = Random.nextInt(10000000)    
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "eth_getUncleByBlockHashAndIndex",
                              "id" -> rpcRequestId,
                              "params" -> Json.arr(blockHash, index))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def pushTransaction(ticker: String, hex:String): Future[(Int, String)] = {
    val rpcRequestId = Random.nextInt(10000000)
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "eth_sendRawTransaction",
                              "id" -> rpcRequestId,
                              "params" -> Json.arr(hex))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      (response.status, response.body)
    }
  }

  def getMempool(ticker: String): Future[JsValue] = {
    val rpcRequestId = Random.nextInt(10000000)
    val rpcRequest = Json.obj("jsonrpc" -> "2.0",
                              "method" -> "eth_getBlockByNumber",
                              "id" -> rpcRequestId,
                              "params" -> Json.arr("pending", true))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      Json.parse(response.body)
    }
  }

  def getTransactionReceipt(ticker: String, txHashes:List[String]): Future[JsValue] = {
    
    val rpcRequest = ListBuffer[JsValue]()
    for(txHash <- txHashes){
      val rpcRequestId = Random.nextInt(10000000)
      rpcRequest += Json.obj("jsonrpc" -> "2.0",
                              "method" -> "eth_getTransactionReceipt",
                              "id" -> rpcRequestId,
                              "params" -> Json.arr(txHash))
    }
    

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(Json.toJson(rpcRequest)).map { response =>
      Json.toJson((Json.parse(response.body) \\ "result").toList)
    }
  }
}