package models

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import com.ning.http.client.Realm.AuthScheme


object BlockchainParser {

  val config = play.api.Play.configuration
  val elasticSearchUrl = config.getString("elasticsearch.url").get

  implicit val blockReads             = Json.reads[Block]
  implicit val blockWrites            = Json.writes[Block]
  implicit val scriptSigReads         = Json.reads[ScriptSig]
  implicit val scriptSigWrites        = Json.writes[ScriptSig]
  implicit val transactionVInReads    = Json.reads[TransactionVIn]
  implicit val transactionVInWrites   = Json.writes[TransactionVIn]
  implicit val scriptPubKeyReads      = Json.reads[ScriptPubKey]
  implicit val scriptPubKeyWrites     = Json.writes[ScriptPubKey]
  implicit val transactionVOutReads   = Json.reads[TransactionVOut]
  implicit val transactionVOutWrites  = Json.writes[TransactionVOut]
  implicit val transactionReads       = Json.reads[Transaction]
  implicit val transactionWrites      = Json.writes[Transaction]

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
                  on pourrait ne pas attendre le retour d'ES pour passer aux transactions/block suivant, 
                  mais actuellement ES ne suit pas : queue de 200 explosée, 
                  à voir aussi pour modifier les pools, threads pour augmenter la vitesse d'indexation d'ES 
                */

                /*
                  TODO:
                  gérer les exceptions et stopper l'indexation si une erreur s'est produite
                */

                this.exploreTransactions(ticker, block).map { response =>
                  block.nextblockhash match {
                    case Some(nextblockhash) => getBlock(ticker, nextblockhash)
                    case None => Logger.debug("Blocks synchronized !")
                  }
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

  private def exploreTransactions(ticker:String, block:Block, currentTx: Int = 0):Future[Unit] = {
    this.getTransaction(ticker, block.tx(currentTx)).map { response =>
      if(currentTx + 1 < block.tx.size){
        exploreTransactions(ticker, block, currentTx + 1)
      }
    }
  }

  private def getTransaction(ticker:String, txHash:String):Future[Unit] = {
    if(txHash == "97ddfbbae6be97fd6cdf3e7ca13232a3afff2353e29badfab7f73011edd4ced9"){
      /* Block genesis */
      Future.successful("")
    }else{
      val rpcRequestRaw = Json.obj("jsonrpc" -> "1.0",
                              "method" -> "getrawtransaction",
                              "params" -> Json.arr(txHash))

      val (url, user, pass) = this.connectionParameters(ticker)

      WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequestRaw).map { response =>
        val rpcResult = Json.parse(response.body)
        (rpcResult \ "result").validate[String] match {
          case raw: JsSuccess[String] => {
            val rpcRequestDecoded = Json.obj("jsonrpc" -> "1.0",
                                              "method" -> "decoderawtransaction",
                                              "params" -> Json.arr(raw.get))
            WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequestDecoded).map { response =>
              println(response.body)
              val rpcResult = Json.parse(response.body)
              (rpcResult \ "result") match {
                case JsNull => Logger.warn("Transaction '"+txHash+"' not found")
                case result: JsObject => {
                  result.validate[Transaction] match {
                    case t: JsSuccess[Transaction] => {
                      val tx = t.get

                      this.indexTransaction(ticker, tx).map{ response =>
                        Logger.debug("Transaction '"+tx.txid+"' added !")
                      }
                    }
                    case e: JsError => {
                      Logger.error("Invalid transaction from RPC "+ e)
                      throw new Exception("Invalid transaction from RPC")
                    } 
                  }
                }
                case _ => Logger.error("Invalid result from RPC")
              }
            }
          }
          case e: JsError => Logger.warn("Transaction '"+txHash+"' not found")
        }
      }
    }
  }

  private def indexTransaction(ticker:String, tx:Transaction) = {
    ElasticSearch.set(ticker, "transaction", tx.txid, Json.toJson(tx)).map { response =>
      println(response)
    }
  }

  private def start(ticker:String, fromBlockHash:String) = {
    this.getBlock(ticker, fromBlockHash)
  }

  def resume(ticker:String) = {
    //on reprend la suite de l'indexation à partir de l'avant dernier block stocké (si le dernier n'a pas été ajouté correctement) dans notre bdd
    ElasticSearch.getBeforeLastBlockHash(ticker, "block").map { beforeLastBlockHash =>
      beforeLastBlockHash match {
        case Some(b) => this.start(ticker, b)
        case None => Logger.error("No blocks found, can't resume")
      }
    }
  }

  def restart(ticker:String) = {
    //on recommence l'indexation à partir du block genesis
    val genesisBlock = config.getString("coins."+ticker+".genesisBlock").get
    this.start(ticker, genesisBlock)
  }

}