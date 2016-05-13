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
import scala.util.{Success, Failure}
import scala.collection.mutable.{ListBuffer, Map}

import com.ning.http.client.Realm.AuthScheme

import java.io._

import blockchains._


object Neo4jBlockchainIndexer {

  val config = play.api.Play.configuration
  val RPCMaxQueries = config.getInt("rpc.maxqueries").get

  var maxqueries = RPCMaxQueries

  var blockchainsList: Map[String, BlockchainAPI] = Map()
  blockchainsList += ("btc" -> blockchains.BitcoinBlockchainAPI)
  blockchainsList += ("ltc" -> blockchains.BitcoinBlockchainAPI)
  blockchainsList += ("doge" -> blockchains.BitcoinBlockchainAPI)


  implicit val blockReads             = Json.reads[RPCBlock]
  implicit val blockWrites            = Json.writes[RPCBlock]
  implicit val scriptSigReads         = Json.reads[RPCScriptSig]
  implicit val scriptSigWrites        = Json.writes[RPCScriptSig]
  implicit val transactionVInReads    = Json.reads[RPCTransactionVIn]
  implicit val transactionVInWrites   = Json.writes[RPCTransactionVIn]
  implicit val scriptPubKeyReads      = Json.reads[RPCScriptPubKey]
  implicit val scriptPubKeyWrites     = Json.writes[RPCScriptPubKey]
  implicit val transactionVOutReads   = Json.reads[RPCTransactionVOut]
  implicit val transactionVOutWrites  = Json.writes[RPCTransactionVOut]
  implicit val transactionReads       = Json.reads[RPCTransaction]
  implicit val transactionWrites      = Json.writes[RPCTransaction]

  implicit val esTransactionBlockReads  = Json.reads[NeoBlock ]
  implicit val esTransactionBlockWrites = Json.writes[NeoBlock ]
  
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

  private def satoshi(btc:BigDecimal):Long = {
    (btc * 100000000).toLong
  }

  def processBlock(ticker:String, blockHash:String, prevBlockNode:Option[Long] = None):Future[Either[Exception,(String, Long, Option[String])]] = {
    getBlock(ticker, blockHash).flatMap { response =>
      response match {
        case Left(e) => Future(Left(e))
        case Right(rpcBlock) => {

          exploreTransactionsAsync(ticker, rpcBlock, 0).flatMap { response =>
            response match {
              case Right(txs) => {

                indexFullBlock(ticker, rpcBlock, prevBlockNode, txs)

              }
              case Left(e) => {
                Future(Left(e))
              }
            }
          }


          
        }
      }
    }
  }

  private def getBlock(ticker:String, blockHash:String):Future[Either[Exception,RPCBlock]] = {
    blockchainsList(ticker).getBlock(ticker, blockHash).map { response =>
      (response \ "result") match {
        case JsNull => {
          ApiLogs.error("Block '"+blockHash+"' not found")
          Left(new Exception("Block '"+blockHash+"' not found"))
        }
        case result: JsObject => {
          result.validate[RPCBlock] match {
            case b: JsSuccess[RPCBlock] => {
              Right(b.get)
            }
            case e: JsError => {
              ApiLogs.error("Invalid block '"+blockHash+"' from RPC : "+response)
              Left(new Exception("Invalid block '"+blockHash+"' from RPC : "+response))
            }
          }
        }
        case _ => {
          ApiLogs.error("Invalid block '"+blockHash+"' result from RPC : "+response)
          Left(new Exception("Invalid block '"+blockHash+"' result from RPC : "+response))
        }
      }
    }
  }

  private def indexFullBlock(ticker:String, rpcBlock:RPCBlock, prevBlockNode:Option[Long], transactions:ListBuffer[RPCTransaction]):Future[Either[Exception,(String, Long, Option[String])]] = {
    EmbeddedNeo4j2.batchInsert(rpcBlock, prevBlockNode, transactions).map { result =>
      result match {
        case Left(e) => Left(e)
        case Right(r) => {
          var (message, blockNode) = r

          rpcBlock.nextblockhash match {
            case Some(nextblockhash) => {
              Right(message, blockNode, rpcBlock.nextblockhash)
            }
            case None => {
              Right(message, blockNode, None)
            }
          }
          
        }
      }
    } recover {
      case e:Exception => Left(e)
    }
  }

  private def exploreTransactionsAsync(ticker:String, block:RPCBlock, currentPool:Int, batch:ListBuffer[RPCTransaction] = ListBuffer[RPCTransaction]()):Future[Either[Exception,ListBuffer[RPCTransaction]]] = {
    var resultsFuts: ListBuffer[Future[Either[Exception,Option[RPCTransaction]]]] = ListBuffer()

    val txNb = block.tx.length
    val poolsTxs = block.tx.grouped(maxqueries).toList

    for(tx <- poolsTxs(currentPool)){
      resultsFuts += processTransaction(ticker, tx)
    }

    if(resultsFuts.size > 0) {
      val futuresResponses: Future[ListBuffer[Either[Exception,Option[RPCTransaction]]]] = Future.sequence(resultsFuts)
      futuresResponses.flatMap { transactions =>
        var error:Option[Exception] = None
        for(transaction <- transactions){
          transaction match {
            case Left(e) => {
              error = Some(e)
            }
            case Right(tx) => {
              tx match {
                case None => /* genesis tx */
                case Some(t) => {
                  batch += t
                }
              }
            }
          }
        }

        error match {
          case Some(e) => Future(Left(e))
          case None => {

            if(currentPool + 1 < poolsTxs.size){
              exploreTransactionsAsync(ticker, block, currentPool + 1, batch).map { result =>
                result match {
                  case Right(b) => {
                    batch +: b
                    Right(batch)
                  }
                  case Left(e) => {
                    Left(e)
                  }
                }
              }
            }else{
              Future(Right(batch))
            }

          }
        }

      }
    }else{
      Future(Right(batch))
    }     
  }

  private def processTransaction(ticker:String, txHash:String):Future[Either[Exception,Option[RPCTransaction]]] = {
    val genesisTx = config.getString("coins."+ticker+".genesisTransaction").get
    if(txHash == genesisTx){
      Future(Right(None))
    }else{
      getTransaction(ticker, txHash).flatMap { response =>
        response match {
          case Left(e) => Future(Left(e))
          case Right(rpcTransaction) => {
            Future(Right(Some(rpcTransaction)))
          }
        }
      }
    }
  }

  private def getTransaction(ticker:String, txHash:String):Future[Either[Exception,RPCTransaction]] = {
    blockchainsList(ticker).getTransaction(ticker, txHash).map { response =>
      (response \ "result") match {
        case JsNull => {
          ApiLogs.error("Transaction '"+txHash+"' not found")
          Left(new Exception("Transaction '"+txHash+"' not found"))
        }
        case result: JsObject => {
          result.validate[RPCTransaction] match {
            case tx: JsSuccess[RPCTransaction] => {
              Right(tx.get)
            }
            case e: JsError => {
              ApiLogs.error("Invalid transaction '"+txHash+"' from RPC : "+response)
              Left(new Exception("Invalid transaction '"+txHash+"' from RPC : "+response))
            }
          }
        }
        case _ => {
          ApiLogs.error("Invalid transaction '"+txHash+"' from RPC : "+response)
          Left(new Exception("Invalid transaction '"+txHash+"' from RPC : "+response))
        }
      }
    }
  }


/*
  

  private def indexTransaction(ticker:String, rpcTx:RPCTransaction):Future[Either[Exception,String]] = {

      //on récupère les informations qui nous manquent concernant la transaction
      var resultsFuts: ListBuffer[Future[Unit]] = ListBuffer()

      var inputs: Map[Int, NeoInput] = Map()
      var outputs: Map[Int, NeoOutput] = Map()
      
      var previousOuts = Map[String, List[(Int, Int)]]()
      for((vin, i) <- rpcTx.vin.zipWithIndex){
        vin.coinbase match {
          case Some(c) => {
            //generation transaction
            inputs(i) = NeoInput(i, Some(c), None, None)
          }
          case None => {
            //standard transaction
            inputs(i) = NeoInput(i, None, Some(vin.vout.get.toInt), Some(vin.txid.get))
          }
        }
      }

      for(vout <- rpcTx.vout){
        outputs(vout.n.toInt) = NeoOutput(vout.n.toInt, satoshi(vout.value), vout.scriptPubKey.hex, vout.scriptPubKey.addresses.getOrElse(List[String]()))
      }      

      def finalizeTransaction:Future[Either[Exception,String]] = {
        val tx = NeoTransaction(rpcTx.txid, rpcTx.locktime, None, None)

        EmbeddedNeo4j2.addTransaction(ticker, tx, inputs, outputs).flatMap { response =>
          response
        }
      }

      if(resultsFuts.size > 0) {
        val futuresResponses: Future[ListBuffer[Unit]] = Future.sequence(resultsFuts)
        futuresResponses.flatMap { responses =>
          finalizeTransaction
        }
      }else{
        finalizeTransaction
      }
  }
	

  def startAt(ticker:String, fromBlockHash:String):Future[Either[Exception,String]] = {
    getBlock(ticker, fromBlockHash)
  }



  def resume(ticker:String, force:Boolean = false):Future[Either[Exception,String]] = {
    //on reprend la suite de l'indexation à partir de l'avant dernier block stocké (si le dernier n'a pas été ajouté correctement) dans notre bdd
    EmbeddedNeo4j2.getBeforeLastBlockHash(ticker).flatMap { response =>
      response match {
        case Right(beforeLastBlockHash) => {
          beforeLastBlockHash match {
            case Some(b) => {
              startAt(ticker, b)
            }
            case None => {
              force match {
                case true => restart(ticker)
                case false => {
                  ApiLogs.error("No blocks found, can't resume")
                  Future(Left(new Exception("No blocks found, can't resume")))
                }
              }
            }
          }
        }
        case Left(e) => Future(Left(e))
      }
    }

    /*
      TODO:
      vérifier qu'il n'y a pas eu de réorg avant de reprendre l'indexation
    */
  }

  def restart(ticker:String):Future[Either[Exception,String]] = {
    //on recommence l'indexation à partir du block genesis
    val genesisBlock = config.getString("coins."+ticker+".genesisBlock").get
    startAt(ticker, genesisBlock)
  }

  */

}
