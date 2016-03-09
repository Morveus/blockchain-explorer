package models

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


object BlockchainExplorer {

  val config = play.api.Play.configuration

  def getBalance(ticker:String, addresses:List[String]):Future[Unit] = {
    Future{
      for(address <- addresses){
        ElasticSearch.getTotalReceived(ticker, address).map { response =>
          //Logger.debug("totalReceived: "+response)
        }
        ElasticSearch.getTotalSent(ticker, address).map { response =>
          //Logger.debug("totalSent: "+response)
        }
      }
    }
    
  }

}