package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ecosys.v1.DeviceRole;
import ecosys.v1.EnrollResponse;
import ecosys.v1.GestureResult;
import ecosys.v1.StreamFrame;
import ecosys.v1.StreamOpenRequest;
import ecosys.v1.StreamResult;
import ecosys.v1.TtsAudio;

import com.google.protobuf.ByteString;

import org.junit.Test;

/**
 * Pure-JVM tests for the readable round-trip lines the DEBUG page appends to
 * its log. They pin that a hub result is shown with its speaker label and that
 * a gesture shows its spoken text.
 */
public class DebugFrameTextTest {

    @Test
    public void resultShowsTranscriptSpeakerAndPts() {
        StreamFrame frame = StreamFrame.newBuilder()
                .setResult(StreamResult.newBuilder()
                        .setText("你好，世界")
                        .setSpeakerLabel("Alice")
                        .setPtsMs(1_234L)
                        .build())
                .build();

        String line = DebugFrameText.describe(frame);

        assertTrue(line.contains("字幕結果"));
        assertTrue(line.contains("你好，世界"));
        assertTrue(line.contains("Alice"));
        assertTrue(line.contains("1234 ms"));
    }

    @Test
    public void resultWithoutSpeakerLabelFallsBackToUnknown() {
        StreamFrame frame = StreamFrame.newBuilder()
                .setResult(StreamResult.newBuilder().setText("測試字幕").build())
                .build();

        String line = DebugFrameText.describe(frame);

        assertTrue(line.contains("測試字幕"));
        assertTrue(line.contains(DebugFrameText.UNKNOWN_SPEAKER));
    }

    @Test
    public void gestureShowsSpokenTextAndLabel() {
        StreamFrame frame = StreamFrame.newBuilder()
                .setGesture(GestureResult.newBuilder()
                        .setText("讚")
                        .setLabel("Thumb_Up")
                        .setConfidence(0.93f)
                        .setPtsMs(42L)
                        .build())
                .build();

        String line = DebugFrameText.describe(frame);

        assertTrue(line.contains("手勢結果"));
        assertTrue(line.contains("讚"));
        assertTrue(line.contains("Thumb_Up"));
        assertTrue(line.contains("0.93"));
    }

    @Test
    public void gestureFallsBackToLabelWhenTextIsBlank() {
        StreamFrame frame = StreamFrame.newBuilder()
                .setGesture(GestureResult.newBuilder().setLabel("Victory").build())
                .build();

        String line = DebugFrameText.describe(frame);

        assertTrue(line.contains("Victory"));
    }

    @Test
    public void ttsAudioReportsBytesAndDuration() {
        byte[] pcm = DebugMedia.primaryTonePcm16k();
        StreamFrame frame = StreamFrame.newBuilder()
                .setTtsAudio(TtsAudio.newBuilder()
                        .setPcm1616K(ByteString.copyFrom(pcm))
                        .setPtsMs(10L)
                        .build())
                .build();

        String line = DebugFrameText.describe(frame);

        assertTrue(line.contains("TTS 語音"));
        assertTrue(line.contains(String.valueOf(pcm.length)));
        assertTrue(line.contains("2000 ms"));
    }

    @Test
    public void streamOpenShowsBindingIdentity() {
        StreamFrame frame = StreamFrame.newBuilder()
                .setStreamOpen(StreamOpenRequest.newBuilder()
                        .setDeviceId("glasses-debug")
                        .setRole(DeviceRole.DEVICE_ROLE_GLASS)
                        .build())
                .build();

        String line = DebugFrameText.describe(frame);

        assertTrue(line.contains("glasses-debug"));
        assertTrue(line.contains("DEVICE_ROLE_GLASS"));
    }

    @Test
    public void enrollResponseShowsOkAndReason() {
        StreamFrame frame = StreamFrame.newBuilder()
                .setEnrollResponse(EnrollResponse.newBuilder()
                        .setOk(true)
                        .setReason("已註冊")
                        .build())
                .build();

        assertEquals("註冊結果：ok=true reason=已註冊", DebugFrameText.describe(frame));
    }

    @Test
    public void unsetAndNullFramesAreStillReadable() {
        assertEquals("收到空影格", DebugFrameText.describe(null));

        String line = DebugFrameText.describe(StreamFrame.getDefaultInstance());
        assertTrue(line.contains("其他影格"));
        assertFalse(line.isEmpty());
    }
}
