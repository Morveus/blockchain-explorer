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

  def getTransaction(esIndex:String, txHash:String) = {
    WS.url(elasticSearchUrl+"/"+esIndex+"/transaction/"+txHash).get().map { response =>
      (Json.parse(response.body) \ "_source")
    }
  }

  def getTotalReceived(esIndex:String, address:String) = {
    val query = Json.obj("query" -> 
                  Json.obj("bool" -> 
                    Json.obj("should" -> 
                      Json.arr(
                        Json.obj("match" -> 
                          Json.obj("inputs.addresses" -> address)
                        )
                      )
                    )
                  )
                )

    /* TODO : retourner directement la value de l'input concerné */

    WS.url(elasticSearchUrl+"/"+esIndex+"/transaction/_search").post(query).map { response =>
      ((Json.parse(response.body) \ "hits" \ "hits")(0) \ "_source")
    }
  }

  def getTotalSent(esIndex:String, address:String) = {
    val query = Json.obj("query" -> 
                  Json.obj("bool" -> 
                    Json.obj("should" -> 
                      Json.arr(
                        Json.obj("match" -> 
                          Json.obj("outputs.addresses" -> address)
                        )
                      )
                    )
                  )
                )

    /* TODO : retourner directement la value de l'output concerné */

    WS.url(elasticSearchUrl+"/"+esIndex+"/transaction/_search").post(query).map { response =>
      ((Json.parse(response.body) \ "hits" \ "hits")(0) \ "_source")
    }
  }

}