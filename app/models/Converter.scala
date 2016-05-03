package models

object Converter {

  def hexToBigDecimal(value:String):BigDecimal = {
    BigDecimal.apply( Integer.decode(value) )
  }

}