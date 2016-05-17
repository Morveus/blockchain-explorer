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

object Neo4jEmbedded {
	val config 	= play.Play.application.configuration
	val DB_PATH = "graph-db"

	var db:Option[GraphDatabaseService] = None                

	def startService {
		db = Some(new GraphDatabaseFactory().newEmbeddedDatabase( new File(DB_PATH) ))
		registerShutdownHookBatch()
		ApiLogs.debug("started")
	}

	def stopService {
		db match {
			case None => /* */
			case Some(d) => d.shutdown()
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

	def getCurrentBlock():Future[Either[Exception, String]] = {
		Future {
			try {	
				val query = "MATCH (b:Block) RETURN b ORDER BY b.height DESC LIMIT 1"
				val resultIterator:ResourceIterator[Node] = db.get.execute( query ).columnAs( "b" )
				val node:Node = resultIterator.next()
				val currentBlockHash:String = node.getProperty("hash").toString
				Right(currentBlockHash)
			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}

	def getAddressesTransactions(addressesHashes:String, blockHash: Option[String]):Future[Either[Exception, String]] = {
		Future {
			try {	
				val addresses = addressesHashes.split(",")
				val addressesList = addresses.mkString("['", "', '", "']")

				/*
				val query = """
					MATCH (a1:Address)<-[:IS_SENT_TO]-(i:InputOutput)-[:SUPPLIES]->(tx:Transaction)-[:EMITS]->(o:InputOutput)-[:IS_SENT_TO]->(a2:Address),
						(b:Block)-[:CONTAINS]->(tx:Transaction)
					WHERE a1.value IN """+addressesList+""" OR a2.value IN """+addressesList+"""
					WITH tx, a1, a2, b, o,
						{input_index: i.input_index, output_index: i.output_index, value: i.value, addresses: collect(a1.value)} as i_inputs ORDER BY i_inputs.input_index ASC 
					WITH tx, a1, a2, b, o, i_inputs, 
						{output_index: o.output_index, value: o.value, addresses: collect(a2.value)} as o_outputs ORDER BY o_outputs.output_index ASC 
					RETURN {hash: tx.hash, block: b, inputs: collect(DISTINCT i_inputs), outputs: collect(DISTINCT o_outputs)} as res ORDER BY res.hash ASC
				"""
				*/
				//val query = "MATCH (b:Block) RETURN b ORDER BY b.height DESC LIMIT 20"

				val query = """
					MATCH (tx:Transaction)-[:EMITS]->(o:InputOutput)-[:IS_SENT_TO]->(a2:Address),
						(b:Block)-[:CONTAINS]->(tx:Transaction)
					WHERE a2.value IN ['DRJ4grRhcuse9pV1sfVvcGJZKdg4wwzhUR']
					WITH tx, a2, b, o,
						{output_index: o.output_index, value: o.value, addresses: collect(a2.value)} as o_outputs ORDER BY o_outputs.output_index ASC
					WITH tx, a2, b, o, o_outputs,
						{hash: b.hash, height: b.height} as block
					RETURN {hash: tx.hash, block: block, outputs: collect(DISTINCT o_outputs)} as res ORDER BY res.hash ASC
				"""

				println(query)

				// val resultIterator:ResourceIterator[Node] = db.get.execute( query ).columnAs( "tx" )
				// var result = ""
				// while ( resultIterator.hasNext() ){
				// 	val tx = resultIterator.next()
				// 	result += tx.getProperty( "hash" ).toString() + ","
				// }



				println("1")
				var result:org.neo4j.graphdb.Result = db.get.execute( query )
				println("2")
				var retour = ""
				while ( result.hasNext() )
				{
					var row:Map[String, Object] = result.next()
					// for ( key <- result.columns() ){
					// 	retour += key + ": " + row.get(key) + "; ";
					// }

					var tx:Object = row.get("res").get

					retour += tx

					println("3")
				}
				println("4")


				println(retour)







				// println("1")
				// val resultIterator:ResourceIterator[Node] = db.get.execute( query ).columnAs( "res" )
				// println("2")
				// var result = ""
				// while ( resultIterator.hasNext() ){
				// 	println("3")
				// 	result += "1, "
				// }
				// println("4")


				// for (Object cell : IteratorUtil.asIterable(result.columnAs("result"))) {
    //         Iterable<Node> nodes = (Iterable<Node>) cell;
    //         for (Node n : nodes) {
    //             System.out.println(n);
    //         }
    //     }


				Right(retour)
			} catch {
				case e:Exception => {
					Left(e)
				}
			}
		}
	}
}