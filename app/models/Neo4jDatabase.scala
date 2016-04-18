package models

import scala.async.Async.async
import scala.concurrent.ExecutionContext.Implicits.global

import java.io.File
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.neo4j.graphdb.factory.GraphDatabaseSettings
import org.neo4j.graphdb.GraphDatabaseService

import scala.collection.JavaConversions._
import enums._

object Neo4jDatabase {
	val graphDb: GraphDatabaseService = new GraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder( new File("graph-db") )
                .setConfig( GraphDatabaseSettings.pagecache_memory, "512M" )
                .setConfig( GraphDatabaseSettings.string_block_size, "60" )
                .setConfig( GraphDatabaseSettings.array_block_size, "300" )
                .newGraphDatabase();

    def hello = {
    	ApiLogs.info("Instanciating Neo4jDatabase.graphDb...")
    }
}