package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

/**
 * The kind of media a glasses session sends up to the hub.
 *
 * <p>This is deliberately a tiny, Android-free enum so the role → payload mapping
 * stays unit-testable on the JVM without a device.
 */
public enum PayloadKind {
    /** 16 kHz PCM16 microphone audio, streamed to the hub for speech-to-text. */
    AUDIO,
    /** Still images (JPEG), streamed to the hub for gesture recognition. */
    IMAGE
}
