package com.echomine.xmpp;

import java.io.IOException;
import java.net.Socket;

import com.echomine.net.ConnectionContext;
import com.echomine.net.HandshakeFailedException;
import com.echomine.xmpp.impl.XMPPConnectionHandler;

/**
 * This mock socket handler will emulate the server by simply streaming out
 * predefined xml data. It will gulp down the incoming xml data for retrieval
 * and assertion testing afterwards. This class is useful for testing connection
 * logic.
 */
public class MockXMPPConnectionHandler extends XMPPConnectionHandler {
    boolean failHandshake;
    String sessionId;
    String version;

    public void handshake(Socket socket, ConnectionContext connCtx) throws HandshakeFailedException {
        if (failHandshake)
            throw new HandshakeFailedException("Simulated Handshake failure");
        sessCtx.setHostName(socket.getInetAddress().getHostName());
        sessCtx.setStreamId(sessionId);
        sessCtx.setVersion(version);
        state = RunningState.RUNNING;
    }

    public void handle(Socket socket, ConnectionContext connCtx) throws IOException {
        while (state != RunningState.STOPPED)
            Thread.yield();
    }

    public void shutdown() {
        super.shutdown();
    }

    public void setFailHandshake(boolean fail) {
        this.failHandshake = fail;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
