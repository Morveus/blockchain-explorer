package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current
import play.api.libs.concurrent._

import scala.concurrent.ExecutionContext.Implicits.global

import models._


import play.api.libs.json._


import scala.concurrent.Future
import scala.util.{Success, Failure}
object Application extends Controller {

  def index = Action { 
    Ok
  }

  def newblock(blockHeight:Long) = Action {
    Indexer.newblock(blockHeight)
    Ok
  }

}
