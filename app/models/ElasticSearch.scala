package models

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Failure}


/* MAPPING:
{
  "mappings": {
    "transaction": {
      "properties": {
        "inputs": {
          "type": "nested", 
          "properties": {
            "output_hash":    { "type": "string"  },
            "output_index": { "type": "integer"  },
            "input_index":     { "type": "integer"   },
            "value":   { "type": "long"   }
          }
        },
        "outputs": {
          "type": "nested", 
          "properties": {
            "value":    { "type": "long"  },
            "output_index": { "type": "integer"  },
            "script_hex":     { "type": "string"   }
          }
        }
      }
    }
  }
}



{
    "query" : {
        "nested" : {
            "path" : "outputs",
            "query" : {
                "match" : {"outputs.script_hex" : "76a9149c317d7946c494b77c26cd8520550838212b828588ac"}
            },
            "inner_hits" : {} 
        }
    }
} 
*/


object ElasticSearch {

  val config = play.api.Play.configuration
  val elasticSearchUrl = config.getString("elasticsearch.url").get

  def set(esIndex:String, esType:String, esId:String, data:JsValue):Future[Either[Exception,String]] = {
    WS.url(elasticSearchUrl+"/"+esIndex+"/"+esType+"/"+esId).post(data).map { response =>
      response.status match {
        case 200 | 201 => Right(response.body)
        case _ => Left(new Exception("ElasticSearch.set("+esIndex+","+esType+","+esId+",...) (http status: "+response.status.toString+") (http body: "+response.body.toString+")"))
      }
    }
  }

  def getBeforeLastBlockHash(esIndex:String):Future[Either[Exception,Option[String]]] = {
    val query = """
      {
        "sort":[
          {
            "height":{
              "order":"desc"
            }
          }
        ],
        "from":1,
        "size":1,
        "fields":["hash"]
      }
    """
    WS.url(elasticSearchUrl+"/"+esIndex+"/block/_search").post(query).map { response =>
      response.status match {
        case 200 => Right(((Json.parse(response.body) \ "hits" \ "hits")(0) \ "fields" \ "hash")(0).asOpt[String])
        case _ => Left(new Exception("ElasticSearch.getBeforeLastBlockHash("+esIndex+") (http status: "+response.status.toString+") (http body: "+response.body.toString+")"))
      }
    }
  }

  def getBlockFromTxHash(esIndex:String, txHash:String):Future[Either[Exception,JsValue]] = {
    val query = """
      {
        "query":{
          "match":{
            "tx":"""+txHash+"""
          }
        }
      }
    """
    WS.url(elasticSearchUrl+"/"+esIndex+"/block/_search").post(query).map { response =>
      response.status match {
        case 200 => Right(((Json.parse(response.body) \ "hits" \ "hits")(0) \ "_source"))
        case _ => Left(new Exception("ElasticSearch.getBlockFromTxHash("+esIndex+","+txHash+") (http status: "+response.status.toString+") (http body: "+response.body.toString+")"))
      }
    }
  }

  def getTransaction(esIndex:String, txHash:String):Future[Either[Exception,JsValue]] = {
    WS.url(elasticSearchUrl+"/"+esIndex+"/transaction/"+txHash).get().map { response =>
      response.status match {
        case 200 => Right((Json.parse(response.body) \ "_source"))
        case _ => Left(new Exception("ElasticSearch.getTransaction("+esIndex+","+txHash+") (http status: "+response.status.toString+") (http body: "+response.body.toString+")"))
      }     
    }
  }

  def getNotFinalizedTransactions(esIndex:String, size:Int=100):Future[Either[Exception,(Boolean,JsValue)]] = {
    val query = """
      {
        "sort":[
          {
            "received_at":{
              "order":"asc"
            }
          }
        ],
        "from":0,
        "size":"""+size+""",
        "query":{
          "filtered":{
            "query":{
              "match_all":{}
            },
            "filter":{
              "bool":{
                "must":[
                  {
                    "missing":{
                      "field":"inputs.coinbase",
                      "existence":true,
                      "null_value":true
                    }
                  },
                  {
                    "missing":{
                      "field":"inputs.value",
                      "existence":true,
                      "null_value":true
                    }
                  }
                ],
                "should":{},
                "must_not":{}
              }
            }
          } 
        }  
      }
    """
    WS.url(elasticSearchUrl+"/"+esIndex+"/transaction/_search").post(query).map { response =>
       response.status match {
        case 200 => {
          val json = Json.parse(response.body)
          var truncated = true
          if((json \ "hits" \ "hits").as[JsArray].value.size < size){
            truncated = false
          }
          val results = Json.toJson((json \ "hits" \ "hits" \\ "_source"))
          Right((truncated,results))
        }
        case _ => Left(new Exception("ElasticSearch.getNotFinalizedTransactions("+esIndex+","+size+") (http status: "+response.status.toString+") (http body: "+response.body.toString+")"))
      }
    }
  }






  def getTotalReceived(esIndex:String, address:String) = {
    val query = """
      {
        "query":{
          "bool":{
            "should":[
              {
                "match":{
                  "outputs.addresses": """+address+"""
                }
              }
            ]
          }
        }
      }
    """
    /* TODO : retourner directement la value de l'input concerné */

    WS.url(elasticSearchUrl+"/"+esIndex+"/transaction/_search").post(query).map { response =>
      ((Json.parse(response.body) \ "hits" \ "hits")(0) \ "_source")
    }
  }

  def getTotalSent(esIndex:String, address:String) = {
    val query = """
      {
        "query":{
          "bool":{
            "should":[
              {
                "match":{
                  "inputs.addresses": """+address+"""
                }
              }
            ]
          }
        },
        "aggs":{
          "spents":{
            "sum":{
              "field":"inputs.value"
            }
          }
        }
      }
    """

    /* TODO : retourner directement la value de l'output concerné */

    WS.url(elasticSearchUrl+"/"+esIndex+"/transaction/_search").post(query).map { response =>
      ((Json.parse(response.body) \ "hits" \ "hits")(0) \ "_source")
    }
  }

}