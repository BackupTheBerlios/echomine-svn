package com.echomine.xmpp.impl;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jibx.runtime.IXMLReader;
import org.jibx.runtime.JiBXException;
import org.jibx.runtime.impl.UnmarshallingContext;

import com.echomine.jibx.JiBXUtil;
import com.echomine.jibx.XMPPLoggableReader;
import com.echomine.net.ConnectionContext;
import com.echomine.net.HandshakeFailedException;
import com.echomine.net.HandshakeableSocketHandler;
import com.echomine.util.IOUtil;
import com.echomine.xmpp.ErrorCode;
import com.echomine.xmpp.IDGenerator;
import com.echomine.xmpp.IStanzaPacket;
import com.echomine.xmpp.IXMPPStream;
import com.echomine.xmpp.SendPacketFailedException;
import com.echomine.xmpp.XMPPConstants;
import com.echomine.xmpp.XMPPException;
import com.echomine.xmpp.XMPPSessionContext;
import com.echomine.xmpp.XMPPStanzaErrorException;
import com.echomine.xmpp.XMPPStreamContext;
import com.echomine.xmpp.XMPPStreamFactory;
import com.echomine.xmpp.packet.ErrorPacket;
import com.echomine.xmpp.packet.IQPacket;
import com.echomine.xmpp.packet.MessagePacket;
import com.echomine.xmpp.packet.PresencePacket;
import com.echomine.xmpp.packet.StanzaErrorPacket;
import com.echomine.xmpp.packet.StanzaPacketBase;
import com.echomine.xmpp.packet.XMLTextPacket;

/**
 * The handler for working with the xmpp client connection. The handler will
 * actually delegate the work to Streams that handle all the incoming and
 * outgoing parsing. In addition, the handler will do automatic TLS negotation
 * if the remote entity supports it. It is on by default.
 * <p>
 * This connection also follows the XMPP specs on ignoring unknown stanzas. XMPP
 * specs states the following for ignoring unknown stanzas:
 * <ul>
 * <li>If an entity receives a message or presence stanza that contains XML
 * data qualified by a namespace it does not understand, the portion of the
 * stanza that is in the unknown namespace SHOULD be ignored.</li>
 * <li>If an entity receives a message stanza whose only child element is
 * qualified by a namespace it does not understand, it MUST ignore the entire
 * stanza.</li>
 * <li>If an entity receives an IQ stanza of type "get" or "set" containing a
 * child element qualified by a namespace it does not understand, the entity
 * SHOULD return an IQ stanza of type "error" with an error condition of
 * &lt;service-unavailable/>.</li>
 * </ul>
 * This connection handler follows the first and second rule. For the third
 * rule, this handler will send back an error iq packet. However, it does not
 * attach the original request. This should be fixed in future releases.
 * </p>
 * <p>
 * TODO: Error reply for IQ request will include the original request data if
 * the request is unknown.
 * </p>
 */
public class XMPPConnectionHandler implements HandshakeableSocketHandler,
        XMPPConstants {
    private static final Log log = LogFactory.getLog(XMPPConnectionHandler.class);
    private static final String PRESENCE_ELEMENT_NAME = "presence";
    private static final String IQ_ELEMENT_NAME = "iq";
    private static final String MESSAGE_ELEMENT_NAME = "message";
    private static final String ERROR_ELEMENT_NAME = "error";

    protected enum RunningState {
        HANDSHAKING, RUNNING, PAUSED, STOPPED
    };

    protected XMPPSessionContext sessCtx;
    protected RunningState state = RunningState.STOPPED;
    protected XMPPStreamContext streamCtx;
    private IXMPPStream handshakeStream;
    private IXMPPStream tlsStream;
    private PacketQueue queue;
    private PacketListenerManager listenerManager;
    private Socket mainSocket;
    private ReentrantLock lock;
    private Semaphore pauseLock = new Semaphore(1);

    /**
     * The constructor for the handler. It accepts a connection context to use
     * the data stored or to store any connection-related data.
     * 
     * @throws IllegalArgumentException if handshake stream is not defined or
     *             cannot be found
     */
    public XMPPConnectionHandler() {
        this(new XMPPSessionContext(), new XMPPStreamContext());
    }

    /**
     * A method allowing the customization of the session and stream context.
     * This is normally used for unit testing purposes. Normal users will use
     * the default no-argument constructor.
     * 
     * @param sessCtx the custom session context
     * @param streamCtx the custom stream context
     * @throws IllegalArgumentException if handshake stream is not defined or
     *             cannot be found
     */
    public XMPPConnectionHandler(XMPPSessionContext sessCtx, XMPPStreamContext streamCtx) {
        this.sessCtx = sessCtx;
        this.streamCtx = streamCtx;
        this.queue = new PacketQueue(this);
        lock = new ReentrantLock();
        try {
            handshakeStream = XMPPStreamFactory.getFactory().createStream(XMPPConstants.NS_STREAM_HANDSHAKE);
            if (handshakeStream == null)
                throw new IllegalArgumentException("Unable to find handshake stream. Must be declared in config");
            tlsStream = XMPPStreamFactory.getFactory().createStream(XMPPConstants.NS_STREAM_TLS);
        } catch (XMPPException ex) {
            throw new IllegalArgumentException("Error while retrieving stream: "
                    + ex.getMessage());
        }
    }

    /*
     * This handshake will negotiate the initial handshaking of the XMPP stream.
     * Furthermore, if TLS is supported, it will automatically negotiate TLS
     * before returning. If TLS requires a callback to verify any unknown
     * certificates, it will be done here as well. Handshaking does NOT include
     * authentication, SASL, or any other kind of packet processing.
     * 
     * @see com.echomine.net.HandshakeableSocketHandler#handshake(java.net.Socket,
     *      com.echomine.net.ConnectionContext)
     */
    public void handshake(Socket socket, ConnectionContext connCtx) throws HandshakeFailedException {
        state = RunningState.HANDSHAKING;
        try {
            this.mainSocket = socket;
            socket.setKeepAlive(true);
            streamCtx.getWriter().setOutput(socket.getOutputStream());
            XMPPLoggableReader reader = new XMPPLoggableReader(socket.getInputStream(), "UTF-8");
            streamCtx.getUnmarshallingContext().setDocument(reader);
            streamCtx.setSocket(socket);
            streamCtx.setReader(reader);
            if (log.isDebugEnabled())
                log.debug("Starting Handshake with " + connCtx.getHostName());
            sessCtx.setHostName(connCtx.getHostName());
            handshakeStream.process(sessCtx, streamCtx);
            // tls stream negotiation if a stream supports it
            if (tlsStream != null && streamCtx.getFeatures().isTLSSupported()) {
                if (log.isDebugEnabled())
                    log.debug("Found TLS Feature support and stream processor.  Trying to negotiate TLS...");
                tlsStream.process(sessCtx, streamCtx);
                if (log.isDebugEnabled())
                    log.debug("TLS negotiation successful! Redoing handshaking in TLS mode...");
                handshakeStream.process(sessCtx, streamCtx);
            }
            if (log.isDebugEnabled())
                log.debug("Handshake completed... Ready for XMPP Stanza processing...");
            state = RunningState.RUNNING;
        } catch (IOException ex) {
            throw new HandshakeFailedException("Socket error during handshake", ex);
        } catch (JiBXException ex) {
            throw new HandshakeFailedException("Jibx Exception occurred during handshake", ex);
        } catch (XMPPException ex) {
            throw new HandshakeFailedException("XMPP Exception occurred during handshake", ex);
        }
    }

    /*
     * The main handler method. It simply begins the incoming packet processing
     * mode. The code is also written so that the packet processing mode can be
     * paused and resumed. This is useful when login or other streaming
     * capabilities need to take over the stream data processing. <br/>The way
     * data is handled in this API is that there is only one thread processing
     * incoming messages, which is the thread that runs this handle method.
     * Whoever is sending the packet (ie. the packet router) SHOULD run it in a
     * separate thread. This way, for instance, the router will run its queue
     * off its own thread and allow asynchronicity. Incoming messages already
     * run in the current connection thread, so incoming message processing will
     * not be affected.
     * 
     * @see com.echomine.net.SocketHandler#handle(alt.java.net.Socket,
     *      com.echomine.net.ConnectionContext)
     */
    public void handle(Socket socket, ConnectionContext connCtx) throws IOException {
        UnmarshallingContext uctx = streamCtx.getUnmarshallingContext();
        // sets the stream context as user context for unmarshallers to use
        uctx.setUserContext(streamCtx);
        // start incoming data packet reading and outgoing packet queue sending
        try {
            while (state != RunningState.STOPPED) {
                while (state == RunningState.PAUSED)
                    try {
                        pauseLock.acquire();
                    } catch (InterruptedException ex) {
                        // intentionally left empty
                    } finally {
                        pauseLock.release();
                    }
                if (state == RunningState.STOPPED)
                    break;
                streamCtx.getReader().startLogging();
                // purposely synchronize because of possible multithread
                // accessing issue
                synchronized (uctx) {
                    uctx.next();
                }
                IStanzaPacket packet = null;
                if (state == RunningState.RUNNING) {
                    // parse incoming data
                    if (uctx.currentEvent() == IXMLReader.END_DOCUMENT) {
                        break;
                    } else if (uctx.isEnd()) {
                        continue;
                    } else if (uctx.isAt(NS_XMPP_CLIENT, PRESENCE_ELEMENT_NAME)) {
                        packet = (IStanzaPacket) JiBXUtil.unmarshallObject(uctx, PresencePacket.class);
                    } else if (uctx.isAt(NS_XMPP_CLIENT, MESSAGE_ELEMENT_NAME)) {
                        MessagePacket msgPkt = (MessagePacket) JiBXUtil.unmarshallObject(uctx, MessagePacket.class);
                        // according to XMPP, message stanza with no child
                        // element or unknown namespace extensions should be
                        // ignored. This translates to this API ignoring message
                        // stanzas with no child elements and no extensions in
                        // this API.
                        if (msgPkt.getBodies().isEmpty()
                                && msgPkt.getExtensions().isEmpty()
                                && msgPkt.getSubjects().isEmpty()
                                && msgPkt.getThreadID() == null)
                            streamCtx.getReader().flushIgnoredDataToLog();
                        else
                            packet = msgPkt;
                    } else if (uctx.isAt(NS_XMPP_CLIENT, IQ_ELEMENT_NAME)) {
                        IQPacket iqpkt = (IQPacket) JiBXUtil.unmarshallObject(uctx, IQPacket.class);
                        // according to XMPP, if an entity receives an IQ stanza
                        // of type "get" or "set" containing a child element
                        // qualified by a namespace it does not understand, the
                        // entity SHOULD return an IQ stanza of type "error"
                        // with an error condition of <service-unavailable/>.
                        // Here, it is ignored as well. In addition, an
                        // error packet is also sent back to the user, as
                        // specified by the specs.
                        if (IQPacket.class.getName().equals(iqpkt.getClass().getName())
                                && (IQPacket.TYPE_SET.equals(iqpkt.getType()) || IQPacket.TYPE_GET.equals(iqpkt.getType()))) {
                            if (log.isDebugEnabled())
                                log.debug("Found IQ packet with unknown extension inside.  Ignoring and sending unavailable error packet reply...");
                            streamCtx.getReader().flushIgnoredDataToLog();
                            IQPacket errpkt = new IQPacket();
                            errpkt.setTo(iqpkt.getFrom());
                            errpkt.setId(iqpkt.getId());
                            // TODO: For now, the return result does not include
                            // the original packet request data. XMPP specs
                            // RECOMMENDS includes the original packet request
                            // data.
                            StanzaErrorPacket error = new StanzaErrorPacket();
                            error.setCondition(ErrorCode.C_SERVICE_UNAVAILABLE);
                            error.setErrorType(StanzaErrorPacket.CANCEL);
                            errpkt.setError(error);
                            try {
                                queuePacket(errpkt, false);
                            } catch (SendPacketFailedException ex) {
                                // intentionally empty (will never get thrown)
                            }
                        } else {
                            packet = iqpkt;
                        }
                    } else if (uctx.isAt(NS_JABBER_STREAM, ERROR_ELEMENT_NAME)) {
                        // stream level error received = close stream
                        ErrorPacket errorPkt = (ErrorPacket) JiBXUtil.unmarshallObject(uctx, ErrorPacket.class);
                        XMPPStanzaErrorException ex = new XMPPStanzaErrorException("Stream error", errorPkt);
                        IOException ioex = new IOException();
                        ioex.initCause(ex);
                        throw ioex;
                    } else {
                        uctx.skipElement();
                        streamCtx.getReader().flushIgnoredDataToLog();
                    }
                    // match packets with those in queue in case any packets are
                    // waiting for replies
                    if (packet != null) {
                        packet = queue.packetReceived(packet);
                        if (listenerManager != null)
                            listenerManager.firePacketReceived(packet);
                        streamCtx.getReader().flushLog();
                    }
                }
            }
        } catch (JiBXException ex) {
            // intentionally left empty
            if (log.isInfoEnabled())
                log.info("Error while reading incoming data. Likely stream is closed due to shutdown or error", ex);
        } finally {
            // error reading incoming data (maybe connection closed)
            shutdown();
            endStream();
            IOUtil.closeSocket(streamCtx.getSocket());
        }
    }

    /**
     * This will queue a packet for later delivery. This should be the method of
     * choice when sending ALL packets. The sendPacket() is used by the queue.
     * If the packet extends from StanzaPacketBase, it will also set the ID of
     * the packet if one doesn't already exist.
     * 
     * @param packet the packet to send
     * @param wait whether to wait for a reply
     * @return the reply packet, or null if the wait is false
     * @throws SendPacketFailedException if waiting time expired before reply is
     *             received, or if IO Exception occurred, possibly due to
     *             shutdown
     */
    public IStanzaPacket queuePacket(IStanzaPacket packet, boolean wait) throws SendPacketFailedException {
        // set default ID if one isn't set
        if (packet.getId() == null && packet instanceof StanzaPacketBase)
            try {
                ((StanzaPacketBase) packet).setId(IDGenerator.nextID());
            } catch (XMPPException ex) {
                if (log.isWarnEnabled())
                    log.warn("Unable to generate packet ID.  Will not auto-set ID. You should check into cause", ex);
            }
        return queue.queuePacket(packet, wait);
    }

    /**
     * Sends a packet to the remote network, synchronously. This method is used
     * internally by the queue and should not be used by outside users. However,
     * it IS safe to use this method to immediately send a packet without going
     * through the queue. The method is synchronized to prevent overlapping
     * writes. This method also supports XMLTextPacket, and will output direct
     * xml text to the stream without going through any marshalling.
     * 
     * @param packet the packet to send
     * @see com.echomine.xmpp.packet.XMLTextPacket
     * @throws SendPacketFailedException if packet cannot be sent (connection
     *             closed, IO error, etc)
     */
    void sendPacket(IStanzaPacket packet) throws SendPacketFailedException {
        if (packet == null)
            return;
        lock.lock();
        try {
            // IQ Packets are marshalled differently
            if (packet instanceof IQPacket)
                JiBXUtil.marshallIQPacket(streamCtx.getWriter(), (IQPacket) packet);
            else if (packet instanceof XMLTextPacket)
                streamCtx.getWriter().writeMarkup(((XMLTextPacket) packet).getText());
            else
                JiBXUtil.marshallObject(streamCtx.getWriter(), packet);
            streamCtx.getWriter().flush();
        } catch (JiBXException ex) {
            throw new SendPacketFailedException(ex);
        } catch (IOException ex) {
            throw new SendPacketFailedException(ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * This will put the current packet processing on hold and begin stream
     * processing. After stream processing, packet processing will be resumed.
     * 
     * @param stream the stream processor
     * @param redoHandshake true to do handshake, false to simply process the
     *            given stream
     * @throws XMPPException if any processing exceptions occur
     */
    public void processStream(IXMPPStream stream, boolean redoHandshake) throws XMPPException {
        try {
            pause();
            stream.process(sessCtx, streamCtx);
            if (redoHandshake)
                handshakeStream.process(sessCtx, streamCtx);
        } finally {
            resume();
        }
    }

    /*
     * Resets the data before a connection begins for reusing the handler
     * 
     * @see com.echomine.net.SocketHandler#start()
     */
    public void start() {
        lock.lock();
        try {
            state = RunningState.STOPPED;
            streamCtx.reset();
            sessCtx.reset();
            // start queue paused
            queue.start(true);
        } finally {
            lock.unlock();
        }
    }

    /*
     * shutdown the connection. This method will actually block until all
     * packets are sent out. Note that even if sent packets errored, sending of
     * subsequently packets are stopped because likely the output stream is
     * already closed.
     * 
     * @see com.echomine.net.SocketHandler#shutdown()
     */
    public void shutdown() {
        if (state == RunningState.STOPPED)
            return;
        lock.lock();
        try {
            state = RunningState.STOPPED;
            // must physically shutdown input stream in order to release
            // the unmarshalling context's parser wait status
            try {
                if (mainSocket != null)
                    IOUtil.closeStream(mainSocket.getInputStream());
            } catch (IOException ex) {
                // intentionally left empty
            }
            queue.stop();
        } finally {
            lock.unlock();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.echomine.net.SocketHandler#isConnected()
     */
    public boolean isConnected() {
        return state == RunningState.RUNNING;
    }

    /**
     * Retrieves the context used by the handler containing any session specific
     * information.
     * 
     * @return the session context.
     */
    public XMPPSessionContext getSessionContext() {
        return sessCtx;
    }

    /**
     * Retrieves the stream context associated with the handler containing
     * current stream-level details
     */
    public XMPPStreamContext getStreamContext() {
        return streamCtx;
    }

    /**
     * Retrieves the packet listener manager associated with this handler.
     * 
     * @return the listener manager
     */
    public PacketListenerManager getPacketListenerManager() {
        return listenerManager;
    }

    /**
     * sets the packet listener manager
     * 
     * @param lmanager the listener manager
     */
    public void setPacketListenerManager(PacketListenerManager lmanager) {
        this.listenerManager = lmanager;
    }

    /**
     * pauses all processing of incoming packets. This is normally used to
     * indicate that some stream wishes to take over the stream processing for
     * serialized processing.
     */
    protected void pause() {
        if (state == RunningState.PAUSED)
            return;
        lock.lock();
        try {
            state = RunningState.PAUSED;
            queue.pause();
            pauseLock.tryAcquire();
        } finally {
            lock.unlock();
        }
    }

    /**
     * This is called to resume processing of incoming packets after a pause.
     */
    protected void resume() {
        if (state == RunningState.RUNNING)
            return;
        lock.lock();
        try {
            state = RunningState.RUNNING;
            queue.resume();
            if (pauseLock.availablePermits() == 0)
                pauseLock.release();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends the stream due to either receiving an error from remote entity or
     * any error encountered here. The method will not flush or close the
     * underlying stream.
     */
    protected void endStream() {
        try {
            streamCtx.getWriter().endStream();
        } catch (IOException ex) {
            // intentionally left empty
        }
    }
}
