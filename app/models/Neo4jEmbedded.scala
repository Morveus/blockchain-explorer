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
import scala.collection.immutable.ListMap
import play.api.libs.json._
import collection.JavaConversions._


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

import java.time._
import java.sql.Timestamp
import java.lang.Math

import models._
import utils._
import redis.clients.jedis._

object Neo4jEmbedded {
  val config  = play.Play.application.configuration
  val configIndexer:Config = ConfigFactory.parseFile(new File("indexer.conf"))
  val DB_PATH = Play.application.path.getPath + "/" + configIndexer.getString("dbname")

  val utcZoneId = ZoneId.of("UTC")

  var db:Option[GraphDatabaseService] = None    

  var blockLabel: Option[Label] = None
  var transactionLabel: Option[Label] = None
  var inputoutputLabel: Option[Label] = None
  var addressLabel: Option[Label] = None

  var minedby:RelationshipType = RelationshipType.withName( "MINED_BY" )
  var uncleof:RelationshipType = RelationshipType.withName( "UNCLE_OF" )
  var contains:RelationshipType = RelationshipType.withName( "CONTAINS" )
  var follows:RelationshipType = RelationshipType.withName( "FOLLOWS" )
  var issentfrom:RelationshipType = RelationshipType.withName( "IS_SENT_FROM" )
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
        blockNode.setProperty("main_chain", false)
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
  //  Future {
  //    val graphDb = db.get
  //    var tx:Transaction = db.get.beginTx()

  //    val next:Node = graphDb.findNode( blockLabel.get, "hash", prevBlock )

  //  }

  // }

  private def getTransactionNode(txHash:String):(Node, Boolean) = {
    val graphDb = db.get

    var optNode:Option[Node] = None

    // Find Node:
    val result:org.neo4j.graphdb.Result = graphDb.execute("MATCH (tx:Transaction {hash: '"+txHash+"'}) RETURN tx")
    val nodes:ResourceIterator[Node] = result.columnAs( "tx" )
    if ( nodes.hasNext() ){
      optNode = Some(nodes.next())
    }

    optNode match {
      case Some(node) => (node, true)
      case None => {
        // If doesn't exist, create :
        val txNode:Node = graphDb.createNode( transactionLabel.get )
        txNode.setProperty( "hash", txHash )

        (txNode, false)
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

  def insert(rpcBlock:RPCBlock, blockReward: BigDecimal, uncles:ListBuffer[(RPCBlock, Integer, BigDecimal)] = ListBuffer()):Future[Either[Exception,(String, Long)]] = {
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
        blockNode.setProperty( "height", Converter.hexToInt(rpcBlock.number) )
        blockNode.setProperty( "time", Converter.hexToInt(rpcBlock.timestamp) )
        blockNode.setProperty( "reward", blockReward.bigDecimal.toPlainString )
        blockNode.setProperty( "main_chain", true )

        // Parent Block relationship
        rpcBlock.parentHash match {
          case None => /* nothing */
          case Some(prevBlock) => {
            val prevBlockNode:Node = graphDb.findNode( blockLabel.get, "hash", prevBlock )
            blockNode.createRelationshipTo( prevBlockNode, follows )
          }
        }

        // Block miner
        var addressNode:Node = getAddressNode(rpcBlock.miner)
        blockNode.createRelationshipTo( addressNode, minedby)

        // Uncles
        for(uncle <- uncles){
          val (u, u_index, u_reward) = uncle

          val uncleNode:Node = graphDb.createNode( blockLabel.get )
          uncleNode.setProperty( "hash", u.hash )
          uncleNode.setProperty( "height", Converter.hexToInt(u.number) )
          uncleNode.setProperty( "time", Converter.hexToInt(u.timestamp) )
          uncleNode.setProperty( "uncle_index", u_index )
          uncleNode.setProperty( "reward", u_reward.bigDecimal.toPlainString )
          uncleNode.setProperty( "main_chain", true )
          uncleNode.createRelationshipTo( blockNode, uncleof)

          var addressNode:Node = getAddressNode(u.miner)
          uncleNode.createRelationshipTo( addressNode, minedby)
          
        }
        

        // Transactions
        for(rpcTransaction <- rpcBlock.transactions.get){

          var (txNode:Node, alreadyExist) = getTransactionNode(rpcTransaction.hash)

          if(alreadyExist == false) {
            txNode.setProperty( "index", Converter.hexToInt(rpcTransaction.transactionIndex) )
            txNode.setProperty( "nonce", rpcTransaction.nonce )
            txNode.setProperty( "value", Converter.hexToBigDecimal(rpcTransaction.value).bigDecimal.toPlainString )
            txNode.setProperty( "gas", Converter.hexToBigDecimal(rpcTransaction.gas).bigDecimal.toPlainString )
            txNode.setProperty( "gas_price", Converter.hexToBigDecimal(rpcTransaction.gasPrice).bigDecimal.toPlainString )
            txNode.setProperty( "received_at", Converter.hexToInt(rpcBlock.timestamp) )
            txNode.setProperty( "input", rpcTransaction.input )

            var fromNode:Node = getAddressNode(rpcTransaction.from)
            txNode.createRelationshipTo( fromNode, issentfrom)

            rpcTransaction.to match {
              case Some(to) => {
                var toNode:Node = getAddressNode(to)
                txNode.createRelationshipTo( toNode, issentto)
              }
              case None => /* nothing */
            }
          }

          blockNode.createRelationshipTo( txNode, contains )
          
        }

        tx.success()

        Right("Block '"+rpcBlock.hash+"' (n°"+ Converter.hexToInt(rpcBlock.number).toString +") added !", 0)

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

  def insertTransactions(transactions:List[RPCTransaction]):Future[Either[Exception,(String, ListBuffer[String])]] = {
    Future {

      val graphDb = db.get
      var tx:Transaction = graphDb.beginTx()

      try {

        if(isShutdowning){
          throw new Exception("shutdown...")
        }

        val utcDateTime = ZonedDateTime.now.withZoneSameInstant(utcZoneId)

        var txsAdded:ListBuffer[String] = ListBuffer()
        

        // Transactions
        for(rpcTransaction <- transactions){

          var (txNode:Node, alreadyExist) = getTransactionNode(rpcTransaction.hash)

          if(alreadyExist == false) {

            var timestamp:Int = (Timestamp.valueOf(utcDateTime.toLocalDateTime).getTime / 1000).toInt

            txNode.setProperty( "index", Converter.hexToInt(rpcTransaction.transactionIndex) )
            txNode.setProperty( "nonce", rpcTransaction.nonce )
            txNode.setProperty( "value", Converter.hexToBigDecimal(rpcTransaction.value).bigDecimal.toPlainString )
            txNode.setProperty( "gas", Converter.hexToBigDecimal(rpcTransaction.gas).bigDecimal.toPlainString )
            txNode.setProperty( "gas_price", Converter.hexToBigDecimal(rpcTransaction.gasPrice).bigDecimal.toPlainString )
            txNode.setProperty( "received_at", timestamp )
            txNode.setProperty( "input", rpcTransaction.input )

            var fromNode:Node = getAddressNode(rpcTransaction.from)
            txNode.createRelationshipTo( fromNode, issentfrom)

            rpcTransaction.to match {
              case Some(to) => {
                var toNode:Node = getAddressNode(to)
                txNode.createRelationshipTo( toNode, issentto)
              }
              case None => /* nothing */
            }

            txsAdded += rpcTransaction.hash
          }          
        }

        tx.success()

        Right(txsAdded.size + " transaction(s) added !", txsAdded)

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

  def getCurrentBlock():Future[Either[Exception, JsValue]] = {
    Future {
      val graphDb = db.get
      var tx:Transaction = graphDb.beginTx()
      try { 

        val query = """
          MATCH (b:Block)
          WHERE b.main_chain = true
          RETURN b ORDER BY b.height DESC LIMIT 1
        """

        val dbRes:org.neo4j.graphdb.Result = graphDb.execute(query)
        val nodes:ResourceIterator[Node] = dbRes.columnAs( "b" )
        if(nodes.hasNext()){
          //Block
          val blockNode:Node = nodes.next()


          getBlock(blockNode) match {
            case Right(json) => {
              Right(json)
            }
            case Left(e) => {
              Left(new Exception("TODO"))
            }
          }
          
        }else{
          Left(new Exception("TODO"))
        }
      } catch {
        case e:Exception => {
          Left(e)
        }
      }finally {
        tx.close()
      }
    }
  }

  def getBlocks(blockHashesRaw:String):Future[Either[Exception, JsValue]] = {
    Future {
      val graphDb = db.get
      var tx:Transaction = graphDb.beginTx()
      try { 

        val blockHashes: Array[String] = blockHashesRaw.split(",")

        val query = """
          MATCH (b:Block)
          WHERE b.hash IN { blockHashes }
          RETURN b
        """

        var parameters:java.util.Map[String,Object] = new java.util.HashMap()
        parameters.put( "blockHashes",  blockHashes)

        var result:ListBuffer[JsValue] = ListBuffer()

        val dbRes:org.neo4j.graphdb.Result = graphDb.execute(query, parameters)
        val nodes:ResourceIterator[Node] = dbRes.columnAs( "b" )
        while(nodes.hasNext()){
          //Block
          val blockNode:Node = nodes.next()

          getBlock(blockNode) match {
            case Right(json) => {
              result += json
            }
            case Left(e) => {
              //TODO
            }
          }
        }

        Right(Json.toJson(result))
      } catch {
        case e:Exception => {
          Left(e)
        }
      }finally {
        tx.close()
      }
    }
  }

  def getAddressesTransactions(addressesRaw:String, blockHash: Option[String]):Future[Either[Exception, JsValue]] = {

    val blockHeightFut:Future[Either[Exception, Long]] = if(blockHash.isDefined) {
      getBlockHeight(blockHash.get).map { res =>
        res
      }
    } else {
      Future(Right(0.toLong))
    }

    blockHeightFut.flatMap { res =>
      res match {
        case Right(height) => {
          getAddressesTxs(addressesRaw, height)
        }
        case Left(e) => Future(Left(e))
      }

    }
  }

  def getTransactions(txsHashesRaw:String):Future[Either[Exception, JsValue]] = {
    Future {
      val graphDb = db.get
      var tx:Transaction = graphDb.beginTx()
      try { 

        val txsHashes: Array[String] = txsHashesRaw.split(",")

        val query = """
          MATCH (tx:Transaction)
          WHERE tx.hash IN { txsHashes }
          RETURN DISTINCT tx
        """

        var parameters:java.util.Map[String,Object] = new java.util.HashMap()
        parameters.put( "txsHashes",  txsHashes)

        var result:ListBuffer[JsValue] = ListBuffer()

        val dbRes:org.neo4j.graphdb.Result = graphDb.execute(query, parameters)
        val nodes:ResourceIterator[Node] = dbRes.columnAs( "tx" )
        while(nodes.hasNext()){
          //Transaction
          val txNode:Node = nodes.next()

          getTransaction(txNode) match {
            case Right(json) => {
              result += json
            }
            case Left(e) => {
              //TODO
            }
          }
        }

        Right(Json.toJson(result))

      } catch {
        case e:Exception => {
          Left(e)
        }
      }finally {
        tx.close()
      }
    }
  }


  private def getBlockHeight(hash:String):Future[Either[Exception, Long]] = {
    Future {
      val graphDb = db.get
      var tx:Transaction = graphDb.beginTx()
      try { 
        val blockNode:Node = graphDb.findNode( blockLabel.get, "hash", hash )
        val height = blockNode.getProperty("height").toString.toLong
        Right(height)
      } catch {
        case e:Exception => {
          Left(e)
        }
      }finally {
        tx.close()
      }
    }
  }

  private def getBlock(blockNode:Node):Either[Exception, JsValue] = {
    //Result
    val result = Json.obj( 
      "hash" -> blockNode.getProperty("hash").toString,
      "height" -> blockNode.getProperty("height").toString.toLong,
      "time" -> blockNode.getProperty("time").toString.toLong
    )

    Right(result)
  }

  private def getTransaction(txNode:Node):Either[Exception, JsValue] = {

    //Block
    var block:JsValue = txNode.getSingleRelationship( contains , Direction.INCOMING ) match {
      case r:Relationship => {
        val blockNode:Node = r.getStartNode()
        Json.obj(
          "hash" -> blockNode.getProperty("hash").toString,
          "height" -> blockNode.getProperty("height").toString.toLong,
          "time" -> blockNode.getProperty("time").toString.toLong
        )
      }
      case null => JsNull
    }

    //From
    val fromNode:Node = txNode.getSingleRelationship( issentfrom , Direction.OUTGOING ).getEndNode()

    //To
    var to:JsValue = txNode.getSingleRelationship( issentto , Direction.OUTGOING ) match {
      case r:Relationship => {
        val toNode:Node = r.getEndNode()
        JsString(toNode.getProperty("value").toString)
      }
      case null => JsNull
    }

    //Result
    val result = Json.obj( 
      "hash" -> txNode.getProperty("hash").toString,
      "received_at" -> txNode.getProperty("received_at").toString.toLong,
      "nonce" -> txNode.getProperty("nonce").toString,
      "value" -> Converter.stringToBigDecimal(txNode.getProperty("value").toString),
      "gas" -> Converter.stringToBigDecimal(txNode.getProperty("gas").toString),
      "gas_price" -> Converter.stringToBigDecimal(txNode.getProperty("gas_price").toString),
      "from" -> fromNode.getProperty("value").toString,
      "to" -> to,
      "input" -> txNode.getProperty("input").toString,
      "index" -> txNode.getProperty("index").toString.toLong,
      "block" -> block
    )



    Right(result)
  }

  private def getAddressesTxs(addressesRaw:String, blockHeight: Long):Future[Either[Exception, JsValue]] = {
    Future {
      val graphDb = db.get
      var tx:Transaction = graphDb.beginTx()
      try { 

        val addresses: Array[String] = addressesRaw.split(",")

        val query = """
          MATCH (a:Address)<-[]-(tx:Transaction)<-[:CONTAINS]-(b:Block)
          WHERE a.value IN { addresses } AND b.height > { blockHeight }
          WITH tx, b ORDER BY b.height ASC LIMIT 50 
          RETURN DISTINCT tx
        """
        var parameters:java.util.Map[String,Object] = new java.util.HashMap()
        parameters.put( "addresses", addresses.asInstanceOf[AnyRef] )
        parameters.put( "blockHeight", blockHeight.asInstanceOf[AnyRef] )

        var result:ListBuffer[JsValue] = ListBuffer()

        val dbRes:org.neo4j.graphdb.Result = graphDb.execute(query, parameters)
        val nodes:ResourceIterator[Node] = dbRes.columnAs( "tx" )
        while(nodes.hasNext()){

          //Transaction
          val txNode:Node = nodes.next()

          getTransaction(txNode) match {
            case Right(json) => {
              result += json
            }
            case Left(e) => {
              //TODO
            }
          }
        }

        Right(Json.toJson(result))
      } catch {
        case e:Exception => {
          Left(e)
        }
      }finally {
        tx.close()
      }
    }
  }

}