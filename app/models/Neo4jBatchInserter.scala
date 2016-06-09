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

object Neo4jBatchInserter {
	val config 	= play.Play.application.configuration

	val configIndexer:Config = ConfigFactory.parseFile(new File("indexer.conf"))
	val DB_PATH = Play.application.path.getPath + "/" + configIndexer.getString("dbname")

	var ticker:String = ""

	var jedis:Option[Jedis] = None
	var batchInserter:Option[BatchInserter] = None
	var blockLabel: Option[Label] = None
	var transactionLabel: Option[Label] = None
	var addressLabel: Option[Label] = None

	var minedby:RelationshipType = RelationshipType.withName( "MINED_BY" )
	var uncleof:RelationshipType = RelationshipType.withName( "UNCLE_OF" )
	var contains:RelationshipType = RelationshipType.withName( "CONTAINS" )
	var follows:RelationshipType = RelationshipType.withName( "FOLLOWS" )
	var issentfrom:RelationshipType = RelationshipType.withName( "IS_SENT_FROM" )
	var issentto:RelationshipType = RelationshipType.withName( "IS_SENT_TO" )

	var isShutdowning:Boolean = false

	def startService(coin: String) {
		ApiLogs.debug("Neo4jBatchInserter start...")
		batchInserter = Some(BatchInserters.inserter( new File( DB_PATH ) ))

		ticker = coin

		jedis = Some(Redis.jedis())

		registerShutdownHookBatch()

		blockLabel = Some(Label.label( "Block" ))
		transactionLabel = Some(Label.label( "Transaction" ))
		addressLabel = Some(Label.label( "Address" ))

		ApiLogs.debug("Neo4jBatchInserter started")
	}

	def init() {
		batchInserter.get.createDeferredSchemaIndex( blockLabel.get ).on( "hash" ).create()
		batchInserter.get.createDeferredSchemaIndex( transactionLabel.get ).on( "hash" ).create()
		batchInserter.get.createDeferredSchemaIndex( addressLabel.get ).on( "value" ).create()
	}

	def stopService {
		isShutdowning = true
		batchInserter match {
			case None => /* */
			case Some(bi) => {
				ApiLogs.debug("Neo4jBatchInserter shutting down...")
				bi.shutdown()
				ApiLogs.debug("Neo4jBatchInserter shutdown ")
			}
		}
		jedis match {
			case None => /* */
			case Some(j) => j.close()
		}
		isShutdowning = false
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
		Redis.dels(jedis.get, "blockchain-explorer:"+ticker+":address:*")
		Redis.dels(jedis.get, "blockchain-explorer:"+ticker+":inputoutput:*")
	}

	/*
	private def getInputOutputNode(txHash: String, outputIndex:Long, data:java.util.Map[String,Object] = Map[String, Object]()):Long = {
		Redis.get(jedis.get, "blockchain-explorer:"+ticker+":inputoutput:"+txHash+":"+outputIndex) match {
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
				Redis.set(jedis.get, "blockchain-explorer:"+ticker+":inputoutput:"+txHash+":"+outputIndex, inputoutputNode.toString)
				inputoutputNode
			}
		}
	}
	*/
	
	private def getAddressNode(address: String):Long = {
		Redis.get(jedis.get, "blockchain-explorer:"+ticker+":address:"+address) match {
			case Some(addressNode) => addressNode.toLong
			case None => {
				var properties:java.util.Map[String,Object] = new java.util.HashMap()
					properties.put( "value", address.asInstanceOf[AnyRef] )
				val addressNode:Long = batchInserter.get.createNode( properties, addressLabel.get )
				Redis.set(jedis.get, "blockchain-explorer:"+ticker+":address:"+address, addressNode.toString)
				addressNode
			}
		}
	}


	def batchInsert(rpcBlock:RPCBlock, prevBlockNode:Option[Long], txsReceipt:List[RPCTransactionReceipt], blockReward:BigDecimal, uncles:ListBuffer[(RPCBlock, Integer, BigDecimal)] = ListBuffer()):Future[Either[Exception,(String, Long)]] = {
		Future {
			try {
				if(isShutdowning){
					throw new Exception("shutdown...")
				}

				// Block
				var properties:java.util.Map[String,Object] = new java.util.HashMap()
					properties.put( "hash", rpcBlock.hash )
					properties.put( "height", Converter.hexToInt(rpcBlock.number).asInstanceOf[AnyRef] )
					properties.put( "time", Converter.hexToInt(rpcBlock.timestamp).asInstanceOf[AnyRef] )
					properties.put( "reward", blockReward.bigDecimal.toPlainString )
					properties.put( "main_chain", true.asInstanceOf[AnyRef] )

				val blockNode:Long = batchInserter.get.createNode( properties, blockLabel.get )

				// Parent Block relationship
				prevBlockNode match {
					case Some(prevNode) => {
						batchInserter.get.createRelationship( blockNode, prevNode, follows, null )
					}
					case None => /* nothing (genesis block) */
				}

				// Block miner
				var addressNode:Long = getAddressNode(rpcBlock.miner)
				batchInserter.get.createRelationship( blockNode, addressNode, minedby, null )

				// Uncles
				for(uncle <- uncles){
					val (u, u_index, u_reward) = uncle
					properties = new java.util.HashMap()
					properties.put( "hash", u.hash )
					properties.put( "height", Converter.hexToInt(u.number).asInstanceOf[AnyRef] )
					properties.put( "time", Converter.hexToInt(u.timestamp).asInstanceOf[AnyRef] )
					properties.put( "uncle_index", u_index.asInstanceOf[AnyRef] )
					properties.put( "reward", u_reward.bigDecimal.toPlainString )
					properties.put( "main_chain", true.asInstanceOf[AnyRef] )
					var uncleNode:Long = batchInserter.get.createNode( properties, blockLabel.get )
          			batchInserter.get.createRelationship( uncleNode, blockNode, uncleof, null )
          			var addressNode:Long = getAddressNode(u.miner)
					batchInserter.get.createRelationship( uncleNode, addressNode, minedby, null )
				}

				// Transactions
				for((tx, i) <- rpcBlock.transactions.get.zipWithIndex){

					val txReceipt = txsReceipt(i)

					properties = new java.util.HashMap()
					properties.put( "hash", tx.hash )
					properties.put( "index", Converter.hexToInt(tx.transactionIndex).asInstanceOf[AnyRef] )
					properties.put( "nonce", tx.nonce.asInstanceOf[AnyRef] )
					properties.put( "value", Converter.hexToBigDecimal(tx.value).bigDecimal.toPlainString )
					properties.put( "gas", Converter.hexToBigDecimal(tx.gas).bigDecimal.toPlainString )
					properties.put( "gas_price", Converter.hexToBigDecimal(tx.gasPrice).bigDecimal.toPlainString )

					properties.put( "cumulative_gas_used", Converter.hexToBigDecimal(txReceipt.cumulativeGasUsed).bigDecimal.toPlainString )
					properties.put( "gas_used", Converter.hexToBigDecimal(txReceipt.gasUsed).bigDecimal.toPlainString )

					properties.put( "received_at", Converter.hexToInt(rpcBlock.timestamp).asInstanceOf[AnyRef] )
					properties.put( "input", tx.input.asInstanceOf[AnyRef] )
					var txNode:Long = batchInserter.get.createNode( properties, transactionLabel.get )
					batchInserter.get.createRelationship( blockNode, txNode, contains, null )
					var fromNode:Long = getAddressNode(tx.from)
					batchInserter.get.createRelationship( txNode, fromNode, issentfrom, null )
					tx.to match {
				    	case Some(to) => {
				    		var toNode:Long = getAddressNode(to)
				    		batchInserter.get.createRelationship( txNode, toNode, issentto, null )
				    	}
				    	case None => {
				    		txReceipt.contractAddress match {
				    			case Some(to) => {
				    				var toNode:Long = getAddressNode(to)
				    				batchInserter.get.createRelationship( txNode, toNode, issentto, null )
				    			}
				    			case None => /* */
				    		}
				    	}
				    }
				}

	      		Right("Block '"+rpcBlock.hash+"' (n°"+Converter.hexToInt(rpcBlock.number).toString+") added !", blockNode)

			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}
}