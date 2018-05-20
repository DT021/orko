package com.grahamcrockford.oco.marketdata;

import static com.grahamcrockford.oco.marketdata.MarketDataType.TICKER;

import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.grahamcrockford.oco.spi.TickerSpec;

import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;


@Singleton
class ExchangeEventBus implements ExchangeEventRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeEventBus.class);

  private final ConcurrentMap<MarketDataSubscription, AtomicInteger> allSubscriptions = Maps.newConcurrentMap();
  private final Multimap<String, MarketDataSubscription> subscriptionsBySubscriber = MultimapBuilder.hashKeys().hashSetValues().build();

  @Deprecated
  private final Multimap<String, Disposable> tickerDisposables = MultimapBuilder.hashKeys().hashSetValues().build();

  private final StampedLock rwLock = new StampedLock();
  private final MarketDataSubscriptionManager marketDataSubscriptionManager;

  @Inject
  ExchangeEventBus(MarketDataSubscriptionManager marketDataSubscriptionManager) {
    this.marketDataSubscriptionManager = marketDataSubscriptionManager;
  }

  @Override
  public void changeSubscriptions(String subscriberId, Set<MarketDataSubscription> targetSubscriptions) {

    LOGGER.info("Changing subscriptions for subscriber {} to {}", subscriberId, targetSubscriptions);

    long stamp = rwLock.writeLock();
    try {

      boolean updated = false;

      Set<MarketDataSubscription> currentForSubscriber = ImmutableSet.copyOf(subscriptionsBySubscriber.get(subscriberId));
      Set<MarketDataSubscription> toRemove = Sets.difference(currentForSubscriber, targetSubscriptions);
      Set<MarketDataSubscription> toAdd = Sets.difference(targetSubscriptions, currentForSubscriber);

      for (MarketDataSubscription sub : toRemove) {
        LOGGER.info("... unsubscribing {}", sub);
        if (unsubscribe(subscriberId, sub)) {
          LOGGER.info("   ... removing global subscription");
          updated = true;
        }
      }

      for (MarketDataSubscription sub : toAdd) {
        LOGGER.info("... subscribing {}", sub);
        if (subscribe(subscriberId, sub)) {
          LOGGER.info("   ... new global subscription");
          updated = true;
        }
      }

      if (updated) {
        updateSubscriptions();
      }

    } finally {
      rwLock.unlockWrite(stamp);
    }
  }

  @Override
  public Flowable<TickerEvent> getTickers(String subscriberId) {
    return getStream(subscriberId, MarketDataType.TICKER, marketDataSubscriptionManager::getTicker);
  }

  @Override
  public Flowable<OpenOrdersEvent> getOpenOrders(String subscriberId) {
    return getStream(subscriberId, MarketDataType.OPEN_ORDERS, marketDataSubscriptionManager::getOpenOrders);
  }

  @Override
  public Flowable<OrderBookEvent> getOrderBooks(String subscriberId) {
    return getStream(subscriberId, MarketDataType.ORDERBOOK, marketDataSubscriptionManager::getOrderBook);
  }

  @Override
  public Flowable<TradeEvent> getTrades(String subscriberId) {
    return getStream(subscriberId, MarketDataType.TRADES, marketDataSubscriptionManager::getTrades);
  }

  @Override
  public void registerTicker(TickerSpec tickerSpec, String subscriberId, Consumer<TickerEvent> callback) {
    changeSubscriptions(subscriberId, MarketDataSubscription.create(tickerSpec, TICKER));
    long stamp = rwLock.writeLock();
    try {
      tickerDisposables.put(subscriberId, getTickers(subscriberId).subscribe(e -> callback.accept(e)));
    } finally {
      rwLock.unlockWrite(stamp);
    }
  }

  @Override
  public void unregisterTicker(TickerSpec tickerSpec, String subscriberId) {
    long stamp = rwLock.writeLock();
    try {
      tickerDisposables.get(subscriberId).forEach(d -> {
        try {
          d.dispose();
        } catch (Exception e) {
          LOGGER.error("Error disposing of subscription", e);
        }
      });
      tickerDisposables.removeAll(subscriberId);
    } finally {
      rwLock.unlockWrite(stamp);
    }
    clearSubscriptions(subscriberId);
  }

  private <T> Flowable<T> getStream(String subscriberId, MarketDataType marketDataType, Function<TickerSpec, Flowable<T>> source) {
    long stamp = rwLock.readLock();
    try {
      FluentIterable<Flowable<T>> streams = FluentIterable
          .from(subscriptionsBySubscriber.get(subscriberId))
          .filter(s -> s.type().equals(marketDataType))
          .transform(sub -> source.apply(sub.spec()).onBackpressureLatest());
      return Flowable.merge(streams);
    } finally {
      rwLock.unlockRead(stamp);
    }
  }

  private <T> boolean subscribe(String subscriberId, MarketDataSubscription subscription) {
    if (subscriptionsBySubscriber.put(subscriberId, subscription)) {
      return allSubscriptions.computeIfAbsent(subscription, s -> new AtomicInteger(0)).incrementAndGet() == 1;
    } else {
      LOGGER.info("   ... subscriber already subscribed");
      return false;
    }
  }

  private <T> boolean unsubscribe(String subscriberId, MarketDataSubscription subscription) {
    if (subscriptionsBySubscriber.remove(subscriberId, subscription)) {
      AtomicInteger refCount = allSubscriptions.get(subscription);
      if (refCount == null) {
        LOGGER.warn("   ... Refcount is unset for live subscription: {}/{}", subscriberId, subscription);
        return true;
      }
      LOGGER.info("   ... refcount is {}", refCount.get());
      if (refCount.decrementAndGet() == 0) {
        LOGGER.debug("   ... refcount set to {}", refCount.get());
        allSubscriptions.remove(subscription);
        return true;
      } else {
        LOGGER.debug("   ... other subscribers still holding it open");
        return false;
      }
    } else {
      LOGGER.warn("   ... subscriber {} not actually subscribed to {}", subscriberId, subscription);
      return false;
    }
  }

  private void updateSubscriptions() {
    marketDataSubscriptionManager.updateSubscriptions(allSubscriptions.keySet());
  }
}