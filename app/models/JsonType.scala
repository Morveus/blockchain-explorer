package models

import scala.collection.mutable.ListBuffer

/*
  RPC data format
*/
case class RPCBlock(
  number: String,
  hash: String,
  parentHash: Option[String],
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