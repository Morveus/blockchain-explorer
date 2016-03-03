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

  implicit val esTransactionBlockReads  = Json.reads[ESTransactionBlock ]
  implicit val esTransactionBlockWrites = Json.writes[ESTransactionBlock ]
  implicit val esTransactionVInReads    = Json.reads[ESTransactionVIn]
  implicit val esTransactionVInWrites   = Json.writes[ESTransactionVIn]
  implicit val esTransactionVOutReads   = Json.reads[ESTransactionVOut]
  implicit val esTransactionVOutWrites  = Json.writes[ESTransactionVOut]
  implicit val esTransactionReads       = Json.reads[ESTransaction]
  implicit val esTransactionWrites      = Json.writes[ESTransaction]

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

    /*
      TODO:
      vérifier qu'il n'y a pas de réorg pendant l'indexation
    */

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

                      /*
                        TODO:
                        vérifier que lorsqu'on n'est pas dans le cas d'une transaction coinbase, les données des inputs soient bien tous renseignés
                      */

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
    //on récupère les informations qui nous manquent concernant la transaction

    var inputs: Map[Int, JsValue] = Map()
    for((vin, i) <- tx.vin.zipWithIndex){
      val input = vin.coinbase match {
        case Some(c) => {
          //generation transaction
          Future.successful(
            Json.obj(
              "coinbase" -> c,
              "input_index" -> i
            )
          )
        }
        case None => {
          //standard transaction
          ElasticSearch.getOutputFromTransaction(ticker, vin.txid.get, vin.vout.get).map { result => 
            result.validate[ESTransaction] match {
              case esTx: JsSuccess[ESTransaction] => {
                val output = esTx.get.outputs(vin.vout.get.toInt) /* TODO: A modifier quand ElasticSearch.getOutputFromTransaction retournera directement l'output */

                //On passe l'output en spent
                /* TODO URGENT */


                Json.obj(
                  "output_hash" -> vin.txid.get,
                  "output_index" -> vin.vout.get,
                  "input_index" -> i,
                  "value" -> output.value,
                  "addresses" -> output.addresses
                )
              }
              case e: JsError => Logger.warn("Invalid result from ElasticSearch")
            }
          }
        }
      }
      
      input.map { result =>
        //On ajoute l'input dans le tableau d'inputs
        inputs(i) = result  /* TODO URGENT: vérifier que i est à la bonne valeur, sinon il faudra le passer dans le résultat de input */
      }
    }

    var outputs: Map[Int, JsValue] = Map()
    for(vout <- tx.vout){
      val output = Json.obj(
        "value" -> vout.value,
        "output_index" -> vout.n,
        "script_hex" -> vout.scriptPubKey.hex,
        "addresses" -> vout.scriptPubKey.addresses,
        "spent_by" -> null
      )
      outputs(vout.n) = output
    }


    ElasticSearch.getBlockFromTxHash(ticker, tx.txid).map { result => 
      result.validate[Block] match {
        case b: JsSuccess[Block] => {
          val block = b.get
          Json.obj(
            "hash" -> block.hash,
            "height" -> block.height,
            "time" -> block.time
          )
        }
        case e: JsError => Logger.warn("Invalid result from ElasticSearch")
      }
    }


    /*
      TODO URGENT:
      Attendre que les futures soient terminées pour récupérer les données, les ajouter au Json et l'envoyer sur ES
    */

    /*

    var esTx = Json.obj(
      "hash" -> tx.txid,
      "lock_time" -> tx.locktime,
      "block" -> block,
      "inputs" -> inputs,
      "outputs" -> outputs,
      "fees" -> fees,
      "amount" -> amount)

    ElasticSearch.set(ticker, "transaction", tx.txid, Json.toJson(tx)).map { response =>
      println(response)
    }
    */
  }

  private def start(ticker:String, fromBlockHash:String) = {
    this.getBlock(ticker, fromBlockHash)
  }

  def resume(ticker:String) = {
    //on reprend la suite de l'indexation à partir de l'avant dernier block stocké (si le dernier n'a pas été ajouté correctement) dans notre bdd
    ElasticSearch.getBeforeLastBlockHash(ticker).map { beforeLastBlockHash =>
      beforeLastBlockHash match {
        case Some(b) => this.start(ticker, b)
        case None => Logger.error("No blocks found, can't resume")
      }
    }

    /*
      TODO:
      vérifier qu'il n'y a pas eu de réorg avant de reprendre l'indexation
    */
  }

  def restart(ticker:String) = {
    //on recommence l'indexation à partir du block genesis
    val genesisBlock = config.getString("coins."+ticker+".genesisBlock").get
    this.start(ticker, genesisBlock)
  }

  def tempTest() = {
    this.getTransaction("ltc", "ffff5c1145bcf43d33900d63cecfc75cf428c1713212444fcf16b9ebabc95419")
  }

}