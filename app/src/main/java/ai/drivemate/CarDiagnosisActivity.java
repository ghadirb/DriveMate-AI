package ai.drivemate;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Base64;

import ai.drivemate.ai.CarDiagnosisClient;

/** First-version workflow for sound, video, image, and textual vehicle-symptom analysis. */
public class CarDiagnosisActivity extends android.app.Activity {
    public static final String EXTRA_PROXY_URL = "car_diagnosis_proxy_url";
    private static final int REQUEST_PICK = 701;
    private static final int REQUEST_RECORD_AUDIO = 702;
    private static final int REQUEST_CAPTURE_VIDEO = 703;
    private static final long MAX_AUDIO_BYTES = 8L * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 25L * 1024 * 1024;
    private final Handler handler = new Handler();
    private String proxyUrl, mode = "audio", mediaMime;
    private Uri mediaUri;
    private MediaRecorder recorder;
    private File recordingFile, capturedVideoFile;
    private boolean recording;
    private TextView mediaLabel, status, result;
    private Button recordButton, captureVideoButton, analyzeButton;
    private EditText vehicle, year, mileage, engineState, drivingState, throttleChange, description;

    @Override public void onCreate(Bundle state) { super.onCreate(state); proxyUrl = getIntent().getStringExtra(EXTRA_PROXY_URL); buildUi(); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18), dp(18), dp(18), dp(30)); scroll.addView(root); setContentView(scroll);
        TextView title = text("عیب‌یابی هوشمند خودرو", 25); title.setGravity(Gravity.RIGHT); root.addView(title);
        root.addView(text("نتیجه، تشخیص قطعی مکانیکی نیست. در نشانه‌های خطرناک رانندگی را ادامه ندهید و خودرو را بررسی کنید.", 14));
        RadioGroup group = new RadioGroup(this); group.setOrientation(RadioGroup.VERTICAL);
        addMode(group, "تحلیل صدای موتور"); addMode(group, "تحلیل ویدیو (تصویر و صدای ویدیو)"); addMode(group, "بررسی تصویر"); addMode(group, "شرح مشکل");
        group.check(100); group.setOnCheckedChangeListener((g, id) -> { mode = id == 100 ? "audio" : id == 101 ? "video" : id == 102 ? "image" : "text"; mediaUri = null; mediaMime = null; refreshMediaUi(); }); root.addView(group);
        Button pick = button("انتخاب فایل"); pick.setOnClickListener(v -> pickMedia()); root.addView(pick);
        captureVideoButton = button("ضبط ویدیوی کوتاه"); captureVideoButton.setOnClickListener(v -> captureVideo()); root.addView(captureVideoButton);
        recordButton = button("ضبط ۳۰ ثانیه صدای موتور"); recordButton.setOnClickListener(v -> toggleRecording()); root.addView(recordButton);
        mediaLabel = text("فایلی انتخاب نشده است.", 14); root.addView(mediaLabel);
        vehicle = field(root, "مدل خودرو (اختیاری)"); year = field(root, "سال خودرو (اختیاری)"); mileage = field(root, "کارکرد تقریبی (اختیاری)");
        engineState = field(root, "موتور سرد است یا گرم؟"); drivingState = field(root, "درجا یا در حال حرکت؟"); throttleChange = field(root, "با گاز دادن صدا تغییر می‌کند؟"); description = field(root, "شرح کوتاه مشکل"); description.setMinLines(3);
        analyzeButton = button("ارسال برای تحلیل"); analyzeButton.setOnClickListener(v -> submit()); root.addView(analyzeButton);
        status = text("", 15); root.addView(status); result = text("", 16); result.setPadding(0, dp(12), 0, 0); root.addView(result); refreshMediaUi();
    }

    private void addMode(RadioGroup group, String label) { RadioButton button = new RadioButton(this); button.setId(100 + group.getChildCount()); button.setText(label); button.setTextSize(16); group.addView(button); }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextDirection(View.TEXT_DIRECTION_RTL); view.setPadding(0, dp(8), 0, dp(4)); return view; }
    private EditText field(LinearLayout root, String hint) { EditText input = new EditText(this); input.setHint(hint); input.setTextDirection(View.TEXT_DIRECTION_RTL); root.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input; }
    private Button button(String title) { Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(16); b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); return b; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }

    private void refreshMediaUi() { recordButton.setVisibility("audio".equals(mode) ? View.VISIBLE : View.GONE); captureVideoButton.setVisibility("video".equals(mode) ? View.VISIBLE : View.GONE); mediaLabel.setText(mediaUri == null ? ("text".equals(mode) ? "برای این حالت، شرح مشکل را وارد کنید." : "فایلی انتخاب نشده است.") : "فایل آمادهٔ ارسال: " + mediaMime); }
    private void pickMedia() { if ("text".equals(mode)) { status.setText("برای حالت شرح مشکل، نیازی به فایل نیست."); return; } Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT); pick.addCategory(Intent.CATEGORY_OPENABLE); pick.setType("audio".equals(mode) ? "audio/*" : "video".equals(mode) ? "video/*" : "image/*"); startActivityForResult(pick, REQUEST_PICK); }
    private void toggleRecording() { if (recording) stopRecording(); else startRecording(); }
    private void captureVideo() {
        try {
            File dir = new File(getCacheDir(), "diagnosis-media"); if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("ساخت فضای موقت ممکن نشد.");
            capturedVideoFile = new File(dir, "engine-video-" + System.currentTimeMillis() + ".mp4");
            Uri output = FileProvider.getUriForFile(this, getPackageName() + ".files", capturedVideoFile);
            Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE); intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30); intent.putExtra(MediaStore.EXTRA_SIZE_LIMIT, MAX_VIDEO_BYTES); intent.putExtra(MediaStore.EXTRA_OUTPUT, output); intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(intent, REQUEST_CAPTURE_VIDEO);
        } catch (Exception error) { status.setText("دوربین برای ضبط ویدیو در دسترس نیست: " + error.getMessage()); }
    }
    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO); return; }
        try {
            File dir = new File(getCacheDir(), "diagnosis-media"); if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("ساخت فضای موقت ممکن نشد.");
            recordingFile = new File(dir, "engine-" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder(); recorder.setAudioSource(MediaRecorder.AudioSource.MIC); recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); recorder.setAudioEncodingBitRate(128000); recorder.setOutputFile(recordingFile.getAbsolutePath()); recorder.prepare(); recorder.start();
            recording = true; recordButton.setText("توقف ضبط"); status.setText("در حال ضبط صدای موتور؛ حداکثر ۳۰ ثانیه."); handler.postDelayed(() -> { if (recording) stopRecording(); }, 30_000);
        } catch (Exception error) { status.setText("ضبط آغاز نشد: " + error.getMessage()); releaseRecorder(); }
    }
    private void stopRecording() { try { recorder.stop(); mediaUri = FileProvider.getUriForFile(this, getPackageName() + ".files", recordingFile); mediaMime = "audio/mp4"; status.setText("ضبط آمادهٔ ارسال است."); } catch (Exception error) { status.setText("فایل صوتی معتبر نیست؛ دوباره ضبط کنید."); } finally { recording = false; handler.removeCallbacksAndMessages(null); releaseRecorder(); recordButton.setText("ضبط ۳۰ ثانیه صدای موتور"); refreshMediaUi(); } }
    private void releaseRecorder() { if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} recorder = null; } }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) { super.onRequestPermissionsResult(request, permissions, grants); if (request == REQUEST_RECORD_AUDIO && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startRecording(); else if (request == REQUEST_RECORD_AUDIO) status.setText("مجوز میکروفون برای ضبط لازم است."); }
    @Override protected void onActivityResult(int request, int code, Intent data) { super.onActivityResult(request, code, data); if (request == REQUEST_PICK && code == RESULT_OK && data != null && data.getData() != null) { mediaUri = data.getData(); mediaMime = getContentResolver().getType(mediaUri); refreshMediaUi(); } else if (request == REQUEST_CAPTURE_VIDEO && code == RESULT_OK) { mediaUri = capturedVideoFile != null && capturedVideoFile.exists() ? FileProvider.getUriForFile(this, getPackageName() + ".files", capturedVideoFile) : data == null ? null : data.getData(); mediaMime = "video/mp4"; if (mediaUri == null) status.setText("ویدیوی ضبط‌شده در دسترس نیست؛ لطفاً دوباره تلاش کنید."); refreshMediaUi(); } }

    private void submit() {
        if (proxyUrl == null || proxyUrl.trim().isEmpty()) { status.setText("آدرس پروکسی عیب‌یابی تنظیم نشده است. مقدار CAR_DIAGNOSIS_PROXY_URL را در تنظیمات راه‌دور قرار دهید."); return; }
        if (!"text".equals(mode) && mediaUri == null) { status.setText("یک فایل انتخاب یا ضبط کنید."); return; }
        if ("text".equals(mode) && value(description).isEmpty()) { status.setText("شرح مشکل را وارد کنید."); return; }
        analyzeButton.setEnabled(false); status.setText("فایل در حال ارسال و تحلیل است…"); result.setText("");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject(); body.put("type", mode); body.put("vehicle", value(vehicle)); body.put("year", value(year)); body.put("mileage", value(mileage)); body.put("engineState", value(engineState)); body.put("drivingState", value(drivingState)); body.put("throttleChange", value(throttleChange)); body.put("description", value(description));
                if (mediaUri != null) { String mime = mediaMime == null ? "application/octet-stream" : mediaMime; body.put("mimeType", mime); body.put("mediaBase64", readBase64(mediaUri, limitFor(mode))); }
                CarDiagnosisClient.analyze(proxyUrl.trim(), body, new CarDiagnosisClient.Callback() { public void onSuccess(String answer) { runOnUiThread(() -> { status.setText("تحلیل دریافت شد."); result.setText(formatAnalysis(answer)); analyzeButton.setEnabled(true); }); } public void onFailure(String message) { runOnUiThread(() -> { status.setText("خطا در تحلیل: " + message); analyzeButton.setEnabled(true); }); } });
            } catch (Exception error) { runOnUiThread(() -> { status.setText("فایل قابل ارسال نیست: " + error.getMessage()); analyzeButton.setEnabled(true); }); }
        }).start();
    }
    private long limitFor(String type) { return "audio".equals(type) ? MAX_AUDIO_BYTES : "video".equals(type) ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES; }
    private String readBase64(Uri uri, long limit) throws Exception { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int total = 0, count; try (InputStream input = getContentResolver().openInputStream(uri)) { if (input == null) throw new IllegalStateException("فایل باز نشد."); while ((count = input.read(block)) != -1) { total += count; if (total > limit) throw new IllegalStateException("حجم فایل از حد مجاز بیشتر است."); output.write(block, 0, count); } } return Base64.getEncoder().encodeToString(output.toByteArray()); }
    private String value(EditText input) { return input.getText().toString().trim(); }
    private String formatAnalysis(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) { int first = value.indexOf('\n'); int last = value.lastIndexOf("```"); if (first > 0 && last > first) value = value.substring(first + 1, last).trim(); }
        try {
            JSONObject json = new JSONObject(value); StringBuilder out = new StringBuilder();
            appendJson(out, json, "summary", "خلاصه"); appendJson(out, json, "observations", "مشاهدات");
            if (json.has("probableCauses")) { out.append("\nعلت‌های احتمالی:\n"); org.json.JSONArray causes = json.optJSONArray("probableCauses"); if (causes != null) for (int i = 0; i < causes.length(); i++) { JSONObject cause = causes.optJSONObject(i); if (cause != null) out.append("• ").append(cause.optString("cause", "نامشخص")).append(" (اطمینان ").append(cause.optString("confidence", "نامشخص")).append(")\n"); } }
            appendJson(out, json, "checks", "بررسی‌های پیشنهادی"); appendJson(out, json, "urgency", "فوریت"); appendJson(out, json, "safetyWarning", "هشدار ایمنی"); return out.toString().trim();
        } catch (Exception ignored) { return value; }
    }
    private void appendJson(StringBuilder out, JSONObject json, String key, String label) { if (!json.has(key)) return; String value = json.optString(key, ""); if (!value.isEmpty()) out.append(label).append(": ").append(value).append("\n"); }
    @Override protected void onDestroy() { if (recording) stopRecording(); releaseRecorder(); super.onDestroy(); }
}
