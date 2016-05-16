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

	var ticker:String = indexer.getString("ticker")
	var currentBlockHash:String = ""
	var currentBlockHeight:Long = indexer.getLong("currentblock")

	var isSaving = false
	def saveState(blockHeight:Long) = {
		Future {
			if(isSaving == false){
				isSaving = true
				indexer = indexer.withValue("currentblock", ConfigValueFactory.fromAnyRef(blockHeight))

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

	class ThreadIndexer() extends Runnable {
	    def run() {
	    	process(currentBlockHash)
	    }

	    def process(blockHash:String, prevBlockNode:Option[Long] = None) {

	  		Neo4jBlockchainIndexer.processBlock(ticker, blockHash, prevBlockNode).map { response =>
	  			response match {
	          case Right(q) => {
	            var (message, blockNode, blockHeight, nextBlockHash) = q
	            ApiLogs.debug(message) //Block added

	            saveState(blockHeight)

	            nextBlockHash match {
	              case Some(next) => {
	          		process(next, Some(blockNode))	                
	              }
	              case None => {
	                ApiLogs.debug("Blocks synchronized !")
	              }
	            }
	          }
	          case Left(e) => {
	            ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
	          }
	        }

		  	}		
	    }
	}	

	def start() = {


		Neo4jBlockchainIndexer.getBlockHash(ticker, currentBlockHeight).map { result =>
			result match {
				case Left(e) => ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
				case Right(hash) => {
					currentBlockHash = hash
					new Thread( new ThreadIndexer() ).start
				}
			}			
		}
		
		
	}
}