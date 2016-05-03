package models

import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.ListBuffer

object Indexer {
	val config = play.api.Play.configuration

	var currentBlockHeight:Long = 0

	def getCurrentBlockHeight():Long = {
		val current = currentBlockHeight
		currentBlockHeight += 1
		current
	}

	class ThreadIndexer(ticker:String) extends Runnable {
	    def run {
	    	val blockHeight = getCurrentBlockHeight()
	    	//if(blockHeight < 100){
	    		Neo4jBlockchainIndexer.processBlock(ticker, blockHeight).map { response =>
			  		response match {
				        case Right(r) => {
				        	ApiLogs.debug(r)
				          	run()
				        }
				        case Left(e) => {
				          ApiLogs.error("Neo4jBlockchainIndexer Exception : " + e.toString)
				        }
			  		}
			  	}		
	    	//}
	    }
	}	

	def start(ticker:String) = {
		val nbThread = config.getInt("indexation.thread").get - 1

		//for( a <- 1 to nbThread){
        	new Thread( new ThreadIndexer(ticker) ).start
        	Thread.sleep(100)
      	//}	
	}
}