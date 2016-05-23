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
    Ok
  }

  def newblock(blockHash:String) = Action {
    Indexer.newblock(blockHash)
    Ok
  }

}
