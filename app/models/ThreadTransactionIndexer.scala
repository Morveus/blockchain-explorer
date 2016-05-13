package models

import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.ListBuffer

object TransactionIndexer {
	val config = play.api.Play.configuration
	/*
	class GetUnprocessedTransaction(ticker:String) {
		var hashes: ListBuffer[String] = ListBuffer()
		var isRunning: Boolean = false
		val nbTxHash = 200
		var retry = 0

		def run():Future[Boolean] = {
			if(!isRunning){
				isRunning = true
				val genesisTx = config.getString("coins."+ticker+".genesisTransaction").get

				EmbeddedNeo4j2.getUnprocessedTransactions(ticker, (ListBuffer(genesisTx) ++ hashes).toList, nbTxHash ).map { response =>
					response match {
			  			case Right(txHashes) => {
			  				hashes ++= txHashes.to[ListBuffer]
			  				isRunning = false
			  				isRunning
			  			}
			  			case Left(e) => {
			  				ApiLogs.error("Neo4jBlockchainIndexer Transaction Exception : " + e.toString)
			  				isRunning = false
			  				isRunning
			  			}
			  		}
				}
			}else{
				Future(isRunning)
			}
			
		}

		def getTxHash() : Future[Option[String]] = {
			if(hashes.size > 0){
				retry = 0
				val hash = hashes(0)
				hashes -= hash
				if(hashes.size < nbTxHash){
					run().map{ res =>
						Some(hash)
					}
				}else{
					Future(Some(hash))
				}
				
			}else{
				if(retry > 5){
					Future(None)
				}else{
					retry += 1
					run().flatMap { res =>
						Thread.sleep(5000)
						getTxHash()
					}
				}
			}
		}
	}

	class ThreadTransactionIndexer(ticker:String, getTx:GetUnprocessedTransaction) extends Runnable {
	    def run {
	    	getTx.getTxHash().map{ result =>
	    		result match {
		    		case Some(txHash) => {
		    			Neo4jBlockchainIndexer.getTransaction(ticker, txHash).map { response =>
					  		response match {
						        case Right(r) => {
						          ApiLogs.debug(r) //Tx added
						          run()
						        }
						        case Left(e) => {
						          ApiLogs.error("Neo4jBlockchainIndexer Transaction Exception : " + e.toString)
						        }
					  		}
					  	}
		    		}
		    		case None => {
		    			ApiLogs.debug("completeTransaction ended")
		    		}
		    	}

	    	} 	  					
	    }
	}	

	def start(ticker:String) = {
		val nbThread = config.getInt("indexation.thread").get - 1

		val unprocTx = new GetUnprocessedTransaction(ticker)
		Thread.sleep(10000)
		unprocTx.run()
		Thread.sleep(5000)

		for( a <- 1 to nbThread){
        	new Thread( new ThreadTransactionIndexer(ticker, unprocTx) ).start
        	Thread.sleep(100)
      	}

      	
	}
	*/
}
