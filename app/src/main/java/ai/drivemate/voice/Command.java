package ai.drivemate.voice;

import ai.drivemate.routing.PoiCategory;

public class Command {
    public final CommandType type;
    public final String rawText;
    /** Set only for FIND_PLACE; identifies which nearby POI category was requested. */
    public final PoiCategory poiCategory;

    public Command(CommandType type, String rawText) {
        this(type, rawText, null);
    }

    public Command(CommandType type, String rawText, PoiCategory poiCategory) {
        this.type = type;
        this.rawText = rawText;
        this.poiCategory = poiCategory;
    }
}
