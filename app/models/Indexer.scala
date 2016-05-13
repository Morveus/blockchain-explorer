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

	var currentBlockHash:String = indexer.getString("currentblock")

	var toSave:Int = 0
	var isSaving = false
	def saveState(blockHash:String) = {
		toSave += 1 
		Future {
			if(toSave > 100 && isSaving == false){
				toSave = 0
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

	class ThreadIndexer(ticker:String) extends Runnable {
	    def run() {
	    	process(ticker, currentBlockHash)
	    }

	    def process(ticker:String, blockHash:String, prevBlockNode:Option[Long] = None) {

	  		Neo4jBlockchainIndexer.processBlock(ticker, blockHash, prevBlockNode).map { response =>
	  			response match {
	          case Right(q) => {
	            var (message, blockNode, nextBlockHash) = q
	            ApiLogs.debug(message) //Block added

	            saveState(blockHash)

	            nextBlockHash match {
	              case Some(next) => {
	              	if(next == "e081e3ab67a210d01dc67537eeae475ceea3dbaef6b0245d235c854f8cdadffc"){
	              		EmbeddedNeo4j2.stopService
	          		}else{
	          			process(ticker, next, Some(blockNode))
	          		}
	                
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

	def start(ticker:String) = {
		new Thread( new ThreadIndexer(ticker) ).start
	}
}