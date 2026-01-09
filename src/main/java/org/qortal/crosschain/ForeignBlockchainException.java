package org.qortal.crosschain;

@SuppressWarnings("serial")
public class ForeignBlockchainException extends Exception {

	public ForeignBlockchainException() {
		super();
	}

	public ForeignBlockchainException(String message) {
		super(message);
	}

	public ForeignBlockchainException(String message, Throwable cause) {
		super(message, cause);
	}

	public static class NetworkException extends ForeignBlockchainException {
		private final Integer daemonErrorCode;
		private final transient Object server;

		public NetworkException() {
			super();
			this.daemonErrorCode = null;
			this.server = null;
		}

		public NetworkException(String message) {
			super(message);
			this.daemonErrorCode = null;
			this.server = null;
		}

		public NetworkException(int errorCode, String message) {
			super(message);
			this.daemonErrorCode = errorCode;
			this.server = null;
		}

		public NetworkException(String message, Object server) {
			super(message);
			this.daemonErrorCode = null;
			this.server = server;
		}

		public NetworkException(int errorCode, String message, Object server) {
			super(message);
			this.daemonErrorCode = errorCode;
			this.server = server;
		}

		public Integer getDaemonErrorCode() {
			return this.daemonErrorCode;
		}

		public Object getServer() {
			return this.server;
		}
	}

	public static class NotFoundException extends ForeignBlockchainException {
		public NotFoundException() {
			super();
		}

		public NotFoundException(String message) {
			super(message);
		}
	}

	public static class InsufficientFundsException extends ForeignBlockchainException {
		public InsufficientFundsException() {
			super();
		}

		public InsufficientFundsException(String message) {
			super(message);
		}
	}

}
