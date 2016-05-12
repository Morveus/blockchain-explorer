package models

import scala.collection.mutable.ListBuffer

/*
  RPC data format
*/
case class RPCBlock(
  number: String,
  hash: String,
  parentHash: String,
  nonce: String,
  sha3Uncles: String,
  logsBloom: String,
  transactionsRoot: String,
  stateRoot:  String,
  receiptsRoot: Option[String],
  receiptHash: Option[String],
  miner: String,
  difficulty: String,
  totalDifficulty: Option[String],
  extraData: String,
  size: Option[String],
  gasLimit:  String,
  gasUsed: String,
  timestamp: String,
  transactions: Option[List[RPCTransaction]],
  uncles: Option[List[String]]
)

case class RPCTransaction(
  hash: String,
  nonce: String,
  blockHash: String,
  blockNumber: String,
  transactionIndex: String,
  from: String,
  to: Option[String],
  value: String,
  gas: String,
  gasPrice: String,
  input: String
)


/*
  Neo4j data format
*/
case class NeoBlock(
  hash: String,
  height: Long,
  time: Long,
  uncle_index: Option[Integer]
)

case class NeoTransaction(
  hash: String,
  tx_index: Long,
  nonce: Long,
  value: BigDecimal,
  gas: Long,
  gasPrice: BigDecimal,
  input: String
)

case class NeoAddress(
  address_id: String
)








case class TxBatch(
  txHash: String,
  inputsTxs: ListBuffer[String],
  query: String
)