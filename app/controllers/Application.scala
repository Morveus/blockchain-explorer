package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current

import scala.concurrent.ExecutionContext.Implicits.global

import models._

object Application extends Controller {

  def index = Action {
    //BlockchainParser.restart("ltc")
    BlockchainParser.resume("ltc")
    Ok("Indexation en cours...")
  }
}