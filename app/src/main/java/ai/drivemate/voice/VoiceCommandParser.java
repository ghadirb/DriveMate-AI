package ai.drivemate.voice;

import java.util.LinkedHashMap;
import java.util.Map;

import ai.drivemate.routing.PoiCategory;

public class VoiceCommandParser {
    /** Natural driver phrases per POI category, e.g. "بنزینم کم شده" -> PoiCategory.FUEL.
     *  These feed into the same FIND_PLACE + confirm-to-navigate flow for every category. */
    private static final Map<PoiCategory, String[]> CATEGORY_PHRASES = new LinkedHashMap<>();
    static {
        CATEGORY_PHRASES.put(PoiCategory.FUEL, new String[]{
                "پمپ بنزین", "بنزین بزن", "بنزینم کم", "بنزین کم دارم", "سوخت کم دارم", "سوختم کم"});
        CATEGORY_PHRASES.put(PoiCategory.CNG, new String[]{
                "جایگاه cng", "سی ان جی", "گازم کم", "گاز کم دارم"});
        CATEGORY_PHRASES.put(PoiCategory.PARKING, new String[]{
                "پارکینگ می خوام", "پارکینگ میخوام", "جای پارک", "پارک کجاست"});
        CATEGORY_PHRASES.put(PoiCategory.MECHANIC, new String[]{
                "ماشینم خراب شده", "ماشین خراب کرده", "تعمیرگاه می خوام", "نیاز به تعمیرگاه", "مکانیکی می خوام"});
        CATEGORY_PHRASES.put(PoiCategory.TIRE_REPAIR, new String[]{
                "پنچر شدم", "پنچر شده", "لاستیکم پنچر", "چرخم پنچر", "پنچرگیری می خوام"});
        CATEGORY_PHRASES.put(PoiCategory.BATTERY, new String[]{
                "باتری خوابیده", "باطری خوابیده", "باتری تموم کرده", "باتری سازی می خوام"});
        CATEGORY_PHRASES.put(PoiCategory.ROADSIDE_ASSIST, new String[]{
                "امداد خودرو می خوام", "زنگ بزن امداد", "امداد خودرو لازم دارم"});
        CATEGORY_PHRASES.put(PoiCategory.HOSPITAL, new String[]{
                "حالم بد شده", "بیمارستان می خوام", "بیمارستان نزدیک"});
        CATEGORY_PHRASES.put(PoiCategory.CLINIC, new String[]{
                "درمانگاه می خوام", "نیاز به درمانگاه"});
        CATEGORY_PHRASES.put(PoiCategory.PHARMACY, new String[]{
                "داروخانه می خوام", "داروخانه نزدیک", "دارو لازم دارم"});
        CATEGORY_PHRASES.put(PoiCategory.POLICE, new String[]{
                "پلیس می خوام", "کلانتری کجاست", "افسر پلیس لازم دارم"});
        CATEGORY_PHRASES.put(PoiCategory.RESTAURANT, new String[]{
                "گرسنه ام", "گشنمه", "دنبال رستوران", "رستوران می خوام"});
        CATEGORY_PHRASES.put(PoiCategory.COFFEE_SHOP, new String[]{
                "کافی شاپ می خوام", "قهوه می خوام", "خیلی خسته ام", "خوابم میاد"});
        CATEGORY_PHRASES.put(PoiCategory.MOSQUE, new String[]{
                "مسجد می خوام", "جای نماز", "مسجد نزدیک"});
        CATEGORY_PHRASES.put(PoiCategory.RESTROOM, new String[]{
                "دستشویی دارم", "سرویس بهداشتی می خوام", "توالت نزدیک"});
        CATEGORY_PHRASES.put(PoiCategory.ATM, new String[]{
                "عابر بانک می خوام", "خودپرداز نزدیک", "پول نقد لازم دارم"});
    }

    public Command parse(String rawText) {
        String text = normalize(rawText);
        if (containsAll(text, "اینجا", "خانه")) return new Command(CommandType.SAVE_HOME, rawText);
        if (containsAll(text, "اینجا", "کار")) return new Command(CommandType.SAVE_WORK, rawText);
        if (containsAny(text, "برو خانه", "به خانه", "خانه برو")) return new Command(CommandType.NAVIGATE_HOME, rawText);
        if (containsAny(text, "برو محل کار", "به محل کار", "سر کار")) return new Command(CommandType.NAVIGATE_WORK, rawText);
        if (containsAny(text, "بنزین زدم", "باک را پر کردم", "باک پر شد", "سوخت زدم", "پر کردم باک"))
            return new Command(CommandType.FUEL_REFILLED, rawText);
        for (Map.Entry<PoiCategory, String[]> entry : CATEGORY_PHRASES.entrySet()) {
            if (containsAny(text, entry.getValue())) return new Command(CommandType.FIND_PLACE, rawText, entry.getKey());
        }
        if (containsAny(text, "استراحتگاه", "مجتمع خدماتی", "جای استراحت")) return new Command(CommandType.FIND_REST, rawText);
        if (text.startsWith("برو ") || text.startsWith("به ") || text.startsWith("مسیریابی ")) return new Command(CommandType.NAVIGATE_NAMED_PLACE, rawText);
        if (isConfirmWord(text)) return new Command(CommandType.CONFIRM_SUGGESTION, rawText);
        if (isDeclineWord(text)) return new Command(CommandType.DECLINE_SUGGESTION, rawText);
        if (containsAny(text, "بلندتر", "صدا را زیاد", "صدایت را زیاد")) return new Command(CommandType.VOLUME_UP, rawText);
        if (containsAny(text, "کمتر", "صدا را کم", "صدایت را کم")) return new Command(CommandType.VOLUME_DOWN, rawText);
        if (containsAny(text, "تکرار", "دوباره بگو", "مسیر بعدی")) return new Command(CommandType.REPEAT, rawText);
        if (isExplicitQuestion(text)) {
            return new Command(CommandType.ASK_AI, rawText);
        }
        return new Command(CommandType.UNKNOWN, rawText);
    }

    public boolean isExplicitQuestion(String rawText) {
        String text = normalize(rawText);
        return text.contains("؟") || text.endsWith("?") || containsAny(text,
                "چرا", "چطور", "چگونه", "کجا", "چند", "ترافیک", "خلوت", "شلوغ", "وضعیت", "آب و هوا", "استراحت", "پیشنهاد");
    }

    /** Short, unambiguous acknowledgements only — checked as whole words so e.g. "خانه" never
     *  false-matches the bare word "نه" inside it. */
    private boolean isConfirmWord(String text) {
        return equalsWholeWord(text, "بله") || equalsWholeWord(text, "باشه") || equalsWholeWord(text, "آره")
                || equalsWholeWord(text, "قبول") || equalsWholeWord(text, "قبوله")
                || containsAny(text, "مسیر را عوض کن", "مسیر عوض شود", "مسیرو عوض کن");
    }

    private boolean isDeclineWord(String text) {
        return equalsWholeWord(text, "نه") || containsAny(text, "لازم نیست", "نمی خوام", "نمیخوام", "نه ممنون");
    }

    private boolean equalsWholeWord(String text, String word) {
        return text.equals(word) || (" " + text + " ").contains(" " + word + " ");
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().replace('ي', 'ی').replace('ك', 'ک').replace("‌", " ");
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private boolean containsAll(String text, String first, String second) {
        return text.contains(first) && text.contains(second);
    }
}
