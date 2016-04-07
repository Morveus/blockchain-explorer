package models

import scala.collection.mutable.ListBuffer

/*
  RPC data format
*/
case class RPCBlock(
  hash: String,
  confirmations: Long,
  height: Long,
  tx: List[String],
  time: Long,
  previousblockhash: Option[String],
  nextblockhash: Option[String]
)

case class RPCTransaction(
  txid: String,
  version: Long,
  locktime: Long,
  vin: List[RPCTransactionVIn],
  vout: List[RPCTransactionVOut]
)

case class RPCTransactionVIn(
  coinbase: Option[String],
  txid: Option[String],
  vout: Option[Long],
  scriptSig: Option[RPCScriptSig],
  sequence: Long
)

case class RPCTransactionVOut(
  value: BigDecimal,
  n: Long,
  scriptPubKey: RPCScriptPubKey
)

case class RPCScriptSig(
  asm: String,
  hex: String
)

case class RPCScriptPubKey(
  asm: String,
  hex: String,
  reqSigs: Option[Long],
  `type`: String,
  addresses: Option[List[String]]
)



/*
  Neo4j data format
*/
case class NeoBlock(
  hash: String,
  height: Long,
  time: Long,
  main_chain: Boolean
)

case class NeoTransaction(
  hash: String,
  received_at: Long,
  lock_time: Long,
  fees: Option[Long],
  amount: Option[Long]
)

case class NeoAddress(
  address_id: String
)

case class NeoInput(
  input_index: Long,
  coinbase: Option[String],
  output_index: Option[Long],
  output_tx_hash: Option[String]
)

case class NeoOutput(
  output_index: Long,
  value: Long,
  script_hex: String,
  addresses: List[String]
)



case class TxBatch(
  txHash: String,
  inputsTxs: ListBuffer[String],
  query: String
)