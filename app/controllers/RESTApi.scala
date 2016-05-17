package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current

import scala.concurrent.ExecutionContext.Implicits.global

import models._


import scala.concurrent.Future
import scala.util.{Success, Failure}


object RESTApi extends Controller {

  def getCurrentBlock(coin:String) = Action.async { 

  	Neo4jEmbedded.getCurrentBlock().map { result =>
  		result match {
  			case Right(s) => Ok(s)
  			case Left(e) => InternalServerError(e.getMessage)
  		}
  	}
   
  }

  def getAddressesTransactions(coin:String, addressesHashes:String, blockHash: Option[String]) = Action.async { 
    Neo4jEmbedded.getAddressesTransactions(addressesHashes, blockHash).map { result =>
  		result match {
  			case Right(s) => Ok(s)
  			case Left(e) => InternalServerError(e.getMessage)
  		}
  	}
  }

  def getAddressesUnspents(coin:String, addressesHashes:String) = Action {
  	Ok("")
  }

  def getTransactions(coin: String, txsHashes: String) = Action {
  	Ok("")
  }

  def getTransactionsHex(coin: String, transactionHash: String) = Action {
  	Ok("")
  }

  def sendTransaction(coin: String) = Action {
  	Ok("")
  }
}
