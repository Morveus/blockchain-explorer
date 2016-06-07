package utils

import java.math.BigInteger

object Converter {

  def hexToInt(value:String):Int = {
    Integer.decode(value)
  }

  def hexToBigDecimal(value:String):BigDecimal = {
    val hexValue = value.replace("0x","")
    val bigInt = new BigInteger(hexValue, 16)
    BigDecimal.apply( bigInt )
  }

  def stringToBigDecimal(value:String):BigDecimal = {
  	val bigInt = new BigInteger(value, 10)
    BigDecimal.apply( bigInt )
  }
}