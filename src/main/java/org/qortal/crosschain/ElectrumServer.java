package org.qortal.crosschain;

import org.qortal.crypto.TrustlessSSLSocketFactory;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.regex.Pattern;

public class ElectrumServer {

    private final Object serverLock = new Object();
    private ChainableServer server;
    private Socket socket;
    private Scanner scanner;
    private int nextId = 1;

    private ChainableServerConnectionRecorder recorder;

    private ElectrumServer() {

    }

    public static ElectrumServer createInstance(ChainableServer server, SocketAddress endpoint, int timeout, ChainableServerConnectionRecorder recorder) throws IOException {
        ElectrumServer instance = new ElectrumServer();

        instance.init( server, endpoint, timeout, recorder );

        return instance;
    }

    private void init(ChainableServer server, SocketAddress endpoint, int timeout, ChainableServerConnectionRecorder recorder) throws IOException {
        this.server = server;

        this.socket = new Socket();
        this.socket.connect(endpoint, timeout);
        this.socket.setSoTimeout(timeout);
        this.socket.setTcpNoDelay(true);

        if (this.server.getConnectionType() == ElectrumX.Server.ConnectionType.SSL) {
            SSLSocketFactory factory = TrustlessSSLSocketFactory.getSocketFactory();
            this.socket = factory.createSocket(this.socket, server.getHostName(), server.getPort(), true);
        }

        this.scanner = new Scanner(this.socket.getInputStream());
        this.scanner.useDelimiter("\n");

        this.recorder = recorder;
    }

    public long averageResponseTime() {
        return server.averageResponseTime();
    }

    public int incrementNextId() {
        return nextId++;
    }

    public String write(byte[] bytes, String id) throws IOException {

        synchronized (this.serverLock) {
            if( this.socket == null ) {
                throw new IOException("socket is closed");
            }

            this.socket.getOutputStream().write(bytes);

            String response = this.scanner.next();

            while( !response.contains( id )) {

                response = this.scanner.next();
            }

            return response;
        }
    }

    public void addResponseTime(long responseTime) {
        server.addResponseTime(responseTime);
    }

    public ChainableServer getServer() {
        return this.server;
    }

    public Optional<ChainableServerConnection> closeServer(ChainableServer server, String requestedBy, String notes) {

        ChainableServerConnection chainableServerConnection
                = this.recorder.recordConnection(server, requestedBy, false, true, notes);

        if (this.socket != null && !this.socket.isClosed())
            try {
                this.socket.close();
            } catch (IOException e) {
                // We did try...
            }

        this.socket = null;
        this.scanner = null;

        return Optional.of( chainableServerConnection );
    }

    /** Closes connection to currently connected server (if any). */
    public Optional<ChainableServerConnection> closeServer(String requestedBy, String notes) {
        synchronized (this.serverLock) {
            return this.closeServer(this.server, requestedBy, notes);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ElectrumServer that = (ElectrumServer) o;
        return server.equals(that.server);
    }

    @Override
    public int hashCode() {
        return Objects.hash(server);
    }
}
