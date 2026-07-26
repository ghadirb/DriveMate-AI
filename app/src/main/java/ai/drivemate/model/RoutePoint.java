package ai.drivemate.model;

/** A point on the actual road geometry returned by a routing provider. */
public class RoutePoint {
    public final double latitude;
    public final double longitude;

    public RoutePoint(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
