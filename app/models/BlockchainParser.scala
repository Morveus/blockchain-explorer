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
import scala.collection.mutable.{ListBuffer, Map}

import com.ning.http.client.Realm.AuthScheme


object BlockchainParser {

  val config = play.api.Play.configuration
  val elasticSearchUrl = config.getString("elasticsearch.url").get

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
    (JsPath \ "addresses").read[List[String]] and
    (JsPath \ "spent_by").readNullable[String]
  )(ESTransactionVOut.apply _)
  //implicit val esTransactionVOutWrites  = Json.writes[ESTransactionVOut]
  implicit val esTransactionVOutWrites : Writes[ESTransactionVOut] = (
    (JsPath \ "value").write[Long] and
    (JsPath \ "output_index").write[Long] and
    (JsPath \ "script_hex").write[String] and
    (JsPath \ "addresses").write[List[String]] and
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

  private def getBlock(ticker:String, blockHash:String):Unit = {

    /*
      TODO:
      vérifier qu'il n'y a pas de réorg pendant l'indexation
    */

    val rpcRequest = Json.obj("jsonrpc" -> "1.0",
                              "method" -> "getblock",
                              "params" -> Json.arr(blockHash))

    val (url, user, pass) = this.connectionParameters(ticker)

    WS.url(url).withAuth(user, pass, WSAuthScheme.BASIC).post(rpcRequest).map { response =>
      val rpcResult = Json.parse(response.body)
      (rpcResult \ "result") match {
        case JsNull => Logger.warn("Block '"+blockHash+"' not found")
        case result: JsObject => {
          result.validate[Block] match {
            case b: JsSuccess[Block] => {
              val block = b.get

              this.indexBlock(ticker, block).map{ response =>
                //Logger.debug("Block '"+block.hash+"' added !")

                /* 
                  NOTE: 
                  on pourrait ne pas attendre le retour d'ES pour passer aux transactions/block suivant, 
                  mais actuellement ES ne suit pas : queue de 200 explosée, 
                  à voir aussi pour modifier les pools, threads pour augmenter la vitesse d'indexation d'ES 
                */

                /*
                  TODO:
                  gérer les exceptions et stopper l'indexation si une erreur s'est produite
                */

                this.exploreTransactions(ticker, block).map { response =>
                  block.nextblockhash match {
                    case Some(nextblockhash) => {
                      //if(nextblockhash != "3ec54cd17449d3f52fb0c3031abc06aa56736427edf117e7513ba6a4355d0b50"){  /* TODO : à supprimer après les test */
                        getBlock(ticker, nextblockhash)
                      //} 
                    }
                    case None => Logger.debug("Blocks synchronized !")
                  }
                }
              }
            }
            case e: JsError => Logger.error("Invalid block from RPC")
          }
        }
        case _ => Logger.error("Invalid result from RPC")
      }
    }
  }

  private def indexBlock(ticker:String, block:Block) = {
    ElasticSearch.set(ticker, "block", block.hash, Json.toJson(block)).map { response =>
      //println(response)
    }
  }

  private def exploreTransactions(ticker:String, block:Block, currentTx: Int = 0):Future[Unit] = {
    this.getTransaction(ticker, block.tx(currentTx), Some(block)).map { response =>
      if(currentTx + 1 < block.tx.size){
        exploreTransactions(ticker, block, currentTx + 1)
      }
    }
  }

  private def getTransaction(ticker:String, txHash:String, block:Option[Block] = None):Future[Unit] = {
    if(txHash == "97ddfbbae6be97fd6cdf3e7ca13232a3afff2353e29badfab7f73011edd4ced9"){
      /* Block genesis */
      Future.successful(())
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
                case JsNull => Future{Logger.warn("Transaction '"+txHash+"' not found")}
                case result: JsObject => {
                  result.validate[Transaction] match {
                    case t: JsSuccess[Transaction] => {
                      val tx = t.get

                      /*
                        TODO:
                        vérifier que lorsqu'on n'est pas dans le cas d'une transaction coinbase, les données des inputs soient bien tous renseignés
                      */
                      this.indexTransaction(ticker, tx, block).map{ response =>
                        Logger.debug("Transaction '"+tx.txid+"' added !")
                      }
                    }
                    case e: JsError => {
                      Logger.error("Invalid transaction from RPC "+ e)
                      throw new Exception("Invalid transaction from RPC")
                    } 
                  }
                }
                case _ => Future{Logger.error("Invalid result from RPC")}
              }
            }
          }
          case e: JsError => Future{Logger.warn("Transaction '"+txHash+"' not found")}
        }
      }
    }
  }

  private def indexTransaction(ticker:String, tx:Transaction, block:Option[Block] = None):Future[Unit] = {

      //on récupère les informations qui nous manquent concernant la transaction
      var resultsFuts: ListBuffer[Future[Unit]] = ListBuffer()

      var jsBlock: JsValue = Json.obj()
      var inputs: Map[Int, JsValue] = Map()
      var outputs: Map[Int, JsValue] = Map()
      block match {
        case Some(b) => {
          jsBlock = Json.obj(
            "hash" -> b.hash,
            "height" -> b.height,
            "time" -> b.time
          )
        }
        case None => {
          resultsFuts += ElasticSearch.getBlockFromTxHash(ticker, tx.txid).map { result => 
            println(result)
            result.validate[Block] match {
              case b: JsSuccess[Block] => {
                jsBlock = Json.obj(
                  "hash" -> b.get.hash,
                  "height" -> b.get.height,
                  "time" -> b.get.time
                )
              }
              case e: JsError => Logger.error("Invalid result from ElasticSearch.getBlockFromTxHash (txid : "+tx.txid+") "+e)
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
            previousOuts.contains(vin.txid.get) match {
              case true => {
                var listPrev:List[(Int, Int)] = previousOuts(vin.txid.get)
                previousOuts(vin.txid.get) = listPrev :+ (i, vin.vout.get.toInt)
              }
              case false => {
                previousOuts(vin.txid.get) = List((i, vin.vout.get.toInt))
              }
            }
          }
        }
      }

      for((inTxHash, data) <- previousOuts){
        resultsFuts += ElasticSearch.getTransaction(ticker, inTxHash).map { result => 
          result.validate[ESTransaction] match {
            case t: JsSuccess[ESTransaction] => {
              var inputTx = t.get

              var updatedInputTx = Json.toJson(inputTx)
              for((inputIndex, outputIndex) <- data){
                //On passe l'output en spent
                val jsonTransformer = (__ \ 'outputs).json.update( 
                    __.read[JsArray].map { a => 
                      JsArray(a.value.updated(outputIndex, a(outputIndex).as[JsObject] ++ Json.obj("spent_by" -> tx.txid)))
                    }
                  )
                updatedInputTx = updatedInputTx.transform(jsonTransformer).get

                Logger.debug("transaction output '"+inTxHash+"'("+outputIndex+") spent by "+tx.txid)

                val output = inputTx.outputs(outputIndex)

                inputs(inputIndex) = Json.obj(
                  "output_hash" -> inTxHash,
                  "output_index" -> outputIndex,
                  "input_index" -> inputIndex,
                  "value" -> output.value,
                  "addresses" -> output.addresses
                )
              }

              ElasticSearch.set(ticker, "transaction", inTxHash, Json.toJson(updatedInputTx)).map { response =>
                
                //Logger.warn(Json.toJson(inputTx).toString)
                //Logger.warn(Json.toJson(updatedInputTx).toString)
              }

              
            }
            case e: JsError => {
              Logger.error("Invalid result from ElasticSearch.getTransaction (txid: "+tx.txid+", inputtxid: "+inTxHash+") "+e)
              /* TODO : Exception */
            }
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

      def finalizeTransaction = {
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
          "lock_time" -> tx.locktime,
          "block" -> jsBlock,
          "inputs" -> inputsJs,
          "outputs" -> outputsJs,
          "fees" -> fees,
          "amount" -> amount)
  

        ElasticSearch.set(ticker, "transaction", tx.txid, Json.toJson(esTx)).map { response =>
          //println(response)
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

  def startAt(ticker:String, fromBlockHash:String) = {
    this.getBlock(ticker, fromBlockHash)
  }

  def resume(ticker:String, force:Boolean = false) = {
    //on reprend la suite de l'indexation à partir de l'avant dernier block stocké (si le dernier n'a pas été ajouté correctement) dans notre bdd
    ElasticSearch.getBeforeLastBlockHash(ticker).map { beforeLastBlockHash =>
      beforeLastBlockHash match {
        case Some(b) => this.startAt(ticker, b)
        case None => {
          force match {
            case true => restart(ticker)
            case false => Logger.error("No blocks found, can't resume")
          }
        }
      }
    }

    /*
      TODO:
      vérifier qu'il n'y a pas eu de réorg avant de reprendre l'indexation
    */
  }

  def restart(ticker:String) = {
    //on recommence l'indexation à partir du block genesis
    val genesisBlock = config.getString("coins."+ticker+".genesisBlock").get
    this.startAt(ticker, genesisBlock)
  }




}