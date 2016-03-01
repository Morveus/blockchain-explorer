package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current

import scala.concurrent.ExecutionContext.Implicits.global

import models._

object Application extends Controller {

  def index = Action.async {
    
    BlockchainParser.resume("ltc").map{ result =>
      Ok(result)
    }

  }
}