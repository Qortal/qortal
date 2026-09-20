package org.qortal.controller.tradebot;

import java.util.Map;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public class TradeStates {
    public enum State implements TradeBot.StateNameAndValueSupplier {
        BOB_WAITING_FOR_AT_CONFIRM(10, false, false),
        BOB_WAITING_FOR_MESSAGE(15, true, true),
        BOB_WAITING_FOR_AT_REDEEM(25, true, true),
        BOB_DONE(30, false, false),
        BOB_REFUNDED(35, false, false),

        ALICE_WAITING_FOR_FUNDING(80, true, true),
        ALICE_WAITING_FOR_AT_LOCK(85, true, true),
        ALICE_DONE(95, false, false),
        ALICE_REFUNDING_A(105, true, true),
        ALICE_REFUNDED(110, false, false);

        private static final Map<Integer, State> map = stream(State.values()).collect(toMap(state -> state.value, state -> state));

        public final int value;
        public final boolean requiresAtData;
        public final boolean requiresTradeData;

        State(int value, boolean requiresAtData, boolean requiresTradeData) {
            this.value = value;
            this.requiresAtData = requiresAtData;
            this.requiresTradeData = requiresTradeData;
        }

        public static State valueOf(int value) {
            return map.get(value);
        }

        @Override
        public String getState() {
            return this.name();
        }

        @Override
        public int getStateValue() {
            return this.value;
        }
    }
}
