package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import ecosys.v1.EnrollResponse;
import ecosys.v1.GestureResult;
import ecosys.v1.StreamFrame;
import ecosys.v1.StreamOpenRequest;
import ecosys.v1.StreamResult;
import ecosys.v1.TtsAudio;

import java.util.Locale;

/**
 * Turns an inbound {@code ecosys.v1.StreamFrame} into one readable debug-log
 * line.
 *
 * <p>The DEBUG page must show what actually came back from the hub: the
 * transcript text with its speaker label and the gesture text, not just a frame
 * count. Keeping the formatting here (instead of inside the activity) makes the
 * round-trip display unit-testable on a plain JVM.
 */
public final class DebugFrameText {

    /** Displayed when the hub sends a result without a speaker label. */
    public static final String UNKNOWN_SPEAKER = "Unknown";

    private DebugFrameText() {
        // Utility class.
    }

    /** One readable line for {@code frame}; never null. */
    public static String describe(StreamFrame frame) {
        if (frame == null) {
            return "收到空影格";
        }
        switch (frame.getPayloadCase()) {
            case RESULT:
                return describeResult(frame.getResult());
            case GESTURE:
                return describeGesture(frame.getGesture());
            case TTS_AUDIO:
                return describeTtsAudio(frame.getTtsAudio());
            case STREAM_OPEN:
                return describeStreamOpen(frame.getStreamOpen());
            case ENROLL_RESPONSE:
                return describeEnrollResponse(frame.getEnrollResponse());
            case AUDIO_PCM16_16K: {
                int bytes = frame.getAudioPcm1616K().size();
                return "回傳音訊：" + bytes + " 位元組 / " + pcmDurationMs(bytes) + " ms";
            }
            default:
                return "其他影格：" + frame.getPayloadCase().name();
        }
    }

    private static String describeResult(StreamResult result) {
        String text = normalize(result.getText());
        String speaker = normalize(result.getSpeakerLabel());
        String body = text == null ? "（無文字）" : text;
        String label = speaker == null ? UNKNOWN_SPEAKER : speaker;
        return "字幕結果：[" + label + "] " + body + "  (pts=" + result.getPtsMs() + " ms)";
    }

    private static String describeGesture(GestureResult gesture) {
        String shown = GestureText.displayText(gesture.getText(), gesture.getLabel());
        if (shown == null) {
            return "手勢結果：（空白）";
        }
        String label = normalize(gesture.getLabel());
        String labelPart = label == null ? "" : ", label=" + label;
        return "手勢結果：" + shown
                + "  (信心=" + String.format(Locale.US, "%.2f", gesture.getConfidence())
                + labelPart + ", pts=" + gesture.getPtsMs() + " ms)";
    }

    private static String describeTtsAudio(TtsAudio audio) {
        int bytes = audio.getPcm1616K().size();
        return "TTS 語音：" + bytes + " 位元組 / " + pcmDurationMs(bytes) + " ms";
    }

    private static String describeEnrollResponse(EnrollResponse response) {
        return "註冊結果：ok=" + response.getOk() + " reason=" + response.getReason();
    }

    private static String describeStreamOpen(StreamOpenRequest open) {
        return "串流已綁定：" + open.getDeviceId() + " (" + open.getRole().name() + ")";
    }

    private static long pcmDurationMs(int byteCount) {
        return Pcm16.durationMs(byteCount / Pcm16.BYTES_PER_SAMPLE);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
