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

	var currentBlockHeight:Long = indexer.getInt("currentblock") //1467659

	def getCurrentBlockHeight():Long = {
		val current = currentBlockHeight
		currentBlockHeight -= 1
		current
	}

	var toSave:Int = 0
	var isSaving = false
	def saveState = {
		toSave += 1 
		Future {
			if(toSave > 100 && isSaving == false){
				toSave = 0
				isSaving = true
				indexer = indexer.withValue("currentblock", ConfigValueFactory.fromAnyRef(currentBlockHeight))

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
	    	process()
	    }

	    def process(prevBlockNode:Option[Long] = None) {
	    	val blockHeight = getCurrentBlockHeight()
    		Neo4jBlockchainIndexer.processBlock(ticker, blockHeight, prevBlockNode).map { response =>
		  		response match {
			        case Right(r) => {
			        	var (log, node) = r 
			        	ApiLogs.debug(log)
			        	saveState
			        
			        	process(Some(node))
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