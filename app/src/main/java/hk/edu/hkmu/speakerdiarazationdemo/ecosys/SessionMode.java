package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

/**
 * The two deaf/hearing roles the user picks on the first screen. The choice gates
 * what the glasses session does:
 *
 * <ul>
 *   <li>{@link #DEAF} (聽障) sends captured <b>audio</b> to the hub, shows
 *       <b>subtitles</b> (with a speaker tag on each line) and shows the
 *       hub-derived <b>gesture text</b>, but does <b>not</b> play the
 *       gesture audio: the necklace speaks for this role.</li>
 *   <li>{@link #HEARING} (健全) shows the <b>gesture text</b> the hub derives
 *       from the necklace's camera while playing the hub-synthesized audio.</li>
 * </ul>
 *
 * <p>Pure and Android-free by design: the display capabilities the session reads
 * are exposed as explicit predicates and the JVM unit test pins them.
 */
public enum SessionMode {

    /** 聽障人士: send S2 audio, display subtitles + speaker tag + gesture text, no local gesture audio. */
    DEAF("我是聽障人士", true, true, true, false),

    /** 健全人士: display gesture text and play the hub-synthesized audio. */
    HEARING("我是健全人士", false, false, true, true);

    private final String label;
    private final boolean sendsAudio;
    private final boolean showsSubtitles;
    private final boolean showsGestureText;
    private final boolean playsGestureAudio;

    SessionMode(
            String label,
            boolean sendsAudio,
            boolean showsSubtitles,
            boolean showsGestureText,
            boolean playsGestureAudio) {
        this.label = label;
        this.sendsAudio = sendsAudio;
        this.showsSubtitles = showsSubtitles;
        this.showsGestureText = showsGestureText;
        this.playsGestureAudio = playsGestureAudio;
    }

    /** The on-screen choice label (Traditional Chinese, matching the rest of the app). */
    public String label() {
        return label;
    }

    public boolean sendsAudio() {
        return sendsAudio;
    }

    public boolean showsSubtitles() {
        return showsSubtitles;
    }

    /** True when inbound {@code gesture} frames should be rendered as overlay text. */
    public boolean showsGestureText() {
        return showsGestureText;
    }

    /**
     * True in 健全 ({@link #HEARING}) mode: the glasses plays inbound
     * {@code tts_audio} frames locally. False in 聽障 ({@link #DEAF}) mode, where
     * the hub sends the audio to the necklace instead.
     */
    public boolean playsGestureAudio() {
        return playsGestureAudio;
    }

    /**
     * True when this mode consumes any hub frame at all: subtitles / gesture text
     * in 聽障 mode, gesture text / audio in 健全 mode. The stream is only opened
     * when this is true.
     */
    public boolean expectsInboundFrames() {
        return showsSubtitles || showsGestureText;
    }

    /** Parses the value persisted by the chooser, or {@code null} when absent/unknown. */
    public static SessionMode fromStoredName(String storedName) {
        if (storedName == null) {
            return null;
        }
        for (SessionMode mode : values()) {
            if (mode.name().equals(storedName)) {
                return mode;
            }
        }
        return null;
    }
}
