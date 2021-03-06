package com.echomine.xmpp.stream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLSocket;

import com.echomine.net.MockSSLSocket;
import com.echomine.xmpp.BaseStreamTestCase;
import com.echomine.xmpp.XMPPException;

/**
 * This tests the TLS handshaking feature. It will not test the TLS negotiation,
 * which is assumed to be working since JSSE is used. However, it will tests the
 * XML handshaking.
 */
public class TLSHandshakeStreamTest extends BaseStreamTestCase {
    TLSHandshakeStream stream;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        stream = new TestableTLSHandshakeStream();
        sessCtx.setHostName("example.com");
        sessCtx.setUsername("romeo");
        streamCtx.getFeatures().setTLSRequired(true);
        Socket socket = new Socket();
        streamCtx.setSocket(socket);
    }

    public void testHandshakeFailure() throws Exception {
        String inRes = "com/echomine/xmpp/data/TLSHandshakeError_in.xml";
        String outRes = "com/echomine/xmpp/data/TLSHandshake_out.xml";
        try {
            runAndCompare(inRes, outRes, stream, true, true);
            fail("Test should throw exception on TLS negotiation failure");
        } catch (XMPPException ex) {
            // test passed
        }
    }

    /**
     * This is a regressive test for catching invalid data during TLS
     * negotiation. When local entity sends a 'starttls' negotiation, the remote
     * entity should only reply with either a 'failure' or 'proceed'. Any other
     * elements are invalid.
     * 
     * @throws Exception
     */
    public void testInvalidTLSHandshake() throws Exception {
        String inRes = "com/echomine/xmpp/data/InvalidTLSHandshake_in.xml";
        String outRes = "com/echomine/xmpp/data/TLSHandshake_out.xml";
        try {
            runAndCompare(inRes, outRes, stream, true, true);
            fail("Test should throw exception on TLS negotiation failure");
        } catch (XMPPException ex) {
            // test passed
        }
    }

    /**
     * The remote entity will send a 'proceed'. This test will verify that the
     * sockets and streams are all changed.
     * 
     * @throws Exception
     */
    public void testProceedTLSHandshake() throws Exception {
        String inRes = "com/echomine/xmpp/data/TLSHandshakeProceed_in.xml";
        String outRes = "com/echomine/xmpp/data/TLSHandshake_out.xml";
        runAndCompare(inRes, outRes, stream, true, true);
        assertTrue(streamCtx.getSocket() instanceof SSLSocket);
        //these values must be reset after successful TLS handshake
        assertEquals("example.com", sessCtx.getHostName());
        assertNull(sessCtx.getStreamId());
        assertNull(sessCtx.getVersion());
    }

    class TestableTLSHandshakeStream extends TLSHandshakeStream {
        protected SSLSocket startTLSHandshake(Socket socket) throws KeyManagementException, NoSuchAlgorithmException, IOException {
            MockSSLSocket mockSocket = new MockSSLSocket();
            mockSocket.setInputStream(new ByteArrayInputStream("".getBytes()));
            mockSocket.setOutputStream(new ByteArrayOutputStream());
            mockSocket.setUseClientMode(true);
            return mockSocket;
        }
    }
}
