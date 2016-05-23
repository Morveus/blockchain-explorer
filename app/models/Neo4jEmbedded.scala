package models

import play.api._
import play.api.Play.current
import play.api.mvc._
import scala.util.{ Try, Success, Failure }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.collection.JavaConversions._

import java.io.File;
import java.io.IOException;
import scala.collection.mutable.{ListBuffer, Map}

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.ResourceIterator;

import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserters;
import org.neo4j.graphdb.RelationshipType;

import com.typesafe.config._

import models._
import utils._
import redis.clients.jedis._

object Neo4jEmbedded {
	val config 	= play.Play.application.configuration
	val configIndexer:Config = ConfigFactory.parseFile(new File("indexer.conf"))
	val DB_PATH = Play.application.path.getPath + "/" + configIndexer.getString("dbname")

	var db:Option[GraphDatabaseService] = None    

	var blockLabel: Option[Label] = None
	var transactionLabel: Option[Label] = None
	var inputoutputLabel: Option[Label] = None
	var addressLabel: Option[Label] = None

	var follows:RelationshipType = RelationshipType.withName( "FOLLOWS" )
	var contains:RelationshipType = RelationshipType.withName( "CONTAINS" )
	var emits:RelationshipType = RelationshipType.withName( "EMITS" )
	var supplies:RelationshipType = RelationshipType.withName( "SUPPLIES" )
	var issentto:RelationshipType = RelationshipType.withName( "IS_SENT_TO" )

	var isShutdowning:Boolean = false

	def startService {
		db = Some(new GraphDatabaseFactory().newEmbeddedDatabase( new File(DB_PATH) ))
		registerShutdownHookBatch()

		blockLabel = Some(Label.label( "Block" ))
		transactionLabel = Some(Label.label( "Transaction" ))
		inputoutputLabel = Some(Label.label( "InputOutput" ))
		addressLabel = Some(Label.label( "Address" ))

		ApiLogs.debug("Neo4jEmbedded started")
	}

	def stopService {
		if(!isShutdowning){
			isShutdowning = true
			db match {
				case None => /* */
				case Some(d) => {
					ApiLogs.debug("Neo4jEmbedded shutting down...")
					d.shutdown()
					ApiLogs.debug("Neo4jEmbedded shutdown ")
				}
			}
			isShutdowning = false
		}
	}

	private def registerShutdownHookBatch() = {
		Runtime.getRuntime().addShutdownHook( new Thread()
			{
				override def run()
				{
					stopService
				}
			}
	    )
	}

	def exist(hash:String):Future[Either[Exception, Boolean]] = {
		Future {
			val graphDb = db.get
			var tx:Transaction = graphDb.beginTx()
			try {	
				val blockNode:Node = graphDb.findNode( blockLabel.get, "hash", hash )
				val exist = blockNode.hasLabel( blockLabel.get )
				Right(true)
			} catch {
				case e:Exception => {
					Right(false)
				}
			}finally {
				tx.close()
			}
		}
		
	}

	def getBlockNode(hash:String):Future[Either[Exception, Option[Long]]] = {
		Future {
			try {	
				val query = "MATCH (b:Block {hash:'"+hash+"'}) RETURN ID(b) as id"
				val resultIterator:ResourceIterator[Long] = db.get.execute( query ).columnAs( "id" )
				if ( resultIterator.hasNext() ){
					val blockNode:Long = resultIterator.next()
					Right(Some(blockNode))
				}else{
					Right(None)
				}
			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}

	def getBlockChildren(hash:String):Future[Either[Exception, List[String]]] = {
		Future {
			val graphDb = db.get
			try {	
				val query = "MATCH (b:Block {hash: '"+hash+"'})<-[:FOLLOWS]-(nextBlock:Block) RETURN nextBlock"
				var result:org.neo4j.graphdb.Result = graphDb.execute( query )
				val childrenNode:ResourceIterator[Node] = result.columnAs( "nextBlock" )
				var response:ListBuffer[String] = ListBuffer()
				while(childrenNode.hasNext()){
					val childNode:Node = childrenNode.next()
					response += childNode.getProperty( "hash" ).toString
				}
				// for ( childNode <- Iterators.asIterable( childrenNode ) )
				// {
				//     response += childNode.getProperty( "hash" )
				// }

				Right(response.toList)
			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}

	def notMainchain(hash:String):Future[Either[Exception, String]] = {
		Future {
			val graphDb = db.get
			var tx:Transaction = graphDb.beginTx()
			println("notMainchain")
			try {

				if(isShutdowning){
					throw new Exception("shutdown...")
				}

				val blockNode:Node = graphDb.findNode( blockLabel.get, "hash", hash )
				println("hash: "+blockNode.getProperty("hash"))
				blockNode.setProperty("main_chain", false)
				println("main_chain: "+blockNode.getProperty("main_chain"))
				println("trololo")
				tx.success()

	      		Right("Block '"+hash+"' setMainchain = false !")

			} catch {
				case e:Exception => {
					Left(e)
				}
			}
			finally {
				tx.close()
			}	
		}
	}

	// def cleanDB(blockHash:String):Future[Either[Exception, Long]] = {
	// 	Future {
	// 		val graphDb = db.get
	// 		var tx:Transaction = db.get.beginTx()

	// 		val next:Node = graphDb.findNode( blockLabel.get, "hash", prevBlock )

	// 	}

	// }

	private def getTransactionNode(txHash:String):Node = {
		val graphDb = db.get

		var optNode:Option[Node] = None

		// Find Node:
		val result:org.neo4j.graphdb.Result = graphDb.execute("MATCH (tx:Transaction {hash: '"+txHash+"'}) RETURN tx")
		val nodes:ResourceIterator[Node] = result.columnAs( "tx" )
		if ( nodes.hasNext() ){
	        optNode = Some(nodes.next())
	    }

		optNode match {
			case Some(node) => node
			case None => {
				// If doesn't exist, create :
				val txNode:Node = graphDb.createNode( transactionLabel.get )
				txNode.setProperty( "hash", txHash )

				txNode
			}
		}
	}

	
	private def getInputOutputNode(txHash: String, outputIndex:Long, createOutputTx:Boolean = true):Node = {
		val graphDb = db.get
		
		var optNode:Option[Node] = None

		// Find Node:
		val result:org.neo4j.graphdb.Result = graphDb.execute("MATCH (io:InputOutput {output_index: "+outputIndex+"})<-[:EMITS]-(tx:Transaction {hash: '"+txHash+"'}) RETURN io")
		val nodes:ResourceIterator[Node] = result.columnAs( "io" )
		if ( nodes.hasNext() ){
	        optNode = Some(nodes.next())
	    }
		
		optNode match {
			case Some(node) => node
			case None => {
				// If doesn't exist, create :

				val inputoutputNode:Node = graphDb.createNode( inputoutputLabel.get )
				inputoutputNode.setProperty( "output_index", outputIndex )

				createOutputTx match {
					case true => {
						val txNode:Node = graphDb.createNode( transactionLabel.get )
						txNode.setProperty( "hash", txHash )
						txNode.createRelationshipTo( inputoutputNode , emits )
					}
					case false => /* nothing */
				}					

				inputoutputNode
			}
		}
	}

	private def getAddressNode(address: String):Node = {
		val graphDb = db.get

		var optNode:Option[Node] = None

		// Find Node:
		val result:org.neo4j.graphdb.Result = graphDb.execute("MATCH (a:Address {value: '"+address+"'}) RETURN a")
		val nodes:ResourceIterator[Node] = result.columnAs( "a" )
		if ( nodes.hasNext() ){
	        optNode = Some(nodes.next())
	    }

	    optNode match {
			case Some(node) => node
			case None => {
				// If doesn't exist, create :
				val addressNode:Node = graphDb.createNode( addressLabel.get )
				addressNode.setProperty( "value", address )

				addressNode
			}
		}
	}

	def insert(rpcBlock:RPCBlock, transactions:ListBuffer[RPCTransaction]):Future[Either[Exception,(String, Long)]] = {
		Future {

			val graphDb = db.get
			var tx:Transaction = graphDb.beginTx()

			try {

				if(isShutdowning){
					throw new Exception("shutdown...")
				}

				// Block
				val blockNode:Node = graphDb.createNode( blockLabel.get )
        		blockNode.setProperty( "hash", rpcBlock.hash )
        		blockNode.setProperty( "height", rpcBlock.height )
        		blockNode.setProperty( "time", rpcBlock.time )
        		blockNode.setProperty( "main_chain", true )

        		// Parent Block relationship
        		rpcBlock.previousblockhash match {
        			case None => /* nothing */
        			case Some(prevBlock) => {
        				val prevBlockNode:Node = graphDb.findNode( blockLabel.get, "hash", prevBlock )
        				blockNode.createRelationshipTo( prevBlockNode, follows )
        			}
        		}
        		
        		// Transactions
        		for(rpcTransaction <- transactions){

        			var txNode:Node = getTransactionNode(rpcTransaction.txid)

    				txNode.setProperty( "hash", rpcTransaction.txid )
    				txNode.setProperty( "received_at", rpcBlock.time )
    				txNode.setProperty( "lock_time", rpcTransaction.locktime )

        			blockNode.createRelationshipTo( txNode, contains )
        			
        			// Inputs
        			for((rpcInput, index) <- rpcTransaction.vin.zipWithIndex){

        				val inputNode:Node = rpcInput.coinbase match {
							case Some(coinbase) => {
								graphDb.createNode( inputoutputLabel.get )
							}
							case None => {
								getInputOutputNode(rpcInput.txid.get, rpcInput.vout.get)
							}
						}
        				
        				inputNode.setProperty( "input_index", index )
        				inputNode.setProperty( "sequence", rpcInput.sequence )

						rpcInput.txinwitness match {
							case None => /* nothing */
							case Some(witness) => {
								inputNode.setProperty( "txinwitness", witness.toArray )
							}
						}

						rpcInput.coinbase match {
							case Some(coinbase) => {
								inputNode.setProperty( "coinbase", coinbase )
							}
							case None => {
								inputNode.setProperty( "script_signature", rpcInput.scriptSig.get.hex )
							}
						}

						inputNode.createRelationshipTo( txNode, supplies )
        			}

        			// Outputs
					for(rpcOutput <- rpcTransaction.vout){
						val outputNode:Node = getInputOutputNode(rpcTransaction.txid, rpcOutput.n, false)
						outputNode.setProperty( "value", Converter.btcToSatoshi(rpcOutput.value) )
						outputNode.setProperty( "script_hex", rpcOutput.scriptPubKey.hex )

						txNode.createRelationshipTo( outputNode, emits )

						// Addresses
						rpcOutput.scriptPubKey.addresses match {
							case None => /* nothing */
							case Some(addresses) => {
								for(address <- addresses){
									var addressNode:Node = getAddressNode(address)
									outputNode.createRelationshipTo( addressNode, issentto )
								}
							}
						}
					}
        		}

				tx.success()

	      		Right("Block '"+rpcBlock.hash+"' (n°"+rpcBlock.height.toString+") added !", 0)

			} catch {
				case e:Exception => {
					Left(e)
				}
			}
			finally {
				tx.close()
			}
		}
	}

}