package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current
import play.api.libs.json._
import scala.concurrent.Future
import scala.util.{Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global

import models._
import blockchains._

import com.typesafe.config._
import java.io._



object RESTApi extends Controller {

	var blockchainsList: Map[String, BlockchainAPI] = Map()
	  blockchainsList += ("btc" -> blockchains.BitcoinBlockchainAPI)
	  blockchainsList += ("ltc" -> blockchains.BitcoinBlockchainAPI)
	  blockchainsList += ("doge" -> blockchains.BitcoinBlockchainAPI)
	  blockchainsList += ("btcsegnet" -> blockchains.BitcoinBlockchainAPI)

	var indexer: Config = ConfigFactory.parseFile(new File("indexer.conf"))
	var ticker:String = indexer.getString("ticker")

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
