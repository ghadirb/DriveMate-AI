package ai.drivemate.ai;

public class AiAssistant {
    public interface AnswerCallback {
        void onAnswer(String answer);
    }

    private final String apiKey;

    public AiAssistant(String apiKey) {
        this.apiKey = apiKey;
    }

    public void answer(String question, AnswerCallback callback) {
        new Thread(() -> {
            String normalized = question == null ? "" : question;
            String answer;
            if (apiKey == null || apiKey.isEmpty()) {
                answer = offlineAnswer(normalized);
            } else {
                answer = offlineAnswer(normalized) + "\nاتصال مدل واقعی در این نقطه آماده شده است.";
            }
            callback.onAnswer(answer);
        }).start();
    }

    private String offlineAnswer(String question) {
        if (question.contains("پمپ بنزین")) {
            return "برای پیدا کردن پمپ بنزین، در نسخه بعدی جست‌وجوی POI به API نقشه وصل می‌شود. فعلاً مسیر اصلی حفظ می‌شود.";
        }
        if (question.contains("خلوت")) {
            return "برای مسیر خلوت‌تر باید ترافیک زنده از سرویس نقشه دریافت شود. اگر مسیر جایگزین وجود داشته باشد، دوباره محاسبه می‌شود.";
        }
        if (question.contains("چرا")) {
            return "معمولاً مسیر به‌خاطر خروج از مسیر، ترافیک، یا خطای GPS دوباره محاسبه می‌شود.";
        }
        return "این سؤال برای حالت هوشمند ثبت شد. پاسخ کامل با اتصال مدل فعال می‌شود.";
    }
}
