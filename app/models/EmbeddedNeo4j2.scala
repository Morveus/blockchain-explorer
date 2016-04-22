package models

import play.api._
import play.api.Play.current
import play.api.mvc._
import scala.util.{ Try, Success, Failure }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

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
		graphDb = Some(new GraphDatabaseFactory().newEmbeddedDatabase(DB_PATH))
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

	def addBlock(ticker:String, block:NeoBlock, txHashes:List[String], previousBlockHash:Option[String]):Future[Either[Exception,String]]  = {
		Future {
			try {
				val query = prepareBlockQuery(block, txHashes, previousBlockHash)
				EmbeddedNeo4j.insertBlock(graphDb.get, query, block.hash, block.height)
	      		Right("Block '"+block.hash+"' added !")
			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}

	def getUnprocessedTransaction(ticker:String, noHashes:List[String]):Future[Either[Exception,String]] = {
		Future{
			try {
				val txHash = EmbeddedNeo4j.getUnprocessedTransaction(graphDb.get)
				Right(txHash)
			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}

	def addTransaction(ticker:String, tx:NeoTransaction, inputs:Map[Int, NeoInput], outputs:Map[Int, NeoOutput]):Future[Either[Exception,String]]  = {
		Future {
			try {
				val query = prepareTransactionQuery(tx, inputs, outputs)
				EmbeddedNeo4j.insertTransaction(graphDb.get, query, tx.hash)
	      Right("Transaction '"+tx.hash+"' added !")
			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
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

	private def prepareBlockQuery(block:NeoBlock, txHashes:List[String], previousBlockHash:Option[String]):String = {
		var query = """
      MERGE (b:Block { hash: '"""+block.hash+"""' })
      ON CREATE SET 
        b.height = """+block.height+""",
        b.time = """+block.time+""",
        b.main_chain = """+block.main_chain+"""
    """
    previousBlockHash match {
      case Some(prev) => {
        query = """
          MATCH (prevBlock:Block { hash: '"""+prev+"""' })
        """+query+"""
          MERGE (b)-[:FOLLOWS]->(prevBlock)
        """
      }
      case None => /*Nothing*/
    }

    	if(block.hash == "917c9b20465203f1f3e25ba219a3fd5d533223affc1de91028cddcafc13fa70e"){
    		println(txHashes.toString)
    	}

    for(txHash <- txHashes){
      query += """
        MERGE (:Transaction { hash: '"""+txHash+"""' })<-[:CONTAINS]-(b)
      """
    }

    query += """RETURN b"""

    if(block.hash == "917c9b20465203f1f3e25ba219a3fd5d533223affc1de91028cddcafc13fa70e"){
    		println(query)
    	}

    query
	}

	private def prepareTransactionQuery(tx:NeoTransaction, inputs:Map[Int, NeoInput], outputs:Map[Int, NeoOutput]):String = {
		var query = ""

    query += """
		  MATCH (tx:Transaction { hash: '"""+tx.hash+"""' })
		  SET tx.lock_time = """+tx.lock_time+"""
		"""

    for((inIndex, input) <- inputs.toSeq.sortBy(_._1)){
      input.coinbase match {
        case Some(c) => {
          	query += """
				CREATE (in"""+inIndex+""":InputOutput { input_index: """+inIndex+""", coinbase: '"""+c+"""' })-[:SUPPLIES]->(tx)
			"""
        }
        case None => {
			query += """
				MERGE (:Transaction { hash: '"""+input.output_tx_hash.get+"""' })-[:EMITS]->(in"""+inIndex+""":InputOutput { output_index: """+input.output_index.get+""" })-[:SUPPLIES]->(tx)
				SET in"""+inIndex+""".input_index = """+inIndex+"""

			"""
        }
      }
    } 

    for((outIndex, output) <- outputs.toSeq.sortBy(_._1)){
      query += """
			  MERGE (out"""+outIndex+""":InputOutput { output_index: """+output.output_index+"""})<-[:EMITS]-(tx)
			  SET
			    out"""+outIndex+""".value= """+output.value+""",
			    out"""+outIndex+""".script_hex= '"""+output.script_hex+"""'
			"""

      for(address <- output.addresses){
        query += """
				  MERGE (out"""+outIndex+"""Addr:Address { address: '"""+address+"""' })<-[:IS_SENT_TO]-(out"""+outIndex+""")
				"""
      }
    }

    query += """RETURN tx"""

    query
	}
}