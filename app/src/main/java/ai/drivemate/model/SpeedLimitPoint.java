package ai.drivemate.model;

/** A numeric road-speed value tied to a map point and its supplying data source. */
public final class SpeedLimitPoint {
    public final double latitude;
    public final double longitude;
    public final int kilometersPerHour;
    public final String source;

    public SpeedLimitPoint(double latitude, double longitude, int kilometersPerHour, String source) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.kilometersPerHour = kilometersPerHour;
        this.source = source == null ? "" : source;
    }
}
