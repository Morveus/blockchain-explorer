package models

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.concurrent.ExecutionContext.Implicits.global

import com.ning.http.client.Realm.AuthScheme


object BlockchainParser {

  val config = play.api.Play.configuration
  val elasticSearchUrl = config.getString("elasticsearch.url").get


  case class Block(
    hash: String,
    confirmations: Long,
    height: Long,
    tx: List[String],
    time: Long,
    previousblockhash: Option[String],
    nextblockhash: Option[String]
  )
  implicit val blockReads : Reads[Block] = (
    (JsPath \ "hash").read[String] and
    (JsPath \ "confirmations").read[Long] and
    (JsPath \ "height").read[Long] and
    (JsPath \ "tx").read[List[String]] and
    (JsPath \ "time").read[Long] and
    (JsPath \ "previousblockhash").readNullable[String] and
    (JsPath \ "nextblockhash").readNullable[String]
  )(Block.apply _)
  implicit val blockWrites: Writes[Block] = (
    (JsPath \ "hash").write[String] and
    (JsPath \ "confirmations").write[Long] and
    (JsPath \ "height").write[Long] and
    (JsPath \ "tx").write[List[String]] and
    (JsPath \ "time").write[Long] and
    (JsPath \ "previousblockhash").writeNullable[String] and
    (JsPath \ "nextblockhash").writeNullable[String]
  )(unlift(Block.unapply))


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

  private def getBlock(ticker:String, blockHash:String):Unit = {

    val rpcRequest = Json.obj("jsonrpc" -> "1.0",
                              "method" -> "getblock",
                              "params" -> Json.arr(blockHash))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      val rpcResult = Json.parse(response.body)
      (rpcResult \ "result") match {
        case JsNull => Logger.warn("Block '"+blockHash+"' not found")
        case result: JsObject => {
          result.validate[Block] match {
            case b: JsSuccess[Block] => {
              val block = b.get
              this.indexBlock(ticker, block).map{ response =>
                Logger.debug("Block '"+block.hash+"' added !")
                /* 
                  NOTE: 
                  on pourrait ne pas attendre le retour d'ES pour passer au block suivant, 
                  mais actuellement ES ne suit pas : queue de 200 explosée, 
                  à voir aussi pour modifier les pools, threads pour augmenter la vitesse d'indexation d'ES 
                */
                block.nextblockhash match {
                  case Some(nextblockhash) => getBlock(ticker, nextblockhash)
                  case None => Logger.debug("Blocks synchronized !")
                }
              }
            }
            case e: JsError => Logger.error("Invalid block from RPC")
          }
        }
        case _ => Logger.error("Invalid result from RPC")
      }
    }
  }

  private def indexBlock(ticker:String, block:Block) = {
    ElasticSearch.set(ticker, "block", block.hash, Json.toJson(block)).map { response =>
      //println(response)
    }
  }

  private def transaction(ticker:String, txHash:String) = {
    val rpcRequest = Json.obj("jsonrpc" -> "1.0",
                              "method" -> "getrawtransaction",
                              "params" -> Json.arr(txHash))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      response.body
    }
  }

  def resume(ticker:String) = {
    //on reprend la suite de l'indexation à partir du dernier block stocké dans notre bdd
    val genesisBlock = config.getString("coins."+ticker+".genesisBlock").get
    this.getBlock(ticker, genesisBlock)
    //this.transaction(ticker, "21f0cb929ac6890ccff94c95918031ce7875d8b8b1d3393a1d71f59a67cd334e")
  }

}