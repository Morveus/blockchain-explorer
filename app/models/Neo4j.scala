// package models

// import play.api._
// import play.api.Play.current
// import play.api.mvc._
// import play.api.libs.json._
// import scala.collection.mutable.{ListBuffer, Map}
// import scala.util.control.Breaks._

// import scala.concurrent.ExecutionContext.Implicits.global

// import com.ning.http.client.AsyncHttpClientConfig
// import com.ning.http.client.extra.ThrottleRequestFilter

// import org.anormcypher._
// import play.api.libs.ws._, ning._
// import scala.concurrent._

// object Neo4j {

//   val config = play.api.Play.configuration
 

//   private def connect(ticker:String, requestTimeOut:Option[Int] = Some(120000)) = {
//     val neo4jUrl = config.getString("coins."+ticker+".neo4j.url").get
//     val neo4jPort = config.getInt("coins."+ticker+".neo4j.port").get
//     val neo4jUser = config.getString("coins."+ticker+".neo4j.user").get
//     val neo4jPass = config.getString("coins."+ticker+".neo4j.pass").get

//     val clientConfig = new DefaultWSClientConfig()
//     val secureDefaults: AsyncHttpClientConfig = new NingAsyncHttpClientConfigBuilder(clientConfig).build()

//     val builder = new AsyncHttpClientConfig.Builder(secureDefaults)
//     requestTimeOut match {
//       case Some(time) => builder.setRequestTimeoutInMs(time)
//       case None => /* Default */
//     }
    

//     val secureDefaultsWithSpecificOptions: AsyncHttpClientConfig = builder.build()
//     val wsclient = new NingWSClient(secureDefaultsWithSpecificOptions)
//     val connection = Neo4jREST(neo4jUrl, neo4jPort, "/db/data/", neo4jUser, neo4jPass)(wsclient)

//     (wsclient, connection)
//   }

//   def setConstraints(ticker: String):Future[Either[Exception,String]] = {
//     Future {
//       var query1 = """
//         CREATE CONSTRAINT ON (b:Block) ASSERT b.hash IS UNIQUE
//       """
//       var query2 = """
//         CREATE CONSTRAINT ON (tx:Transaction) ASSERT tx.hash IS UNIQUE
//       """
//       var query3 = """
//         CREATE CONSTRAINT ON (addr:Adresse) ASSERT addr.address IS UNIQUE
//       """

//       val cypherQuery1 = Cypher(query1)
//       val cypherQuery2 = Cypher(query2)
//       val cypherQuery3 = Cypher(query3)



//       implicit val (wsclient, connection) = connect(ticker)
//       val success1 = cypherQuery1.execute()
//       val success2 = cypherQuery2.execute()
//       val success3 = cypherQuery3.execute()
//       wsclient.close()

//       (success1, success2, success3) match {
//         case (true, true, true) => Right("Constraints added")
//         case _ => {
//           ApiLogs.debug(query1)
//           ApiLogs.debug(query2)
//           ApiLogs.debug(query3)
//           Left(new Exception("Error : Neo4j.setConstraints("+ticker+") not inserted"))
//         }
//       }
//     }
//   }

//   def addBlock(ticker:String, block:NeoBlock, previousBlockHash:Option[String]):Future[Either[Exception,String]] = {
//     Future {
      
//       var query = """
//         MERGE (b:Block { hash: '"""+block.hash+"""' })
//         ON CREATE SET 
//           b.height = """+block.height+""",
//           b.time = """+block.time+""",
//           b.main_chain = """+block.main_chain+"""
//       """
     

//       previousBlockHash match {
//         case Some(prev) => {
//           query = """
//             MATCH (prevBlock:Block { hash: '"""+prev+"""' })
//           """+query+"""
//             MERGE (b)-[:FOLLOWS]->(prevBlock)
//           """
//         }
//         case None => /*Nothing*/
//       }

//       query += """;"""

//       Right(query)
//     }
//   }

//   def addTransaction(ticker:String, tx:NeoTransaction, blockHash:String, inputs:Map[Int, NeoInput], outputs:Map[Int, NeoOutput], attempt:Int = 1):Future[Either[Exception,String]] = {
//     addTransactionAttempt(ticker, tx, blockHash, inputs, outputs).flatMap { result =>
//       result match {
//         case Left(e) => {
//           if(attempt < 3){
//             Thread.sleep(2000)
//             addTransaction(ticker, tx, blockHash, inputs, outputs, attempt + 1)
//           }else{
//             Future(Left(e))
//           }
//         }
//         case Right(s) => Future(Right(s))
//       }
//     }
//   }

//   def tempAddTxFile(ticker:String, tx:NeoTransaction, blockHash:String, inputs:Map[Int, NeoInput], outputs:Map[Int, NeoOutput]):Future[Either[Exception,TxBatch]] = {
//     Future { 

//       var query = ""
//       var inputsTxs = ListBuffer[String]()

//       query += """
//         MATCH (b:Block { hash: '"""+blockHash+"""' })
//         MERGE (tx:Transaction { hash: '"""+tx.hash+"""' })
//         ON CREATE SET
//           tx.received_at = """+tx.received_at+""",
//           tx.lock_time = """+tx.lock_time+"""
//         MERGE (tx)<-[:CONTAINS]-(b)
//       """

//       for((inIndex, input) <- inputs.toSeq.sortBy(_._1)){
//         input.coinbase match {
//           case Some(c) => {
//             query += """
//               MERGE (in"""+inIndex+""":Input { input_index: """+inIndex+""" })-[:SUPPLIES]->(tx)
//               ON CREATE SET
//                 in"""+inIndex+""".coinbase= '"""+c+"""'
//             """
//           }
//           case None => {
//             query += """
//               MERGE (in"""+inIndex+""":Input { input_index: """+inIndex+""" })-[:SUPPLIES]->(tx)
//               MERGE (inOut"""+inIndex+"""Tx:Transaction { hash: '"""+input.output_tx_hash.get+"""' })
//               MERGE (inOut"""+inIndex+""":Output { output_index: """+input.output_index.get+"""})<-[:EMITS]-(inOut"""+inIndex+"""Tx)
//               MERGE (in"""+inIndex+""":Input)<-[:IS_SPENT_BY]-(inOut"""+inIndex+""")
//             """
//             inputsTxs += input.output_tx_hash.get
//           }
//         }
//       } 


//       for((outIndex, output) <- outputs.toSeq.sortBy(_._1)){
//         query += """
//           MERGE (out"""+outIndex+""":Output { output_index: """+output.output_index+"""})<-[:EMITS]-(tx)
//           ON CREATE SET
//             out"""+outIndex+""".value= """+output.value+""",
//             out"""+outIndex+""".script_hex= '"""+output.script_hex+"""'
//         """

//         for(address <- output.addresses){
//           query += """
//             MERGE (out"""+outIndex+"""Addr:Address { address: '"""+address+"""' })
//             MERGE (out"""+outIndex+"""Addr)<-[:IS_SENT_TO]-(out"""+outIndex+""")
//           """
//         }
//       }
//       query += """;"""

//       Right(new TxBatch(tx.hash, inputsTxs, query))
//     } 
//   }

//   def addTransactionAttempt(ticker:String, tx:NeoTransaction, blockHash:String, inputs:Map[Int, NeoInput], outputs:Map[Int, NeoOutput]):Future[Either[Exception,String]] = {
//     val neo4jMaxQueries = config.getInt("coins."+ticker+".neo4j.maxQueries").get
//     Future { 

//       var queries = ListBuffer[ListBuffer[String]]()
//       var queriesPool = ListBuffer[String]()

//       //        MATCH (b:Block { hash: {blockHash} })
//       queriesPool += """
//         MERGE (tx:Transaction { hash:{txHash} })
//         ON CREATE SET
//           tx.received_at = {txReceivedAt},
//           tx.lock_time = {txLockTime}
//         MERGE (tx)<-[:CONTAINS]-(b)
//       """
//       var params:Map[String, Any] = Map("blockHash" -> blockHash,
//                                         "txHash" -> tx.hash,
//                                         "txReceivedAt" -> tx.received_at,
//                                         "txLockTime" -> tx.lock_time
//                                       )


//       for((inIndex, input) <- inputs.toSeq.sortBy(_._1)){
//         if(queriesPool.size == neo4jMaxQueries){
//           queries += queriesPool
//           queriesPool = ListBuffer[String]()
//           queriesPool += """
//             MERGE (tx:Transaction { hash:{txHash} })
//           """
//         }
//         input.coinbase match {
//           case Some(c) => {
//             queriesPool += """
//               MERGE (in"""+inIndex+""":Input { input_index: """+inIndex+""" })-[:SUPPLIES]->(tx)
//               ON CREATE SET
//                 in"""+inIndex+""".coinbase= '"""+c+"""'
//             """
//           }
//           case None => {
//             queriesPool += """
//               MERGE (in"""+inIndex+""":Input { input_index: """+inIndex+""" })-[:SUPPLIES]->(tx)
//               MERGE (inOut"""+inIndex+"""Tx:Transaction { hash: '"""+input.output_tx_hash.get+"""' })
//               MERGE (inOut"""+inIndex+""":Output { output_index: """+input.output_index.get+"""})<-[:EMITS]-(inOut"""+inIndex+"""Tx)
//               MERGE (in"""+inIndex+""":Input)<-[:IS_SPENT_BY]-(inOut"""+inIndex+""")
//             """
//           }
//         }
//       } 


//       for((outIndex, output) <- outputs.toSeq.sortBy(_._1)){
//         if(queriesPool.size == neo4jMaxQueries){
//           queries += queriesPool
//           queriesPool = ListBuffer[String]()
//           queriesPool += """
//             MERGE (tx:Transaction { hash:{txHash} })
//           """
//         }
//         queriesPool += """
//           MERGE (out"""+outIndex+""":Output { output_index: """+output.output_index+"""})<-[:EMITS]-(tx)
//           ON CREATE SET
//             out"""+outIndex+""".value= """+output.value+""",
//             out"""+outIndex+""".script_hex= '"""+output.script_hex+"""'
//         """

//         for(address <- output.addresses){
//           if(queriesPool.size == neo4jMaxQueries){
//             queries += queriesPool
//             queriesPool = ListBuffer[String]()
//             queriesPool += """
//               MERGE (tx:Transaction { hash:{txHash} })
//             """
//           }
//           queriesPool += """
//             MERGE (out"""+outIndex+"""Addr:Address { address: '"""+address+"""' })
//             MERGE (out"""+outIndex+"""Addr)<-[:IS_SENT_TO]-(out"""+outIndex+""")
//           """
//         }
//       }
//       queries += queriesPool



//       var fullQuery = ""
//       var cypherQueries = ListBuffer[CypherStatement]()
//       for(pool <- queries){
//         var query = String.format(pool.mkString("%n"))
//         fullQuery += query
//         cypherQueries += Cypher(query).on(params.toSeq:_*)
//       }

//       var results = ListBuffer[Boolean]()
//       var temp:Boolean = false
//       var crashedQuery:Option[Int] = None
//       implicit val (wsclient, connection) = connect(ticker)
//       breakable {
//         for((cypherQuery, i) <- cypherQueries.zipWithIndex){
//           if(cypherQueries.size > 1){
//             ApiLogs.debug("Query n°"+i+"/"+cypherQueries.size.toString+"...")
//           }   
//           temp = cypherQuery.execute()
//           results += temp
                 
//           if(temp == false){
//             crashedQuery = Some(i)
//             break
//           }
//         }
//       }
//       wsclient.close()
      

//       results.contains(false) match {
//         case true => {

//           ApiLogs.debug(fullQuery)
//           ApiLogs.debug(params.toString)
//           ApiLogs.debug("============")
//           ApiLogs.debug(cypherQueries(crashedQuery.get).query)

//           Left(new Exception("Error : Neo4j.addTransaction("+ticker+","+tx+","+blockHash+", "+inputs+", "+outputs+") not inserted"))    
//         }
//         case false => Right("Transaction added")//Right("Transaction added")//Left(new Exception("Transaction added"))
//       }
//     }
//   }





//   def getBeforeLastBlockHash(ticker:String):Future[Either[Exception,Option[String]]] = {
//     Future { 
//       val query = """
//         MATCH (b:Block)
//         RETURN b.hash
//         ORDER BY b.height DESC
//         SKIP 1
//         LIMIT 1
//       """

//       val cypherQuery = Cypher(query)

//       implicit val (wsclient, connection) = connect(ticker)
//       try {
//         val response = cypherQuery.apply()
//         wsclient.close()
//         if(response.size > 0){
//           val result = response.head
//           val blockHash = result[String]("b.hash")
//           Right(Some(blockHash))
//         }else{
//           Right(None)
//         }     
//       }catch{
//         case e:Exception => {
//           wsclient.close()
//           ApiLogs.debug(query)
//           Left(new Exception("Error : Neo4j.getBeforeLastBlockHash("+ticker+") no result : "+e.getMessage))
//         }
//       }
//     }
//   }

//   def getCurrentBlock(ticker:String, blockHash:String):Future[Either[Exception,Option[NeoBlock]]] = {
//     Future { 
//       val query = """
//         MATCH (b:Block)
//         RETURN b.hash, b.height, b.time, b.main_chain
//         ORDER BY b.height DESC
//         LIMIT 1
//       """

//       val cypherQuery = Cypher(query)
//       implicit val (wsclient, connection) = connect(ticker)
//       try {
//         val response = cypherQuery.apply()
//         wsclient.close()
//         if(response.size > 0){
//           val result = response.head
//           val block = NeoBlock(result[String]("b.hash"), result[Long]("b.height"), result[Long]("b.time"), result[Boolean]("b.main_chain"))
//           Right(Some(block))
//         }else{
//           Right(None)
//         }     
//       }catch{
//         case e:Exception => {
//           wsclient.close()
//           ApiLogs.debug(query)
//           Left(new Exception("Error : Neo4j.getBeforeLastBlockHash("+ticker+") no result"))
//         }
//       }
//     }
//   }


//   def getBlock(ticker:String, blockHash:String) = {

//     val query = """
//       MATCH (b:Block { hash:{blockHash} })
//       RETURN b.hash
//     """

//     val cypherQuery = Cypher(query).on("blockHash" -> blockHash)
//     implicit val (wsclient, connection) = connect(ticker)
//     val blockResult = cypherQuery.apply().head
//     wsclient.close()
//     val block = blockResult[String]("b.hash")

//     /*
//     val block = query.apply().map(row => 
//       row[String]("b.hash") -> row[Int]("b.height")
//     ).toList
//     */

//     println(block)

    
// 	}

  

// }