package models

import java.math.BigInteger

object Converter {

  def hexToBigDecimal(value:String):BigDecimal = {
    val hexValue = value.replace("0x","")
    val bigInt = new BigInteger(hexValue, 16)
    BigDecimal.apply( bigInt )
  }
}