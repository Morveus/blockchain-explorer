package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current
import play.api.libs.concurrent._

import scala.concurrent.ExecutionContext.Implicits.global

import models._

import actors._
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.json._
import scala.concurrent.duration._
import play.api.libs.iteratee._


import scala.concurrent.Future
import scala.util.{Success, Failure}
object Application extends Controller {

  Akka.system.actorOf(Props[WebSocketActor], name = "blockchain-explorer")
  val webSocketActor = Akka.system.actorSelection("user/blockchain-explorer")

  def index = Action { 
    Ok
  }

  def newblock(blockHash:String) = Action {
    Indexer.newblock(blockHash)
    Ok
  }


  def ws = WebSocket.tryAccept[JsValue] {
    request =>
    implicit val timeout = Timeout(3 seconds)

    val userId = java.util.UUID.randomUUID.toString

    // using the ask pattern of akka,
    // get the enumerator for that user
    (webSocketActor ? WebSocketActor.StartSocket(userId)) map {
      enumerator =>

      // create a Iteratee which process the input and
      // and send a SocketClosed message to the actor when
      // connection is closed from the client
      Right(Iteratee.foreach[JsValue](msg => {
        webSocketActor ! WebSocketActor.JsFromClient(userId, msg)
      }).map{ _ =>
        webSocketActor ! WebSocketActor.SocketClosed(userId)
      }, enumerator.asInstanceOf[Enumerator[JsValue]])
    }
  }

}
