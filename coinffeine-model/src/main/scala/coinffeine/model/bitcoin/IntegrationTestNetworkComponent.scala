package coinffeine.model.bitcoin

import java.net.InetAddress

import org.bitcoinj.core.PeerAddress
import org.bitcoinj.params.TestNet3Params

trait IntegrationTestNetworkComponent extends NetworkComponent {

  override lazy val network = IntegrationTestNetworkComponent.NetworkParams

  override lazy val peerAddresses = Seq(testnetAddress())

  def testnetAddress() = new PeerAddress(
    InetAddress.getByName(IntegrationTestNetworkComponent.Hostname),
    IntegrationTestNetworkComponent.Port
  )
}

object IntegrationTestNetworkComponent {
  val Port = 19000
  val Hostname = "testnet.test.coinffeine.com"
  val NetworkParams = new TestNet3Params() {
    dnsSeeds = Array.empty
  }
}
