package coinffeine.peer.market.orders.controller

import coinffeine.model.currency.FiatCurrency
import coinffeine.model.exchange._
import coinffeine.model.market.InProgressOrder
import coinffeine.protocol.messages.brokerage.OrderMatch

private[controller] class ExchangingState[C <: FiatCurrency](exchangeInProgress: ExchangeId)
  extends State[C] {

  override def enter(ctx: Context): Unit = {
    ctx.updateOrderStatus(InProgressOrder)
  }

  override def exchangeCompleted(ctx: Context, exchange: CompletedExchange[C]): Unit = {
    if (!exchange.state.isSuccess) {
      throw new NotImplementedError(s"Don't know what to do with $exchange")
    }
    ctx.transitionTo(
      if (ctx.order.amounts.pending.isPositive) new WaitingForMatchesState()
      else new FinalState(FinalState.OrderCompletion)
    )
  }

  override def acceptOrderMatch(ctx: Context, orderMatch: OrderMatch[C]): Unit = {
    val resolution = if (exchangeInProgress == orderMatch.exchangeId)
      MatchAlreadyAccepted[C](ctx.order.exchanges(exchangeInProgress))
    else MatchRejected[C]("Exchange already in progress")
    ctx.resolveOrderMatch(orderMatch, resolution)
  }

  override def cancel(ctx: Context, reason: String): Unit = {
    // TODO: is this what we wanna do?
    ctx.transitionTo(new FinalState(FinalState.OrderCancellation(reason)))
  }
}
