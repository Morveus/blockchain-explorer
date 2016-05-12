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

  val defaultBlockReward = 5

  var blockchainsList: Map[String, BlockchainAPI] = Map()
  blockchainsList += ("eth" -> blockchains.EthereumBlockchainAPI)

  implicit val transactionReads   = Json.reads[RPCTransaction]
  implicit val transactionWrites  = Json.writes[RPCTransaction]
  implicit val blockReads         = Json.reads[RPCBlock]
  implicit val blockWrites        = Json.writes[RPCBlock]
  
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


  def processBlock(ticker:String, blockHeight:Long):Future[Either[Exception,String]] = {
    getBlock(ticker, blockHeight).flatMap { response =>
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
                  EmbeddedNeo4j2.insert(rpcBlock, blockReward, uncles.toList)
                }
              }
            }
          }else{
            EmbeddedNeo4j2.insert(rpcBlock, blockReward)
          }
        }
      }
    }
  }

  private def getBlock(ticker:String, blockHeight:Long):Future[Either[Exception,RPCBlock]] = {

    blockchainsList(ticker).getBlock(ticker, blockHeight).map { response =>
      (response \ "result") match {
        case JsNull => {
          ApiLogs.error("Block n°"+blockHeight+" not found")
          Left(new Exception("Block n°"+blockHeight+" not found"))
        }
        case result: JsObject => {
          result.validate[RPCBlock] match {
            case b: JsSuccess[RPCBlock] => {
              Right(b.get)
            }
            case e: JsError => {
              ApiLogs.error("Invalid block n°"+blockHeight+" from RPC : "+response)
              Left(new Exception("Invalid block n°"+blockHeight+" from RPC : "+response))
            }
          }
        }
        case _ => {
          ApiLogs.error("Invalid block n°"+blockHeight+" result from RPC : "+response)
          Left(new Exception("Invalid block n°"+blockHeight+" result from RPC : "+response))
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
    val unclesRewards:BigDecimal = BigDecimal.apply(block.uncles.size) * BigDecimal.apply(1) / BigDecimal.apply(32) * BigDecimal.apply(defaultBlockReward)
    var fees:BigDecimal = 0
    for(tx <- block.transactions.get){
      fees += (Converter.hexToBigDecimal(tx.gas) * Converter.hexToBigDecimal(tx.gasPrice))
    }
    val result:BigDecimal = BigDecimal.apply(defaultBlockReward) + unclesRewards + fees
    result
  }

  private def getUncleReward(block:RPCBlock, uncle: RPCBlock):BigDecimal = {

    val uncleReward:BigDecimal = (Converter.hexToBigDecimal(uncle.number) + BigDecimal.apply(8) -  Converter.hexToBigDecimal(block.number)) * BigDecimal.apply(defaultBlockReward) / BigDecimal.apply(8)

    uncleReward
  }

}