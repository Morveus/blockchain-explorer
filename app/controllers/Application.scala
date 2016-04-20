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
  	var hello = new EmbeddedNeo4j()
		hello.createDb()
		hello.testInsert()
        hello.shutDown()
    Ok
  }
}
