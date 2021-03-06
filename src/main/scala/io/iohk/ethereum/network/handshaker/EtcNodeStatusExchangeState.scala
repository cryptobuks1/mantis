package io.iohk.ethereum.network.handshaker

import io.iohk.ethereum.network.EtcPeerManagerActor.{PeerInfo, RemoteStatus}
import io.iohk.ethereum.network.handshaker.Handshaker.NextMessage
import io.iohk.ethereum.network.p2p.messages.WireProtocol.Disconnect
import io.iohk.ethereum.network.p2p.messages.WireProtocol.Disconnect.Reasons
import io.iohk.ethereum.network.p2p.{Message, MessageSerializable}
import io.iohk.ethereum.utils.Logger

trait EtcNodeStatusExchangeState[T <: Message] extends InProgressState[PeerInfo] with Logger {

  val handshakerConfiguration: EtcHandshakerConfiguration

  import handshakerConfiguration._

  def nextMessage: NextMessage =
    NextMessage(
      messageToSend = createStatusMsg(),
      timeout = peerConfiguration.waitForStatusTimeout
    )

  def processTimeout: HandshakerState[PeerInfo] = {
    log.debug("Timeout while waiting status")
    DisconnectedState(Disconnect.Reasons.TimeoutOnReceivingAMessage)
  }

  protected def applyRemoteStatusMessage: RemoteStatus => HandshakerState[PeerInfo] = { status: RemoteStatus =>
    log.debug("Peer returned status ({})", status)

    val validNetworkID = status.networkId == handshakerConfiguration.peerConfiguration.networkId
    val validGenesisHash = status.genesisHash == blockchain.genesisHeader.hash

    if (validNetworkID && validGenesisHash) {
      forkResolverOpt match {
        case Some(forkResolver) =>
          EtcForkBlockExchangeState(handshakerConfiguration, forkResolver, status)
        case None =>
          ConnectedState(PeerInfo.withForkAccepted(status))
      }
    } else
      DisconnectedState(Reasons.DisconnectRequested)
  }

  protected def getBestBlockHeader() = {
    val bestBlockNumber = blockchain.getBestBlockNumber()
    blockchain.getBlockHeaderByNumber(bestBlockNumber).getOrElse(blockchain.genesisHeader)
  }

  protected def createStatusMsg(): MessageSerializable

}
