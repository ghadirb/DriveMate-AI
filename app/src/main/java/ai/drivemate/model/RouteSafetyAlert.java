package ai.drivemate.model;

/**
 * A single point-based safety alert tied to the active route: railway crossing, school zone
 * (only meaningful during active hours, decided by the caller), best-effort OSM-tagged
 * accident-prone point, tunnel, narrow bridge, steep grade or a geometrically-detected sharp
 * curve. All of these are best-effort, community-mapped or purely geometric signals - never an
 * official hazard feed - matching the same honesty rules already used for OverpassPoiProvider's
 * speed camera / speed bump / police / traffic-sign hazards and OSM maxspeed lookups.
 */
public final class RouteSafetyAlert {
    public enum Type {
        RAILWAY_CROSSING,
        SCHOOL_ZONE,
        ACCIDENT_PRONE,
        TUNNEL,
        NARROW_BRIDGE,
        STEEP_GRADE,
        SHARP_CURVE,
        SPEED_CAMERA,
        SPEED_BUMP,
        STOP_SIGN
    }

    public final Type type;
    public final double latitude;
    public final double longitude;
    /** Signed incline percent for STEEP_GRADE, or the absolute bearing-change in degrees for
     *  SHARP_CURVE. Unused (0) for every other type. */
    public final double detail;

    public RouteSafetyAlert(Type type, double latitude, double longitude, double detail) {
        this.type = type;
        this.latitude = latitude;
        this.longitude = longitude;
        this.detail = detail;
    }
}
