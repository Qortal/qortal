package org.qortal.data.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.json.JSONObject;
import org.qortal.utils.Base58;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotData {

	private byte[] tradePrivateKey;

	private String acctName;
	private String tradeState;

	// Internal use - not shown via API
	@XmlTransient
	@Schema(hidden = true)
	private int tradeStateValue;

	private String creatorAddress;
	private String atAddress;

	private long timestamp;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long qortAmount;

	private byte[] tradeNativePublicKey;
	private byte[] tradeNativePublicKeyHash;
	String tradeNativeAddress;

	private byte[] secret;
	private byte[] hashOfSecret;

	private String foreignBlockchain;
	private byte[] tradeForeignPublicKey;
	private byte[] tradeForeignPublicKeyHash;

	@Deprecated
	@Schema(description = "DEPRECATED: use foreignAmount instead", type = "number")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long bitcoinAmount;

	@Schema(description = "amount in foreign blockchain currency", type = "number")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long foreignAmount;

	// Never expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private String foreignKey;

	private byte[] lastTransactionSignature;
	private Integer lockTimeA;

	// Null means the original reserve is unknown (legacy trades).
	private Long fundingFee;
	private int fundingOutputIndex = 0;

	// Could be Bitcoin or Qortal...
	private byte[] receivingAccountInfo;

	protected TradeBotData() {
		/* JAXB */
	}

	public TradeBotData(byte[] tradePrivateKey, String acctName, String tradeState, int tradeStateValue,
			String creatorAddress, String atAddress,
			long timestamp, long qortAmount,
			byte[] tradeNativePublicKey, byte[] tradeNativePublicKeyHash, String tradeNativeAddress,
			byte[] secret, byte[] hashOfSecret,
			String foreignBlockchain, byte[] tradeForeignPublicKey, byte[] tradeForeignPublicKeyHash,
			long foreignAmount, String foreignKey,
			byte[] lastTransactionSignature, Integer lockTimeA, byte[] receivingAccountInfo) {
		this.tradePrivateKey = tradePrivateKey;
		this.acctName = acctName;
		this.tradeState = tradeState;
		this.tradeStateValue = tradeStateValue;
		this.creatorAddress = creatorAddress;
		this.atAddress = atAddress;
		this.timestamp = timestamp;
		this.qortAmount = qortAmount;
		this.tradeNativePublicKey = tradeNativePublicKey;
		this.tradeNativePublicKeyHash = tradeNativePublicKeyHash;
		this.tradeNativeAddress = tradeNativeAddress;
		this.secret = secret;
		this.hashOfSecret = hashOfSecret;
		this.foreignBlockchain = foreignBlockchain;
		this.tradeForeignPublicKey = tradeForeignPublicKey;
		this.tradeForeignPublicKeyHash = tradeForeignPublicKeyHash;
		// deprecated copy
		this.bitcoinAmount = foreignAmount;
		this.foreignAmount = foreignAmount;
		this.foreignKey = foreignKey;
		this.lastTransactionSignature = lastTransactionSignature;
		this.lockTimeA = lockTimeA;
		this.receivingAccountInfo = receivingAccountInfo;
	}

	public byte[] getTradePrivateKey() {
		return this.tradePrivateKey;
	}

	public String getAcctName() {
		return this.acctName;
	}

	public String getState() {
		return this.tradeState;
	}

	public void setState(String state) {
		this.tradeState = state;
	}

	public int getStateValue() {
		return this.tradeStateValue;
	}

	public void setStateValue(int stateValue) {
		this.tradeStateValue = stateValue;
	}

	public String getCreatorAddress() {
		return this.creatorAddress;
	}

	public String getAtAddress() {
		return this.atAddress;
	}

	public void setAtAddress(String atAddress) {
		this.atAddress = atAddress;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	public long getQortAmount() {
		return this.qortAmount;
	}

	public byte[] getTradeNativePublicKey() {
		return this.tradeNativePublicKey;
	}

	public byte[] getTradeNativePublicKeyHash() {
		return this.tradeNativePublicKeyHash;
	}

	public String getTradeNativeAddress() {
		return this.tradeNativeAddress;
	}

	public byte[] getSecret() {
		return this.secret;
	}

	public byte[] getHashOfSecret() {
		return this.hashOfSecret;
	}

	public String getForeignBlockchain() {
		return this.foreignBlockchain;
	}

	public byte[] getTradeForeignPublicKey() {
		return this.tradeForeignPublicKey;
	}

	public byte[] getTradeForeignPublicKeyHash() {
		return this.tradeForeignPublicKeyHash;
	}

	public long getForeignAmount() {
		return this.foreignAmount;
	}

	public String getForeignKey() {
		return this.foreignKey;
	}

	public byte[] getLastTransactionSignature() {
		return this.lastTransactionSignature;
	}

	@javax.xml.bind.annotation.XmlElement
	@Schema(description = "For ARRR refund terminals, whether Core verified the foreign-chain confirmation depth")
	public Boolean getForeignRefundConfirmed() {
		if (!"PIRATECHAIN".equals(this.foreignBlockchain) || !"ALICE_REFUNDED".equals(this.tradeState)) return null;
		return org.qortal.crosschain.PirateChainRefundState.isConfirmed(this.lastTransactionSignature);
	}

	public void setLastTransactionSignature(byte[] lastTransactionSignature) {
		this.lastTransactionSignature = lastTransactionSignature;
	}

	public Integer getLockTimeA() {
		return this.lockTimeA;
	}

	public void setLockTimeA(Integer lockTimeA) {
		this.lockTimeA = lockTimeA;
	}

	public Long getFundingFee() {
		return this.fundingFee;
	}

	public void setFundingFee(Long fundingFee) {
		if (fundingFee != null && fundingFee < 0) throw new IllegalArgumentException("Negative funding fee");
		this.fundingFee = fundingFee;
	}

	public int getFundingOutputIndex() {
		return this.fundingOutputIndex;
	}

	public void setFundingOutputIndex(int fundingOutputIndex) {
		if (fundingOutputIndex < 0) throw new IllegalArgumentException("Negative funding output index");
		this.fundingOutputIndex = fundingOutputIndex;
	}

	public byte[] getReceivingAccountInfo() {
		return this.receivingAccountInfo;
	}

	public JSONObject toJson() {
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("tradePrivateKey", Base58.encode(this.getTradePrivateKey()));
		jsonObject.put("acctName", this.getAcctName());
		jsonObject.put("tradeState", this.getState());
		jsonObject.put("tradeStateValue", this.getStateValue());
		jsonObject.put("creatorAddress", this.getCreatorAddress());
		jsonObject.put("atAddress", this.getAtAddress());
		jsonObject.put("timestamp", this.getTimestamp());
		jsonObject.put("qortAmount", this.getQortAmount());
		if (this.getTradeNativePublicKey() != null) jsonObject.put("tradeNativePublicKey", Base58.encode(this.getTradeNativePublicKey()));
		if (this.getTradeNativePublicKeyHash() != null) jsonObject.put("tradeNativePublicKeyHash", Base58.encode(this.getTradeNativePublicKeyHash()));
		jsonObject.put("tradeNativeAddress", this.getTradeNativeAddress());
		if (this.getSecret() != null) jsonObject.put("secret", Base58.encode(this.getSecret()));
		if (this.getHashOfSecret() != null) jsonObject.put("hashOfSecret", Base58.encode(this.getHashOfSecret()));
		jsonObject.put("foreignBlockchain", this.getForeignBlockchain());
		if (this.getTradeForeignPublicKey() != null) jsonObject.put("tradeForeignPublicKey", Base58.encode(this.getTradeForeignPublicKey()));
		if (this.getTradeForeignPublicKeyHash() != null) jsonObject.put("tradeForeignPublicKeyHash", Base58.encode(this.getTradeForeignPublicKeyHash()));
		jsonObject.put("foreignKey", this.getForeignKey());
        jsonObject.put("foreignAmount", this.getForeignAmount());
		if (this.getLastTransactionSignature() != null) jsonObject.put("lastTransactionSignature", Base58.encode(this.getLastTransactionSignature()));
        jsonObject.put("lockTimeA", this.getLockTimeA());
		if (this.getReceivingAccountInfo() != null) jsonObject.put("receivingAccountInfo", Base58.encode(this.getReceivingAccountInfo()));
		jsonObject.put("fundingFee", this.fundingFee);
		jsonObject.put("fundingOutputIndex", this.fundingOutputIndex);
		return jsonObject;
	}

	public static TradeBotData fromJson(JSONObject json) {
		TradeBotData trade = new TradeBotData(
				json.isNull("tradePrivateKey") ? null : Base58.decode(json.getString("tradePrivateKey")),
				json.isNull("acctName") ? null : json.getString("acctName"),
				json.isNull("tradeState") ? null : json.getString("tradeState"),
				json.isNull("tradeStateValue") ? null : json.getInt("tradeStateValue"),
				json.isNull("creatorAddress") ? null : json.getString("creatorAddress"),
				json.isNull("atAddress") ? null : json.getString("atAddress"),
				json.isNull("timestamp") ? null : json.getLong("timestamp"),
				json.isNull("qortAmount") ? null : json.getLong("qortAmount"),
				json.isNull("tradeNativePublicKey") ? null : Base58.decode(json.getString("tradeNativePublicKey")),
				json.isNull("tradeNativePublicKeyHash") ? null : Base58.decode(json.getString("tradeNativePublicKeyHash")),
				json.isNull("tradeNativeAddress") ? null : json.getString("tradeNativeAddress"),
				json.isNull("secret") ? null : Base58.decode(json.getString("secret")),
				json.isNull("hashOfSecret") ? null : Base58.decode(json.getString("hashOfSecret")),
				json.isNull("foreignBlockchain") ? null : json.getString("foreignBlockchain"),
				json.isNull("tradeForeignPublicKey") ? null : Base58.decode(json.getString("tradeForeignPublicKey")),
				json.isNull("tradeForeignPublicKeyHash") ? null : Base58.decode(json.getString("tradeForeignPublicKeyHash")),
				json.isNull("foreignAmount") ? null : json.getLong("foreignAmount"),
				json.isNull("foreignKey") ? null : json.getString("foreignKey"),
				json.isNull("lastTransactionSignature") ? null : Base58.decode(json.getString("lastTransactionSignature")),
				json.isNull("lockTimeA") ? null : json.getInt("lockTimeA"),
				json.isNull("receivingAccountInfo") ? null : Base58.decode(json.getString("receivingAccountInfo"))
		);
		trade.setFundingFee(json.isNull("fundingFee") ? null : json.getLong("fundingFee"));
		trade.setFundingOutputIndex(json.optInt("fundingOutputIndex", 0));
		return trade;
	}

	// Mostly for debugging
	public String toString() {
		return String.format("%s: %s (%d)", this.atAddress, this.tradeState, this.tradeStateValue);
	}

}
