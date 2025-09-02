package org.qortal.crosschain;

import org.libdohj.params.LitecoinMainNetParams;

public class LitecoinMainNetParamsP2ShOverride extends LitecoinMainNetParams {

    public LitecoinMainNetParamsP2ShOverride(int p2shHeader) {
        super();

        this.p2shHeader = p2shHeader;
    }
}
