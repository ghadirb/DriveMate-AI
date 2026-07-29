package ai.drivemate.model;

import java.util.Collections;
import java.util.List;

public class RouteResult {
    public final String providerName;
    public final int distanceMeters;
    public final int durationSeconds;
    public final String rawSummary;
    public final List<RouteStep> steps;
    public final List<RoutePoint> geometry;
    /** Optional explicit numeric values returned by the routing provider itself. */
    public final List<SpeedLimitPoint> providerSpeedLimits;

    public RouteResult(String providerName, int distanceMeters, int durationSeconds, String rawSummary) {
        this.providerName = providerName;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.rawSummary = rawSummary;
        this.steps = Collections.emptyList();
        this.geometry = Collections.emptyList();
        this.providerSpeedLimits = Collections.emptyList();
    }

    public RouteResult(String providerName, int distanceMeters, int durationSeconds, String rawSummary, List<RouteStep> steps) {
        this.providerName = providerName;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.rawSummary = rawSummary;
        this.steps = steps == null ? Collections.emptyList() : steps;
        this.geometry = Collections.emptyList();
        this.providerSpeedLimits = Collections.emptyList();
    }

    public RouteResult(String providerName, int distanceMeters, int durationSeconds, String rawSummary,
                       List<RouteStep> steps, List<RoutePoint> geometry) {
        this(providerName, distanceMeters, durationSeconds, rawSummary, steps, geometry, Collections.emptyList());
    }

    public RouteResult(String providerName, int distanceMeters, int durationSeconds, String rawSummary,
                       List<RouteStep> steps, List<RoutePoint> geometry, List<SpeedLimitPoint> providerSpeedLimits) {
        this.providerName = providerName;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.rawSummary = rawSummary;
        this.steps = steps == null ? Collections.emptyList() : steps;
        this.geometry = geometry == null ? Collections.emptyList() : geometry;
        this.providerSpeedLimits = providerSpeedLimits == null ? Collections.emptyList() : providerSpeedLimits;
    }
}
