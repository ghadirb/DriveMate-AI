package ai.drivemate.routing;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;

public class RouteRepository {
    public interface SuccessCallback { void onSuccess(RouteResult route); }
    public interface RoutesCallback { void onSuccess(List<RouteResult> routes); }
    public interface ErrorCallback { void onError(String message); }

    private final List<RoutingProvider> providers;

    public RouteRepository(RoutingProvider primary, RoutingProvider fallback) { this(primary, fallback, null); }
    public RouteRepository(RoutingProvider primary, RoutingProvider fallback, RoutingProvider finalFallback) {
        this(new RoutingProvider[]{primary, fallback, finalFallback});
    }

    public RouteRepository(RoutingProvider... providers) {
        this.providers = new ArrayList<>();
        if (providers == null) return;
        for (RoutingProvider provider : Arrays.asList(providers)) if (provider != null) this.providers.add(provider);
    }

    public boolean hasConfiguredProvider() {
        for (RoutingProvider provider : providers) if (isConfigured(provider)) return true;
        return false;
    }

    public void getRoute(double originLat, double originLng, double destinationLat, double destinationLng,
                         SuccessCallback successCallback, ErrorCallback errorCallback) {
        getRoutes(originLat, originLng, destinationLat, destinationLng,
                routes -> successCallback.onSuccess(routes.get(0)), errorCallback);
    }

    public void getRoutes(double originLat, double originLng, double destinationLat, double destinationLng,
                          RoutesCallback successCallback, ErrorCallback errorCallback) {
        getRoutesValidated(originLat, originLng, null, destinationLat, destinationLng,
                provider -> provider.routes(originLat, originLng, destinationLat, destinationLng),
                successCallback, errorCallback);
    }

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
        ArrayList<RoutePoint> cleanWaypoints = new ArrayList<>();
        for (RoutePoint point : waypoints) if (validCoordinate(point)) cleanWaypoints.add(point);
        if (cleanWaypoints.isEmpty()) {
            getRoutes(originLat, originLng, destinationLat, destinationLng, successCallback, errorCallback);
            return;
        }
        getRoutesValidated(originLat, originLng, cleanWaypoints, destinationLat, destinationLng,
                provider -> provider.routesWithWaypoints(originLat, originLng, cleanWaypoints,
                        destinationLat, destinationLng), successCallback, errorCallback);
    }

    private interface RoutesRequest { List<RouteResult> run(RoutingProvider provider) throws Exception; }

    private void getRoutesValidated(double originLat, double originLng, List<RoutePoint> waypoints,
                                    double destinationLat, double destinationLng,
                                    RoutesRequest request, RoutesCallback successCallback,
                                    ErrorCallback errorCallback) {
        if (!validCoordinate(originLat, originLng) || !validCoordinate(destinationLat, destinationLng)) {
            reportFailure(new IllegalArgumentException("Invalid route coordinates"), errorCallback);
            return;
        }
        new Thread(() -> {
            Exception lastError = null;
            for (RoutingProvider provider : providers) {
                if (!isConfigured(provider)) {
                    Log.d("DriveMateRoute", "skip provider=" + provider.name() + " (not configured)");
                    continue;
                }
                try {
                    Log.i("DriveMateRoute", "request provider=" + provider.name());
                    List<RouteResult> rawRoutes = request.run(provider);
                    List<RouteResult> routes = usableRoutes(rawRoutes);
                    if (routes.isEmpty()) throw new IllegalStateException(provider.name() + " returned no usable routes.");
                    Log.i("DriveMateRoute", "success provider=" + provider.name() + " alternatives=" + routes.size());
                    successCallback.onSuccess(routes);
                    return;
                } catch (Exception error) {
                    Log.w("DriveMateRoute", "failed provider=" + provider.name(), error);
                    lastError = error;
                }
            }
            reportFailure(lastError, errorCallback);
        }).start();
    }

    private List<RouteResult> usableRoutes(List<RouteResult> routes) {
        ArrayList<RouteResult> result = new ArrayList<>();
        if (routes == null) return result;
        for (RouteResult route : routes) {
            if (route == null || route.steps == null || route.steps.isEmpty()) continue;
            if (route.geometry != null && route.geometry.size() == 1) continue;
            if (route.geometry != null && route.geometry.size() > 0) {
                boolean valid = true;
                for (RoutePoint point : route.geometry) if (!validCoordinate(point)) { valid = false; break; }
                if (!valid) continue;
            }
            result.add(route);
        }
        return result;
    }

    private boolean validCoordinate(RoutePoint point) {
        return point != null && validCoordinate(point.latitude, point.longitude);
    }

    private boolean validCoordinate(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && !Double.isInfinite(lat) && !Double.isInfinite(lng)
                && lat >= -90d && lat <= 90d && lng >= -180d && lng <= 180d
                && !(Math.abs(lat) < 1e-9 && Math.abs(lng) < 1e-9);
    }

    private boolean isConfigured(RoutingProvider provider) {
        if (provider == null) return false;
        if (provider instanceof NeshanRoutingProvider) return ((NeshanRoutingProvider) provider).isConfigured();
        if (provider instanceof MapIrRoutingProvider) return ((MapIrRoutingProvider) provider).isConfigured();
        if (provider instanceof TomTomRoutingProvider) return ((TomTomRoutingProvider) provider).isConfigured();
        if (provider instanceof OpenRouteServiceRoutingProvider) return ((OpenRouteServiceRoutingProvider) provider).isConfigured();
        return true;
    }

    private void reportFailure(Exception error, ErrorCallback errorCallback) {
        new Handler(Looper.getMainLooper()).post(() ->
                errorCallback.onError("دریافت مسیر در حال حاضر انجام نشد. اتصال اینترنت را بررسی و دوباره تلاش کنید."));
    }
}
