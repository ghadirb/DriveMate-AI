package ai.drivemate.model;

public class RouteStep {
    public final double latitude;
    public final double longitude;
    public final String instruction;
    public final int distanceMeters;
    /** Explicit per-lane turn guidance for this maneuver's intersection, only when the routing
     *  provider's response actually included it (currently: map.ir's OSRM-style
     *  intersections[0].lanes). Null when no such data was returned - never inferred from the
     *  instruction text or road class. See LaneGuidance. */
    public final LaneGuidance lanes;
    /** 0-based position of the intermediate stop this step marks arrival at, in the same order as
     *  the waypoints list passed to the routing provider - or -1 for every ordinary maneuver step
     *  and for the final destination-arrival step. Only set by a provider when multi-stop routing
     *  (see RoutingProvider.routeWithWaypoints) actually built this route; never inferred from
     *  instruction text. NavigationEngine uses this to fire onWaypointReached instead of treating
     *  the stop like a normal turn instruction. */
    public final int waypointOrdinal;

    public RouteStep(double latitude, double longitude, String instruction, int distanceMeters) {
        this(latitude, longitude, instruction, distanceMeters, null);
    }

    public RouteStep(double latitude, double longitude, String instruction, int distanceMeters, LaneGuidance lanes) {
        this(latitude, longitude, instruction, distanceMeters, lanes, -1);
    }

    public RouteStep(double latitude, double longitude, String instruction, int distanceMeters, LaneGuidance lanes,
                     int waypointOrdinal) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.instruction = instruction;
        this.distanceMeters = distanceMeters;
        this.lanes = lanes;
        this.waypointOrdinal = waypointOrdinal;
    }
}
