package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ecosys.v1.DeviceRole;
import ecosys.v1.StreamFrame;
import ecosys.v1.StreamServiceGrpc;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public class EcosysStreamClientTest {

    private String serverName;
    private Server server;
    private EcosysStreamClient client;

    @Before
    public void setUp() {
        serverName = InProcessServerBuilder.generateName();
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void bindingFrameIsFirstAndFramesRoundTrip() throws IOException {
        RecordingStreamService service = startServer();
        client = new EcosysStreamClient(inProcessFactory(), "glasses-test-id", DeviceRole.DEVICE_ROLE_GLASS);

        client.start();

        assertEquals(1, service.received.size());
        StreamFrame binding = service.received.get(0);
        assertEquals(StreamFrame.PayloadCase.STREAM_OPEN, binding.getPayloadCase());
        assertEquals("glasses-test-id", binding.getStreamOpen().getDeviceId());
        assertEquals(DeviceRole.DEVICE_ROLE_GLASS, binding.getStreamOpen().getRole());

        client.send(StreamFrame.newBuilder().setTranscript("hello-hub").build());

        assertEquals(2, service.received.size());
        StreamFrame sent = service.received.get(1);
        assertEquals(StreamFrame.PayloadCase.TRANSCRIPT, sent.getPayloadCase());
        assertEquals("hello-hub", sent.getTranscript());
    }

    @Test
    public void inboundFramesAreDeliveredToListeners() throws Exception {
        startEchoingServer();
        CountDownLatch inbound = new CountDownLatch(1);
        List<StreamFrame> receivedByClient = new CopyOnWriteArrayList<>();
        client = new EcosysStreamClient(inProcessFactory(), "glasses-test-id", DeviceRole.DEVICE_ROLE_GLASS);
        client.addListener(new EcosysStreamClient.Listener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onFrame(StreamFrame frame) {
                receivedByClient.add(frame);
                inbound.countDown();
            }

            @Override
            public void onDisconnected(Throwable cause) {
            }
        });

        client.start();

        assertTrue("client listener should receive the echoed binding frame",
                inbound.await(5, TimeUnit.SECONDS));
        StreamFrame echoed = receivedByClient.get(0);
        assertEquals(StreamFrame.PayloadCase.STREAM_OPEN, echoed.getPayloadCase());
        assertEquals("glasses-test-id", echoed.getStreamOpen().getDeviceId());
    }

    @Test
    public void serverShutdownReportsDisconnectWithoutCrashing() throws Exception {
        startServer();
        CountDownLatch disconnected = new CountDownLatch(1);
        client = new EcosysStreamClient(inProcessFactory(), "glasses-test-id", DeviceRole.DEVICE_ROLE_GLASS);
        client.addListener(new EcosysStreamClient.Listener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onFrame(StreamFrame frame) {
            }

            @Override
            public void onDisconnected(Throwable cause) {
                disconnected.countDown();
            }
        });
        client.start();

        server.shutdownNow();

        assertTrue("client should report a disconnect on transport loss",
                disconnected.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void framesResumeAfterReconnect() throws Exception {
        RecordingStreamService firstServer = startServer();
        CountDownLatch disconnected = new CountDownLatch(1);
        client = new EcosysStreamClient(inProcessFactory(), "glasses-test-id", DeviceRole.DEVICE_ROLE_GLASS);
        client.addListener(new EcosysStreamClient.Listener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onFrame(StreamFrame frame) {
            }

            @Override
            public void onDisconnected(Throwable cause) {
                disconnected.countDown();
            }
        });
        client.start();
        assertTrue("initial session should bind the device", await(() -> !firstServer.received.isEmpty()));

        server.shutdownNow();
        assertTrue("client should report a disconnect on transport loss",
                disconnected.await(5, TimeUnit.SECONDS));
        server.awaitTermination(5, TimeUnit.SECONDS);

        RecordingStreamService secondServer = startServer();
        assertTrue("client should rebind once the hub returns",
                await(() -> !secondServer.received.isEmpty()));

        client.send(StreamFrame.newBuilder().setTranscript("after-reconnect").build());
        assertTrue("frames should resume on the rebound stream", await(() ->
                secondServer.received.stream().anyMatch(frame ->
                        frame.getPayloadCase() == StreamFrame.PayloadCase.TRANSCRIPT
                                && "after-reconnect".equals(frame.getTranscript()))));
    }

    @Test
    public void healthyStreamReportsConnectedOnce() throws Exception {
        startServer();
        AtomicInteger connectedCount = new AtomicInteger();
        client = new EcosysStreamClient(inProcessFactory(), "glasses-test-id", DeviceRole.DEVICE_ROLE_GLASS);
        client.addListener(recordingListener(connectedCount));

        client.start();

        assertTrue("a ready stream should report connected",
                await(() -> connectedCount.get() == 1));
        Thread.sleep(200L);
        assertEquals("connected must be reported once per stream", 1, connectedCount.get());
    }

    @Test
    public void failingChannelGrowsTheReconnectDelayAndNeverReportsConnected() throws Exception {
        List<Long> attempts = new CopyOnWriteArrayList<>();
        AtomicInteger connectedCount = new AtomicInteger();
        String deadServerName = InProcessServerBuilder.generateName();
        client = new EcosysStreamClient(
                () -> {
                    attempts.add(System.nanoTime());
                    return InProcessChannelBuilder.forName(deadServerName).directExecutor().build();
                },
                "glasses-test-id",
                DeviceRole.DEVICE_ROLE_GLASS);
        client.addListener(recordingListener(connectedCount));

        client.start();

        assertTrue("client should keep retrying the dead hub",
                await(() -> attempts.size() >= 4));

        long gap0 = millisBetween(attempts.get(0), attempts.get(1));
        long gap1 = millisBetween(attempts.get(1), attempts.get(2));
        long gap2 = millisBetween(attempts.get(2), attempts.get(3));

        assertAtLeastJitterFloor(gap0, 0);
        assertAtLeastJitterFloor(gap1, 1);
        assertAtLeastJitterFloor(gap2, 2);
        assertTrue("the fourth attempt must back off well past the initial 500 ms, got " + gap2 + " ms",
                gap2 >= 2 * EcosysStreamClient.INITIAL_BACKOFF_MS - 150L);
        assertEquals("a failing stream must never report itself as connected",
                0, connectedCount.get());
    }

    @Test
    public void backoffBaseGrowsExponentiallyAndJitterStaysWithinTheCap() {
        assertEquals(EcosysStreamClient.INITIAL_BACKOFF_MS, EcosysStreamClient.backoffBaseMs(0));
        for (int attempt = 1; attempt <= EcosysStreamClient.MAX_BACKOFF_SHIFT; attempt++) {
            long expected = Math.min(EcosysStreamClient.MAX_BACKOFF_MS,
                    EcosysStreamClient.backoffBaseMs(attempt - 1) * 2L);
            assertEquals("backoff base should double for attempt " + attempt,
                    expected, EcosysStreamClient.backoffBaseMs(attempt));
        }
        assertEquals("backoff base must stop at the cap",
                EcosysStreamClient.MAX_BACKOFF_MS,
                EcosysStreamClient.backoffBaseMs(EcosysStreamClient.MAX_BACKOFF_SHIFT + 3));

        for (int attempt = 0; attempt <= EcosysStreamClient.MAX_BACKOFF_SHIFT + 3; attempt++) {
            long base = EcosysStreamClient.backoffBaseMs(attempt);
            for (int sample = 0; sample < 200; sample++) {
                long delay = EcosysStreamClient.reconnectDelayMs(attempt);
                assertTrue("delay " + delay + " ms is below the jitter floor for attempt " + attempt,
                        delay >= base / 2);
                assertTrue("delay " + delay + " ms is above the jitter ceiling for attempt " + attempt,
                        delay <= Math.min(EcosysStreamClient.MAX_BACKOFF_MS, base + base / 2));
                assertTrue("delay " + delay + " ms exceeds the hard cap",
                        delay <= EcosysStreamClient.MAX_BACKOFF_MS);
            }
        }
    }

    @Test
    public void stableStreamResetsBackoffSoANewFailureStartsFromTheBase() throws Exception {
        RecordingStreamService service = startServer();
        String deadServerName = InProcessServerBuilder.generateName();
        AtomicBoolean hubUp = new AtomicBoolean(false);
        AtomicLong lastDisconnectNanos = new AtomicLong(0L);
        List<Long> attempts = new CopyOnWriteArrayList<>();
        client = new EcosysStreamClient(
                () -> {
                    attempts.add(System.nanoTime());
                    String name = hubUp.get() ? serverName : deadServerName;
                    return InProcessChannelBuilder.forName(name).directExecutor().build();
                },
                "glasses-test-id",
                DeviceRole.DEVICE_ROLE_GLASS,
                200L);
        client.addListener(new EcosysStreamClient.Listener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onFrame(StreamFrame frame) {
            }

            @Override
            public void onDisconnected(Throwable cause) {
                lastDisconnectNanos.set(System.nanoTime());
            }
        });

        client.start();

        assertTrue("client should keep retrying the dead hub", await(() -> attempts.size() >= 3));
        long gap0 = millisBetween(attempts.get(0), attempts.get(1));
        long gap1 = millisBetween(attempts.get(1), attempts.get(2));
        assertAtLeastJitterFloor(gap0, 0);
        assertAtLeastJitterFloor(gap1, 1);

        hubUp.set(true);
        assertTrue("client should reconnect once the hub returns",
                await(() -> attempts.size() >= 4 && !service.received.isEmpty()));
        long gap2 = millisBetween(attempts.get(2), attempts.get(3));
        assertAtLeastJitterFloor(gap2, 2);
        assertTrue("the third failed attempt must back off past the initial base, got " + gap2 + " ms",
                gap2 >= 2 * EcosysStreamClient.INITIAL_BACKOFF_MS - 150L);

        assertTrue("the idle stable stream should reset the accumulated backoff",
                await(() -> reconnectAttempt(client) == 0L));

        int nextAttemptIndex = attempts.size();
        lastDisconnectNanos.set(0L);
        hubUp.set(false);
        server.shutdownNow();
        assertTrue("client should notice the stable stream dropping",
                await(() -> lastDisconnectNanos.get() != 0L));
        long disconnectNanos = lastDisconnectNanos.get();

        assertTrue("client should retry after the stable stream drops",
                await(() -> attempts.size() > nextAttemptIndex));
        long gapAfterStableDrop = millisBetween(disconnectNanos, attempts.get(nextAttemptIndex));

        assertAtLeastJitterFloor(gapAfterStableDrop, 0);
        assertTrue("after a stable period the delay must restart at the base, got "
                        + gapAfterStableDrop + " ms",
                gapAfterStableDrop < 1800L);
    }

    private static long reconnectAttempt(EcosysStreamClient client) {
        try {
            Field field = EcosysStreamClient.class.getDeclaredField("reconnectAttempt");
            field.setAccessible(true);
            return ((AtomicLong) field.get(client)).get();
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private EcosysStreamClient.Listener recordingListener(AtomicInteger connectedCount) {
        return new EcosysStreamClient.Listener() {
            @Override
            public void onConnected() {
                connectedCount.incrementAndGet();
            }

            @Override
            public void onFrame(StreamFrame frame) {
            }

            @Override
            public void onDisconnected(Throwable cause) {
            }
        };
    }

    private static void assertAtLeastJitterFloor(long observedMs, long attempt) {
        long floor = EcosysStreamClient.backoffBaseMs(attempt) / 2;
        assertTrue("attempt " + attempt + " retried after " + observedMs
                        + " ms, below the jitter floor of " + floor + " ms",
                observedMs >= floor - 150L);
    }

    private static long millisBetween(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    private RecordingStreamService startServer() throws IOException {
        return startServer(false);
    }

    private RecordingStreamService startEchoingServer() throws IOException {
        return startServer(true);
    }

    private RecordingStreamService startServer(boolean echo) throws IOException {
        RecordingStreamService service = new RecordingStreamService(echo);
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        return service;
    }

    private EcosysStreamClient.ChannelFactory inProcessFactory() {
        return () -> InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    private static final class RecordingStreamService extends StreamServiceGrpc.StreamServiceImplBase {
        final List<StreamFrame> received = new CopyOnWriteArrayList<>();
        private final boolean echo;

        RecordingStreamService(boolean echo) {
            this.echo = echo;
        }

        @Override
        public StreamObserver<StreamFrame> openStream(StreamObserver<StreamFrame> responseObserver) {
            return new StreamObserver<StreamFrame>() {
                @Override
                public void onNext(StreamFrame frame) {
                    received.add(frame);
                    if (echo) {
                        responseObserver.onNext(frame);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
