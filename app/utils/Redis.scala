package utils

import play.api.Play.current
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._

import redis.clients.jedis._

object Redis {

  val conf = play.api.Play.configuration
  val password = conf.getString("redis.password")
  val port = conf.getInt("redis.port")
  val host = conf.getString("redis.host")

  val jPool = new JedisPool(new JedisPoolConfig, host.get, port.get)


  def jedis(): Jedis = {
    val jedis:Jedis = jPool.getResource
    if(password.isDefined) jedis.auth(password.get)
    jedis
  }

  /* STRINGS COMMANDS */

  def get(key: String): Option[String] = {
    val jedis:Jedis = this.jedis()
    val result:String = jedis.get(key)
    jedis.close()

    if(result == null){
      None
    }else{
      Some(result)
    }
  }

  def get(jedis:Jedis, key: String): Option[String] = {
    val result:String = jedis.get(key)

    if(result == null){
      None
    }else{
      Some(result)
    }
  }

  def gets(pattern: String): List[String] = {
    val jedis:Jedis = this.jedis()
    val keys:List[String] = jedis.keys(pattern).asScala.toList
    val result = keys.length match {
      case 0 => List[String]()
      case _ => jedis.mget(keys : _*).asScala.toList
    }
    jedis.close()

    result
  }

  def set(key: String, value: String): String = {
    val jedis:Jedis = this.jedis()
    val result:String = jedis.set(key, value)
    jedis.close()

    result
  }

  def set(key: String, value: String, param: String): String = {
    val jedis:Jedis = this.jedis()
    val result:String = jedis.set(key, value, param)
    jedis.close()

    result
  }

  def set(jedis:Jedis, key: String, value: String): String = {
    val result:String = jedis.set(key, value)

    result
  }

  def del(key: String): Long = {
    val jedis:Jedis = this.jedis()
    val result:Long = jedis.del(key)
    jedis.close()

    result
  }

  def dels(pattern: String): Long = {
    val jedis:Jedis = this.jedis()
    val keys:List[String] = jedis.keys(pattern).asScala.toList
    val result:Long = keys.length match {
      case 0 => 0
      case _ => jedis.del(keys : _*)
    }
    jedis.close()

    result
  }

  /* SETS COMMANDS */

  def sadd(key: String, members: String*): Long = {
    val jedis:Jedis = this.jedis()
    val result:Long = jedis.sadd(key, members:_*)
    jedis.close()

    result
  }

  def srem(key: String, members: String*): Long = {
    val jedis:Jedis = this.jedis()
    val result:Long = jedis.srem(key, members:_*)
    jedis.close()

    result
  }

  def sismember(key: String, member: String): Boolean = {
    val jedis:Jedis = this.jedis()
    val result:Boolean = jedis.sismember(key, member)
    jedis.close()

    result
  }

  /* MISCELLANOUS COMMANDS */

  def keys(pattern: String): List[String] = {
    val jedis:Jedis = this.jedis()
    val result:List[String] = jedis.keys(pattern).asScala.toList
    jedis.close()

    result
  }
}
