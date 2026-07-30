package ai.drivemate.model;

/**
 * A single OSM maxspeed reading projected onto the active route's own distance axis (meters from
 * the route start), so speed-zone transitions can be detected and announced before the driver
 * reaches them, not only reacted to afterward. Built once per route from the plain
 * (lat, lon, kmh, source) SpeedLimitPoint list - see MainActivity's buildOrderedSpeedZones.
 */
public final class RouteSpeedZone {
    public final double distanceMeters;
    public final int kilometersPerHour;
    public final double latitude;
    public final double longitude;
    public final String source;

    public RouteSpeedZone(double distanceMeters, int kilometersPerHour, double latitude, double longitude, String source) {
        this.distanceMeters = distanceMeters;
        this.kilometersPerHour = kilometersPerHour;
        this.latitude = latitude;
        this.longitude = longitude;
        this.source = source;
    }
}
