package exchange.core2.orderbook.api;

public sealed interface Event permits ReduceEvent, TradeEvent {}

