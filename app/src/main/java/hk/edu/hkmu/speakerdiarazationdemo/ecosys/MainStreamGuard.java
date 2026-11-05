package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

/**
 * Enforces one GLASS session at a time across the settings/enrollment path.
 *
 * <p>The main screen owns the hub stream while recording. The settings gear can
 * launch {@code SpeakerEnrollmentActivity}, which opens its own GLASS client, so
 * the main stream must be closed before that screen starts. This guard keeps the
 * decision (and the stop call) a pure, testable step instead of an inline
 * {@code if} buried in the activity.
 */
public final class MainStreamGuard {

    private MainStreamGuard() {
        // Utility class.
    }

    /** Stops the main recording/stream. */
    @FunctionalInterface
    public interface MainStreamStopper {
        void stopMainStream();
    }

    /**
     * Stops the main stream when it is active and reports whether a stop was
     * requested. The caller must invoke this before launching enrollment.
     */
    public static boolean stopBeforeEnrollment(
            boolean recording,
            boolean streamOpen,
            MainStreamStopper stopper) {
        if (!recording && !streamOpen) {
            return false;
        }
        stopper.stopMainStream();
        return true;
    }
}
