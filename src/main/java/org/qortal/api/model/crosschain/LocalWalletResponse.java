package org.qortal.api.model.crosschain;

import java.util.List;
import java.util.Map;

/** Marks the public local-wallet JSON contract for its dedicated writer. */
public final class LocalWalletResponse {
    final Object payload;

    public LocalWalletResponse(Map<String, Object> context) {
        this.payload = context;
    }

    public LocalWalletResponse(List<Map<String, Object>> plans) {
        this.payload = plans;
    }
}
