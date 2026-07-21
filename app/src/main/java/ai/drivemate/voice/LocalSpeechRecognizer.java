package ai.drivemate.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.os.Bundle;

import java.util.ArrayList;

/** Uses Android's on-device recognizer only; it never launches a recognizer activity or Google UI. */
public class LocalSpeechRecognizer implements RecognitionListener {
    public interface Callback { void onText(String text); void onError(); }

    private final Context context;
    private SpeechRecognizer recognizer;
    private Callback callback;

    public LocalSpeechRecognizer(Context context) { this.context = context.getApplicationContext(); }

    public boolean start(Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return false;
        stop();
        this.callback = callback;
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        recognizer.setRecognitionListener(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(intent);
        return true;
    }

    public void stop() { if (recognizer != null) recognizer.stopListening(); }
    public void cancel() {
        if (recognizer != null) recognizer.cancel();
        destroy();
    }
    public void destroy() {
        if (recognizer != null) recognizer.destroy();
        recognizer = null;
        callback = null;
    }

    @Override public void onResults(Bundle results) {
        ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = values == null || values.isEmpty() ? "" : values.get(0);
        Callback value = callback;
        destroy();
        if (value != null && !text.trim().isEmpty()) value.onText(text);
        else if (value != null) value.onError();
    }

    @Override public void onError(int error) { Callback value = callback; destroy(); if (value != null) value.onError(); }
    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }
}
