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

    public RouteRepository(RoutingProvider primary, RoutingProvider fallback) {
        this(primary, fallback, null);
    }

    public RouteRepository(RoutingProvider primary, RoutingProvider fallback, RoutingProvider finalFallback) {
        this(new RoutingProvider[]{primary, fallback, finalFallback});
    }

    /** Providers are tried in order. Disabled or unconfigured entries are skipped, so a missing
     * middle-provider key never prevents a later configured provider from being used. */
    public RouteRepository(RoutingProvider... providers) {
        this.providers = new ArrayList<>();
        if (providers == null) return;
        for (RoutingProvider provider : Arrays.asList(providers)) {
            if (provider != null) this.providers.add(provider);
        }
    }

    public boolean hasConfiguredProvider() {
        for (RoutingProvider provider : providers) {
            if (isConfigured(provider)) return true;
        }
        return false;
    }

    public void getRoute(double originLat, double originLng, double destinationLat, double destinationLng,
                         SuccessCallback successCallback, ErrorCallback errorCallback) {
        getRoutes(originLat, originLng, destinationLat, destinationLng,
                routes -> successCallback.onSuccess(routes.get(0)), errorCallback);
    }

    public void getRoutes(double originLat, double originLng, double destinationLat, double destinationLng,
                          RoutesCallback successCallback, ErrorCallback errorCallback) {
        requestRoutes(provider -> provider.routes(originLat, originLng, destinationLat, destinationLng),
                successCallback, errorCallback);
    }

    /** Multi-stop counterpart of getRoute: origin -> each waypoint in order -> destination, as one
     * continuous route. Same ordered provider chain as the plain two-point request. */
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
        requestRoutes(provider -> provider.routesWithWaypoints(originLat, originLng, waypoints,
                destinationLat, destinationLng), successCallback, errorCallback);
    }

    private interface RoutesRequest {
        List<RouteResult> run(RoutingProvider provider) throws Exception;
    }

    private void requestRoutes(RoutesRequest request, RoutesCallback successCallback, ErrorCallback errorCallback) {
        new Thread(() -> {
            Exception lastError = null;
            for (RoutingProvider provider : providers) {
                if (!isConfigured(provider)) {
                    Log.d("DriveMateRoute", "skip provider=" + provider.name() + " (not configured)");
                    continue;
                }
                try {
                    Log.i("DriveMateRoute", "request provider=" + provider.name());
                    List<RouteResult> routes = request.run(provider);
                    if (routes == null || routes.isEmpty()) {
                        throw new IllegalStateException(provider.name() + " returned no routes.");
                    }
                    Log.i("DriveMateRoute", "success provider=" + provider.name()
                            + " alternatives=" + routes.size());
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
