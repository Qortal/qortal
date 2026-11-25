package org.qortal.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** Native wallet readiness, independent of the wallet's displayed balance. */
@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainSpendability {

	@Schema(description = "Whether the native wallet is ready to attempt spending; does not guarantee sufficient funds")
	public boolean spendable;

	@Schema(description = "Whether the native wallet requires a full rescan before spending")
	public boolean rescan_required;

	public long target_height;
	public long anchor_height;
	public long validated_anchor_height;
	public boolean repair_queued;

	@Schema(description = "Native readiness reason, for example ERR_RESCAN_REQUIRED")
	public String reason_code;

	public PirateChainSpendability() {
	}
}
