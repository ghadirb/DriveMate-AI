# عیب‌یابی هوشمند خودرو

Android هیچ کلید AvalAI یا نام مدل ثابتی ندارد. برنامه فقط `CAR_DIAGNOSIS_PROXY_URL` را از تنظیمات راه‌دور موجود می‌خواند و فایل را به Web Appِ Google Apps Script می‌فرستد.

## راه‌اندازی GAS

1. پوشهٔ `gas/` را در یک Apps Script project قرار دهید و Web App را با دسترسی مناسب deploy کنید.
2. در **Project Settings → Script properties** این مقدارها را بگذارید: `AVALAI_API_KEY`، `CAR_DIAGNOSIS_MODEL=gemini-2.5-pro` و در صورت نیاز `AVALAI_BASE_URL=https://api.avalai.ir`.
3. URL نهایی Web App را با نام `CAR_DIAGNOSIS_PROXY_URL` به payload تنظیمات راه‌دور اپ اضافه کنید.
4. پس از هر تغییر در `CarDiagnosisProxy.gs`، در Apps Script روی **Save** بزنید، از **Deploy → Manage deployments** نسخهٔ Web App را **Edit** و **Deploy** کنید. صرف تغییر فایل محلی پروژه، Web App منتشرشده را به‌روز نمی‌کند.

برای تعویض مدل، فقط مقدار `CAR_DIAGNOSIS_MODEL` را در Apps Script تغییر دهید؛ نیازی به انتشار APK جدید نیست. `gemini-3.1-pro-preview` نیز در صورتی که حساب AvalAI شما آن را فعال دارد، از همین قرارداد استفاده می‌کند.

## حدود نسخهٔ اول

صوت تا ۸MB (ضبط خودکار حداکثر ۳۰ ثانیه)، تصویر تا ۱۰MB و ویدیو تا ۲۵MB پذیرفته می‌شود. پاسخ همیشه باید احتمالی تلقی شود و جایگزین مکانیک یا دادهٔ OBD-II نیست. برای تست اولیهٔ صوت، MP3 کوتاه بهترین گزینه است؛ ضبط داخلی برنامه فایل M4A تولید می‌کند.
