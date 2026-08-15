package ai.drivemate.model;

/**
 * A single live traffic incident near the active route, from the remote Iran traffic feed.
 * This is third-party live data and is never treated as an official police/road-authority feed.
 */
public final class TrafficIncident {
    public enum Type { TRAFFIC_JAM, ACCIDENT, ROAD_CLOSED, ROADWORK, HAZARD }

    public final String id;
    public final Type type;
    public final double latitude;
    public final double longitude;
    public final String description;
    public final int delaySeconds;

    public TrafficIncident(String id, Type type, double latitude, double longitude, String description, int delaySeconds) {
        this.id = id == null ? "" : id;
        this.type = type;
        this.latitude = latitude;
        this.longitude = longitude;
        this.description = description == null ? "" : description;
        this.delaySeconds = delaySeconds;
    }
}
