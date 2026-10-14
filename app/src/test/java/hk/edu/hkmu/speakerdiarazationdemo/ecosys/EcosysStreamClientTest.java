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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
