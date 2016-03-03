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

  def getBeforeLastBlockHash(esIndex:String) = {
    val query = Json.obj("sort" -> Json.arr(Json.obj("height" -> Json.obj("order" -> "desc"))),
                         "from" -> 1, "size" -> 1,
                         "fields" -> Json.arr("hash"))
    WS.url(elasticSearchUrl+"/"+esIndex+"/block/_search").post(query).map { response =>
      ((Json.parse(response.body) \ "hits" \ "hits")(0) \ "fields" \ "hash")(0).asOpt[String]
    }
  }

  def getBlockFromTxHash(esIndex:String, txHash:String) = {
    val query = Json.obj("query" -> Json.obj("match" -> Json.obj("tx" -> txHash)))
    WS.url(elasticSearchUrl+"/"+esIndex+"/block/_search").post(query).map { response =>
      ((Json.parse(response.body) \ "hits" \ "hits")(0) \ "_source")
    }
  }

  def getOutputFromTransaction(esIndex:String, txHash:String, output_index:Long) = {
    /*
      TODO:
      récupérer directement l'output que l'on recherche via ES plutôt que de récupérer toute la tx
    */
    val query = Json.obj("query" -> Json.obj("match" -> Json.obj("txid" -> txHash)))
    WS.url(elasticSearchUrl+"/"+esIndex+"/block/_search").post(query).map { response =>
      ((Json.parse(response.body) \ "hits" \ "hits")(0) \ "_source")
    }
  }

}