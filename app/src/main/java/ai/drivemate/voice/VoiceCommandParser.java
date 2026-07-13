package ai.drivemate.voice;

public class VoiceCommandParser {
    public Command parse(String rawText) {
        String text = normalize(rawText);
        if (containsAll(text, "اینجا", "خانه")) return new Command(CommandType.SAVE_HOME, rawText);
        if (containsAll(text, "اینجا", "کار")) return new Command(CommandType.SAVE_WORK, rawText);
        if (containsAny(text, "برو خانه", "به خانه", "خانه برو")) return new Command(CommandType.NAVIGATE_HOME, rawText);
        if (containsAny(text, "برو محل کار", "به محل کار", "سر کار")) return new Command(CommandType.NAVIGATE_WORK, rawText);
        if (containsAny(text, "پمپ بنزین", "بنزین بزن")) return new Command(CommandType.FIND_FUEL, rawText);
        if (text.startsWith("برو ") || text.startsWith("به ") || text.startsWith("مسیریابی ")) return new Command(CommandType.NAVIGATE_NAMED_PLACE, rawText);
        if (containsAny(text, "بلندتر", "صدا را زیاد", "صدایت را زیاد")) return new Command(CommandType.VOLUME_UP, rawText);
        if (containsAny(text, "کمتر", "صدا را کم", "صدایت را کم")) return new Command(CommandType.VOLUME_DOWN, rawText);
        if (containsAny(text, "تکرار", "دوباره بگو", "مسیر بعدی")) return new Command(CommandType.REPEAT, rawText);
        if (containsAny(text, "چرا", "خلوت", "داروخانه", "پارکینگ", "استراحت")) {
            return new Command(CommandType.ASK_AI, rawText);
        }
        return new Command(CommandType.UNKNOWN, rawText);
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
