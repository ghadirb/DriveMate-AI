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
    ASK_AI,
    UNKNOWN
}
