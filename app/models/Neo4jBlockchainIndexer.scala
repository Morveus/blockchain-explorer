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
import utils._


object Neo4jBlockchainIndexer {

  val config = play.api.Play.configuration
  val RPCMaxQueries = config.getInt("rpc.maxqueries").get

  var maxqueries = RPCMaxQueries

  val defaultBlockReward = 5e18

  var blockchainsList: Map[String, BlockchainAPI] = Map()
  blockchainsList += ("eth" -> blockchains.EthereumBlockchainAPI)


  implicit val transactionReads   = Json.reads[RPCTransaction]
  implicit val transactionWrites  = Json.writes[RPCTransaction]
  implicit val blockReads         = Json.reads[RPCBlock]
  implicit val blockWrites        = Json.writes[RPCBlock]
  implicit val pendingBlockReads         = Json.reads[RPCPendingBlock]
  implicit val pendingBlockWrites        = Json.writes[RPCPendingBlock]
  

 

  def processBlock(mode:String, ticker:String, blockHeight:Long, prevBlockNode:Option[Long] = None):Future[Either[Exception,(String, Long, String)]] = {
    getBlockByHeight(ticker, blockHeight).flatMap { response =>
      response match {
        case Left(e) => Future(Left(e))
        case Right(rpcBlock) => {

          var resultsFuts: ListBuffer[Future[Either[Exception,(RPCBlock, Integer)]]] = ListBuffer()

          // On récupère les Uncles :
          for((uncleHash, i) <- rpcBlock.uncles.get.zipWithIndex){
            resultsFuts += getUncle(ticker, rpcBlock.hash, i)
          }

          // On calcule le reward du mineur :
          val blockReward:BigDecimal = getBlockReward(rpcBlock)

          if(resultsFuts.size > 0) {
            val futuresResponses: Future[ListBuffer[Either[Exception,(RPCBlock, Integer)]]] = Future.sequence(resultsFuts)
            futuresResponses.flatMap { responses =>

              var exception:Option[Exception] = None
              var uncles:ListBuffer[(RPCBlock, Integer, BigDecimal)] = ListBuffer[(RPCBlock, Integer, BigDecimal)]()
              for(response <- responses){
                response match {
                  case Left(e) => {
                    exception = Some(e)
                  }
                  case Right((rpcUncle, u_index)) => {

                    //val uncle:NeoBlock = NeoBlock(rpcUncle.hash, rpcUncle.height, rpcUncle.time, Some(u_index))
                    val uncleReward:BigDecimal = getUncleReward(rpcBlock, rpcUncle)

                    uncles += ((rpcUncle, u_index, uncleReward))
                  }
                }
              }

              exception match {
                case Some(e) => {
                  Future(Left(e))
                }
                case None => {
                  indexFullBlock(mode, rpcBlock, prevBlockNode, blockReward, uncles)
                }
              }
            }
          }else{
            indexFullBlock(mode, rpcBlock, prevBlockNode, blockReward)
          }

        }
      }
    }
  }

  def processTransactions(mode:String, ticker:String, transactions:List[RPCTransaction]):Future[Either[Exception,String]] = {
    indexTransactions(mode, transactions)
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

  def getBlockByHeight(ticker:String, blockHeight:Long):Future[Either[Exception,RPCBlock]] = {
    blockchainsList(ticker).getBlockByHeight(ticker, blockHeight).map { response =>
      (response \ "result") match {
        case JsNull => {
          ApiLogs.error("Block '"+blockHeight+"' not found")
          Left(new Exception("Block '"+blockHeight+"' not found"))
        }
        case result: JsObject => {
          result.validate[RPCBlock] match {
            case b: JsSuccess[RPCBlock] => {
              Right(b.get)
            }
            case e: JsError => {
              ApiLogs.error("Invalid block '"+blockHeight+"' from RPC : "+response)
              Left(new Exception("Invalid block '"+blockHeight+"' from RPC : "+response))
            }
          }
        }
        case _ => {
          ApiLogs.error("Invalid block '"+blockHeight+"' result from RPC : "+response)
          Left(new Exception("Invalid block '"+blockHeight+"' result from RPC : "+response))
        }
      }
    }
  }

  def getLatestBlock(ticker:String):Future[Either[Exception,RPCBlock]] = {
    blockchainsList(ticker).getLatestBlock(ticker).map { response =>
      (response \ "result") match {
        case JsNull => {
          ApiLogs.error("Latest Block not found")
          Left(new Exception("Latest Block not found"))
        }
        case result: JsObject => {
          result.validate[RPCBlock] match {
            case b: JsSuccess[RPCBlock] => {
              Right(b.get)
            }
            case e: JsError => {
              ApiLogs.error("Invalid latest block from RPC : "+response)
              Left(new Exception("Invalid latest block from RPC : "+response))
            }
          }
        }
        case _ => {
          ApiLogs.error("Invalid latest block from RPC : "+response)
          Left(new Exception("Invalid latest block from RPC : "+response))
        }
      }
    }
  }

  def getMempool(ticker:String):Future[Either[Exception,List[RPCTransaction]]] = {
    blockchainsList(ticker).getMempool(ticker).map { response =>
      (response \ "result") match {
        case JsNull => {
          ApiLogs.error("Mempool not found")
          Left(new Exception("Mempool not found"))
        }
        case result: JsObject => {
          result.validate[RPCPendingBlock] match {
            case b: JsSuccess[RPCPendingBlock] => {
              b.get.transactions match {
                case None => Right(List())
                case Some(txs) => Right(txs)
              }
            }
            case e: JsError => {
              ApiLogs.error("Invalid mempool from RPC ("+e.toString+") : "+response)
              Left(new Exception("Invalid mempool from RPC ("+e.toString+") : "+response))
            }
          }
        }
        case _ => {
          ApiLogs.error("Invalid mempool from RPC : "+response)
          Left(new Exception("Invalid mempool from RPC : "+response))
        }
      }
    }
  }

  private def getUncle(ticker:String, blockHash:String, uncleIndex: Int):Future[Either[Exception,(RPCBlock, Integer)]] = {
    blockchainsList(ticker).getUncle(ticker, blockHash, uncleIndex).map { response =>
      (response \ "result") match {
        case JsNull => {
          ApiLogs.error("Uncle n°"+uncleIndex+" from block '"+blockHash+"' not found")
          Left(new Exception("Uncle n°"+uncleIndex+" from block '"+blockHash+"' not found"))
        }
        case result: JsObject => {
          result.validate[RPCBlock] match {
            case u: JsSuccess[RPCBlock] => {
              Right(u.get, uncleIndex)
            }
            case e: JsError => {
              ApiLogs.error("Invalid uncle n°"+uncleIndex+" from block '"+blockHash+"' from RPC : "+response)
              Left(new Exception("Invalid uncle n°"+uncleIndex+" from block '"+blockHash+"' from RPC : "+response))
            }
          }
        }
      }
    }
  }

  private def getBlockReward(block: RPCBlock):BigDecimal = {
    val unclesRewards:BigDecimal = block.uncles match {
      case Some(uncles) => BigDecimal.apply(uncles.size) * BigDecimal.apply(1) / BigDecimal.apply(32) * BigDecimal.apply(defaultBlockReward)
      case None => 0
    }
    var fees:BigDecimal = 0
    for(tx <- block.transactions.get){
      fees += (Converter.hexToBigDecimal(tx.gas) * Converter.hexToBigDecimal(tx.gasPrice))
    }
    val result:BigDecimal = BigDecimal.apply(defaultBlockReward) + unclesRewards + fees  
    result
  }

  private def getUncleReward(block:RPCBlock, uncle: RPCBlock):BigDecimal = {
    val uncleReward:BigDecimal = (Converter.hexToBigDecimal(uncle.number) + BigDecimal.apply(8) - Converter.hexToBigDecimal(block.number)) * BigDecimal.apply(defaultBlockReward) / BigDecimal.apply(8)
    uncleReward
  }

  private def indexFullBlock(mode:String, rpcBlock:RPCBlock, prevBlockNode:Option[Long], blockReward:BigDecimal, uncles:ListBuffer[(RPCBlock, Integer, BigDecimal)] = ListBuffer()):Future[Either[Exception,(String, Long, String)]] = {

    var method = mode match {
      case "batch" => Neo4jBatchInserter.batchInsert(rpcBlock, prevBlockNode, blockReward, uncles)
      case _ => Neo4jEmbedded.insert(rpcBlock, blockReward, uncles)
    }

    method.map { result =>
      result match {
        case Left(e) => Left(e)
        case Right(r) => {
          var (message, blockNode) = r

          Right(message, blockNode, rpcBlock.hash)
          
        }
      }
    } recover {
      case e:Exception => Left(e)
    }
  }

  private def indexTransactions(mode:String, transactions:List[RPCTransaction]):Future[Either[Exception,String]] = {

    var method = mode match {
      case "batch" => Future(Left(new Exception("mode unavailable")))
      case _ => Neo4jEmbedded.insertTransactions(transactions)
    }

    method.map { result =>
      result match {
        case Left(e) => Left(e)
        case Right(message) => {
          Right(message)
        }
      }
    } recover {
      case e:Exception => Left(e)
    }
  }

}
