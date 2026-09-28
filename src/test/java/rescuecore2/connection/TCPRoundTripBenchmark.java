package rescuecore2.connection;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import rescuecore2.messages.control.EntityIDRequest;
import rescuecore2.messages.control.EntityIDResponse;

/** Loopback framing/transport benchmark, with the same request count in both modes. */
public final class TCPRoundTripBenchmark {
    public static void main(String[] args) throws Exception {
        double[] oldTimes = new double[3], newTimes = new double[3];
        for (int i = 0; i < 3; i++) {
            oldTimes[i] = run(false, 30);
            newTimes[i] = run(true, 30);
        }
        Arrays.sort(oldTimes);
        Arrays.sort(newTimes);
        System.out.printf("30 request/reply pairs: original=%.2f ms, buffered TCP_NODELAY=%.2f ms, %.2fx%n",
                oldTimes[1], newTimes[1], oldTimes[1] / newTimes[1]);
    }

    private static double run(boolean optimized, int count) throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
                Socket client = new Socket(loopback, server.getLocalPort());
                Socket peer = server.accept();
                var executor = Executors.newSingleThreadExecutor()) {
            StreamConnection sender = optimized ? new TCPConnection(client)
                    : new StreamConnection(client.getInputStream(), client.getOutputStream());
            StreamConnection receiver = optimized ? new TCPConnection(peer)
                    : new StreamConnection(peer.getInputStream(), peer.getOutputStream());
            client.setSoTimeout(5000);
            peer.setSoTimeout(5000);
            var response = new EntityIDResponse(1, 2, List.of()).toMessageProto();
            var request = new EntityIDRequest(1, 2, 0).toMessageProto();
            var future = executor.submit(() -> {
                for (int i = 0; i < count + 2; i++) {
                    if (!request.equals(receiver.deserializeMessageProto())) throw new AssertionError("Request differs");
                    receiver.serializeMessageProto(response);
                    receiver.out.flush();
                }
                return null;
            });
            long start = 0;
            for (int i = 0; i < count + 2; i++) {
                if (i == 2) start = System.nanoTime();
                sender.serializeMessageProto(request);
                sender.out.flush();
                if (!response.equals(sender.deserializeMessageProto())) throw new AssertionError("Response differs");
            }
            double milliseconds = (System.nanoTime() - start) / 1e6;
            future.get(10, TimeUnit.SECONDS);
            return milliseconds;
        }
    }
}
