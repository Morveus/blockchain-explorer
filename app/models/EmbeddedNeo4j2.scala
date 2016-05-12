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

import models._

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

	def dropDb {
		FileUtils.deleteRecursively(new File(DB_PATH))
	}

	private def hexToInt(value:String):Int = {
		Integer.decode(value)
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