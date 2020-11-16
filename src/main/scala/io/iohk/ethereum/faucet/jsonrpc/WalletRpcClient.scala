package io.iohk.ethereum.faucet.jsonrpc

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import io.circe.syntax._
import akka.util.ByteString
import io.iohk.ethereum.domain.Address
import io.iohk.ethereum.jsonrpc.jsonrpc.RpcBaseClient
import io.iohk.ethereum.jsonrpc.jsonrpc.RpcBaseClient.RpcError
import io.iohk.ethereum.utils.Logger
import javax.net.ssl.SSLContext
import monix.eval.Task

import scala.concurrent.ExecutionContext

class WalletRpcClient(node: Uri, maybeSslContext: Option[SSLContext])(implicit
    system: ActorSystem,
    ec: ExecutionContext
) extends RpcBaseClient(node, maybeSslContext)
    with Logger {
  import io.iohk.ethereum.jsonrpc.jsonrpc.CommonJsonCodecs._

  def getNonce(address: Address): Task[Either[RpcError, BigInt]] =
    doRequest[BigInt]("eth_getTransactionCount", List(address.asJson, "latest".asJson))

  def sendTransaction(rawTx: ByteString): Task[Either[RpcError, ByteString]] =
    doRequest[ByteString]("eth_sendRawTransaction", List(rawTx.asJson))
}