package ai.drivemate.model;

public class RouteStep {
    public final double latitude;
    public final double longitude;
    public final String instruction;
    public final int distanceMeters;

    public RouteStep(double latitude, double longitude, String instruction, int distanceMeters) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.instruction = instruction;
        this.distanceMeters = distanceMeters;
    }
}
