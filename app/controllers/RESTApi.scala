package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current

import scala.concurrent.ExecutionContext.Implicits.global

import models._


import scala.concurrent.Future
import scala.util.{Success, Failure}


object RESTApi extends Controller {

  def getTransactionsHex(coin: String, transactionHash: String) = Action {
  	Ok("")
  }

  def sendTransaction(coin: String) = Action {
  	Ok("")
  }
}
