/*
package models

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Failure}
import scala.collection.mutable.{ListBuffer, Map}

import com.ning.http.client.Realm.AuthScheme


object BlockchainParser {

  val config = play.api.Play.configuration
  val elasticSearchUrl = config.getString("elasticsearch.url").get
  val elasticSearchMaxQueries = config.getInt("elasticsearch.maxqueries").get
  val RPCMaxQueries = config.getInt("rpc.maxqueries").get

  var maxqueries = RPCMaxQueries
  if(elasticSearchMaxQueries < RPCMaxQueries){
    maxqueries = elasticSearchMaxQueries
  }


  implicit val blockReads             = Json.reads[Block]
  implicit val blockWrites            = Json.writes[Block]
  implicit val scriptSigReads         = Json.reads[ScriptSig]
  implicit val scriptSigWrites        = Json.writes[ScriptSig]
  implicit val transactionVInReads    = Json.reads[TransactionVIn]
  implicit val transactionVInWrites   = Json.writes[TransactionVIn]
  implicit val scriptPubKeyReads      = Json.reads[ScriptPubKey]
  implicit val scriptPubKeyWrites     = Json.writes[ScriptPubKey]
  implicit val transactionVOutReads   = Json.reads[TransactionVOut]
  implicit val transactionVOutWrites  = Json.writes[TransactionVOut]
  implicit val transactionReads       = Json.reads[Transaction]
  implicit val transactionWrites      = Json.writes[Transaction]

  implicit val esTransactionBlockReads  = Json.reads[ESTransactionBlock ]
  implicit val esTransactionBlockWrites = Json.writes[ESTransactionBlock ]
  implicit val esTransactionVInReads    = Json.reads[ESTransactionVIn]
  implicit val esTransactionVInWrites   = Json.writes[ESTransactionVIn]
  //implicit val esTransactionVOutReads   = Json.reads[ESTransactionVOut]
  implicit val esTransactionVOutReads : Reads[ESTransactionVOut] = (
    (JsPath \ "value").read[Long] and
    (JsPath \ "output_index").read[Long] and
    (JsPath \ "script_hex").read[String] and
    (JsPath \ "addresses").readNullable[List[String]] and
    (JsPath \ "spent_by").readNullable[String]
  )(ESTransactionVOut.apply _)
  //implicit val esTransactionVOutWrites  = Json.writes[ESTransactionVOut]
  implicit val esTransactionVOutWrites : Writes[ESTransactionVOut] = (
    (JsPath \ "value").write[Long] and
    (JsPath \ "output_index").write[Long] and
    (JsPath \ "script_hex").write[String] and
    (JsPath \ "addresses").writeNullable[List[String]] and
    (JsPath \ "spent_by").writeNullable[String]
  )(unlift(ESTransactionVOut.unapply))
  implicit val esTransactionReads       = Json.reads[ESTransaction]
  implicit val esTransactionWrites      = Json.writes[ESTransaction]

  private def connectionParameters(ticker:String) = {
    val ipNode = config.getString("coins."+ticker+".ipNode")
    val rpcPort = config.getString("coins."+ticker+".rpcPort")
    val rpcUser = config.getString("coins."+ticker+".rpcUser")
    val rpcPass = config.getString("coins."+ticker+".rpcPass")
    (ipNode, rpcPort, rpcUser, rpcPass) match {
      case (Some(ip), Some(port), Some(user), Some(pass)) => ("http://"+ip+":"+port, user, pass)
      case _ => throw new Exception("'coins."+ticker+".ipNode', 'coins."+ticker+".rpcPort', 'coins."+ticker+".rpcUser' or 'coins."+ticker+".rpcPass' are missing in application.conf")
    }
  }

  private def satoshi(btc:BigDecimal) = {
    btc * 100000000
  }

  private def getBlock(ticker:String, blockHash:String):Future[Either[Exception,String]] = {

    /*
      TODO:
      vérifier qu'il n'y a pas de réorg pendant l'indexation
    */

    val rpcRequest = Json.obj("jsonrpc" -> "1.0",
                              "method" -> "getblock",
                              "params" -> Json.arr(blockHash))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).flatMap { response =>
      val rpcResult = Json.parse(response.body)
      (rpcResult \ "result") match {
        case JsNull => {
          Logger.error("Block '"+blockHash+"' not found")
          Future(Left(new Exception("Block '"+blockHash+"' not found")))
        }
        case result: JsObject => {
          result.validate[Block] match {
            case b: JsSuccess[Block] => {
              val block = b.get

              indexBlock(ticker, block).flatMap { response =>
                response match {
                  case Right(s) => {
                    Logger.debug(s)

                    exploreTransactionsAsync(ticker, block).flatMap { response =>
                      response match {
                        case Right(s) => {
                          block.nextblockhash match {
                            case Some(nextblockhash) => {
                              getBlock(ticker, nextblockhash)
                            }
                            case None => {
                              Logger.debug("Blocks synchronized !")
                              // Future(Right("Blocks synchronized !"))
                              finalizeSpent(ticker).map { response =>
                                response match {
                                  case Right(s) => {
                                    Right("Indexation finished !")
                                  }
                                  case Left(e) => Left(e)
                                }
                              }
                            }
                          }
                        }
                        case Left(e) => {
                          Future(Left(e))
                        }
                      }                       
                    }

                  }
                  case Left(e) => {
                    Future(Left(e))
                  }
                }
                
              }
            }
            case e: JsError => {
              Logger.error("Invalid block '"+blockHash+"' from RPC : "+response.body)
              Future(Left(new Exception("Invalid block '"+blockHash+"' from RPC : "+response.body)))
            }
          }
        }
        case _ => {
          Logger.error("Invalid block '"+blockHash+"' result from RPC : "+response.body)
          Future(Left(new Exception("Invalid block '"+blockHash+"' result from RPC : "+response.body)))
        }
      }
    }
  }

  private def indexBlock(ticker:String, block:Block):Future[Either[Exception,String]] = {
    ElasticSearch.set(ticker, "block", block.hash, Json.toJson(block)).map { response =>
      response match {
        case Right(s) => {
          Right("Block '"+block.hash+"' added !")
        }
        case Left(e) => Left(e)
      }
    } recover {
      case e:Exception => Left(e)
    }
  }

  private def exploreTransactionsSync(ticker:String, block:Block, currentTx: Int = 0):Future[Either[Exception,String]] = {
    getTransaction(ticker, block.tx(currentTx), Some(block)).flatMap { response =>
      response match {
        case Right(s) => {
          if(currentTx + 1 < block.tx.size){
            exploreTransactionsSync(ticker, block, currentTx + 1)
          }else{
            Future(Right("block '"+block.hash+"' transactions added"))
          }
        }
        case Left(e) => Future(Left(e))
      }
    }
  }

  private def exploreTransactionsAsync(ticker:String, block:Block, currentPool:Int = 0):Future[Either[Exception,String]] = {
    var resultsFuts: ListBuffer[Future[Either[Exception,String]]] = ListBuffer()

    val txNb = block.tx.length
    val poolsTxs = block.tx.grouped(maxqueries).toList

    for(tx <- poolsTxs(currentPool)){
      resultsFuts += getTransaction(ticker, tx, Some(block))
    }

    if(resultsFuts.size > 0) {
      val futuresResponses: Future[ListBuffer[Either[Exception,String]]] = Future.sequence(resultsFuts)
      futuresResponses.flatMap { responses =>
        var returnEither:Either[Exception,String] = Right("Block '"+block.hash+"' transactions added")
        for(response <- responses){
          response match {
            case Right(s) => 
            case Left(e) => {
              returnEither = Left(e)
            }
          }
        }

        returnEither match {
          case Right(s) => {
            if(currentPool + 1 < poolsTxs.size){
              exploreTransactionsAsync(ticker, block, currentPool + 1)
            }else{
              Future(Right("Block '"+block.hash+"' transactions added"))
            }
          }
          case Left(e) => Future(Left(e))
        }

      }
    }else{
      Future(Right("No block '"+block.hash+"' transactions to add"))
    }     
  }


  private def getTransaction(ticker:String, txHash:String, block:Option[Block] = None):Future[Either[Exception,String]] = {
    if(txHash == "97ddfbbae6be97fd6cdf3e7ca13232a3afff2353e29badfab7f73011edd4ced9"){
      /* Block genesis */
      Future(Right("genesis block"))
    }else{
      val rpcRequestRaw = Json.obj("jsonrpc" -> "1.0",
                              "method" -> "getrawtransaction",
                              "params" -> Json.arr(txHash))

      val (url, user, pass) = this.connectionParameters(ticker)

      WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequestRaw).flatMap { response =>
        val rpcResult = Json.parse(response.body)
        (rpcResult \ "result").validate[String] match {
          case raw: JsSuccess[String] => {
            val rpcRequestDecoded = Json.obj("jsonrpc" -> "1.0",
                                              "method" -> "decoderawtransaction",
                                              "params" -> Json.arr(raw.get))
            WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequestDecoded).flatMap { response =>
              //println(response.body)
              val rpcResult = Json.parse(response.body)
              (rpcResult \ "result") match {
                case JsNull => {
                  Logger.error("Transaction '"+txHash+"' not found")
                  Future(Left(new Exception("Transaction '"+txHash+"' not found")))
                }
                case result: JsObject => {
                  result.validate[Transaction] match {
                    case t: JsSuccess[Transaction] => {
                      val tx = t.get

                      /*
                        TODO:
                        vérifier que lorsqu'on n'est pas dans le cas d'une transaction coinbase, les données des inputs soient bien tous renseignés
                      */
                      this.indexTransaction(ticker, tx, block).map { response =>
                        response match {
                          case Right(s) => {
                            Logger.debug(s)
                            Right(s)
                          }
                          case Left(e) => Left(e)
                        }
                        
                      }
                    }
                    case e: JsError => {
                      Logger.error("Invalid transaction '"+txHash+"' from RPC : "+response.body)
                      Future(Left(new Exception("Invalid transaction '"+txHash+"' from RPC : "+response.body)))
                    } 
                  }
                }
                case _ => {
                  Logger.error("Invalid transaction '"+txHash+"' result from RPC : "+response.body)
                  Future(Left(new Exception("Invalid transaction '"+txHash+"' result from RPC : "+response.body)))
                }
              }
            }
          }
          case e: JsError => {
            Logger.error("Transaction '"+txHash+"' not found")
            Future(Left(new Exception("Transaction '"+txHash+"' not found")))
          }
        }
      }
    }
  }

  private def indexTransaction(ticker:String, tx:Transaction, block:Option[Block] = None):Future[Either[Exception,String]] = {

      //on récupère les informations qui nous manquent concernant la transaction
      var resultsFuts: ListBuffer[Future[Unit]] = ListBuffer()

      var esBlock: ESTransactionBlock = ESTransactionBlock("",0,0)
      var inputs: Map[Int, JsValue] = Map()
      var outputs: Map[Int, JsValue] = Map()
      block match {
        case Some(b) => {
          esBlock = ESTransactionBlock(b.hash, b.height, b.time)
        }
        case None => {
          resultsFuts += ElasticSearch.getBlockFromTxHash(ticker, tx.txid).map { result => 
            result match {
              case Right(s) => {
                s.validate[Block] match {
                  case b: JsSuccess[Block] => {
                    esBlock = ESTransactionBlock(b.get.hash, b.get.height, b.get.time)
                  }
                  case e: JsError => Logger.error("Invalid result from ElasticSearch.getBlockFromTxHash (txid : "+tx.txid+") "+e)
                }
              }
              case Left(e) => Left(e)
            }

          }
        }
      }

      var previousOuts = Map[String, List[(Int, Int)]]()
      for((vin, i) <- tx.vin.zipWithIndex){
        vin.coinbase match {
          case Some(c) => {
            //generation transaction
            inputs(i) = Json.obj(
              "coinbase" -> c,
              "input_index" -> i
            )
          }
          case None => {
            //standard transaction

            inputs(i) = Json.obj(
              "output_hash" -> vin.txid.get,
              "output_index" -> vin.vout.get.toInt,
              "input_index" -> i,
              "value" -> JsNull,
              "addresses" -> JsNull
            )
          }
        }
      }

      for(vout <- tx.vout){
        var output = Json.obj(
          "value" -> satoshi(vout.value),
          "output_index" -> vout.n,
          "script_hex" -> vout.scriptPubKey.hex,
          "addresses" -> vout.scriptPubKey.addresses,
          "spent_by" -> JsNull
        )
        outputs(vout.n.toInt) = output
      }      

      def finalizeTransaction:Future[Either[Exception,String]] = {
        var inValues: Long = 0
        var outValues: Long = 0

        var inputsJs:JsArray = new JsArray
        for((inIndex, input) <- inputs.toSeq.sortBy(_._1)){
          inputsJs = inputsJs ++ Json.arr(Json.toJson(input))
          (input \ "value").asOpt[Long] match {
            case Some(v) => inValues += v 
            case None => //coinbase
          }
        }
        var outputsJs:JsArray = new JsArray
        for((outIndex, output) <- outputs.toSeq.sortBy(_._1)){
          outputsJs = outputsJs ++ Json.arr(Json.toJson(output))
          (output \ "value").asOpt[Long] match {
            case Some(v) => outValues += v 
            case None => //coinbase
          }
        }

        var fees:Long = 0
        if(inValues > 0){
          fees = inValues - outValues
        }
        var amount = outValues

        var esTx = Json.obj(
          "hash" -> tx.txid,
          "received_at" -> esBlock.time,
          "lock_time" -> tx.locktime,
          "block" -> esBlock,
          "inputs" -> inputsJs,
          "outputs" -> outputsJs,
          "fees" -> fees,
          "amount" -> amount)
  

        ElasticSearch.set(ticker, "transaction", tx.txid, Json.toJson(esTx)).map { response =>
          response match {
            case Right(s) => {
              Right("Transaction '"+tx.txid+"' added !")
            }
            case Left(e) => Left(e)
          }
        }
      }

      if(resultsFuts.size > 0) {
        val futuresResponses: Future[ListBuffer[Unit]] = Future.sequence(resultsFuts)
        futuresResponses.flatMap { responses =>
          finalizeTransaction
        }
      }else{
        finalizeTransaction
      }
  }

  def finalizeSpent(ticker:String):Future[Either[Exception,String]] = {
    finalizePageSpent(ticker).flatMap { response =>
      response match {
        case Right(next) => {
          next match {
            case true => {
              finalizeSpent(ticker)
            }
            case false => Future(Right("finalizeSpent finished"))
          }
        }
        case Left(e) => Future(Left(e))
      }
    }
  }

  private def finalizePageSpent(ticker:String):Future[Either[Exception,Boolean]] = {
    var resultsFuts: ListBuffer[Future[Either[Exception,String]]] = ListBuffer()

    class BlockchainParserInput(var tx_hash:String, var input_index:Long, var output_index:Long, var value:Option[Long], var addresses:Option[List[String]])

    var inputsList = List[BlockchainParserInput]()
    var outputsHashes:Map[String, List[Int]] = Map()
    var outputsHashesJson:Map[String, JsValue] = Map()
    var txHashes:Map[String, List[Int]] = Map()
    var txHashesJson:Map[String, ESTransaction] = Map()


    def explorePoolOutputs(poolsOutputsHashes: List[Map[String, List[Int]]], currentPool: Int = 0):Future[Either[Exception,String]] = {
      var resultsFuts: ListBuffer[Future[Either[Exception,String]]] = ListBuffer()

        
        for((outputHash, inputsListIndexes) <- poolsOutputsHashes(currentPool)){
          resultsFuts += ElasticSearch.getTransaction(ticker, outputHash).map { result =>
            result match {
              case Right(s) => {
                s.validate[ESTransaction] match {
                  case t: JsSuccess[ESTransaction] => {
                    var inputTx = t.get

                    outputsHashesJson(outputHash) = Json.toJson(inputTx)

                    for(inputsListIndex <- inputsListIndexes){
                      var inputIndex = inputsList(inputsListIndex).input_index
                      var outputIndex = inputsList(inputsListIndex).output_index
                      val output = inputTx.outputs(outputIndex.toInt)
                      inputsList(inputsListIndex).value = Some(output.value)
                      inputsList(inputsListIndex).addresses = output.addresses
                    }

                    Right("")             
                  }
                  case e: JsError => {
                    Logger.error("Invalid result from ElasticSearch.getTransaction("+ticker+", "+outputHash+") : "+e)
                    Left(new Exception("Invalid result from ElasticSearch.getTransaction("+ticker+", "+outputHash+") : "+e))
                  }
                }
              }
              case Left(e) => Left(e)
            }

          }
        }
      

      if(resultsFuts.size > 0) {
        val futuresResponses: Future[ListBuffer[Either[Exception,String]]] = Future.sequence(resultsFuts)
        futuresResponses.flatMap { responses =>
          var returnEither:Either[Exception,String] = Right("")
          for(response <- responses){
            response match {
              case Right(s) => 
              case Left(e) => {
                returnEither = Left(e)
              }
            }
          }

          returnEither match {
            case Right(s) => {
              if(currentPool + 1 < poolsOutputsHashes.size){
                explorePoolOutputs(poolsOutputsHashes, currentPool+1)
              }else{
                Future(Right(""))
              }
            }
            case Left(e) => Future(Left(e))
          }
        }
      }else{
        Future(Right(""))
      }
    }

    def setPoolSpentBy(poolsSpentBy:List[Map[String, JsValue]], currentPool:Int=0):Future[Either[Exception,String]] = {
      var resultsFuts: ListBuffer[Future[Either[Exception,String]]] = ListBuffer()
      for((hash, json) <- poolsSpentBy(currentPool)){
        var updatedTx = json

        for(inputsListIndex <- outputsHashes(hash)){
          var outputIndex = inputsList(inputsListIndex).output_index
          var tx_hash = inputsList(inputsListIndex).tx_hash
          val jsonTransformer = (__ \ 'outputs).json.update( 
            __.read[JsArray].map { a => 
              JsArray(a.value.updated(outputIndex.toInt, a(outputIndex.toInt).as[JsObject] ++ Json.obj("spent_by" -> tx_hash)))
            }
          )
          updatedTx = updatedTx.transform(jsonTransformer).get
        }

        resultsFuts += ElasticSearch.set(ticker, "transaction", hash, Json.toJson(updatedTx)).map { response =>
          Right("")
        }
      }


      if(resultsFuts.size > 0) {
        val futuresResponses: Future[ListBuffer[Either[Exception,String]]] = Future.sequence(resultsFuts)
        futuresResponses.flatMap { responses =>
          var returnEither:Either[Exception,String] = Right("")
          for(response <- responses){
            response match {
              case Right(s) => 
              case Left(e) => {
                returnEither = Left(e)
              }
            }
          }

          returnEither match {
            case Right(s) => {
              if(currentPool + 1 < poolsSpentBy.size){
                setPoolSpentBy(poolsSpentBy, currentPool+1)
              }else{
                Future(Right(""))
              }
            }
            case Left(e) => Future(Left(e))
          }
        }
      }else{
        Future(Right(""))
      }
    }

    //Récupère toute les txs par date
    var esSize = 150
    ElasticSearch.getNotFinalizedTransactions(ticker, esSize).flatMap { result =>
      result match {
        case Right(s) => {
          var (truncated, jsResult) = s
          jsResult.validate[List[ESTransaction]] match {
            case r: JsSuccess[List[ESTransaction]] => {
              var txs = r.get          
              for(tx <- txs){
                Logger.debug("TX: "+tx.hash+"...")
                txHashesJson(tx.hash) = tx
                for(in <- tx.inputs){
                  inputsList = inputsList :+ new BlockchainParserInput(tx.hash, in.input_index.get, in.output_index.get, None, None)
                  outputsHashes.contains(in.output_hash.get) match {
                    case true => {
                      var listPrev:List[Int] = outputsHashes(in.output_hash.get)
                      outputsHashes(in.output_hash.get) = listPrev :+ (inputsList.size - 1)
                    }
                    case false => {
                      outputsHashes(in.output_hash.get) = List(inputsList.size - 1)
                    }
                  }
                  txHashes.contains(tx.hash) match {
                    case true => {
                      var listPrev:List[Int] = txHashes(tx.hash)
                      txHashes(tx.hash) = listPrev :+ (inputsList.size - 1)
                    }
                    case false => {
                      txHashes(tx.hash) = List(inputsList.size - 1)
                    }
                  }
                }
              }

              if(outputsHashes.size > 0){
                val poolsOutputsHashes:List[Map[String, List[Int]]] = outputsHashes.grouped(elasticSearchMaxQueries).toList
                explorePoolOutputs(poolsOutputsHashes, 0).flatMap { response =>
                  //Pour chaque output, on set le spent_by:
                  val poolsSpentBy = outputsHashesJson.grouped(elasticSearchMaxQueries).toList
                  setPoolSpentBy(poolsSpentBy, 0).flatMap { response =>

                    var resultsFuts: ListBuffer[Future[Either[Exception,String]]] = ListBuffer()

                    //Pour chaque input, on set la value & addresses:
                    for((txHash, listI) <- txHashes){
                      
                      var updatedTx = Json.toJson(txHashesJson(txHash))
                      for(i <- listI){
                        var input =  inputsList(i)
                        val jsonTransformer = (__ \ 'inputs).json.update( 
                          __.read[JsArray].map { a => 
                            JsArray(a.value.updated(input.input_index.toInt, a(input.input_index.toInt).as[JsObject] ++ Json.obj("value" -> input.value) 
                                                                                                                      ++ Json.obj("addresses" -> input.addresses)))
                          }
                        )
                        updatedTx = updatedTx.transform(jsonTransformer).get
                        
                      }
                      resultsFuts += ElasticSearch.set(ticker, "transaction", txHash, updatedTx)
                    }

                    if(resultsFuts.size > 0) {
                      val futuresResponses: Future[ListBuffer[Either[Exception,String]]] = Future.sequence(resultsFuts)
                      futuresResponses.map { responses =>
                        var returnEither:Either[Exception,Boolean] = Right(truncated)
                        for(response <- responses){
                          response match {
                            case Right(s) => 
                            case Left(e) => {
                              returnEither = Left(e)
                            }
                          }
                        }

                        returnEither
                      }
                    }else{
                      Future(Right(truncated))
                    }

                  }  
        
                }
              }else{
                Future(Right(false))
              }

            }
            case e: JsError => {
              Logger.error("Invalid result from ElasticSearch.getNotFinalizedTransactions("+ticker+", "+esSize+") : "+e)
              Future(Left(new Exception("Invalid result from ElasticSearch.getNotFinalizedTransactions("+ticker+", "+esSize+") : "+e)))
            }
          }
        }
        case Left(e) => Future(Left(e))
      }
    }
  }

  def startAt(ticker:String, fromBlockHash:String):Future[Either[Exception,String]] = {
    getBlock(ticker, fromBlockHash)
  }

  def resume(ticker:String, force:Boolean = false):Future[Either[Exception,String]] = {
    //on reprend la suite de l'indexation à partir de l'avant dernier block stocké (si le dernier n'a pas été ajouté correctement) dans notre bdd
    ElasticSearch.getBeforeLastBlockHash(ticker).flatMap { response =>
      response match {
        case Right(beforeLastBlockHash) => {
          beforeLastBlockHash match {
            case Some(b) => {
              startAt(ticker, b)
            }
            case None => {
              force match {
                case true => restart(ticker)
                case false => {
                  Logger.error("No blocks found, can't resume")
                  Future(Left(new Exception("No blocks found, can't resume")))
                }
              }
            }
          }
        }
        case Left(e) => Future(Left(e))
      }
    }

    /*
      TODO:
      vérifier qu'il n'y a pas eu de réorg avant de reprendre l'indexation
    */
  }

  def restart(ticker:String):Future[Either[Exception,String]] = {
    //on recommence l'indexation à partir du block genesis
    val genesisBlock = config.getString("coins."+ticker+".genesisBlock").get
    startAt(ticker, genesisBlock)
  }




}
*/