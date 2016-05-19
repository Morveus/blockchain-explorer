package models

import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.ListBuffer

import com.typesafe.config._
import java.io._

object Indexer {
  val config = play.api.Play.configuration

  var indexer: Config = ConfigFactory.parseFile(new File("indexer.conf"))

  var batchmod:Boolean = indexer.getBoolean("batchmod")
  var ticker:String = indexer.getString("ticker")
  //var currentBlockHeight:Long = indexer.getLong("currentblock")
  var currentBlockHash:String = indexer.getString("currentblock")

  val genesisBlockHash:String = config.getString("coins."+ticker+".genesisBlock").get

  var isSaving = false
  def saveState(blockHash:String) = {
    Future {
      if(isSaving == false){
        isSaving = true
        indexer = indexer.withValue("currentblock", ConfigValueFactory.fromAnyRef(blockHash))

        val renderOpts = ConfigRenderOptions.defaults().setOriginComments(false).setComments(false).setJson(false);
        //println(indexer.root().render(renderOpts))

        val file = new File("indexer.conf")
        val bw = new BufferedWriter(new FileWriter(file))
        bw.write(indexer.root().render(renderOpts))
        bw.close()

        isSaving = false
      }
    }

  }

  def start() = {
    batchmod match {
      case true => startBatchMod()
      case false => startStandardMod()
    }
  }

  private def startBatchMod() = {

    //On regarde s'il y a une reprise à faire:
    if(currentBlockHash != genesisBlockHash){

      Neo4jBlockchainIndexer.getBlock(ticker, currentBlockHash).map { response =>
        response match {
         case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
          case Right(rpcBlock) => {
            rpcBlock.nextblockhash match {
              case None => ApiLogs.debug("Blocks synchronized !")
              case Some(b) => {
                Neo4jEmbedded.startService
                Neo4jEmbedded.getBlockNode(currentBlockHash).map { result =>
                  Neo4jEmbedded.stopService
                  result match {
                    case Right(nodeId) => {
                      Neo4jBatchInserter.startService(ticker)
                      process(b, Some(nodeId))
                    }
                    case Left(e) => {
                      ApiLogs.error("Indexer Exception : "+e.toString)
                    }
                  }
                }
              }
            }
          }
        }
      }			
    }else{
      Neo4jBatchInserter.dropDb
      Neo4jBatchInserter.startService(ticker)
      Neo4jBatchInserter.init
      Neo4jBatchInserter.cleanRedis
      process(currentBlockHash)
    }

    def process(blockHash:String, prevBlockNode:Option[Long] = None) {
      Neo4jBlockchainIndexer.processBlock("batch", ticker, blockHash, prevBlockNode).map { response =>
        response match {
          case Right(q) => {
            var (message, blockNode, blockHeight, nextBlockHash) = q
            ApiLogs.debug(message) //Block added

            saveState(blockHash)

            nextBlockHash match {
              case Some(next) => {
                process(next, Some(blockNode))	                
              }
              case None => {
                ApiLogs.debug("Blocks synchronized !")
                Neo4jBatchInserter.stopService

                startStandardMod()
              }
            }
          }
          case Left(e) => {
            ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
            Neo4jBatchInserter.stopService
          }
        }
      }		
    }
  }


  private def startStandardMod() = {

    Neo4jBlockchainIndexer.getBlock(ticker, currentBlockHash).map { response =>
      response match {
        case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
        case Right(rpcBlock) => {
          rpcBlock.nextblockhash match {
            case None => ApiLogs.debug("Blocks synchronized !")
            case Some(b) => {
              Neo4jEmbedded.startService
              //Neo4jEmbedded.cleanDB(currentBlockHash)
              process(b)
            }
          }
        }
      }
    }

    def process(blockHash:String) {
      Neo4jBlockchainIndexer.processBlock("standard", ticker, blockHash).map { response =>
        response match {
          case Right(q) => {
            var (message, blockNode, blockHeight, nextBlockHash) = q
            ApiLogs.debug(message) //Block added

            saveState(blockHash)

            nextBlockHash match {
              case Some(next) => {
                process(next)	                
              }
              case None => {
                ApiLogs.debug("Blocks synchronized !")
              }
            }
          }
          case Left(e) => {
            ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
            Neo4jEmbedded.stopService
          }
        }
      }
    }
  }
}