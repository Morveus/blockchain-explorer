package models

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.json._
import scala.collection.mutable.{ListBuffer, Map}
import scala.util.control.Breaks._

import scala.concurrent.ExecutionContext.Implicits.global

import com.ning.http.client.AsyncHttpClientConfig
import com.ning.http.client.extra.ThrottleRequestFilter

import org.anormcypher._
import play.api.libs.ws._, ning._
import scala.concurrent._

object Neo4j {

  val config = play.api.Play.configuration
 

  private def connect(ticker:String, requestTimeOut:Option[Int] = None) = {
    val neo4jUrl = config.getString("coins."+ticker+".neo4j.url").get
    val neo4jPort = config.getInt("coins."+ticker+".neo4j.port").get
    val neo4jUser = config.getString("coins."+ticker+".neo4j.user").get
    val neo4jPass = config.getString("coins."+ticker+".neo4j.pass").get

    val clientConfig = new DefaultWSClientConfig()
    val secureDefaults: AsyncHttpClientConfig = new NingAsyncHttpClientConfigBuilder(clientConfig).build()

    val builder = new AsyncHttpClientConfig.Builder(secureDefaults)
    requestTimeOut match {
      case Some(time) => builder.setRequestTimeoutInMs(time)
      case None => /* Default */
    }
    

    val secureDefaultsWithSpecificOptions: AsyncHttpClientConfig = builder.build()
    val wsclient = new NingWSClient(secureDefaultsWithSpecificOptions)
    val connection = Neo4jREST(neo4jUrl, neo4jPort, "/db/data/", neo4jUser, neo4jPass)(wsclient)

    (wsclient, connection)
  }

  def addBlock(ticker:String, block:NeoBlock, previousBlockHash:Option[String]):Future[Either[Exception,String]] = {
    Future {
      
      var query = """
        MERGE (b:Block { hash:{blockHash} })
        ON CREATE SET 
          b.height = {blockHeight},
          b.time = {blockTime},
          b.main_chain = {blockMainChain}
      """
      var params:Map[String, Any] = Map("blockHash" -> block.hash,
                                        "blockHeight" -> block.height,
                                        "blockTime" -> block.time,
                                        "blockMainChain" -> block.main_chain
                                      )

      previousBlockHash match {
        case Some(prev) => {
          query = """
            MATCH (prevBlock:Block { hash: {prevBlockHash} })
          """+query+"""
            MERGE (b)-[:FOLLOWS]->(prevBlock)
          """
          params("prevBlockHash") = prev
        }
        case None => /*Nothing*/
      }

      val cypherQuery = Cypher(query).on(params.toSeq:_*)

      implicit val (wsclient, connection) = connect(ticker)
      val success = cypherQuery.execute()
      wsclient.close()

      success match {
        case true => Right("Block added")
        case false => {
          ApiLogs.debug(query)
          ApiLogs.debug(params.toString)
          Left(new Exception("Error : Neo4j.addBlock("+ticker+","+block+","+previousBlockHash+") not inserted"))
        }
      }
    }
  }

  def addTransaction(ticker:String, tx:NeoTransaction, blockHash:String, inputs:Map[Int, NeoInput], outputs:Map[Int, NeoOutput]):Future[Either[Exception,String]] = {
    val neo4jMaxQueries = config.getInt("coins."+ticker+".neo4j.maxQueries").get
    Future { 

      var queries = ListBuffer[ListBuffer[String]]()
      var queriesPool = ListBuffer[String]()
      queriesPool += """
        MATCH (b:Block { hash: {blockHash} })
        MERGE (tx:Transaction { hash:{txHash} })
        ON CREATE SET
          tx.received_at = {txReceivedAt},
          tx.lock_time = {txLockTime}
        MERGE (tx)<-[:CONTAINS]-(b)
      """
      var params:Map[String, Any] = Map("blockHash" -> blockHash,
                                        "txHash" -> tx.hash,
                                        "txReceivedAt" -> tx.received_at,
                                        "txLockTime" -> tx.lock_time
                                      )


      for((inIndex, input) <- inputs.toSeq.sortBy(_._1)){
        if(queriesPool.size == neo4jMaxQueries){
          queries += queriesPool
          queriesPool = ListBuffer[String]()
          queriesPool += """
            MERGE (tx:Transaction { hash:{txHash} })
          """
        }
        input.coinbase match {
          case Some(c) => {
            queriesPool += """
              MERGE (in"""+inIndex+""":Input { input_index: """+inIndex+""" })-[:SUPPLIES]->(tx)
              ON CREATE SET
                in"""+inIndex+""".coinbase= '"""+c+"""'
            """
          }
          case None => {
            queriesPool += """
              MERGE (in"""+inIndex+""":Input { input_index: """+inIndex+""" })-[:SUPPLIES]->(tx)
              MERGE (inOut"""+inIndex+"""Tx:Transaction { hash: '"""+input.output_tx_hash.get+"""' })
              MERGE (inOut"""+inIndex+""":Output { output_index: """+input.output_index.get+"""})<-[:EMITS]-(inOut"""+inIndex+"""Tx)
              MERGE (in"""+inIndex+""":Input)<-[:IS_SPENT_BY]-(inOut"""+inIndex+""")
            """
          }
        }
      } 


      for((outIndex, output) <- outputs.toSeq.sortBy(_._1)){
        if(queriesPool.size == neo4jMaxQueries){
          queries += queriesPool
          queriesPool = ListBuffer[String]()
          queriesPool += """
            MERGE (tx:Transaction { hash:{txHash} })
          """
        }
        queriesPool += """
          MERGE (out"""+outIndex+""":Output { output_index: """+output.output_index+"""})<-[:EMITS]-(tx)
          ON CREATE SET
            out"""+outIndex+""".value= """+output.value+""",
            out"""+outIndex+""".script_hex= '"""+output.script_hex+"""'
        """

        for(address <- output.addresses){
          if(queriesPool.size == neo4jMaxQueries){
            queries += queriesPool
            queriesPool = ListBuffer[String]()
            queriesPool += """
              MERGE (tx:Transaction { hash:{txHash} })
            """
          }
          queriesPool += """
            MERGE (out"""+outIndex+"""Addr:Address { address: '"""+address+"""' })
            MERGE (out"""+outIndex+"""Addr)<-[:IS_SENT_TO]-(out"""+outIndex+""")
          """
        }
      }
      queries += queriesPool



      var fullQuery = ""
      var cypherQueries = ListBuffer[CypherStatement]()
      for(pool <- queries){
        var query = String.format(pool.mkString("%n"))
        fullQuery += query
        cypherQueries += Cypher(query).on(params.toSeq:_*)
      }

      var results = ListBuffer[Boolean]()
      var temp:Boolean = false
      implicit val (wsclient, connection) = connect(ticker)
      breakable {
        for((cypherQuery, i) <- cypherQueries.zipWithIndex){
          temp = cypherQuery.execute()
          results += temp
          if(cypherQueries.size > 1){
            ApiLogs.debug("Query n°"+i+"/"+cypherQueries.size.toString)
          }          
          if(temp == false){
            break
          }
        }
      }
      wsclient.close()
      

      results.contains(false) match {
        case true => {
          ApiLogs.debug(fullQuery)
          ApiLogs.debug(params.toString)  
          Left(new Exception("Error : Neo4j.addTransaction("+ticker+","+tx+","+blockHash+", "+inputs+", "+outputs+") not inserted"))
        }
        case false => Right("Transaction added")//Right("Transaction added")//Left(new Exception("Transaction added"))
      }
    }
  }





  def getBeforeLastBlockHash(ticker:String):Future[Either[Exception,Option[String]]] = {
    Future { 
      val query = """
        MATCH (b:Block)
        RETURN b.hash
        ORDER BY b.height DESC
        SKIP 1
        LIMIT 1
      """

      val cypherQuery = Cypher(query)

      implicit val (wsclient, connection) = connect(ticker)
      try {
        val response = cypherQuery.apply()
        wsclient.close()
        if(response.size > 0){
          val result = response.head
          val blockHash = result[String]("b.hash")
          Right(Some(blockHash))
        }else{
          Right(None)
        }     
      }catch{
        case e:Exception => {
          wsclient.close()
          ApiLogs.debug(query)
          Left(new Exception("Error : Neo4j.getBeforeLastBlockHash("+ticker+") no result : "+e.getMessage))
        }
      }
    }
  }

  def getCurrentBlock(ticker:String, blockHash:String):Future[Either[Exception,Option[NeoBlock]]] = {
    Future { 
      val query = """
        MATCH (b:Block)
        RETURN b.hash, b.height, b.time, b.main_chain
        ORDER BY b.height DESC
        LIMIT 1
      """

      val cypherQuery = Cypher(query)
      implicit val (wsclient, connection) = connect(ticker)
      try {
        val response = cypherQuery.apply()
        wsclient.close()
        if(response.size > 0){
          val result = response.head
          val block = NeoBlock(result[String]("b.hash"), result[Long]("b.height"), result[Long]("b.time"), result[Boolean]("b.main_chain"))
          Right(Some(block))
        }else{
          Right(None)
        }     
      }catch{
        case e:Exception => {
          wsclient.close()
          ApiLogs.debug(query)
          Left(new Exception("Error : Neo4j.getBeforeLastBlockHash("+ticker+") no result"))
        }
      }
    }
  }


  def getBlock(ticker:String, blockHash:String) = {
    implicit val (wsclient, connection) = connect(ticker)


    val query = Cypher("""
      MATCH (b:Block { hash:{blockHash} })
      RETURN b.hash
    """).on("blockHash" -> blockHash)

    /*
    val block = query.apply().map(row => 
      row[String]("b.hash") -> row[Int]("b.height")
    ).toList*/
    val blockResult = query.apply().head
    val block = blockResult[String]("b.hash")

    println(block)

    wsclient.close()
	}

  

}