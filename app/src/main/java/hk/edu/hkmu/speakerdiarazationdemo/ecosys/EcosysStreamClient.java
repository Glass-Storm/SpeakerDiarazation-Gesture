package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import ecosys.v1.DeviceRole;
import ecosys.v1.StreamFrame;
import ecosys.v1.StreamOpenRequest;
import ecosys.v1.StreamServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
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

    public static final int DEFAULT_HUB_PORT = 50051;

    private static final Logger LOG = Logger.getLogger(EcosysStreamClient.class.getName());
    private static final long INITIAL_BACKOFF_MS = 500L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final int MAX_BACKOFF_SHIFT = 6;

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
    private final ScheduledExecutorService scheduler;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong reconnectAttempt = new AtomicLong(0L);

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
        this.channelFactory = channelFactory;
        this.deviceId = deviceId;
        this.role = role;
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
        ManagedChannel newChannel = channelFactory.create();
        StreamObserver<StreamFrame> observer;
        synchronized (sendLock) {
            if (closed.get()) {
                newChannel.shutdownNow();
                return;
            }
            channel = newChannel;
            StreamServiceGrpc.StreamServiceStub stub = StreamServiceGrpc.newStub(newChannel);
            observer = stub.openStream(new ResponseObserver());
            observer.onNext(bindFrame());
            requestObserver = observer;
        }
        reconnectAttempt.set(0L);
        for (Listener listener : listeners) {
            listener.onConnected();
        }
    }

    private void scheduleReconnect() {
        if (closed.get()) {
            return;
        }
        long attempt = reconnectAttempt.getAndIncrement();
        long delay = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L << Math.min(attempt, MAX_BACKOFF_SHIFT)));
        scheduler.schedule(this::reconnect, delay, TimeUnit.MILLISECONDS);
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

    private final class ResponseObserver implements StreamObserver<StreamFrame> {
        @Override
        public void onNext(StreamFrame frame) {
            for (Listener listener : listeners) {
                listener.onFrame(frame);
            }
        }

        @Override
        public void onError(Throwable cause) {
            if (closed.get()) {
                return;
            }
            for (Listener listener : listeners) {
                listener.onDisconnected(cause);
            }
            scheduleReconnect();
        }

        @Override
        public void onCompleted() {
            if (closed.get()) {
                return;
            }
            for (Listener listener : listeners) {
                listener.onDisconnected(null);
            }
            scheduleReconnect();
        }
    }
}
