package ai.drivemate.model;

public class RouteResult {
    public final String providerName;
    public final int distanceMeters;
    public final int durationSeconds;
    public final String rawSummary;

    public RouteResult(String providerName, int distanceMeters, int durationSeconds, String rawSummary) {
        this.providerName = providerName;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.rawSummary = rawSummary;
    }
}
