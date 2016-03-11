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
    Ok("Indexation en cours...")
  }

  def getBalance(ticker: String, addresses: String) = Action.async {
    val addressesList:List[String] = addresses.split(",").toList
    BlockchainExplorer.getBalance(ticker, addressesList).map { result =>
    Ok
    }
  }
}