package ai.drivemate.routing;

import android.os.Handler;
import android.os.Looper;

import java.util.List;

import ai.drivemate.model.RouteResult;

public class RouteRepository {
    public interface SuccessCallback { void onSuccess(RouteResult route); }
    public interface RoutesCallback { void onSuccess(List<RouteResult> routes); }
    public interface ErrorCallback { void onError(String message); }

    private final RoutingProvider primary;
    private final RoutingProvider fallback;

    public RouteRepository(RoutingProvider primary, RoutingProvider fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    public boolean hasConfiguredProvider() {
        return (!(primary instanceof NeshanRoutingProvider) || ((NeshanRoutingProvider) primary).isConfigured())
                || (!(fallback instanceof MapIrRoutingProvider) || ((MapIrRoutingProvider) fallback).isConfigured());
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
                try {
                    List<RouteResult> routes = fallback.routes(originLat, originLng, destinationLat, destinationLng);
                    if (routes == null || routes.isEmpty()) throw new IllegalStateException("Fallback provider returned no routes.");
                    successCallback.onSuccess(routes);
                } catch (Exception fallbackError) {
                    String primaryMessage = messageOf(primaryError);
                    String fallbackMessage = messageOf(fallbackError);
                    new Handler(Looper.getMainLooper()).post(() -> errorCallback.onError(
                            "Neshan: " + primaryMessage + " | map.ir: " + fallbackMessage));
                }
            }
        }).start();
    }

    private String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Unknown error" : message;
    }
}
