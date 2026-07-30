package ai.drivemate.model;

/**
 * A single live, point-based traffic incident (accident, closure, roadworks, or another
 * dangerous-condition report) near the active route, from a third-party live traffic feed
 * (see TrafficIncidentProvider). Never an official police/road-authority feed and never treated
 * as certain - matching the same honesty rules already used for OverpassPoiProvider's hazards
 * and WeatherHazardProvider's live weather snapshot.
 */
public final class TrafficIncident {
    public enum Type { ACCIDENT, ROAD_CLOSED, ROADWORK, HAZARD }

    public final String id;
    public final Type type;
    public final double latitude;
    public final double longitude;
    /** Provider-supplied description, already in Persian when the feed's language parameter is
     *  honored; empty string when the provider gave none. Never invented locally. */
    public final String description;
    /** Provider-reported delay in seconds caused by this incident, or 0 when not reported. */
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
