package models


/*
  RPC data format
*/
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


/*
  ElasticSearch data format
*/
case class ESTransaction(
  hash: String,
  lock_time: Long,
  block: ESTransactionBlock,
  inputs: List[ESTransactionVIn],
  outputs: List[ESTransactionVOut],
  fees: Long,
  amount: Long
)

case class ESTransactionBlock(
  hash: String,
  height: Long,
  time: Long
)

case class ESTransactionVIn(
  coinbase: Option[String],
  output_hash: Option[String],
  output_index: Option[Long],
  input_index: Option[Long],
  value: Option[Long],
  addresses: Option[List[String]]
)

case class ESTransactionVOut(
  value: Long,
  output_index: Long,
  script_hex: String,
  addresses: List[String],
  spent_by: String
)