/*
 * Copyright 2020 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.orderbook;

import org.agrona.BitUtil;
import org.agrona.DirectBuffer;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public interface IOrderBook<S extends ISymbolSpecification> extends StateHash {

    /**
     * Process new order.
     * Depending on price specified (whether the order is marketable),
     * order will be matched to existing opposite GTC orders from the order book.
     * In case of remaining volume (order was not matched completely):
     * IOC - reject it as partially filled.
     * GTC - place as a new limit order into th order book.
     * <p>
     * Rejection chain attached in case of error (to simplify risk handling)
     * <p>
     */
    void newOrder(DirectBuffer buffer, int offset, long timestamp);

    /**
     * Cancel order completely.
     * <p>
     * fills cmd.action  with original original order action
     * <p>
     */
    void cancelOrder(DirectBuffer buffer, int offset);

    /**
     * Decrease the size of the order by specific number of lots
     * <p>
     * fills cmd.action  with original  order action
     * <p>
     */
    void reduceOrder(DirectBuffer buffer, int offset);

    /**
     * Move order
     * <p>
     * newPrice - new price (if 0 or same - order will not moved)
     * fills cmd.action  with original original order action
     * <p>
     */
    void moveOrder(DirectBuffer buffer, int offset);


    void sendL2Snapshot(DirectBuffer buffer, int offset);

    IOrder getOrderById(long orderId);

    /**
     * Search for all orders for specified user.<p>
     * Slow, because order book do not maintain uid-to-order index.<p>
     * Produces garbage.<p>
     * Orders must be processed before doing any other mutable call.<p>
     *
     * @param uid user id
     * @return list of orders
     */
    List<IOrder> findUserOrders(long uid);

    S getSymbolSpec();

    Stream<? extends IOrder> askOrdersStream(boolean sorted);

    Stream<? extends IOrder> bidOrdersStream(boolean sorted);

    // testing only - validateInternalState without changing state
    void verifyInternalState();

    /**
     * State hash for order books is implementation-agnostic
     * Look {@link IOrderBook#verifyInternalState} for full internal state validation for de-serialized objects
     *
     * @return state hash code
     */
    @Override
    default int stateHash() {

        // log.debug("State hash of {}", orderBook.getClass().getSimpleName());
        // log.debug("  Ask orders stream: {}", orderBook.askOrdersStream(true).collect(Collectors.toList()));
        // log.debug("  Ask orders hash: {}", stateHashStream(orderBook.askOrdersStream(true)));
        // log.debug("  Bid orders stream: {}", orderBook.bidOrdersStream(true).collect(Collectors.toList()));
        // log.debug("  Bid orders hash: {}", stateHashStream(orderBook.bidOrdersStream(true)));
        // log.debug("  getSymbolSpec: {}", orderBook.getSymbolSpec());
        // log.debug("  getSymbolSpec hash: {}", orderBook.getSymbolSpec().stateHash());

        return Objects.hash(
                stateHashStream(askOrdersStream(true)),
                stateHashStream(bidOrdersStream(true)),
                getSymbolSpec().stateHash());
    }

    static int stateHashStream(final Stream<? extends StateHash> stream) {
        int h = 0;
        final Iterator<? extends StateHash> iterator = stream.iterator();
        while (iterator.hasNext()) {
            h = h * 31 + iterator.next().stateHash();
        }
        return h;
    }

    /**
     * Obtain current L2 Market Data snapshot
     *
     * @param size max size for each part (ask, bid)
     * @return L2 Market Data snapshot
     */
    default L2MarketData getL2MarketDataSnapshot(final int size) {
        final int asksSize = getTotalAskBuckets(size);
        final int bidsSize = getTotalBidBuckets(size);
        final L2MarketData data = new L2MarketData(asksSize, bidsSize);
        fillAsks(asksSize, data);
        fillBids(bidsSize, data);
        return data;
    }

    default L2MarketData getL2MarketDataSnapshot() {
        return getL2MarketDataSnapshot(Integer.MAX_VALUE);
    }

    void fillAsks(int size, L2MarketData data);

    void fillBids(int size, L2MarketData data);

    int getTotalAskBuckets(int limit);

    int getTotalBidBuckets(int limit);


    /*
     * Order book command codes
     */
    byte COMMAND_PLACE_ORDER = 1;
    byte COMMAND_CANCEL_ORDER = 2;
    byte COMMAND_MOVE_ORDER = 3;
    byte COMMAND_REDUCE_ORDER = 4;
    byte QUERY_ORDER_BOOK = 5;

    /*
     * Error codes
     */
    short RESULT_SUCCESS = 0;
    short RESULT_UNKNOWN_ORDER_ID = 1;
    short RESULT_UNSUPPORTED_COMMAND = 2;
    short RESULT_INVALID_ORDER_BOOK_ID = 3;
    short RESULT_INCORRECT_ORDER_SIZE = 4;
    short RESULT_INCORRECT_REDUCE_SIZE = 5;
    short RESULT_MOVE_FAILED_PRICE_OVER_RISK_LIMIT = 6;
    short RESULT_UNSUPPORTED_ORDER_TYPE = 7;
    short RESULT_INCORRECT_L2_SIZE_LIMIT = 8;

    short RESULT_OFFSET_REDUCE_EVT_FLAG = 1 << 14;
    short RESULT_OFFSET_TAKER_ACTION_BID_FLAG = 1 << 13;
    short RESULT_OFFSET_TAKE_ORDER_COMPLETED_FLAG = 1 << 12;
    short RESULT_MASK = (1 << 12) - 1;

    /*
     * Incoming message offsets
     *
     */
    // Place
    int PLACE_OFFSET_UID = 0;
    int PLACE_OFFSET_ORDER_ID = PLACE_OFFSET_UID + BitUtil.SIZE_OF_LONG;
    int PLACE_OFFSET_PRICE = PLACE_OFFSET_ORDER_ID + BitUtil.SIZE_OF_LONG;
    int PLACE_OFFSET_RESERVED_BID_PRICE = PLACE_OFFSET_PRICE + BitUtil.SIZE_OF_LONG;
    int PLACE_OFFSET_SIZE = PLACE_OFFSET_RESERVED_BID_PRICE + BitUtil.SIZE_OF_LONG;
    int PLACE_OFFSET_USER_COOKIE = PLACE_OFFSET_SIZE + BitUtil.SIZE_OF_LONG;
    int PLACE_OFFSET_ACTION = PLACE_OFFSET_USER_COOKIE + BitUtil.SIZE_OF_INT;
    int PLACE_OFFSET_TYPE = PLACE_OFFSET_ACTION + BitUtil.SIZE_OF_BYTE;
    int PLACE_OFFSET_END = PLACE_OFFSET_TYPE + BitUtil.SIZE_OF_BYTE;

    // Cancel
    int CANCEL_OFFSET_UID = 0;
    int CANCEL_OFFSET_ORDER_ID = CANCEL_OFFSET_UID + BitUtil.SIZE_OF_LONG;
    int CANCEL_OFFSET_END = CANCEL_OFFSET_ORDER_ID + BitUtil.SIZE_OF_LONG;

    // Reduce
    int REDUCE_OFFSET_UID = 0;
    int REDUCE_OFFSET_ORDER_ID = REDUCE_OFFSET_UID + BitUtil.SIZE_OF_LONG;
    int REDUCE_OFFSET_SIZE = REDUCE_OFFSET_ORDER_ID + BitUtil.SIZE_OF_LONG;
    int REDUCE_OFFSET_END = REDUCE_OFFSET_SIZE + BitUtil.SIZE_OF_LONG;

    // Move
    int MOVE_OFFSET_UID = 0;
    int MOVE_OFFSET_ORDER_ID = MOVE_OFFSET_UID + BitUtil.SIZE_OF_LONG;
    int MOVE_OFFSET_PRICE = MOVE_OFFSET_ORDER_ID + BitUtil.SIZE_OF_LONG;
    int MOVE_OFFSET_END = MOVE_OFFSET_PRICE + BitUtil.SIZE_OF_LONG;

    /*
     * Outgoing message offset
     */

    // trade event
    int RESPONSE_OFFSET_TEVT_MAKER_ORDER_ID = 0;
    int RESPONSE_OFFSET_TEVT_MAKER_UID = RESPONSE_OFFSET_TEVT_MAKER_ORDER_ID + BitUtil.SIZE_OF_LONG;
    int RESPONSE_OFFSET_TEVT_PRICE = RESPONSE_OFFSET_TEVT_MAKER_UID + BitUtil.SIZE_OF_LONG;
    int RESPONSE_OFFSET_TEVT_RESERV_BID_PRICE = RESPONSE_OFFSET_TEVT_PRICE + BitUtil.SIZE_OF_LONG;
    int RESPONSE_OFFSET_TEVT_TRADE_VOL = RESPONSE_OFFSET_TEVT_RESERV_BID_PRICE + BitUtil.SIZE_OF_LONG;
    int RESPONSE_OFFSET_TEVT_MAKER_ORDER_COMPLETED = RESPONSE_OFFSET_TEVT_TRADE_VOL + BitUtil.SIZE_OF_LONG;
    int RESPONSE_OFFSET_TEVT_END = RESPONSE_OFFSET_TEVT_MAKER_ORDER_COMPLETED + BitUtil.SIZE_OF_BYTE;

    // reduce event
    int RESPONSE_OFFSET_REVT_PRICE = 0;
    int RESPONSE_OFFSET_REVT_RESERV_BID_PRICE = RESPONSE_OFFSET_REVT_PRICE + BitUtil.SIZE_OF_LONG;
    int RESPONSE_OFFSET_REVT_REDUCED_VOL = RESPONSE_OFFSET_REVT_RESERV_BID_PRICE + BitUtil.SIZE_OF_LONG;
    int RESPONSE_OFFSET_REVT_END = RESPONSE_OFFSET_REVT_REDUCED_VOL + BitUtil.SIZE_OF_LONG;

    // L2 data header // TODO add symbolId and time ()
    int RESPONSE_OFFSET_L2_RESULT = BitUtil.SIZE_OF_SHORT;
    int RESPONSE_OFFSET_L2_BID_RECORDS = RESPONSE_OFFSET_L2_RESULT + BitUtil.SIZE_OF_INT;
    int RESPONSE_OFFSET_L2_ASK_RECORDS = RESPONSE_OFFSET_L2_BID_RECORDS + BitUtil.SIZE_OF_INT;

    // L2 data record
    int RESPONSE_OFFSET_L2_RECORD_PRICE = 0;
    int RESPONSE_OFFSET_L2_RECORD_VOLUME = RESPONSE_OFFSET_L2_RECORD_PRICE + BitUtil.SIZE_OF_LONG;
    int RESPONSE_OFFSET_L2_RECORD_ORDERS = RESPONSE_OFFSET_L2_RECORD_VOLUME + BitUtil.SIZE_OF_LONG;
    int RESPONSE_OFFSET_L2_RECORD_END = RESPONSE_OFFSET_L2_RECORD_ORDERS + BitUtil.SIZE_OF_INT;

    /*
     * Order types
     */

    byte ORDER_TYPE_GTC = 0; // Good till Cancel - equivalent to regular limit order

    // Immediate or Cancel - equivalent to strict-risk market order
    byte ORDER_TYPE_IOC = 1; // with price cap
    byte ORDER_TYPE_IOC_BUDGET = 2; // with total amount cap

    // Fill or Kill - execute immediately completely or not at all
    byte ORDER_TYPE_FOK = 3; // with price cap
    byte ORDER_TYPE_FOK_BUDGET = 4; // total amount cap

}
