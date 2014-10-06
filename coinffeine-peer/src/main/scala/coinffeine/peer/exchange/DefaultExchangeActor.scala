package coinffeine.peer.exchange

import akka.actor._

import coinffeine.model.bitcoin.ImmutableTransaction
import coinffeine.model.currency.FiatCurrency
import coinffeine.model.exchange.Exchange.{HandshakeFailed, HandshakeWithCommitmentFailed, InvalidCommitments}
import coinffeine.model.exchange._
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.WalletActor
import coinffeine.peer.exchange.DepositWatcher._
import coinffeine.peer.exchange.ExchangeActor._
import coinffeine.peer.exchange.TransactionBroadcastActor.{UnexpectedTxBroadcast => _, _}
import coinffeine.peer.exchange.handshake.HandshakeActor
import coinffeine.peer.exchange.handshake.HandshakeActor._
import coinffeine.peer.exchange.micropayment.{BuyerMicroPaymentChannelActor, MicroPaymentChannelActor, SellerMicroPaymentChannelActor}
import coinffeine.peer.exchange.protocol._
import coinffeine.peer.payment.PaymentProcessorActor

class DefaultExchangeActor[C <: FiatCurrency](
    exchangeProtocol: ExchangeProtocol,
    exchange: ExchangeToStart[C],
    delegates: DefaultExchangeActor.Delegates,
    collaborators: ExchangeActor.Collaborators) extends Actor with ActorLogging {

  private val txBroadcaster =
    context.actorOf(delegates.transactionBroadcaster, TransactionBroadcastActorName)

  override def preStart(): Unit = {
    log.info("Starting exchange {}", exchange.info.id)
    context.actorOf(delegates.handshake(self), HandshakeActorName)
  }

  override def postStop(): Unit = {
    log.info("Unblocking funds just in case")
    collaborators.wallet ! WalletActor.UnblockBitcoins(exchange.info.blockedFunds.bitcoin)
    exchange.info.blockedFunds.fiat.foreach { id =>
      collaborators.paymentProcessor ! PaymentProcessorActor.UnblockFunds(id)
    }
  }

  override val receive: Receive = inHandshake

  private def inHandshake: Receive = propagatingNotifications orElse {
    case HandshakeSuccess(rawExchange, commitments, refundTx)
      if rawExchange.currency == exchange.info.currency =>
      val handshakingExchange = rawExchange.asInstanceOf[HandshakingExchange[C]]
      spawnDepositWatcher(handshakingExchange, handshakingExchange.role.select(commitments), refundTx)
      spawnBroadcaster(refundTx)
      val validationResult = exchangeProtocol.validateDeposits(
        commitments, handshakingExchange.amounts, handshakingExchange.requiredSignatures,
        handshakingExchange.parameters.network)
      if (validationResult.forall(_.isSuccess)) {
        startMicropaymentChannel(commitments, handshakingExchange)
      } else {
        startAbortion(handshakingExchange.abort(InvalidCommitments(validationResult), refundTx))
      }

    case HandshakeFailure(cause) =>
      finishWith(ExchangeFailure(exchange.info.cancel(HandshakeFailed(cause), Some(exchange.user))))

    case HandshakeFailureWithCommitment(rawExchange, cause, deposit, refundTx) =>
      spawnDepositWatcher(rawExchange, deposit, refundTx)
      spawnBroadcaster(refundTx)
      startAbortion(
        exchange.info.abort(HandshakeWithCommitmentFailed(cause), exchange.user, refundTx))
  }

  private def spawnBroadcaster(refundTx: ImmutableTransaction): Unit = {
    txBroadcaster ! StartBroadcastHandling(refundTx, resultListeners = Set(self))
  }

  private def spawnDepositWatcher(exchange: HandshakingExchange[_ <: FiatCurrency],
                                  deposit: ImmutableTransaction,
                                  refundTx: ImmutableTransaction): Unit = {
    context.actorOf(delegates.depositWatcher(exchange, deposit, refundTx), "depositWatcher")
  }

  private def startMicropaymentChannel(commitments: Both[ImmutableTransaction],
                                       handshakingExchange: HandshakingExchange[C]): Unit = {
    val runningExchange = handshakingExchange.startExchanging(commitments)
    val channel = exchangeProtocol.createMicroPaymentChannel(runningExchange)
    val resultListeners = Set(self, txBroadcaster)
    context.actorOf(delegates.micropaymentChannel(channel, resultListeners), ChannelActorName)
    context.become(inMicropaymentChannel(runningExchange))
  }

  private def inMicropaymentChannel(runningExchange: RunningExchange[C]): Receive =
    propagatingNotifications orElse {

    case MicroPaymentChannelActor.ChannelSuccess(successTx) =>
      log.info("Finishing exchange '{}' successfully", exchange.info.id)
      txBroadcaster ! PublishBestTransaction
      context.become(waitingForFinalTransaction(runningExchange, successTx))

    case DepositSpent(broadcastTx, CompletedChannel) =>
      log.info("Finishing exchange '{}' successfully", exchange.info.id)
      finishWith(ExchangeSuccess(runningExchange.complete))

    case MicroPaymentChannelActor.ChannelFailure(step, cause) =>
      log.error(cause, "Finishing exchange '{}' with a failure in step {}", exchange.info.id, step)
      txBroadcaster ! PublishBestTransaction
      context.become(failingAtStep(runningExchange, step, cause))

    case DepositSpent(broadcastTx, _) =>
      finishWith(ExchangeFailure(runningExchange.panicked(broadcastTx)))
  }

  private def startAbortion(abortingExchange: AbortingExchange[C]): Unit = {
    txBroadcaster ! PublishBestTransaction
    context.become(aborting(abortingExchange))
  }

  private def aborting(abortingExchange: AbortingExchange[C]): Receive = {
    case DepositSpent(tx, DepositRefund | ChannelAtStep(_)) =>
      finishWith(ExchangeFailure(abortingExchange.broadcast(tx)))

    case DepositSpent(tx, _) =>
      log.error("When aborting {} and unexpected transaction was broadcast: {}",
        abortingExchange.id, tx)
      finishWith(ExchangeFailure(abortingExchange.broadcast(tx)))

    case FailedBroadcast(cause) =>
      log.error(cause, "Cannot broadcast the refund transaction")
      finishWith(ExchangeFailure(abortingExchange.failedToBroadcast))
  }

  private def propagatingNotifications: Receive = {
    case update: ExchangeUpdate => collaborators.listener ! update
  }

  private def failingAtStep(runningExchange: RunningExchange[C],
                            step: Int,
                            stepFailure: Throwable): Receive = {
    case DepositSpent(tx, destination) =>
      val expectedDestination = ChannelAtStep(step)
      if (destination != expectedDestination) {
        log.warning("Expected broadcast of {} but got {} for exchange {} (tx = {})",
          expectedDestination, destination, exchange.info.id, tx)
      }
      finishWith(ExchangeFailure(runningExchange.stepFailure(step, stepFailure, Some(tx))))

    case FailedBroadcast(cause) =>
      log.error(cause, "Cannot broadcast any recovery transaction")
      finishWith(ExchangeFailure(runningExchange.stepFailure(step, stepFailure, transaction = None)))
  }

  private def waitingForFinalTransaction(runningExchange: RunningExchange[C],
                                         expectedLastTx: Option[ImmutableTransaction]): Receive = {

    case DepositSpent(_, CompletedChannel) =>
      finishWith(ExchangeSuccess(runningExchange.complete))

    case DepositSpent(broadcastTx, destination) =>
      log.error("{} ({}) was unexpectedly broadcast for exchange {}", broadcastTx,
        destination, exchange.info.id)
      finishWith(ExchangeFailure(runningExchange.unexpectedBroadcast(broadcastTx)))

    case FailedBroadcast(cause) =>
      log.error(cause, "The finishing transaction could not be broadcast")
      finishWith(ExchangeFailure(runningExchange.noBroadcast))
  }

  private def finishWith(result: ExchangeResult): Unit = {
    collaborators.listener ! result
    context.stop(self)
  }
}

object DefaultExchangeActor {

  trait Delegates {
    def handshake(listener: ActorRef): Props
    def micropaymentChannel(channel: MicroPaymentChannel[_ <: FiatCurrency],
                            resultListeners: Set[ActorRef]): Props
    def transactionBroadcaster: Props
    def depositWatcher(exchange: HandshakingExchange[_ <: FiatCurrency],
                       deposit: ImmutableTransaction,
                       refundTx: ImmutableTransaction)(implicit context: ActorContext): Props
  }

  trait Component extends ExchangeActor.Component {
    this: ExchangeProtocol.Component with ProtocolConstants.Component =>

    override lazy val exchangeActorProps = (exchange: ExchangeToStart[_ <: FiatCurrency],
                                            collaborators: ExchangeActor.Collaborators) => {
      import collaborators._

      val delegates = new Delegates {
        val transactionBroadcaster =
          TransactionBroadcastActor.props(bitcoinPeer, blockchain, protocolConstants)

        def handshake(listener: ActorRef) = HandshakeActor.props(exchange,
          HandshakeActor.Collaborators(gateway, blockchain, wallet, listener),
          HandshakeActor.ProtocolDetails(exchangeProtocol, protocolConstants)
        )

        def micropaymentChannel(channel: MicroPaymentChannel[_ <: FiatCurrency],
                                resultListeners: Set[ActorRef]): Props = {
          val propsFactory = exchange.info.role match {
            case BuyerRole => BuyerMicroPaymentChannelActor.props _
            case SellerRole => SellerMicroPaymentChannelActor.props _
          }
          propsFactory(channel, protocolConstants,
            MicroPaymentChannelActor.Collaborators(gateway, paymentProcessor, resultListeners))
        }

        def depositWatcher(exchange: HandshakingExchange[_ <: FiatCurrency],
                           deposit: ImmutableTransaction,
                           refundTx: ImmutableTransaction)(implicit context: ActorContext) =
          Props(new DepositWatcher(exchange, deposit, refundTx,
            DepositWatcher.Collaborators(collaborators.blockchain, context.self)))
      }

      Props(new DefaultExchangeActor(exchangeProtocol, exchange, delegates, collaborators))
    }
  }
}
