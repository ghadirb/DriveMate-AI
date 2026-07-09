package ai.drivemate.routing;

import ai.drivemate.model.RouteResult;

public interface RoutingProvider {
    String name();

    RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception;
}
