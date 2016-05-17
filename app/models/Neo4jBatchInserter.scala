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

import models._
import utils._
import redis.clients.jedis._

object Neo4jBatchInserter {
	val config 	= play.Play.application.configuration
	val DB_PATH = Play.application.path.getPath + "/graph.db"

	var jedis:Option[Jedis] = None
	var batchInserter:Option[BatchInserter] = None
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
		batchInserter = Some(BatchInserters.inserter( new File( DB_PATH ) ))
		jedis = Some(Redis.jedis())
		registerShutdownHookBatch()

		blockLabel = Some(Label.label( "Block" ))
		batchInserter.get.createDeferredSchemaIndex( blockLabel.get ).on( "hash" ).create()

		transactionLabel = Some(Label.label( "Transaction" ))
		batchInserter.get.createDeferredSchemaIndex( transactionLabel.get ).on( "hash" ).create()

		inputoutputLabel = Some(Label.label( "InputOutput" ))

		addressLabel = Some(Label.label( "Address" ))
		batchInserter.get.createDeferredSchemaIndex( addressLabel.get ).on( "value" ).create()

		ApiLogs.debug("started")
	}

	def stopService {
		isShutdowning = true
		batchInserter match {
			case None => /* */
			case Some(bi) => bi.shutdown()
		}
		jedis match {
			case None => /* */
			case Some(j) => j.close()
		}
		ApiLogs.debug("shutdown")
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

	def dropDb {
		FileUtils.deleteRecursively(new File(DB_PATH))
	}

	def cleanRedis {
		Redis.dels(jedis.get, "blockchain-explorer:address:*")
		Redis.dels(jedis.get, "blockchain-explorer:inputoutput:*")
	}


	private def getInputOutputNode(txHash: String, outputIndex:Long, data:java.util.Map[String,Object] = Map[String, Object]()):Long = {
		Redis.get(jedis.get, "blockchain-explorer:inputoutput:"+txHash+":"+outputIndex) match {
			case Some(inputoutputNodeS) => {
				val inputoutputNode:Long = inputoutputNodeS.toLong

				// Update node properties
				var properties:java.util.Map[String,Object] = batchInserter.get.getNodeProperties(inputoutputNode)
					properties.putAll(data)
				batchInserter.get.setNodeProperties(inputoutputNode, properties)

				inputoutputNode
			}
			case None => {
				var properties:java.util.Map[String,Object] = new java.util.HashMap()
					properties.put( "output_index", outputIndex.asInstanceOf[AnyRef] )
					properties.putAll(data)
				val inputoutputNode:Long = batchInserter.get.createNode( properties, inputoutputLabel.get )
				Redis.set(jedis.get, "blockchain-explorer:inputoutput:"+txHash+":"+outputIndex, inputoutputNode.toString)
				inputoutputNode
			}
		}
	}

	private def getAddressNode(address: String):Long = {
		Redis.get(jedis.get, "blockchain-explorer:address:"+address) match {
			case Some(addressNode) => addressNode.toLong
			case None => {
				var properties:java.util.Map[String,Object] = new java.util.HashMap()
					properties.put( "value", address.asInstanceOf[AnyRef] )
				val addressNode:Long = batchInserter.get.createNode( properties, addressLabel.get )
				Redis.set(jedis.get, "blockchain-explorer:address:"+address, addressNode.toString)
				addressNode
			}
		}
	}


	def batchInsert(rpcBlock:RPCBlock, prevBlockNode:Option[Long], transactions:ListBuffer[RPCTransaction]):Future[Either[Exception,(String, Long)]] = {
		Future {
			try {
				if(isShutdowning){
					throw new Exception("shutdown...")
				}

				// Block
				var properties:java.util.Map[String,Object] = new java.util.HashMap()
					properties.put( "hash", rpcBlock.hash.asInstanceOf[AnyRef] )
					properties.put( "height", rpcBlock.height.asInstanceOf[AnyRef] )
					properties.put( "time", rpcBlock.time.asInstanceOf[AnyRef] )
					properties.put( "main_chain", true.asInstanceOf[AnyRef] )

				val blockNode:Long = batchInserter.get.createNode( properties, blockLabel.get )

				// Parent Block relationship
				prevBlockNode match {
					case Some(prevNode) => {
						batchInserter.get.createRelationship( blockNode, prevNode, follows, null )
					}
					case None => /* nothing (genesis block) */
				}

				// Transactions
				for(rpcTransaction <- transactions){
					properties = new java.util.HashMap()
						properties.put( "hash", rpcTransaction.txid.asInstanceOf[AnyRef] )
						properties.put( "received_at", rpcBlock.time.asInstanceOf[AnyRef] )
						properties.put( "lock_time", rpcTransaction.locktime.asInstanceOf[AnyRef] )
					val txNode:Long = batchInserter.get.createNode( properties, transactionLabel.get )
					batchInserter.get.createRelationship( blockNode, txNode, contains, null )

					// Inputs
					for((rpcInput, index) <- rpcTransaction.vin.zipWithIndex){
						properties = new java.util.HashMap()
							properties.put( "input_index", index.asInstanceOf[AnyRef] )

						rpcInput.txinwitness match {
							case None => /* nothing */
							case Some(witness) => {
								properties.put( "txinwitness", witness.toArray.asInstanceOf[AnyRef] )
							}
						}

						rpcInput.coinbase match {
							case Some(coinbase) => {
									properties.put( "coinbase", coinbase.asInstanceOf[AnyRef] )
								val inputoutputNode:Long = batchInserter.get.createNode( properties, inputoutputLabel.get )
								batchInserter.get.createRelationship( inputoutputNode, txNode, supplies, null )
							}
							case None => {
								val inputoutputNode:Long = getInputOutputNode(rpcInput.txid.get, rpcInput.vout.get, properties)
								batchInserter.get.createRelationship( inputoutputNode, txNode, supplies, null )
							}
						}
					}

					// Outputs
					for(rpcOutput <- rpcTransaction.vout){
						properties = new java.util.HashMap()
							properties.put( "value", Converter.btcToSatoshi(rpcOutput.value).asInstanceOf[AnyRef] )
							properties.put( "script_hex", rpcOutput.scriptPubKey.hex.asInstanceOf[AnyRef] )
						val inputoutputNode:Long = getInputOutputNode(rpcTransaction.txid, rpcOutput.n, properties)
						batchInserter.get.createRelationship( txNode, inputoutputNode, emits, null )

						// Addresses
						rpcOutput.scriptPubKey.addresses match {
							case None => /* nothing */
							case Some(addresses) => {
								for(address <- addresses){
									var addressNode:Long = getAddressNode(address)
									batchInserter.get.createRelationship( inputoutputNode, addressNode, issentto, null )
								}
							}
						}

					}

				}

	      		Right("Block '"+rpcBlock.hash+"' (n°"+rpcBlock.height.toString+") added !", blockNode)

			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}
}