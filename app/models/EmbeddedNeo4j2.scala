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
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.ResourceIterator;

import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserters;
import org.neo4j.graphdb.DynamicRelationshipType;


import models._
import utils.Redis
import redis.clients.jedis._

object EmbeddedNeo4j2 {
	val config 	= play.Play.application.configuration
	
	val DB_PATH = "graph-db"
	var graphDb:Option[GraphDatabaseService] = None
	def startService {
		//graphDb = Some(new GraphDatabaseFactory().newEmbeddedDatabase(new File(DB_PATH)))

		graphDb = Some(
			new GraphDatabaseFactory()
	    .newEmbeddedDatabaseBuilder( new File(DB_PATH) )
	    .loadPropertiesFromFile( "neo4j.properties" )
	    .newGraphDatabase()
    )

    registerShutdownHook(graphDb.get)
    ApiLogs.debug("started")
	}

	def stopService {
		ApiLogs.debug("shutdown")
		graphDb.get.shutdown()
	}

	private def registerShutdownHook(graphDb:GraphDatabaseService) = {
		Runtime.getRuntime().addShutdownHook( new Thread()
			{
				override def run()
				{
					graphDb.shutdown()
				}
			}
	    )
	}

	def dropDb {
		FileUtils.deleteRecursively(new File(DB_PATH))
	}



	val DB_BATCH_PATH = "graph-db"
	var batchInserter:Option[BatchInserter] = None
	var blockLabel: Option[Label] = None
	var transactionLabel: Option[Label] = None
	var addressLabel: Option[Label] = None
	var jedis:Option[Jedis] = None

	var minedby:RelationshipType = DynamicRelationshipType.withName( "MINED_BY" )
	var uncleof:RelationshipType = DynamicRelationshipType.withName( "UNCLE_OF" )
	var contains:RelationshipType = DynamicRelationshipType.withName( "CONTAINS" )
	var follows:RelationshipType = DynamicRelationshipType.withName( "FOLLOWS" )
	var issentfrom:RelationshipType = DynamicRelationshipType.withName( "IS_SENT_FROM" )
	var issentto:RelationshipType = DynamicRelationshipType.withName( "IS_SENT_TO" )

	def startServiceBatch {
		batchInserter = Some(BatchInserters.inserter( new File( DB_BATCH_PATH ) ))
		jedis = Some(Redis.jedis())
		registerShutdownHookBatch(batchInserter.get, jedis.get)

		blockLabel = Some(DynamicLabel.label( "Block" ))
		batchInserter.get.createDeferredSchemaIndex( blockLabel.get ).on( "hash" ).create()

		transactionLabel = Some(DynamicLabel.label( "Transaction" ))
		batchInserter.get.createDeferredSchemaIndex( transactionLabel.get ).on( "hash" ).create()

		addressLabel = Some(DynamicLabel.label( "Address" ))
		batchInserter.get.createDeferredSchemaIndex( addressLabel.get ).on( "value" ).create()

		

		ApiLogs.debug("started")
	}

	def stopServiceBatch {
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

	private def registerShutdownHookBatch(bi:BatchInserter, jedis:Jedis) = {
		Runtime.getRuntime().addShutdownHook( new Thread()
			{
				override def run()
				{
					bi.shutdown()
					jedis.close()
				}
			}
	    )
	}

	def dropDbBatch {
		Redis.dels("address:*")
		FileUtils.deleteRecursively(new File(DB_BATCH_PATH))
	}

	

	private def hexToInt(value:String):Int = {
		Integer.decode(value)
	}

	

	def insert(rpcBlock:RPCBlock, blockReward:BigDecimal, uncles:List[(RPCBlock, Integer, BigDecimal)] = List()):Future[Either[Exception,String]] = {
		Future {
			try {
				val blockQuery = prepareBlockQuery(rpcBlock, blockReward)
				 val unclesQuery = prepareUnclesQuery(rpcBlock, uncles)
				 val txQueries = prepareTransactionsQueries(rpcBlock)
				//val unclesQuery = ""
				//val txQueries = List[String]()
				EmbeddedNeo4j.insertBlock(graphDb.get, blockQuery, rpcBlock.hash, hexToInt(rpcBlock.number), unclesQuery, txQueries)
	      Right("Block '"+rpcBlock.hash+"' (n°"+hexToInt(rpcBlock.number)+") added !")
			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}

	def getAddressNode(address: String):Long = {
		Redis.get(jedis.get, "address:"+address) match {
			case Some(addressNode) => addressNode.toLong
			case None => {
				var properties:java.util.Map[String,Object] = new java.util.HashMap()
					properties.put( "value", address )
				val addressNode:Long = batchInserter.get.createNode( properties, addressLabel.get )
				Redis.set(jedis.get, "address:"+address, addressNode.toString)
				addressNode
			}
		}
	}

	def batchInsert(rpcBlock:RPCBlock, prevBlockNode:Option[Long], blockReward:BigDecimal, uncles:List[(RPCBlock, Integer, BigDecimal)] = List()):Future[Either[Exception,(String, Long)]] = {
		Future {
			try {				

				// Block
				var properties:java.util.Map[String,Object] = new java.util.HashMap()
					properties.put( "hash", rpcBlock.hash )
					properties.put( "height", hexToInt(rpcBlock.number).toString )
					properties.put( "time", hexToInt(rpcBlock.timestamp).toString )
				val blockNode:Long = batchInserter.get.createNode( properties, blockLabel.get )

				prevBlockNode match {
					case Some(prevNode) => {
						batchInserter.get.createRelationship( blockNode, prevNode, follows, null )
					}
					case None => /* */
				}				

				// Block miner
				var addressNode:Long = getAddressNode(rpcBlock.miner)
				properties = new java.util.HashMap()
					properties.put( "reward", blockReward.toString )
				batchInserter.get.createRelationship( blockNode, addressNode, minedby, properties )

				// Uncles
				for(uncle <- uncles){
					val (u, u_index, u_reward) = uncle
					properties = new java.util.HashMap()
					properties.put( "hash", u.hash )
					properties.put( "height", hexToInt(u.number).toString )
					properties.put( "time", hexToInt(u.timestamp).toString )
					properties.put( "uncle_index", u_index.toString )
					var uncleNode:Long = batchInserter.get.createNode( properties, blockLabel.get )
          batchInserter.get.createRelationship( uncleNode, blockNode, uncleof, null )
          var addressNode:Long = getAddressNode(u.miner)
          properties = new java.util.HashMap()
						properties.put( "reward", u_reward.toString )
					batchInserter.get.createRelationship( uncleNode, addressNode, minedby, properties )
				}

				// Transactions
				for(tx <- rpcBlock.transactions.get){
					properties = new java.util.HashMap()
					properties.put( "hash", tx.hash )
					properties.put( "index", hexToInt(tx.transactionIndex).toString )
					properties.put( "nonce", tx.nonce.toString )
					properties.put( "value", tx.value.toString )
					properties.put( "gas", tx.gas.toString )
					properties.put( "gasPrice", tx.gasPrice.toString )
					properties.put( "input", tx.input.toString )
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
			    		/* */
			    	}
			    }
				}

	      Right("Block '"+rpcBlock.hash+"' (n°"+hexToInt(rpcBlock.number)+") added !", blockNode)
			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}

	private def prepareBlockQuery(block:RPCBlock, blockReward:BigDecimal):String = {
		val query = """
      MERGE (prevBlock:Block { hash: '"""+block.parentHash+"""' })
      MERGE (a:Address { address_id: '"""+block.miner+"""'})      
      MERGE (b:Block { hash: '"""+block.hash+"""' })
      	SET 
	        b.height = """+hexToInt(block.number)+""",
	        b.time = """+hexToInt(block.timestamp)+"""
      MERGE (b)-[:FOLLOWS]->(prevBlock)
      MERGE (b)-[:MINED_BY { reward: """+blockReward+""" }]->(a)
      RETURN b
    """
    query
	}

	private def prepareUnclesQuery(block:RPCBlock, uncles:List[(RPCBlock, Integer, BigDecimal)]):String = {
		if(uncles.size > 0){
			var query = """
	      MERGE (b:Block { hash: '"""+block.hash+"""' })
	    """
	    for(uncle <- uncles){
	    	val (u, u_index, u_reward) = uncle
	    	query += """
	    		MERGE (a"""+u_index+""":Address { address_id: '"""+u.miner+"""'})
	    		MERGE (u"""+u_index+""":Block { hash: '"""+u.hash+"""' })
		      	SET 
			        u"""+u_index+""".height = """+hexToInt(u.number)+""",
			        u"""+u_index+""".time = """+hexToInt(u.timestamp)+""",
			        u"""+u_index+""".uncle_index = """+u_index+"""
			    MERGE (u"""+u_index+""")-[:UNCLE_OF]->(b)
			    MERGE (u"""+u_index+""")-[:MINED_BY { reward: """+u_reward+""" }]->(a"""+u_index+""")
	    	"""
	    }
	    query
		}else{
			""
		}
	}

	private def prepareTransactionsQueries(block:RPCBlock):List[String] = {
		var queries = ListBuffer[String]()

		for(tx <- block.transactions.get){
			var query = """
	      MERGE (b:Block { hash: '"""+block.hash+"""' })
	      MERGE (from:Address { address_id: '"""+tx.from+"""'})
	      MERGE (tx:Transaction { hash: '"""+tx.hash+"""'})
	      	SET
	      		tx.index = """+hexToInt(tx.transactionIndex)+""",
	      		tx.nonce = '"""+tx.nonce+"""',
	      		tx.value = '"""+tx.value+"""',
	      		tx.gas = '"""+tx.gas+"""',
	      		tx.gasPrice = '"""+tx.gasPrice+"""',
	      		tx.input = '"""+tx.input+"""'
	    """
	    tx.to match {
	    	case Some(to) => {
	    		query += """
	    		MERGE (to:Address { address_id: '"""+to+"""'})
	      	MERGE (from)<-[:IS_SENT_FROM]-(tx)-[:IS_SENT_TO]->(to)
	    		"""
	    	}
	    	case None => {
	    		query += """
	      	MERGE (from)<-[:IS_SENT_FROM]-(tx)
	    		"""
	    	}
	    }
	    queries += query
		}

		queries.toList
	}

}