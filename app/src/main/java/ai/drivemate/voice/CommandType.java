package ai.drivemate.voice;

public enum CommandType {
    SAVE_HOME,
    SAVE_WORK,
    NAVIGATE_HOME,
    NAVIGATE_WORK,
    NAVIGATE_NAMED_PLACE,
    FIND_FUEL,
    FIND_REST,
    /** Generic "find nearby POI" command; the target category travels on Command.poiCategory. */
    FIND_PLACE,
    CONFIRM_SUGGESTION,
    DECLINE_SUGGESTION,
    FUEL_REFILLED,
    VOLUME_UP,
    VOLUME_DOWN,
    REPEAT,
    /** "اسم برنامه چیه؟" / "تو کی هستی؟" style questions - answered instantly and offline with the Persian
     *  app name, never sent to the AI model, so it can never come back with the English name. */
    ASK_APP_NAME,
    ASK_AI,
    UNKNOWN
}
