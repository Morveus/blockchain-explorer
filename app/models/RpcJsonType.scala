package models



case class Block(
  hash: String,
  confirmations: Long,
  height: Long,
  tx: List[String],
  time: Long,
  previousblockhash: Option[String],
  nextblockhash: Option[String]
)

case class Transaction(
  txid: String,
  version: Long,
  locktime: Long,
  vin: List[TransactionVIn],
  vout: List[TransactionVOut]
)

case class TransactionVIn(
  coinbase: Option[String],
  txid: Option[String],
  vout: Option[Long],
  scriptSig: Option[ScriptSig],
  sequence: Long
)

case class TransactionVOut(
  value: Long,
  n: Long,
  scriptPubKey: ScriptPubKey
)

case class ScriptSig(
  asm: String,
  hex: String
)

case class ScriptPubKey(
  asm: String,
  hex: String,
  reqSigs: Long,
  `type`: String,
  addresses: List[String]
)
