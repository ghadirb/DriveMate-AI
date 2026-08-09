package ai.drivemate.routing;

import android.os.Handler;
import android.os.Looper;

import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;

public class RouteRepository {
    public interface SuccessCallback { void onSuccess(RouteResult route); }
    public interface RoutesCallback { void onSuccess(List<RouteResult> routes); }
    public interface ErrorCallback { void onError(String message); }

    private final RoutingProvider primary;
    private final RoutingProvider fallback;
    private final RoutingProvider finalFallback;

    public RouteRepository(RoutingProvider primary, RoutingProvider fallback) {
        this(primary, fallback, null);
    }

    public RouteRepository(RoutingProvider primary, RoutingProvider fallback, RoutingProvider finalFallback) {
        this.primary = primary;
        this.fallback = fallback;
        this.finalFallback = finalFallback;
    }

    public boolean hasConfiguredProvider() {
        return isConfigured(primary) || isConfigured(fallback) || isConfigured(finalFallback);
    }

    public void getRoute(double originLat, double originLng, double destinationLat, double destinationLng,
                         SuccessCallback successCallback, ErrorCallback errorCallback) {
        getRoutes(originLat, originLng, destinationLat, destinationLng,
                routes -> successCallback.onSuccess(routes.get(0)), errorCallback);
    }

    public void getRoutes(double originLat, double originLng, double destinationLat, double destinationLng,
                          RoutesCallback successCallback, ErrorCallback errorCallback) {
        new Thread(() -> {
            try {
                List<RouteResult> routes = primary.routes(originLat, originLng, destinationLat, destinationLng);
                if (routes == null || routes.isEmpty()) throw new IllegalStateException("Primary provider returned no routes.");
                successCallback.onSuccess(routes);
            } catch (Exception primaryError) {
                if (!isConfigured(fallback)) {
                    reportFailure(primaryError, null, null, errorCallback);
                    return;
                }
                try {
                    List<RouteResult> routes = fallback.routes(originLat, originLng, destinationLat, destinationLng);
                    if (routes == null || routes.isEmpty()) throw new IllegalStateException("Fallback provider returned no routes.");
                    successCallback.onSuccess(routes);
                } catch (Exception fallbackError) {
                    if (finalFallback == null || !isConfigured(finalFallback)) {
                        reportFailure(primaryError, fallbackError, null, errorCallback);
                        return;
                    }
                    try {
                        List<RouteResult> routes = finalFallback.routes(originLat, originLng, destinationLat, destinationLng);
                        if (routes == null || routes.isEmpty()) throw new IllegalStateException("Final fallback returned no routes." );
                        successCallback.onSuccess(routes);
                    } catch (Exception finalFallbackError) {
                        reportFailure(primaryError, fallbackError, finalFallbackError, errorCallback);
                    }
                }
            }
        }).start();
    }

    /** Multi-stop counterpart of getRoute: origin -> each waypoint in order -> destination, as one
     *  continuous route. Same primary/fallback/finalFallback chain as the plain two-point request
     *  above; an empty or null waypoints list behaves exactly like the plain request. */
    public void getRoute(double originLat, double originLng, List<RoutePoint> waypoints,
                         double destinationLat, double destinationLng,
                         SuccessCallback successCallback, ErrorCallback errorCallback) {
        if (waypoints == null || waypoints.isEmpty()) {
            getRoute(originLat, originLng, destinationLat, destinationLng, successCallback, errorCallback);
            return;
        }
        getRoutes(originLat, originLng, waypoints, destinationLat, destinationLng,
                routes -> successCallback.onSuccess(routes.get(0)), errorCallback);
    }

    public void getRoutes(double originLat, double originLng, List<RoutePoint> waypoints,
                          double destinationLat, double destinationLng,
                          RoutesCallback successCallback, ErrorCallback errorCallback) {
        if (waypoints == null || waypoints.isEmpty()) {
            getRoutes(originLat, originLng, destinationLat, destinationLng, successCallback, errorCallback);
            return;
        }
        new Thread(() -> {
            try {
                List<RouteResult> routes = primary.routesWithWaypoints(originLat, originLng, waypoints, destinationLat, destinationLng);
                if (routes == null || routes.isEmpty()) throw new IllegalStateException("Primary provider returned no route.");
                successCallback.onSuccess(routes);
            } catch (Exception primaryError) {
                if (!isConfigured(fallback)) {
                    reportFailure(primaryError, null, null, errorCallback);
                    return;
                }
                try {
                    List<RouteResult> routes = fallback.routesWithWaypoints(originLat, originLng, waypoints, destinationLat, destinationLng);
                    if (routes == null || routes.isEmpty()) throw new IllegalStateException("Fallback provider returned no route.");
                    successCallback.onSuccess(routes);
                } catch (Exception fallbackError) {
                    if (finalFallback == null || !isConfigured(finalFallback)) {
                        reportFailure(primaryError, fallbackError, null, errorCallback);
                        return;
                    }
                    try {
                        List<RouteResult> routes = finalFallback.routesWithWaypoints(originLat, originLng, waypoints, destinationLat, destinationLng);
                        if (routes == null || routes.isEmpty()) throw new IllegalStateException("Final fallback returned no route.");
                        successCallback.onSuccess(routes);
                    } catch (Exception finalFallbackError) {
                        reportFailure(primaryError, fallbackError, finalFallbackError, errorCallback);
                    }
                }
            }
        }).start();
    }

    private boolean isConfigured(RoutingProvider provider) {
        if (provider == null) return false;
        if (provider instanceof NeshanRoutingProvider) return ((NeshanRoutingProvider) provider).isConfigured();
        if (provider instanceof MapIrRoutingProvider) return ((MapIrRoutingProvider) provider).isConfigured();
        if (provider instanceof TomTomRoutingProvider) return ((TomTomRoutingProvider) provider).isConfigured();
        if (provider instanceof OpenRouteServiceRoutingProvider) return ((OpenRouteServiceRoutingProvider) provider).isConfigured();
        return true;
    }

    private void reportFailure(Exception primaryError, Exception fallbackError, Exception finalFallbackError,
                               ErrorCallback errorCallback) {
        new Handler(Looper.getMainLooper()).post(() ->
                errorCallback.onError("دریافت مسیر در حال حاضر انجام نشد. اتصال اینترنت را بررسی و دوباره تلاش کنید."));
    }

    private String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Unknown error" : message;
    }
}
