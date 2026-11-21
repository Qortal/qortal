package org.qortal.api;

import org.qortal.utils.Amounts;

import javax.xml.bind.annotation.adapters.XmlAdapter;
import java.math.BigDecimal;

public class AmountTypeAdapter extends XmlAdapter<String, Long> {

	@Override
	public Long unmarshal(String input) throws Exception {
		if (input == null)
			return null;

		return new BigDecimal(input).movePointRight(8).longValueExact();
	}

	@Override
	public String marshal(Long output) throws Exception {
		if (output == null)
			return null;

		return Amounts.prettyAmount(output);
	}

}
