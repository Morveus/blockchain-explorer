package utils


object Converter {
	
	def btcToSatoshi(btc:BigDecimal):Long = {
    (btc * 100000000).toLong
  }
}