package com.ExchangeDataLoader;

import com.DynamoManager;
import com.binance.api.client.BinanceApiClientFactory;
import com.binance.api.client.BinanceApiRestClient;
import com.binance.api.client.BinanceApiWebSocketClient;
import com.binance.api.client.domain.market.OrderBook;
import com.binance.api.client.domain.market.OrderBookEntry;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Most of this code is from the example, will have to refactor since it seems to not remove some items from the cache
 * resulting in hundreds of elements in the cache which as I learned is VERY expensive to write to the db
 */
public class DepthCache {

    private static final String BIDS  = "BIDS";
    private static final String ASKS  = "ASKS";
    private static DynamoManager dynamoManager = new DynamoManager();

    private long lastUpdateId;
    public long lastUpdateTime;

    private Map<String, NavigableMap<BigDecimal, BigDecimal>> depthCache;
    public final String symbol;

    public DepthCache(String symbol) {
        this.symbol = symbol;
        initializeDepthCache(symbol);
        startDepthEventStreaming(symbol);
    }

    /**
     * Initializes the depth cache by using the REST API.
     */
    private void initializeDepthCache(String symbol) {
        BinanceApiClientFactory factory = BinanceApiClientFactory.newInstance();
        BinanceApiRestClient client = factory.newRestClient();
        OrderBook orderBook = client.getOrderBook(symbol.toUpperCase(), 5);

        this.depthCache = new HashMap<>();
        this.lastUpdateId = orderBook.getLastUpdateId();

        NavigableMap<BigDecimal, BigDecimal> asks = new TreeMap<>(Comparator.reverseOrder());
        for (OrderBookEntry ask : orderBook.getAsks()) {
            asks.put(new BigDecimal(ask.getPrice()), new BigDecimal(ask.getQty()));
        }
        depthCache.put(ASKS, asks);

        NavigableMap<BigDecimal, BigDecimal> bids = new TreeMap<>(Comparator.reverseOrder());
        for (OrderBookEntry bid : orderBook.getBids()) {
            bids.put(new BigDecimal(bid.getPrice()), new BigDecimal(bid.getQty()));
        }
        depthCache.put(BIDS, bids);
    }

    /**
     * Begins streaming of depth events.
     */
    private void startDepthEventStreaming(String symbol) {
        BinanceApiClientFactory factory = BinanceApiClientFactory.newInstance();
        BinanceApiWebSocketClient client = factory.newWebSocketClient();

        client.onDepthEvent(symbol.toLowerCase(), response -> {
            if (response.getFinalUpdateId() > lastUpdateId) {
//                System.out.println(response);
                lastUpdateTime = response.getEventTime();
                lastUpdateId = response.getFinalUpdateId();
                updateOrderBook(getAsks(), response.getAsks());
                updateOrderBook(getBids(), response.getBids());
                // lazy way of resetting number of asks/bids, will need to refactor to use a list of lists
                // then just take the first 5-10 elements of each list
                if (getAsks().size() > 15){
                    initializeDepthCache(this.symbol);
                }
                saveDepthCache();
            }
        });
    }

    /**
     * Updates an order book (bids or asks) with a delta received from the server.
     *
     * Whenever the qty specified is ZERO, it means the price should was removed from the order book.
     */
    private void updateOrderBook(NavigableMap<BigDecimal, BigDecimal> lastOrderBookEntries, List<OrderBookEntry> orderBookDeltas) {
        for (OrderBookEntry orderBookDelta : orderBookDeltas) {
            BigDecimal price = new BigDecimal(orderBookDelta.getPrice());
            BigDecimal qty = new BigDecimal(orderBookDelta.getQty());
            if (qty.compareTo(BigDecimal.ZERO) == 0) {
                // qty=0 means remove this level
                lastOrderBookEntries.remove(price);
            } else {
                lastOrderBookEntries.put(price, qty);
            }
        }
    }

    public NavigableMap<BigDecimal, BigDecimal> getAsks() {
        return depthCache.get(ASKS);
    }

    public NavigableMap<BigDecimal, BigDecimal> getBids() {
        return depthCache.get(BIDS);
    }

    /**
     * @return the best ask in the order book
     */
    private Map.Entry<BigDecimal, BigDecimal> getBestAsk() {
        return getAsks().lastEntry();
    }

    /**
     * @return the best bid in the order book
     */
    private Map.Entry<BigDecimal, BigDecimal> getBestBid() {
        return getBids().firstEntry();
    }

    /**
     * @return a depth cache, containing two keys (ASKs and BIDs), and for each, an ordered list of book entries.
     */
    public Map<String, NavigableMap<BigDecimal, BigDecimal>> getDepthCache() {
        return depthCache;
    }


    /**
     *  Saves depth cache
     */
    private void saveDepthCache() {
        dynamoManager.updateDepthItem(this);
//        System.out.println(depthCache);
//        System.out.println("ASKS:");
//        getAsks().entrySet().forEach(entry -> System.out.println(toDepthCacheEntryString(entry)));
//        System.out.println("BIDS:");
//        getBids().entrySet().forEach(entry -> System.out.println(toDepthCacheEntryString(entry)));
//        System.out.println("BEST ASK: " + toDepthCacheEntryString(getBestAsk()));
//        System.out.println("BEST BID: " + toDepthCacheEntryString(getBestBid()));
//        System.out.println(getBids().keySet().size());
    }

    /**
     * Pretty prints an order book entry in the format "price / quantity".
     */
    private static String toDepthCacheEntryString(Map.Entry<BigDecimal, BigDecimal> depthCacheEntry) {
        return depthCacheEntry.getKey().toPlainString() + " / " + depthCacheEntry.getValue();
    }

    public static void main(String[] args) {
        dynamoManager.createPairsTable();
        new DepthCache("ETHBTC");
    }
}