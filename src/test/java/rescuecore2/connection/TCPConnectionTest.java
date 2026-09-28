package rescuecore2.connection;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import rescuecore2.messages.control.EntityIDRequest;
import rescuecore2.messages.protobuf.RCRSProto.MessageProto;
import rescuecore2.misc.EncodingTools;

class TCPConnectionTest {
    private static class CountingOutput extends ByteArrayOutputStream {
        int writes;
        @Override public synchronized void write(int value) { writes++; super.write(value); }
        @Override public synchronized void write(byte[] data, int offset, int size) {
            writes++;
            super.write(data, offset, size);
        }
    }

    private static class TestSocket extends Socket {
        final CountingOutput output = new CountingOutput();
        boolean noDelay;
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(int timeout) { }
        @Override public void setTcpNoDelay(boolean value) { noDelay = value; }
    }

    @Test
    void coalescesSmallFramesWithoutChangingWireFormat() throws Exception {
        TestSocket socket = new TestSocket();
        TCPConnection connection = new TCPConnection(socket);
        assertTrue(socket.noDelay);
        var expected = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) {
            MessageProto message = new EntityIDRequest(1, i, i % 3).toMessageProto();
            byte[] bytes = message.toByteArray();
            EncodingTools.writeInt32(bytes.length, expected);
            expected.write(bytes);
            connection.serializeMessageProto(message);
            connection.out.flush(); // Same flush boundary as WriteThread.
        }
        assertArrayEquals(expected.toByteArray(), socket.output.toByteArray());
        assertEquals(10, socket.output.writes);
    }

    @Test
    void preservesLargeFramesAndFollowingSmallMessages() throws Exception {
        TestSocket socket = new TestSocket();
        TCPConnection connection = new TCPConnection(socket);
        byte[] payload = new byte[50000];
        Arrays.fill(payload, (byte) 42);
        MessageProto small = new EntityIDRequest(1, 2, 3).toMessageProto();
        MessageProto large = small.toBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                .addField(100, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFrom(payload)).build())
                .build()).build();
        for (MessageProto message : new MessageProto[] {small, large, small}) {
            connection.serializeMessageProto(message);
            connection.out.flush();
        }
        InputStream input = new ByteArrayInputStream(socket.output.toByteArray());
        for (MessageProto expected : new MessageProto[] {small, large, small}) {
            int size = EncodingTools.readInt32(input);
            assertEquals(expected, MessageProto.parseFrom(input.readNBytes(size)));
        }
        assertEquals(-1, input.read());
    }
    @Test
    void exchangesRequestsThroughWorkersInOrderAndShutsDown() throws Exception {
        var loopback = java.net.InetAddress.getLoopbackAddress();
        try (var server = new java.net.ServerSocket(0, 1, loopback);
                var clientSocket = new Socket(loopback, server.getLocalPort());
                var serverSocket = server.accept()) {
            TCPConnection client = new TCPConnection(clientSocket);
            TCPConnection kernel = new TCPConnection(serverSocket);
            var received = new java.util.concurrent.LinkedBlockingQueue<rescuecore2.messages.Message>();
            var errors = new java.util.concurrent.atomic.AtomicReference<Exception>();
            client.addConnectionListener((connection, message) -> received.add(message));
            kernel.addConnectionListener((connection, message) -> {
                try {
                    EntityIDRequest request = (EntityIDRequest) message;
                    var ids = new java.util.ArrayList<rescuecore2.worldmodel.EntityID>();
                    for (int i = 0; i < request.getCount(); i++) {
                        ids.add(new rescuecore2.worldmodel.EntityID(request.getRequestID() * 10 + i));
                    }
                    connection.sendMessage(new rescuecore2.messages.control.EntityIDResponse(
                            request.getSimulatorID(), request.getRequestID(), ids));
                } catch (Exception error) {
                    errors.set(error);
                }
            });
            try {
                kernel.startup();
                client.startup();
                for (int i = 0; i < 20; i++) {
                    client.sendMessage(new EntityIDRequest(7, i, i % 4));
                }
                for (int i = 0; i < 20; i++) {
                    var message = received.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertNull(errors.get());
                    assertInstanceOf(rescuecore2.messages.control.EntityIDResponse.class, message);
                    var response = (rescuecore2.messages.control.EntityIDResponse) message;
                    assertEquals(7, response.getSimulatorID());
                    assertEquals(i, response.getRequestID());
                    assertEquals(i % 4, response.getEntityIDs().size());
                    for (int j = 0; j < i % 4; j++) {
                        assertEquals(i * 10 + j, response.getEntityIDs().get(j).getValue());
                    }
                }
                assertTrue(received.isEmpty());
            } finally {
                client.shutdown();
                kernel.shutdown();
            }
            assertFalse(client.isAlive());
            assertFalse(kernel.isAlive());
        }
    }

}
