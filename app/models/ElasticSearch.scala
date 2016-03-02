package models

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global


object ElasticSearch {

  val config = play.api.Play.configuration
  val elasticSearchUrl = config.getString("elasticsearch.url").get

  def set(esIndex:String, esType:String, esId:String, data:JsValue) = {
    WS.url(elasticSearchUrl+"/"+esIndex+"/"+esType+"/"+esId).post(data).map { response =>
      /* TODO : vérifier que les données ont été correctement ajoutées à ES */
      response.body
    }
  }

}