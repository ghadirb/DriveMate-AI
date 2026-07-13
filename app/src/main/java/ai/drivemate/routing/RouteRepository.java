package ai.drivemate.routing;

import android.os.Handler;
import android.os.Looper;

import ai.drivemate.model.RouteResult;

public class RouteRepository {
    public interface SuccessCallback {
        void onSuccess(RouteResult route);
    }

    public interface ErrorCallback {
        void onError(String message);
    }

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
        new Thread(() -> {
            try {
                successCallback.onSuccess(primary.route(originLat, originLng, destinationLat, destinationLng));
            } catch (Exception primaryError) {
                try {
                    successCallback.onSuccess(fallback.route(originLat, originLng, destinationLat, destinationLng));
                } catch (Exception fallbackError) {
                    String primaryMessage = primaryError.getMessage() == null ? "نامشخص" : primaryError.getMessage();
                    String fallbackMessage = fallbackError.getMessage() == null ? "نامشخص" : fallbackError.getMessage();
                    new Handler(Looper.getMainLooper()).post(() -> errorCallback.onError("نشان: " + primaryMessage + " | map.ir: " + fallbackMessage));
                }
            }
        }).start();
    }
}
