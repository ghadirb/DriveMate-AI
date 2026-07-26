package ai.drivemate.routing;

import java.util.Collections;
import java.util.List;

import ai.drivemate.model.RouteResult;

public interface RoutingProvider {
    String name();

    RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception;

    default List<RouteResult> routes(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        return Collections.singletonList(route(originLat, originLng, destinationLat, destinationLng));
    }
}
