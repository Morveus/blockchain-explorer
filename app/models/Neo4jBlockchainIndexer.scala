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
  blockchainsList += ("btcsegnet" -> blockchains.BitcoinBlockchainAPI)


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

 

  def processBlock(mode:String, ticker:String, blockHash:String, prevBlockNode:Option[Long] = None):Future[Either[Exception,(String, Long, Long, Option[String])]] = {
    getBlock(ticker, blockHash).flatMap { response =>
      response match {
        case Left(e) => Future(Left(e))
        case Right(rpcBlock) => {

          exploreTransactionsAsync(ticker, rpcBlock, 0).flatMap { response =>
            response match {
              case Right(txs) => {
                indexFullBlock(mode, ticker, rpcBlock, prevBlockNode, txs)
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

  def getBlock(ticker:String, blockHash:String):Future[Either[Exception,RPCBlock]] = {
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

  private def indexFullBlock(mode:String, ticker:String, rpcBlock:RPCBlock, prevBlockNode:Option[Long], transactions:ListBuffer[RPCTransaction]):Future[Either[Exception,(String, Long, Long, Option[String])]] = {

    var method = mode match {
      case "batch" => Neo4jBatchInserter.batchInsert(rpcBlock, prevBlockNode, transactions)
      case _ => Neo4jEmbedded.insert(rpcBlock, transactions)
    }

    method.map { result =>
      result match {
        case Left(e) => Left(e)
        case Right(r) => {
          var (message, blockNode) = r

          rpcBlock.nextblockhash match {
            case Some(nextblockhash) => {
              Right(message, blockNode, rpcBlock.height, rpcBlock.nextblockhash)
            }
            case None => {
              Right(message, blockNode, rpcBlock.height, None)
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
}
