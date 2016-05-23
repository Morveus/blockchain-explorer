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

  var launched:Boolean = false

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

  def newblock(blockHash:String) = {
     //Si l'indexation des précédents blocks est terminée:
    if(launched == false){
      process(blockHash)
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
                      for(child <- children){
                        setNotMainchain(child)
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
                      rpcBlock.previousblockhash match {
                        case Some(prevB) => {
                          process(prevB).flatMap { result =>
                            
                            // Ajout du block:
                            Neo4jBlockchainIndexer.processBlock("standard", ticker, blockHash).flatMap { response =>
                              response match {
                                case Right(q) => {
                                  var (message, blockNode, blockHeight, nextBlockHash) = q
                                  ApiLogs.debug(message) //Block added
                                  saveState(blockHash)
                                  Future(Right(message))
                                }
                                case Left(e) => {
                                  ApiLogs.error("Indexer.process('"+blockHash+"') Exception : " + e.toString)
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

  private def startBatchMod() = {
    launched = true

    //On regarde s'il y a une reprise à faire:
    if(currentBlockHash != genesisBlockHash){

      Neo4jBlockchainIndexer.getBlock(ticker, currentBlockHash).map { response =>
        response match {
         case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
          case Right(rpcBlock) => {
            rpcBlock.nextblockhash match {
              case None => {
                ApiLogs.debug("Blocks synchronized !")
                launched = false
              }
              case Some(b) => {
                Neo4jEmbedded.startService
                Neo4jEmbedded.getBlockNode(currentBlockHash).map { result =>
                  Neo4jEmbedded.stopService
                  result match {
                    case Right(optNode) => {
                      optNode match {
                        case Some(nodeId) => {
                          Neo4jBatchInserter.startService(ticker)
                          process(b, Some(nodeId))
                        }
                        case None => ApiLogs.error("Indexer Exception : previous node not found !")
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
                launched = false

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
    launched = true
    Neo4jBlockchainIndexer.getBlock(ticker, currentBlockHash).map { response =>
      response match {
        case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
        case Right(rpcBlock) => {
          rpcBlock.nextblockhash match {
            case None => {
              ApiLogs.debug("Blocks synchronized !")
              launched = false
            }
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
                launched = false
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