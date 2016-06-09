package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current
import play.api.libs.json._
import play.api.libs.concurrent._
import scala.concurrent.Future
import scala.util.{Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global

import models._
import blockchains._

import com.typesafe.config._
import java.io._

import actors._
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import play.api.libs.iteratee._



object RESTApi extends Controller {

  var blockchainsList: Map[String, BlockchainAPI] = Map()
      blockchainsList += ("eth" -> blockchains.EthereumBlockchainAPI)

  var confIndexer: Config = ConfigFactory.parseFile(new File("indexer.conf"))
  var ticker:String = confIndexer.getString("ticker")

  val webSocketActor = Akka.system.actorSelection("user/blockchain-explorer")
  def ws = WebSocket.tryAccept[JsValue] {
    request =>
    implicit val timeout = Timeout(3 seconds)

    val userId = java.util.UUID.randomUUID.toString

    // using the ask pattern of akka,
    // get the enumerator for that user
    (webSocketActor ? WebSocketActor.StartSocket(userId)) map {
      enumerator =>

      // create a Iteratee which process the input and
      // and send a SocketClosed message to the actor when
      // connection is closed from the client
      Right(Iteratee.foreach[JsValue](msg => {
        webSocketActor ! WebSocketActor.JsFromClient(userId, msg)
      }).map{ _ =>
        webSocketActor ! WebSocketActor.SocketClosed(userId)
      }, enumerator.asInstanceOf[Enumerator[JsValue]])
    }
  }

  
  def getCurrentBlock = Action.async {
    Neo4jEmbedded.getCurrentBlock().map { result =>
      result match {
        case Right(json) => Ok(json)
        case Left(e) => BadRequest(e.toString)
      }
    }
  }

  def getBlocks(blocksHashes: String) = Action.async {
    Neo4jEmbedded.getBlocks(blocksHashes).map { result =>
      result match {
        case Right(json) => Ok(json)
        case Left(e) => BadRequest(e.toString)
      }
    }
  }
 

  def getAddressesTransactions(addressesHashes: String, blockHash: Option[String]) = Action.async {
    Neo4jEmbedded.getAddressesTransactions(addressesHashes, blockHash).map { result =>
      result match {
        case Right(json) => Ok(json)
        case Left(e) => BadRequest(e.toString)
      }
    }
  }

  def getTransactions(txsHashes: String) = Action.async {
    Neo4jEmbedded.getTransactions(txsHashes).map { result =>
      result match {
        case Right(json) => Ok(json)
        case Left(e) => BadRequest(e.toString)
      }
    }
  }

  def sendTransaction = Action.async { implicit request =>
    var txHex = ""
    var error: Option[String] = None

    request.body.asJson.map { json =>
      (json \ "tx").asOpt[String].map { tx =>
        ApiLogs.debug("Pushtx JSON: " + tx)
        txHex = tx
      }.getOrElse {
        error = Some("Missing parameter [tx]")
      }
    }.getOrElse {
      error = Some("Expecting Json data")
    }

    error match {
      case Some(msg) => {
        ApiLogs.debug("PushTX Error: "+msg)
        Future(BadRequest(Json.obj("error" -> msg)))
      }
      case None => {
        blockchainsList(ticker).pushTransaction(ticker, txHex).map { res =>
          val (status, message) = res
          status match {
            case 200 => Ok(message)
            case _ => {
              Status(status)(Json.parse(message))
            }
          }
        }
      }
    }
  }
}
