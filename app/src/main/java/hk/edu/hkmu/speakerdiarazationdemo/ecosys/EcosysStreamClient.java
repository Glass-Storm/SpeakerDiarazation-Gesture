package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import ecosys.v1.DeviceRole;
import ecosys.v1.StreamFrame;
import ecosys.v1.StreamOpenRequest;
import ecosys.v1.StreamServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plaintext {@code ecosys.v1} client for the hub's {@code StreamService/OpenStream}
 * bidirectional stream. It carries no bearer token: the first frame on every
 * (re)opened stream is the session-binding {@code stream_open} frame that tells
 * the hub which device and role it is talking to.
 */
public final class EcosysStreamClient implements AutoCloseable {

    public static final int DEFAULT_HUB_PORT = 9000;

    private static final Logger LOG = Logger.getLogger(EcosysStreamClient.class.getName());
    static final long INITIAL_BACKOFF_MS = 500L;
    static final long MAX_BACKOFF_MS = 30_000L;
    static final int MAX_BACKOFF_SHIFT = 6;
    private static final long STABLE_STREAM_MS = 10_000L;
    private static final Random JITTER = new Random();

    /** Builds the transport to the hub. Overridden in tests to inject an in-process channel. */
    public interface ChannelFactory {
        ManagedChannel create();
    }

    /** Inbound frame sink plus connection lifecycle callbacks. */
    public interface Listener {
        void onConnected();

        void onFrame(StreamFrame frame);

        void onDisconnected(Throwable cause);
    }

    private final ChannelFactory channelFactory;
    private final String deviceId;
    private final DeviceRole role;
    private final long stableStreamMs;
    private final ScheduledExecutorService scheduler;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicLong reconnectAttempt = new AtomicLong(0L);
    /**
     * Generation of the stream. Incremented for every connect attempt so callbacks
     * from a superseded stream can be ignored instead of starting a second retry chain.
     */
    private final AtomicLong epoch = new AtomicLong(0L);

    private volatile ManagedChannel channel;
    private volatile StreamObserver<StreamFrame> requestObserver;

    /** Dials {@code host} on the default hub port as a {@code DEVICE_ROLE_GLASS} peer. */
    public EcosysStreamClient(String host, String deviceId) {
        this(host, DEFAULT_HUB_PORT, deviceId);
    }

    /** Dials {@code host:port} in plaintext as a {@code DEVICE_ROLE_GLASS} peer. */
    public EcosysStreamClient(String host, int port, String deviceId) {
        this(defaultChannelFactory(host, port), deviceId, DeviceRole.DEVICE_ROLE_GLASS);
    }

    /** Dials {@code host:port} in plaintext with an explicit role. */
    public EcosysStreamClient(String host, int port, String deviceId, DeviceRole role) {
        this(defaultChannelFactory(host, port), deviceId, role);
    }

    EcosysStreamClient(ChannelFactory channelFactory, String deviceId, DeviceRole role) {
        this(channelFactory, deviceId, role, STABLE_STREAM_MS);
    }

    EcosysStreamClient(
            ChannelFactory channelFactory, String deviceId, DeviceRole role, long stableStreamMs) {
        this.channelFactory = channelFactory;
        this.deviceId = deviceId;
        this.role = role;
        this.stableStreamMs = stableStreamMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ecosys-stream-reconnect");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ChannelFactory defaultChannelFactory(String host, int port) {
        return () -> ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Opens the stream and binds the session. A subsequent successful reconnect rebinds automatically. */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("client is closed");
        }
        connect();
    }

    /** Sends one outbound frame. The caller must have called {@link #start()}. */
    public void send(StreamFrame frame) {
        synchronized (sendLock) {
            StreamObserver<StreamFrame> observer = requestObserver;
            if (observer == null) {
                throw new IllegalStateException("stream is not connected; call start() first");
            }
            observer.onNext(frame);
        }
    }

    private StreamFrame bindFrame() {
        return StreamFrame.newBuilder()
                .setStreamOpen(StreamOpenRequest.newBuilder()
                        .setDeviceId(deviceId)
                        .setRole(role)
                        .build())
                .build();
    }

    private void connect() {
        if (closed.get()) {
            return;
        }
        long attemptEpoch = epoch.incrementAndGet();
        ManagedChannel newChannel = channelFactory.create();
        ResponseObserver responseObserver = new ResponseObserver(attemptEpoch);
        StreamObserver<StreamFrame> observer;
        synchronized (sendLock) {
            if (closed.get()) {
                newChannel.shutdownNow();
                return;
            }
            channel = newChannel;
            StreamServiceGrpc.StreamServiceStub stub = StreamServiceGrpc.newStub(newChannel);
            observer = stub.openStream(responseObserver);
            requestObserver = observer;
            observer.onNext(bindFrame());
        }
    }

    private void scheduleStabilityReset(ResponseObserver responseObserver) {
        try {
            scheduler.schedule(
                    () -> markStreamStable(responseObserver), stableStreamMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closing) {
            LOG.log(Level.FINE, "ecosys stream closed before the stability check", closing);
        }
    }

    private void markStreamStable(ResponseObserver responseObserver) {
        if (closed.get() || epoch.get() != responseObserver.streamEpoch) {
            return;
        }
        synchronized (sendLock) {
            if (requestObserver == null) {
                return;
            }
        }
        reconnectAttempt.set(0L);
    }

    static long backoffBaseMs(long attempt) {
        return Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L << Math.min(attempt, MAX_BACKOFF_SHIFT)));
    }

    static long reconnectDelayMs(long attempt) {
        long base = backoffBaseMs(attempt);
        return Math.min(MAX_BACKOFF_MS, base / 2 + (long) (JITTER.nextDouble() * base));
    }

    private void scheduleReconnect() {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long attempt = reconnectAttempt.getAndIncrement();
        long delayMs = reconnectDelayMs(attempt);
        try {
            scheduler.schedule(this::runReconnect, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closing) {
            reconnectScheduled.set(false);
        }
    }

    private void runReconnect() {
        reconnectScheduled.set(false);
        reconnect();
    }

    private void reconnect() {
        if (closed.get()) {
            return;
        }
        ManagedChannel oldChannel;
        synchronized (sendLock) {
            oldChannel = channel;
            channel = null;
            requestObserver = null;
        }
        if (oldChannel != null) {
            oldChannel.shutdownNow();
        }
        try {
            connect();
        } catch (RuntimeException reconnectFailure) {
            LOG.log(Level.FINE, "ecosys stream reconnect failed", reconnectFailure);
            scheduleReconnect();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        StreamObserver<StreamFrame> observer;
        ManagedChannel openChannel;
        synchronized (sendLock) {
            observer = requestObserver;
            requestObserver = null;
            openChannel = channel;
            channel = null;
        }
        if (observer != null) {
            try {
                observer.onCompleted();
            } catch (RuntimeException ignored) {
                // The stream is already gone; closing must stay best-effort.
            }
        }
        if (openChannel != null) {
            openChannel.shutdownNow();
        }
    }

    private final class ResponseObserver
            implements ClientResponseObserver<StreamFrame, StreamFrame> {
        private final long streamEpoch;
        private final AtomicBoolean connectedAnnounced = new AtomicBoolean(false);
        private volatile ClientCallStreamObserver<StreamFrame> callObserver;

        ResponseObserver(long streamEpoch) {
            this.streamEpoch = streamEpoch;
        }

        @Override
        public void beforeStart(ClientCallStreamObserver<StreamFrame> requestStream) {
            callObserver = requestStream;
            requestStream.setOnReadyHandler(this::onTransportReady);
        }

        void onTransportReady() {
            ClientCallStreamObserver<StreamFrame> call = callObserver;
            if (call == null || !call.isReady()) {
                return;
            }
            announceConnected();
        }

        @Override
        public void onNext(StreamFrame frame) {
            if (closed.get() || epoch.get() != streamEpoch) {
                return;
            }
            reconnectAttempt.set(0L);
            announceConnected();
            for (Listener listener : listeners) {
                listener.onFrame(frame);
            }
        }

        @Override
        public void onError(Throwable cause) {
            if (closed.get() || epoch.get() != streamEpoch) {
                return;
            }
            clearObserver(this);
            for (Listener listener : listeners) {
                listener.onDisconnected(cause);
            }
            scheduleReconnect();
        }

        @Override
        public void onCompleted() {
            if (closed.get() || epoch.get() != streamEpoch) {
                return;
            }
            clearObserver(this);
            for (Listener listener : listeners) {
                listener.onDisconnected(null);
            }
            scheduleReconnect();
        }

        private void announceConnected() {
            if (closed.get() || epoch.get() != streamEpoch) {
                return;
            }
            if (!connectedAnnounced.compareAndSet(false, true)) {
                return;
            }
            scheduleStabilityReset(this);
            for (Listener listener : listeners) {
                listener.onConnected();
            }
        }
    }

    private void clearObserver(ResponseObserver responseObserver) {
        synchronized (sendLock) {
            if (requestObserver == responseObserver.callObserver) {
                requestObserver = null;
            }
        }
    }
}
