package com.grahamcrockford.oco.exchange;

import static com.grahamcrockford.oco.marketdata.MarketDataType.TICKER;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderStatus;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.dto.trade.OpenOrders;
import org.knowm.xchange.dto.trade.StopOrder;
import org.knowm.xchange.dto.trade.UserTrades;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.CancelOrderByIdParams;
import org.knowm.xchange.service.trade.params.CancelOrderParams;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParamCurrencyPair;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.grahamcrockford.oco.marketdata.ExchangeEventRegistry;
import com.grahamcrockford.oco.marketdata.MarketDataSubscription;
import com.grahamcrockford.oco.marketdata.TickerEvent;
import com.grahamcrockford.oco.spi.TickerSpec;
import io.reactivex.disposables.Disposable;

/**
 * Paper trading implementation.  Note: doesn't work between restarts. Probably not thread
 * safe either yet.
 */
public final class PaperTradeService implements TradeService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PaperTradeService.class);

  private final AtomicLong orderCounter = new AtomicLong();
  private final ConcurrentMap<Long, LimitOrder> openOrders = new ConcurrentHashMap<>();
  private final ConcurrentMap<Long, Date> placedDates = new ConcurrentHashMap<>();

  private final String exchange;
  private final ExchangeEventRegistry exchangeEventRegistry;

  private final Random random = new Random();

  private final String eventRegistryClientId = PaperTradeService.class.getSimpleName() + "/" + UUID.randomUUID().toString();
  private volatile Disposable subscription;

  private PaperTradeService(String exchange, ExchangeEventRegistry exchangeEventRegistry) {
    this.exchange = exchange;
    this.exchangeEventRegistry = exchangeEventRegistry;
  }

  @Override
  public OpenOrders getOpenOrders() throws IOException {
    return new OpenOrders(FluentIterable.from(openOrders.values()).filter(this::isOpen).toList());
  }

  @SuppressWarnings("unchecked")
  @Override
  public OpenOrders getOpenOrders(OpenOrdersParams params) throws IOException {
    OpenOrders all = getOpenOrders();
    if (params instanceof OpenOrdersParamCurrencyPair) {
      CurrencyPair pair = ((OpenOrdersParamCurrencyPair) params).getCurrencyPair();
      return new OpenOrders(
        all.getOpenOrders().stream().filter(o -> o.getCurrencyPair().equals(pair)).collect(Collectors.toList()),
        (List<Order>) all.getHiddenOrders().stream().filter(o -> o.getCurrencyPair().equals(pair)).collect(Collectors.toList())
      );
    } else{
      return all;
    }
  }

  @Override
  public String placeMarketOrder(MarketOrder marketOrder) throws IOException {
    throw new NotAvailableFromExchangeException();
  }

  @Override
  public String placeLimitOrder(LimitOrder limitOrder) throws IOException {
    randomDelay();
    final long id = orderCounter.incrementAndGet();
    String strId = String.valueOf(id);
    synchronized (this) {
      LimitOrder newOrder = new LimitOrder(
          limitOrder.getType(),
          limitOrder.getOriginalAmount(),
          limitOrder.getCurrencyPair(),
          strId,
          limitOrder.getTimestamp() == null ? new Date() : limitOrder.getTimestamp(),
          limitOrder.getLimitPrice()
      );
      newOrder.setOrderStatus(Order.OrderStatus.NEW);
      openOrders.put(id, newOrder);
      placedDates.put(id, new Date());
      updateTickerRegistry();
    }
    return strId;
  }

  private void updateTickerRegistry() {
    if (subscription != null) {
      try {
        subscription.dispose();
      } catch (Exception e) {
        LOGGER.error("Error unsubscribing to ticker stream", e);
      }
    }
    exchangeEventRegistry.changeSubscriptions(
      eventRegistryClientId,
      FluentIterable.from(openOrders.values()).transform(o -> MarketDataSubscription.create(
          TickerSpec.builder()
            .exchange(exchange)
            .counter(o.getCurrencyPair().counter.getCurrencyCode())
            .base(o.getCurrencyPair().base.getCurrencyCode())
            .build(),
          TICKER
      )).toSet()
    );
    subscription = exchangeEventRegistry.getTickers(eventRegistryClientId).subscribe(this::updateAgainstMarket);
  }

  private boolean isOpen(LimitOrder o) {
    return o.getStatus() != OrderStatus.CANCELED && o.getStatus() != OrderStatus.FILLED;
  }

  @Override
  public String placeStopOrder(StopOrder stopOrder) throws IOException {
    throw new NotAvailableFromExchangeException();
  }

  @Override
  public synchronized boolean cancelOrder(String orderId) throws IOException {
    final Long id = Long.valueOf(orderId);
    final LimitOrder limitOrder = openOrders.get(id);
    if (limitOrder == null) {
      throw new IllegalArgumentException("No such order: " + orderId);
    }
    if (!isOpen(limitOrder)) {
      return false;
    }
    limitOrder.setOrderStatus(Order.OrderStatus.CANCELED);
    updateTickerRegistry();
    return true;
  }

  @Override
  public boolean cancelOrder(CancelOrderParams params) throws IOException {
    if (!(params instanceof CancelOrderByIdParams)) {
      throw new ExchangeException(
          "You need to provide the order id to cancel an order.");
    }
    CancelOrderByIdParams paramId = (CancelOrderByIdParams) params;
    return cancelOrder(paramId.getOrderId());
  }

  @Override
  public UserTrades getTradeHistory(TradeHistoryParams params) throws IOException {
    throw new NotAvailableFromExchangeException();
  }

  @Override
  public TradeHistoryParams createTradeHistoryParams() {
    throw new NotAvailableFromExchangeException();
  }

  @Override
  public OpenOrdersParams createOpenOrdersParams() {
    throw new NotAvailableFromExchangeException();
  }

  @Override
  public void verifyOrder(LimitOrder limitOrder) {
    throw new NotAvailableFromExchangeException();
  }

  @Override
  public void verifyOrder(MarketOrder marketOrder) {
    throw new NotAvailableFromExchangeException();
  }

  @Override
  public Collection<Order> getOrder(String... orderIds) throws IOException {
    final Set<Long> ids = Arrays.asList(orderIds).stream().map(Long::valueOf).collect(Collectors.toSet());
    return openOrders.entrySet().stream().filter(e -> ids.contains(e.getKey())).map(Entry::getValue).collect(Collectors.toList());
  }

  /**
   * Mimics reality by ensuring some operations have an indeterminate completion time,
   * so trades might be out of date.
   */
  private void randomDelay() {
    try {
      Thread.sleep(random.nextInt(2000));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  /**
   * Handles a tick by updating any affected orders.
   */
  private synchronized void updateAgainstMarket(TickerEvent tickerEvent) {
    openOrders.values().stream()
      .filter(o -> o.getCurrencyPair().counter.getCurrencyCode().equals(tickerEvent.spec().counter()) &&
                   o.getCurrencyPair().base.getCurrencyCode().equals(tickerEvent.spec().base())
      )
      .forEach(order -> {
        switch (order.getType()) {
          case ASK:
            if (tickerEvent.ticker().getBid().compareTo(order.getLimitPrice()) >= 0) {
              order.setCumulativeAmount(order.getOriginalAmount());
              order.setOrderStatus(Order.OrderStatus.FILLED);
              return;
            }
            break;
          case BID:
            if (tickerEvent.ticker().getAsk().compareTo(order.getLimitPrice()) <= 0) {
              order.setCumulativeAmount(order.getOriginalAmount());
              order.setOrderStatus(Order.OrderStatus.FILLED);
              return;
            }
            break;
          default:
            throw new UnsupportedOperationException("Derivatives not supported");
        }
      });
  }


  @Singleton
  public static class Factory implements TradeServiceFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(Factory.class);

    private final ExchangeEventRegistry exchangeEventRegistry;

    private final LoadingCache<String, TradeService> services = CacheBuilder.newBuilder().initialCapacity(1000).build(new CacheLoader<String, TradeService>() {
      @Override
      public TradeService load(String exchange) throws Exception {
        LOGGER.warn("No API connection details.  Using paper trading.");
        return new PaperTradeService(exchange, exchangeEventRegistry);
      }
    });


    @Inject
    Factory(ExchangeEventRegistry exchangeEventRegistry) {
      this.exchangeEventRegistry = exchangeEventRegistry;
    }

    @Override
    public TradeService getForExchange(String exchange) {
      return services.getUnchecked(exchange);
    }
  }
}