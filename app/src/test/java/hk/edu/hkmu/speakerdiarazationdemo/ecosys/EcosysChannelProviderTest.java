package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertNotNull;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import org.junit.Test;

/**
 * Guards the PRODUCTION channel path. {@link EcosysStreamClient} dials the hub
 * with the default {@code ManagedChannelBuilder.forAddress(...)}, which resolves
 * the transport through the gRPC {@code ManagedChannelProvider} ServiceLoader. A
 * gRPC runtime with only {@code grpc-stub}/{@code grpc-protobuf-lite} and no
 * transport artifact throws {@code ProviderNotFoundException} at build time — a
 * failure the in-process-channel unit tests never exercise. This test constructs
 * the real channel so a missing transport fails the suite instead of the APK.
 */
public class EcosysChannelProviderTest {

    @Test
    public void defaultChannelFactoryResolvesTransportProvider() {
        ManagedChannel channel =
                ManagedChannelBuilder.forAddress("127.0.0.1", 50051).usePlaintext().build();
        try {
            assertNotNull("the default channel builder must resolve a transport provider", channel);
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    public void productionClientConstructorUsesTheRealTransport() {
        EcosysStreamClient client = new EcosysStreamClient("127.0.0.1", 50051, "glasses-provider-test");
        try {
            client.start();
        } finally {
            client.close();
        }
    }
}
