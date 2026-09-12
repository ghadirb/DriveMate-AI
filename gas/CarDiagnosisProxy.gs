/**
 * Deploy as a Google Apps Script web app. Set server-side Script Properties:
 * AVALAI_API_KEY, CAR_DIAGNOSIS_MODEL, and optionally AVALAI_BASE_URL.
 */
function doPost(e) {
  try {
    const request = JSON.parse(e.postData && e.postData.contents || '{}');
    validateRequest_(request);
    const properties = PropertiesService.getScriptProperties();
    const apiKey = properties.getProperty('AVALAI_API_KEY');
    const model = properties.getProperty('CAR_DIAGNOSIS_MODEL') || 'gemini-2.5-pro';
    const baseUrl = (properties.getProperty('AVALAI_BASE_URL') || 'https://api.avalai.ir').replace(/\/$/, '');
    if (!apiKey) throw new Error('تنظیم AVALAI_API_KEY در Script Properties انجام نشده است.');
    const parts = [{text: diagnosisPrompt_(request)}];
    if (request.mediaBase64) parts.push({inlineData: {mimeType: request.mimeType, data: request.mediaBase64}});
    const payload = {contents: [{role: 'user', parts: parts}], generationConfig: {temperature: 0.2, responseMimeType: 'application/json'}};
    const response = UrlFetchApp.fetch(baseUrl + '/v1beta/models/' + encodeURIComponent(model) + ':generateContent', {
      method: 'post', contentType: 'application/json', payload: JSON.stringify(payload), headers: {'x-goog-api-key': apiKey}, muteHttpExceptions: true
    });
    if (response.getResponseCode() >= 300) throw new Error('AvalAI HTTP ' + response.getResponseCode() + ': ' + response.getContentText().slice(0, 500));
    const provider = JSON.parse(response.getContentText());
    const partsOut = (((provider.candidates || [])[0] || {}).content || {}).parts || [];
    const analysis = partsOut.map(p => p.text || '').join('\n').trim();
    if (!analysis) throw new Error('پاسخ تحلیلی از مدل دریافت نشد.');
    return json_({ok: true, analysis: analysis});
  } catch (error) { return json_({ok: false, error: String(error.message || error)}); }
}

function validateRequest_(r) {
  if (['audio', 'video', 'image', 'text'].indexOf(r.type) < 0) throw new Error('نوع تحلیل نامعتبر است.');
  if (r.type !== 'text' && (!r.mediaBase64 || !r.mimeType)) throw new Error('فایل یا نوع فایل ارسال نشده است.');
  if (r.type === 'text' && !String(r.description || '').trim()) throw new Error('شرح مشکل لازم است.');
}

function diagnosisPrompt_(r) {
  const context = ['خودرو: ' + (r.vehicle || 'نامشخص'), 'سال: ' + (r.year || 'نامشخص'), 'کارکرد: ' + (r.mileage || 'نامشخص'), 'موتور: ' + (r.engineState || 'نامشخص'), 'وضعیت حرکت: ' + (r.drivingState || 'نامشخص'), 'تغییر با گاز: ' + (r.throttleChange || 'نامشخص'), 'شرح کاربر: ' + (r.description || 'ندارد')].join('\n');
  return `تو دستیار عیب‌یابی اولیهٔ خودرو هستی. ورودی ${r.type} را واقعاً تحلیل کن؛ برای ویدیو هم تصویر و هم صدای ویدیو را بررسی کن و فقط تبدیل گفتار به متن انجام نده.\n${context}\nپاسخ را فقط به فارسی و به JSON معتبر با کلیدهای summary، observations، probableCauses (آرایه با cause و confidence)، checks، urgency و safetyWarning بده. علت‌ها را احتمالی و از محتمل‌تر به کم‌محتمل‌تر مرتب کن. هرگز خرابی قطعی را ادعا نکن. urgency فقط یکی از عادی، نیازمند بررسی، بررسی سریع، خطر/توقف استفاده باشد. اگر نشانهٔ خطر مثل صدای شدید موتور، دود، نشتی شدید، داغی، چراغ روغن/دما، ترمز یا فرمان دیدی، هشدار صریح توقف ایمن و مراجعه فوری بده.`;
}

function json_(value) { return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON); }
