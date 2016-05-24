package actors

import play.api.libs.json._
import play.api.libs.json.Json._
import play.api.libs.iteratee.{Concurrent, Enumerator}
import play.api.libs.iteratee.Concurrent.Channel
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import scala.concurrent.duration._
import akka.actor.Actor
import models._
import utils.Redis


class WebSocketActor extends Actor {
     import WebSocketActor._

     case class UserChannel(userId: String, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

     def log(message: String) = {
          Logger.info(this.getClass.getName + " : " + message)
     }

     def stripChars(s:String, ch:String) = s filterNot (ch contains _)

     // this map relate every user with his UserChannel
     var webSockets = Map[String, UserChannel]()

     var usersToken = Map[String, String]()

     val actorName = self.path.name

     override def receive = {

          case StartSocket(userId) =>

               log(s"start new socket for user $userId")

               // get or create the touple (Enumerator[JsValue], Channel[JsValue]) for current user
               // Channel is very useful class, it allows to write data inside its related
               // enumerator, that allow to create WebSocket or Streams around that enumerator and
               // write data iside that using its related Channel
               val userChannel: UserChannel = webSockets.get(userId) getOrElse {
                    val broadcast: (Enumerator[JsValue], Channel[JsValue]) = Concurrent.broadcast[JsValue]
                    UserChannel(userId, 0, broadcast._1, broadcast._2)
               }

               // if user open more then one connection, increment just a counter instead of create
               // another touple (Enumerator, Channel), and return current enumerator,
               // in that way when we write in the channel,
               // all opened WebSocket of that user receive the same data
               userChannel.channelsCount = userChannel.channelsCount + 1
               webSockets += (userId -> userChannel)

               // log(s"channel for user : $userId count : ${userChannel.channelsCount}")
               // log(s"channel count : ${webSockets.size}")

               // return the enumerator related to the user channel,
               // this will be used for create the WebSocket
               sender ! userChannel.enumerator


          case BroadcastToAll(message) =>
               webSockets.foreach {
                    case (wsUserId, wsUserChannel) =>
                         webSockets.get(wsUserId).get.channel push message
               }

          case JsFromClient(userId, something) =>



          case SocketClosed(userId) =>

               var userWs = webSockets.get(userId)
               userWs match {
                    case None => {
                        
                    }
                    case any => {
                         log(s"closed socket for user $userId")

                         val userChannel = userWs.get

                         if (userChannel.channelsCount > 1) {
                              userChannel.channelsCount = userChannel.channelsCount - 1
                              webSockets += (userId -> userChannel)
                         } else {
                              webSockets -= userId
                         }
                    }
               }

     }
}

object WebSocketActor {

     sealed trait SocketMessage

     case class StartSocket(userId: String) extends SocketMessage

     case class SocketClosed(userId: String) extends SocketMessage

     case class BroadcastToAll(message: JsValue) extends SocketMessage

     case class JsFromClient(userId: String, message: JsValue) extends SocketMessage

}