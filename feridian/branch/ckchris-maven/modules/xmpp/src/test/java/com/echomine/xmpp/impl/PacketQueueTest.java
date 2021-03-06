package com.echomine.xmpp.impl;

import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

import junit.framework.TestCase;

import com.echomine.xmpp.IStanzaPacket;
import com.echomine.xmpp.MockXMPPConnectionHandler;
import com.echomine.xmpp.SendPacketFailedException;
import com.echomine.xmpp.packet.IQPacket;
import com.echomine.xmpp.packet.PresencePacket;
import com.echomine.xmpp.packet.RosterIQPacket;

/**
 * Tests the packet queue and make sure it works properly
 */
public class PacketQueueTest extends TestCase {
    MockXMPPConnectionHandler handler;

    TestablePacketQueue queue;

    protected void setUp() throws Exception {
        handler = new MockXMPPConnectionHandler();
        queue = new TestablePacketQueue(handler);
        handler.getStreamContext().getWriter().pushExtensionNamespaces(
                new String[] { "jabber:client" });
    }

    protected void tearDown() throws Exception {
        queue.stop();
    }

    public void testQueueShutdownClearData() throws Exception {
        queue.start(true);
        queue.queuePacket(new PresencePacket(), false);
        assertEquals(1, queue.getQueue().size());
        assertEquals(0, queue.getReplyTable().size());
    }

    /**
     * Tests that the reply packet's class should be the same as the request
     * class, even if the reply packet is an IQ packet. This is specifically an
     * IQ packet use case. Message and presence packets do not have this class
     * casting issue.
     */
    public void testReplyPacketClassTypeSameAsRequest() throws Exception {
        queue.start();
        QueuePacketRunnable runner = new QueuePacketRunnable();
        Thread thread = new Thread(runner);
        thread.start();
        while (queue.getReplyTable().size() == 0)
            Thread.yield();
        IQPacket packet = new IQPacket();
        packet.setId("id_001");
        packet.setType(IQPacket.TYPE_RESULT);
        queue.packetReceived(packet);
        // assertEquals(0, queue.getQueue().size());
        // assertEquals(0, queue.getReplyTable().size());
        assertNotNull(runner.replyPacket);
        assertTrue(runner.replyPacket instanceof RosterIQPacket);
        assertEquals(IQPacket.TYPE_RESULT, runner.replyPacket.getType());
    }

    public void testQueuePacketWithWait() throws Exception {
        queue.start();
        QueuePacketRunnable runner = new QueuePacketRunnable();
        Thread thread = new Thread(runner);
        thread.start();
        while (queue.getReplyTable().size() == 0)
            Thread.yield();
        RosterIQPacket packet = new RosterIQPacket();
        packet.setId("id_001");
        packet.setType(IQPacket.TYPE_RESULT);
        queue.packetReceived(packet);
        assertEquals(0, queue.getQueue().size());
        assertEquals(0, queue.getReplyTable().size());
        assertNotNull(runner.replyPacket);
        assertEquals(IQPacket.TYPE_RESULT, runner.replyPacket.getType());
    }

    public void testPacketReceived() throws Exception {
        assertNull(queue.packetReceived(null));
        assertNotNull(queue.packetReceived(new RosterIQPacket()));
    }

    /**
     * Checks that the start state has all variables set properly
     * 
     * @throws Exception
     */
    public void testStartStopState() throws Exception {
        queue.start();
        assertTrue(queue.isRunning());
        queue.stop();
        assertTrue(queue.isShutdown());
    }

    /**
     * Checks that the resume and pause states work as expected
     * 
     * @throws Exception
     */
    public void testPauseResumeState() throws Exception {
        queue.start();
        assertTrue(queue.isRunning());
        queue.pause();
        assertTrue(queue.isPaused());
        queue.resume();
        assertTrue(queue.isRunning());
        queue.stop();
        assertTrue(queue.isShutdown());
    }

    class QueuePacketRunnable implements Runnable {
        IStanzaPacket replyPacket;

        public void run() {
            RosterIQPacket packet = new RosterIQPacket();
            packet.setId("id_001");
            packet.setType(IQPacket.TYPE_GET);

            // queue the packet, and wait for reply
            try {
                replyPacket = queue.queuePacket(packet, true);
            } catch (SendPacketFailedException ex) {
            } finally {
            }
        }
    }

    class TestablePacketQueue extends PacketQueue {
        public TestablePacketQueue(XMPPConnectionHandler handler) {
            super(handler);
        }

        public boolean isShutdown() {
            return state == RunningState.STOPPED;
        }

        public boolean isRunning() {
            return state == RunningState.RUNNING;
        }

        public boolean isPaused() {
            return state == RunningState.PAUSED;
        }

        public LinkedBlockingQueue getQueue() {
            return queue;
        }

        public HashMap getReplyTable() {
            return packetReplyTable;
        }
    }
}
