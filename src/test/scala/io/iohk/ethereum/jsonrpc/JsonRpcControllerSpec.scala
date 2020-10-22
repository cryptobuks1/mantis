package io.iohk.ethereum.jsonrpc

import io.iohk.ethereum.jsonrpc.DebugService.{ListPeersInfoRequest, ListPeersInfoResponse}
import io.iohk.ethereum.jsonrpc.EthService._
import io.iohk.ethereum.jsonrpc.JsonRpcController.JsonRpcConfig
import io.iohk.ethereum.jsonrpc.JsonSerializers.{
  OptionNoneToJNullSerializer,
  QuantitiesSerializer,
  UnformattedDataJsonSerializer
}
import io.iohk.ethereum.jsonrpc.NetService.{ListeningResponse, PeerCountResponse, VersionResponse}
import io.iohk.ethereum.jsonrpc.server.http.JsonRpcHttpServer
import io.iohk.ethereum.jsonrpc.server.ipc.JsonRpcIpcServer
import io.iohk.ethereum.network.EtcPeerManagerActor.PeerInfo
import io.iohk.ethereum.network.p2p.messages.CommonMessages.Status
import io.iohk.ethereum.network.p2p.messages.Versions
import io.iohk.ethereum.Fixtures
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.TestScheduler
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.{DefaultFormats, Extraction, Formats}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration._

class JsonRpcControllerSpec extends AnyFlatSpec with Matchers with JRCMatchers with ScalaCheckPropertyChecks {

  implicit val tx: Scheduler = TestScheduler()

  implicit val formats: Formats = DefaultFormats.preservingEmptyValues + OptionNoneToJNullSerializer +
    QuantitiesSerializer + UnformattedDataJsonSerializer

  "JsonRpcController" should "handle valid sha3 request" in new JsonRpcControllerFixture {
    val rpcRequest = newJsonRpcRequest("web3_sha3", JString("0x1234") :: Nil)

    val response = jsonRpcController.handleRequest(rpcRequest).runSyncUnsafe()

    response should haveStringResult("0x56570de287d73cd1cb6092bb8fdee6173974955fdef345ae579ee9f475ea7432")
  }

  it should "fail when invalid request is received" in new JsonRpcControllerFixture {
    val rpcRequest = newJsonRpcRequest("web3_sha3", JString("asdasd") :: Nil)

    val response = jsonRpcController.handleRequest(rpcRequest).runSyncUnsafe()

    response should haveError(JsonRpcErrors.InvalidParams("Invalid method parameters"))
  }

  it should "handle clientVersion request" in new JsonRpcControllerFixture {
    val rpcRequest = newJsonRpcRequest("web3_clientVersion")

    val response = jsonRpcController.handleRequest(rpcRequest).runSyncUnsafe()

    response should haveStringResult(version)
  }

  it should "Handle net_peerCount request" in new JsonRpcControllerFixture {
    (netService.peerCount _).expects(*).returning(Task.now(Right(PeerCountResponse(123))))

    val rpcRequest = newJsonRpcRequest("net_peerCount")

    val response = jsonRpcController.handleRequest(rpcRequest).runSyncUnsafe()

    response should haveStringResult("0x7b")
  }

  it should "Handle net_listening request" in new JsonRpcControllerFixture {
    (netService.listening _).expects(*).returning(Task.now(Right(ListeningResponse(false))))

    val rpcRequest = newJsonRpcRequest("net_listening")
    val response = jsonRpcController.handleRequest(rpcRequest).runSyncUnsafe()

    response should haveBooleanResult(false)
  }

  it should "Handle net_version request" in new JsonRpcControllerFixture {
    val netVersion = "99"

    (netService.version _).expects(*).returning(Task.now(Right(VersionResponse(netVersion))))

    val rpcRequest = newJsonRpcRequest("net_version")
    val response = jsonRpcController.handleRequest(rpcRequest).runSyncUnsafe()

    response should haveStringResult(netVersion)
  }

  it should "only allow to call methods of enabled apis" in new JsonRpcControllerFixture {
    override def config: JsonRpcConfig = new JsonRpcConfig {
      override val apis = Seq("web3")
      override val accountTransactionsMaxBlocks = 50000
      override def minerActiveTimeout: FiniteDuration = ???
      override def httpServerConfig: JsonRpcHttpServer.JsonRpcHttpServerConfig = ???
      override def ipcServerConfig: JsonRpcIpcServer.JsonRpcIpcServerConfig = ???
    }

    val ethRpcRequest = newJsonRpcRequest("eth_protocolVersion")
    val ethResponse = jsonRpcController.handleRequest(ethRpcRequest).runSyncUnsafe()

    ethResponse should haveError(JsonRpcErrors.MethodNotFound)

    val web3RpcRequest = newJsonRpcRequest("web3_clientVersion")
    val web3Response = jsonRpcController.handleRequest(web3RpcRequest).runSyncUnsafe()

    web3Response should haveStringResult(version)
  }

  it should "debug_listPeersInfo" in new JsonRpcControllerFixture {
    val peerStatus = Status(
      protocolVersion = Versions.PV63,
      networkId = 1,
      totalDifficulty = BigInt("10000"),
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash
    )
    val initialPeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      totalDifficulty = peerStatus.totalDifficulty,
      forkAccepted = true,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number,
      bestBlockHash = peerStatus.bestHash
    )
    val peers = List(initialPeerInfo)

    (debugService.listPeersInfo _)
      .expects(ListPeersInfoRequest())
      .returning(Task.now(Right(ListPeersInfoResponse(peers))))

    val rpcRequest = newJsonRpcRequest("debug_listPeersInfo")
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).runSyncUnsafe()

    response should haveResult(JArray(peers.map(info => JString(info.toString))))
  }

  it should "rpc_modules" in new JsonRpcControllerFixture {
    val request: JsonRpcRequest = newJsonRpcRequest("rpc_modules")

    val response = jsonRpcController.handleRequest(request).runSyncUnsafe()

    response should haveResult(
      JObject(
        "net" -> "1.0",
        "rpc" -> "1.0",
        "personal" -> "1.0",
        "eth" -> "1.0",
        "web3" -> "1.0",
        "daedalus" -> "1.0",
        "debug" -> "1.0",
        "qa" -> "1.0",
        "checkpointing" -> "1.0"
      )
    )
  }

  it should "daedalus_getAccountTransactions" in new JsonRpcControllerFixture {
    val mockEthService: EthService = mock[EthService]
    override val jsonRpcController = newJsonRpcController(mockEthService)

    val block = Fixtures.Blocks.Block3125369
    val sentTx = block.body.transactionList.head
    val receivedTx = block.body.transactionList.last

    (mockEthService.getAccountTransactions _)
      .expects(*)
      .returning(
        Task.now(
          Right(
            GetAccountTransactionsResponse(
              Seq(
                TransactionResponse(sentTx, Some(block.header), isOutgoing = Some(true)),
                TransactionResponse(receivedTx, Some(block.header), isOutgoing = Some(false))
              )
            )
          )
        )
      )

    val request: JsonRpcRequest = newJsonRpcRequest(
      "daedalus_getAccountTransactions",
      List(
        JString(s"0x7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D"),
        JInt(100),
        JInt(200)
      )
    )

    val response = jsonRpcController.handleRequest(request).runSyncUnsafe()
    val expectedTxs = Seq(
      Extraction.decompose(TransactionResponse(sentTx, Some(block.header), isOutgoing = Some(true))),
      Extraction.decompose(TransactionResponse(receivedTx, Some(block.header), isOutgoing = Some(false)))
    )

    response should haveObjectResult("transactions" -> JArray(expectedTxs.toList))
  }
}
