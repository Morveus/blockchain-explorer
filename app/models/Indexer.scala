package models

import play.api.Play.current
import play.api.libs.concurrent._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.ListBuffer

import actors._
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.json._

import com.typesafe.config._
import java.io._

import utils._

object Indexer {
  val config = play.api.Play.configuration

  var indexer: Config = ConfigFactory.parseFile(new File("indexer.conf"))

  var batchmod:Boolean = indexer.getBoolean("batchmod")
  var ticker:String = indexer.getString("ticker")
  var currentBlockHeight:Long = 0
  var latestBlockHeight:Long = 0
  var currentBlockHash:String = indexer.getString("currentblock")

  val genesisBlockHash:String = config.getString("coins."+ticker+".genesisBlock").get

  var launched:Boolean = false

  val webSocketActor = Akka.system.actorSelection("user/blockchain-explorer")

  var isSaving = false
  def saveState(blockHash:String, blockHeight:Long, setStandardMod:Boolean = false) = {
    currentBlockHash = blockHash
    currentBlockHeight = blockHeight
    Future {
      if(isSaving == false){
        isSaving = true
        indexer = indexer.withValue("currentblock", ConfigValueFactory.fromAnyRef(blockHash))
        if(setStandardMod == true){
          indexer = indexer.withValue("batchmod", ConfigValueFactory.fromAnyRef(false))
        }

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
  
  def newblock(blockHeight:Long) = {
     //Si l'indexation des précédents blocks est terminée:
    if(launched == false){

      Neo4jBlockchainIndexer.getBlockByHeight(ticker, blockHeight).map { result => 
        result match {
          case Left(e) => {
            ApiLogs.error("Indexer.newblock("+blockHeight+") Exception : " + e.toString)
            Future(Left(e))
          }
          case Right(rpcBlock) => {
            process(rpcBlock.hash)
          }
        }
      }
    }

    def setNotMainchain(blockHash:String):Future[Unit] = {
      Neo4jEmbedded.notMainchain(blockHash).flatMap { result =>
        result match {
          case Right(s) => {
            ApiLogs.debug(s)
            Neo4jEmbedded.getBlockChildren(blockHash).flatMap { result =>
              result match {
                case Right(children) => {
                  var resultsFuts: ListBuffer[Future[Unit]] = ListBuffer()
                  
                  for(child <- children){
                    resultsFuts += setNotMainchain(child)
                  }

                  if(resultsFuts.size > 0) {
                    val futuresResponses: Future[ListBuffer[Unit]] = Future.sequence(resultsFuts)
                    futuresResponses.map { result =>
                     
                    }
                  }else{
                    Future(Unit)
                  }
                }
                case Left(e) => {
                  ApiLogs.error("Indexer.process('"+blockHash+"') Exception : " + e.toString)
                  Future(Unit)
                }
              }
            }
          }
          case Left(e) => {
            ApiLogs.error("Indexer.process('"+blockHash+"') Exception : " + e.toString)
          }

        }

      }
    }

    def process(blockHash:String):Future[Either[Exception, String]] = {  
      //Si le block est dans Neo4J on arrête là
      Neo4jEmbedded.exist(blockHash).flatMap { result =>
        result match {
          case Right(optNode) => {
            optNode match {
              case true => {
                // block déjà existant, on vérifie qu'il n'a pas de descendants, sinon, on les passe en main_chain : false
                Neo4jEmbedded.getBlockChildren(blockHash).flatMap { result =>
                  result match {
                    case Right(children) => {
                      var resultsFuts: ListBuffer[Future[Unit]] = ListBuffer()
                      for(child <- children){
                        resultsFuts += setNotMainchain(child)
                      }

                      if(resultsFuts.size > 0) {
                        val futuresResponses: Future[ListBuffer[Unit]] = Future.sequence(resultsFuts)
                        futuresResponses.map { result =>
                          ApiLogs.warn("new-reorg")
                          pushNotification("new-reorg")
                        }
                      }     

                      Future(Right("process notMainchain fini"))
                    }
                    case Left(e) => {
                      ApiLogs.error("Indexer.process('"+blockHash+"') Exception : " + e.toString)
                      Future(Left(e))
                    }
                  }
                }
              }
              case false => {
                Neo4jBlockchainIndexer.getBlock(ticker, blockHash).flatMap { response =>
                  response match {
                    case Left(e) => {
                      ApiLogs.error("Indexer.process('"+blockHash+"') Exception : " + e.toString)
                      Future(Left(e))
                    }
                    case Right(rpcBlock) => {
                      val blockHeight = Converter.hexToInt(rpcBlock.number)
                      rpcBlock.parentHash match {
                        case Some(prevB) => {
                          process(prevB).flatMap { result =>

                            // Ajout du block:
                            Neo4jBlockchainIndexer.processBlock("standard", ticker, blockHeight).flatMap { response =>
                              response match {
                                case Right(q) => {
                                  var (message, blockNode, blockHash) = q
                                  ApiLogs.debug(message) //Block added
                                  saveState(blockHash, blockHeight)

                                  pushNotification("new-block", blockHash).map { result =>
                                    Right(message)
                                  }
                                }
                                case Left(e) => {
                                  ApiLogs.error("Indexer.process('"+blockHeight+"') Exception : " + e.toString)
                                  Future(Left(e))
                                }
                              }
                            }
                            
                          }
                        }
                        case None => {
                          ApiLogs.error("Indexer.process('"+blockHash+"') Exception : Block not found")
                          Future(Left(new Exception("Indexer.process('"+blockHash+"') Exception : Block not found")))
                        }
                      }
                    }
                  }
                }

              }
            }
          }
          case Left(e) => {
            ApiLogs.error("Indexer.process('"+blockHash+"') Exception : " + e.toString)
            Future(Left(e))
          }
        }
      }

    }
  }

  private def mempool():Future[Unit] = {
    Neo4jBlockchainIndexer.getMempool(ticker).map { result =>
      result match {
        case Right(transactions) => {
          Neo4jBlockchainIndexer.processTransactions("standard", ticker, transactions).map { response =>
            response match {
              case Right(s) => {
                var (message, transactions) = s
                for(transaction <- transactions){
                  ApiLogs.debug("mempool tx: "+transaction)
                  pushNotification("new-transaction", transaction)
                }
                if(transactions.size > 0){
                  ApiLogs.debug(message)
                }                
              }
              case Left(e) => ApiLogs.error("Indexer.mempool() Exception : " + e.toString)
            }
            
            Thread.sleep(500)
            if(!Neo4jEmbedded.isShutdowning){
              mempool()
            }
          }
        }
        case Left(e) => ApiLogs.error("Indexer.mempool() Exception : " + e.toString)
      }
    }
  }

  private def pushNotification(datatype:String, hash:String = "") = {
    datatype match {
      case "new-block" => {
        Neo4jEmbedded.getBlocks(hash).map { result =>
          result match {
            case Right(json) => {
              var message = Json.obj("payload" -> Json.obj(
                  "type" -> datatype,
                  "block_chain" -> ticker,
                  "block" -> json(0)))
              webSocketActor ! WebSocketActor.BroadcastToAll(message)
            }
            case Left(e) => ApiLogs.error("Indexer.pushNotification(Block '"+hash+"') Exception : " + e.toString)
          }
        }
      }
      case "new-transaction" => {
        Neo4jEmbedded.getTransactions(hash).map { result =>
          result match {
            case Right(json) => {
              var message = Json.obj("payload" -> Json.obj(
                  "type" -> datatype,
                  "block_chain" -> ticker,
                  "transaction" -> json(0)))
              webSocketActor ! WebSocketActor.BroadcastToAll(message)
            }
            case Left(e) => ApiLogs.error("Indexer.pushNotification(Transaction '"+hash+"') Exception : " + e.toString)
          }
        }
      }
      case "new-reorg" => {
        Future {
          var message = Json.obj("payload" -> Json.obj(
            "type" -> "new-reorg"))
          webSocketActor ! WebSocketActor.BroadcastToAll(message)
        }
      }
    }
  }

                                    

  private def updateLatestBlockHeight() = {
    Neo4jBlockchainIndexer.getLatestBlock(ticker).map { response =>
      response match {
        case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
        case Right(rpcBlock) => {
          latestBlockHeight = Converter.hexToInt(rpcBlock.number)
        }
      }
    }
  }

  private def startBatchMod() = {
    launched = true

    //On regarde s'il y a une reprise à faire:
    if(currentBlockHash != genesisBlockHash){

      Neo4jBlockchainIndexer.getBlock(ticker, currentBlockHash).map { response =>
        response match {
         case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
          case Right(rpcBlock) => {

            currentBlockHeight = Converter.hexToInt(rpcBlock.number)

            Neo4jEmbedded.startService
            Neo4jEmbedded.getBlockNode(currentBlockHash).map { result =>
              result match {
                case Right(optNode) => {
                  optNode match {
                    case Some(nodeId) => {
                      //On delete les données qui auraient pu commencer à être insérées à la suite de ce block:
                      Neo4jEmbedded.deleteNextBlocks(currentBlockHeight).map { result =>
                        result match {
                          case Left(e) => ApiLogs.error("Indexer.startBatchMod Exception : "+e.toString)
                          case Right(s) => {
                            ApiLogs.debug(s)
                            Neo4jEmbedded.stopService

                            Neo4jBatchInserter.startService(ticker)
                            process(currentBlockHeight + 1, Some(nodeId))
                          }
                        }
                      }
                    }
                    case None => {
                      Neo4jEmbedded.stopService
                      ApiLogs.error("Indexer Exception : previous node not found !")
                      // Neo4jBatchInserter.startService(ticker) //test
                      // process(currentBlockHeight + 1)         //test
                    }
                  }
                }
                case Left(e) => {
                  ApiLogs.error("Indexer Exception : "+e.toString)
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
      process(currentBlockHeight)
    }

    def process(blockHeight:Long, prevBlockNode:Option[Long] = None) {
      Neo4jBlockchainIndexer.processBlock("batch", ticker, blockHeight, prevBlockNode).map { response =>
        response match {
          case Right(q) => {
            var (message, blockNode, blockHash) = q
            ApiLogs.debug(message) //Block added

            

            nextBlock(blockHeight).map { response =>
              response match {
                case true => {
                  saveState(blockHash, blockHeight)
                  process(blockHeight + 1, Some(blockNode))
                }
                case false => {
                  saveState(blockHash, blockHeight, true)
                  ApiLogs.debug("Blocks synchronized !")
                  Neo4jBatchInserter.stopService
                  launched = false
                  startStandardMod()
                }
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

  private def nextBlock(currentBlockHeight:Long):Future[Boolean] = {
    if(latestBlockHeight > currentBlockHeight + 1){
      Future(true)
    }else{
      updateLatestBlockHeight().map { response =>
        if(latestBlockHeight > currentBlockHeight + 1){
          true
        }else{
          false
        }
      }
    }
  }


  private def startStandardMod() = {
    launched = true
    Neo4jEmbedded.startService
    Neo4jBlockchainIndexer.getBlock(ticker, currentBlockHash).map { response =>
      response match {
        case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
        case Right(rpcBlock) => {

          currentBlockHeight = Converter.hexToInt(rpcBlock.number)

          nextBlock(currentBlockHeight).map { response =>
            response match {
              case true =>  process(currentBlockHeight + 1)
              case false => {
                ApiLogs.debug("Blocks synchronized !")
                mempool()
                launched = false
              }
            }
          }

        }
      }
    }

    def process(blockHeight:Long) {
      Neo4jBlockchainIndexer.processBlock("standard", ticker, blockHeight).map { response =>
        response match {
          case Right(q) => {
            var (message, blockNode, blockHash) = q
            ApiLogs.debug(message) //Block added

            saveState(blockHash, blockHeight)

            nextBlock(blockHeight).map { response =>
              response match {
                case true =>  process(blockHeight + 1)
                case false => {
                  ApiLogs.debug("Blocks synchronized !")
                  mempool()
                  launched = false
                }
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