package ai.drivemate.routing;

import java.util.Collections;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;

public interface RoutingProvider {
    String name();

    RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception;

    default List<RouteResult> routes(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        return Collections.singletonList(route(originLat, originLng, destinationLat, destinationLng));
    }

    default RouteResult routeWithWaypoints(double originLat, double originLng, List<RoutePoint> waypoints,
                                           double destinationLat, double destinationLng) throws Exception {
        if (waypoints == null || waypoints.isEmpty()) return route(originLat, originLng, destinationLat, destinationLng);
        throw new UnsupportedOperationException(name() + " does not support intermediate stops yet.");
    }

    default List<RouteResult> routesWithWaypoints(double originLat, double originLng, List<RoutePoint> waypoints,
                                                  double destinationLat, double destinationLng) throws Exception {
        if (waypoints == null || waypoints.isEmpty()) return routes(originLat, originLng, destinationLat, destinationLng);
        return Collections.singletonList(routeWithWaypoints(originLat, originLng, waypoints, destinationLat, destinationLng));
    }
}
