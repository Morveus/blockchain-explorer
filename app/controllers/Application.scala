package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current

import scala.concurrent.ExecutionContext.Implicits.global

import models._


import scala.concurrent.Future
import scala.util.{Success, Failure}
object Application extends Controller {

  def index = Action {     

  	/*
  	BlockchainParser.finalizeSpent("ltc").map { response =>
      response match {
        case Right(s) => Logger.debug("BlockchainParser result : " + s)
        case Left(e) => Logger.error("BlockchainParser Exception : " + e.toString)
      }
    }
    */

    Neo4jBlockchainIndexer.resume("ltc", true).map { response =>
      response match {
        case Right(s) => Logger.debug("Neo4jBlockchainIndexer result : " + s)
        case Left(e) => Logger.error("Neo4jBlockchainIndexer Exception : " + e.toString)
      }
    }

    Ok("Indexation en cours...")
  }

  def getBalance(ticker: String, addresses: String) = Action.async {
    val addressesList:List[String] = addresses.split(",").toList
    BlockchainExplorer.getBalance(ticker, addressesList).map { result =>
    Ok
    }
  }
}