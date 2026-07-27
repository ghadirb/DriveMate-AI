package ai.drivemate;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.carto.graphics.Color;
import com.carto.styles.LineStyle;
import com.carto.styles.LineStyleBuilder;
import com.carto.styles.MarkerStyle;
import com.carto.styles.MarkerStyleBuilder;
import com.carto.utils.BitmapUtils;

import org.neshan.common.model.LatLng;
import org.neshan.mapsdk.MapView;
import org.neshan.mapsdk.model.Marker;
import org.neshan.mapsdk.model.Polyline;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NavigationEngine;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.PlaceSearchRepository;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.storage.PlaceStore;

/** Map UI is isolated from the driving activity; it returns a selected destination to the existing engine. */
public class MapActivity extends Activity implements LocationListener, NavigationEngine.Listener {
    public static final String EXTRA_ORIGIN_LATITUDE = "origin_latitude";
    public static final String EXTRA_ORIGIN_LONGITUDE = "origin_longitude";
    public static final String EXTRA_NESHAN_KEY = "neshan_key";
    public static final String EXTRA_MAPIR_KEY = "mapir_key";
    public static final String RESULT_LATITUDE = "destination_latitude";
    public static final String RESULT_LONGITUDE = "destination_longitude";
    public static final String RESULT_NAME = "destination_name";
    public static final String RESULT_ADDRESS = "destination_address";
    public static final String RESULT_START_VOICE = "start_voice";
    public static final String RESULT_OPEN_NAVIGATION_MAP = "open_navigation_map";
    public static final String RESULT_ROUTE_INDEX = "route_index";
    public static final String RESULT_STOP_NAVIGATION = "stop_navigation";
    public static final String EXTRA_NAVIGATION_MODE = "navigation_mode";
    public static final String EXTRA_DESTINATION_LATITUDE = "navigation_destination_latitude";
    public static final String EXTRA_DESTINATION_LONGITUDE = "navigation_destination_longitude";
    public static final String EXTRA_DESTINATION_NAME = "navigation_destination_name";
    public static final String EXTRA_DESTINATION_ADDRESS = "navigation_destination_address";
    public static final String EXTRA_NAVIGATION_ROUTE_INDEX = "navigation_route_index";

    private static final double DEFAULT_LATITUDE = 35.7219;
    private static final double DEFAULT_LONGITUDE = 51.3347;
    private MapView map;
    private Marker currentMarker;
    private Marker vehicleMarker;
    private Marker destinationMarker;
    private Polyline routePolyline;
    private List<RouteResult> routeOptions = new ArrayList<>();
    private RouteResult selectedRoute;
    private TextView destinationText;
    private TextView routeText;
    private EditText searchText;
    private SavedPlace destination;
    private double originLatitude;
    private double originLongitude;
    private PlaceSearchRepository placeSearchRepository;
    private RouteRepository routeRepository;
    private PlaceStore placeStore;
    private LocationManager locationManager;
    private boolean navigationMode;
    private boolean followVehicle = true;
    private float lastBearing;
    private int navigationRouteIndex;
    private final NavigationEngine navigationEngine = new NavigationEngine();
    private final SimpleDateFormat etaFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private View turnBannerContainer;
    private TextView turnArrowText;
    private TextView turnDistanceText;
    private TextView turnInstructionText;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        placeStore = new PlaceStore(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        originLatitude = getIntent().getDoubleExtra(EXTRA_ORIGIN_LATITUDE, DEFAULT_LATITUDE);
        originLongitude = getIntent().getDoubleExtra(EXTRA_ORIGIN_LONGITUDE, DEFAULT_LONGITUDE);
        navigationMode = getIntent().getBooleanExtra(EXTRA_NAVIGATION_MODE, false);
        navigationRouteIndex = Math.max(0, getIntent().getIntExtra(EXTRA_NAVIGATION_ROUTE_INDEX, 0));
        String neshanKey = getIntent().getStringExtra(EXTRA_NESHAN_KEY);
        String mapIrKey = getIntent().getStringExtra(EXTRA_MAPIR_KEY);
        NeshanRoutingProvider neshan = new NeshanRoutingProvider(neshanKey);
        MapIrRoutingProvider mapIr = new MapIrRoutingProvider(mapIrKey);
        placeSearchRepository = new PlaceSearchRepository(neshan, mapIr);
        routeRepository = new RouteRepository(neshan, mapIr);

        destinationText = findViewById(R.id.mapDestinationText);
        routeText = findViewById(R.id.mapRouteText);
        searchText = findViewById(R.id.mapSearchText);
        turnBannerContainer = findViewById(R.id.turnBannerContainer);
        turnArrowText = findViewById(R.id.turnArrowText);
        turnDistanceText = findViewById(R.id.turnDistanceText);
        turnInstructionText = findViewById(R.id.turnInstructionText);
        wireControls();
        initializeMap();
        if (navigationMode) {
            findViewById(R.id.startMapNavigationButton).setEnabled(true);
            ((Button) findViewById(R.id.startMapNavigationButton)).setText("بازگشت به داشبورد");
            SavedPlace active = new SavedPlace(getIntent().getStringExtra(EXTRA_DESTINATION_NAME), "active_navigation",
                    getIntent().getDoubleExtra(EXTRA_DESTINATION_LATITUDE, 0d),
                    getIntent().getDoubleExtra(EXTRA_DESTINATION_LONGITUDE, 0d),
                    getIntent().getStringExtra(EXTRA_DESTINATION_ADDRESS), System.currentTimeMillis(), false);
            if (active.latitude != 0d && active.longitude != 0d) selectDestinationWithOptions(active);
            findViewById(R.id.stopMapNavigationButton).setVisibility(View.VISIBLE);
            findViewById(R.id.drivingOverviewButton).setVisibility(View.VISIBLE);
            findViewById(R.id.routeOptionsButton).setVisibility(View.GONE);
            findViewById(R.id.saveMapPlaceButton).setVisibility(View.GONE);
            findViewById(R.id.mapSearchBarRow).setVisibility(View.GONE);
            findViewById(R.id.savedPlacesButton).setVisibility(View.GONE);
        }
    }

    private void wireControls() {
        findViewById(R.id.mapSearchButton).setOnClickListener(v -> searchDestinations());
        findViewById(R.id.mapCloseButton).setOnClickListener(v -> finish());
        findViewById(R.id.myLocationButton).setOnClickListener(v -> focusOrigin());
        findViewById(R.id.savedPlacesButton).setOnClickListener(v -> chooseSavedPlace());
        findViewById(R.id.saveMapPlaceButton).setOnClickListener(v -> saveSelectedPlace());
        findViewById(R.id.routeOptionsButton).setOnClickListener(v -> chooseRouteOption());
        findViewById(R.id.drivingOverviewButton).setOnClickListener(v -> showRouteOverview());
        findViewById(R.id.stopMapNavigationButton).setOnClickListener(v -> stopNavigationFromMap());
        findViewById(R.id.startMapNavigationButton).setOnClickListener(v -> {
            if (navigationMode) finish(); else startSelectedDestination();
        });
        searchText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchDestinations(); return true; }
            return false;
        });
    }

    private void initializeMap() {
        if (getResources().getIdentifier("neshan", "raw", getPackageName()) == 0) {
            routeText.setText("فایل neshan.license در res/raw برنامه وجود ندارد.");
            return;
        }
        try {
            map = new MapView(this);
            ((FrameLayout) findViewById(R.id.mapContainer)).addView(map,
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            map.moveCamera(new LatLng(originLatitude, originLongitude), 0f);
            map.setZoom(14f, 0f);
            showCurrentMarker();
            map.setOnMapLongClickListener(point -> {
                final double latitude = point.getLatitude();
                final double longitude = point.getLongitude();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        selectDestinationWithOptions(new SavedPlace(
                                "نقطه انتخاب‌شده روی نقشه", "map_pin", latitude, longitude,
                                String.format(Locale.US, "%.6f, %.6f", latitude, longitude), System.currentTimeMillis(), false));
                    } catch (RuntimeException error) {
                        Log.e("DriveMateMap", "Could not select map point", error);
                        routeText.setText("انتخاب نقطه روی نقشه انجام نشد. دوباره تلاش کنید.");
                    }
                });
            });
        } catch (LinkageError error) {
            // A malformed or stale SDK artifact must not close the app or block destination search.
            Log.e("DriveMateMap", "Neshan MapView runtime could not be loaded", error);
            map = null;
            routeText.setText("نقشه نشان آماده نشد؛ جست‌وجو و مکان‌های ذخیره‌شده همچنان در دسترس هستند.");
            Toast.makeText(this, "نمایش نقشه در این نسخه آماده نشد.", Toast.LENGTH_LONG).show();
        }
    }

    private void searchDestinations() {
        String term = searchText.getText().toString().trim();
        if (term.isEmpty()) {
            Toast.makeText(this, "نام یا آدرس مقصد را وارد کنید.", Toast.LENGTH_SHORT).show();
            return;
        }
        routeText.setText("در حال جست‌وجوی مقصد...");
        placeSearchRepository.searchAll(term, originLatitude, originLongitude,
                places -> runOnUiThread(() -> showSearchResults(places)),
                error -> runOnUiThread(() -> routeText.setText(error)));
    }

    private void showSearchResults(List<SavedPlace> places) {
        if (places == null || places.isEmpty()) {
            routeText.setText("نتیجه‌ای پیدا نشد.");
            return;
        }
        String[] labels = new String[places.size()];
        for (int i = 0; i < places.size(); i++) {
            SavedPlace place = places.get(i);
            labels[i] = place.name + (place.address == null || place.address.isEmpty() ? "" : "\n" + place.address);
        }
        new AlertDialog.Builder(this).setTitle("انتخاب مقصد")
                .setItems(labels, (dialog, which) -> selectDestinationWithOptions(places.get(which))).show();
    }

    private void searchDestination() {
        String term = searchText.getText().toString().trim();
        if (term.isEmpty()) { Toast.makeText(this, "نام یا آدرس مقصد را وارد کنید.", Toast.LENGTH_SHORT).show(); return; }
        routeText.setText("در حال جست‌وجوی مقصد...");
        placeSearchRepository.search(term, originLatitude, originLongitude,
                place -> runOnUiThread(() -> selectDestinationWithOptions(place)),
                error -> runOnUiThread(() -> routeText.setText(error)));
    }

    private void chooseSavedPlace() {
        ArrayList<SavedPlace> places = new ArrayList<>(placeStore.allPlaces());
        if (places.isEmpty()) { Toast.makeText(this, "مکان ذخیره‌شده‌ای وجود ندارد.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[places.size()];
        for (int i = 0; i < places.size(); i++) names[i] = places.get(i).name;
        new AlertDialog.Builder(this).setTitle("انتخاب مقصد ذخیره‌شده")
                .setItems(names, (dialog, which) -> selectDestinationWithOptions(places.get(which))).show();
    }

    private void selectDestinationWithOptions(SavedPlace place) {
        destination = place;
        destinationText.setText(place.name);
        routeText.setText("در حال آماده‌سازی مسیرهای پیشنهادی...");
        showDestinationMarker(place);
        routeRepository.getRoutes(originLatitude, originLongitude, place.latitude, place.longitude,
                routes -> runOnUiThread(() -> showRouteOptions(routes)),
                error -> runOnUiThread(() -> routeText.setText("دریافت مسیر انجام نشد: " + error)));
    }

    private void showDestinationMarker(SavedPlace place) {
        if (map == null) return;
        if (destinationMarker != null) map.removeMarker(destinationMarker);
        destinationMarker = new Marker(new LatLng(place.latitude, place.longitude), markerStyle(0xff176b87));
        map.addMarker(destinationMarker);
        map.moveCamera(destinationMarker.getLatLng(), 0.25f);
        map.setZoom(15f, 0.25f);
    }

    private void selectDestination(SavedPlace place) {
        destination = place;
        destinationText.setText(place.name);
        routeText.setText("در حال آماده‌سازی پیش‌نمایش مسیر...");
        if (map != null) {
            if (destinationMarker != null) map.removeMarker(destinationMarker);
            destinationMarker = new Marker(new LatLng(place.latitude, place.longitude), markerStyle(0xff176b87));
            map.addMarker(destinationMarker);
            map.moveCamera(destinationMarker.getLatLng(), 0.25f);
            map.setZoom(15f, 0.25f);
        }
        routeRepository.getRoute(originLatitude, originLongitude, place.latitude, place.longitude,
                route -> runOnUiThread(() -> showRoutePreview(route)),
                error -> runOnUiThread(() -> routeText.setText("پیش‌نمایش مسیر در دسترس نیست: " + error)));
    }

    private void showRouteOptions(List<RouteResult> routes) {
        routeOptions = routes == null ? new ArrayList<>() : new ArrayList<>(routes);
        if (routeOptions.isEmpty()) {
            routeText.setText("مسیر قابل استفاده‌ای پیدا نشد.");
            return;
        }
        selectedRoute = routeOptions.get(navigationMode
                ? Math.min(navigationRouteIndex, routeOptions.size() - 1) : 0);
        showRoutePreview(selectedRoute);
        if (navigationMode) startTurnByTurn(selectedRoute);
        if (!navigationMode && routeOptions.size() > 1) chooseRouteOption();
    }

    private void chooseRouteOption() {
        if (routeOptions.isEmpty()) {
            Toast.makeText(this, "ابتدا یک مقصد انتخاب کنید.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[routeOptions.size()];
        for (int i = 0; i < routeOptions.size(); i++) {
            RouteResult route = routeOptions.get(i);
            labels[i] = "مسیر " + (i + 1) + " - " + Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0))
                    + " دقیقه - " + String.format(Locale.US, "%.1f", route.distanceMeters / 1000.0) + " کیلومتر";
        }
        new AlertDialog.Builder(this).setTitle("مسیرهای پیشنهادی")
                .setSingleChoiceItems(labels, Math.max(0, routeOptions.indexOf(selectedRoute)), (dialog, which) -> {
                    selectedRoute = routeOptions.get(which);
                    showRoutePreview(selectedRoute);
                    dialog.dismiss();
                }).show();
    }

    private void showRoutePreview(RouteResult route) {
        int minutes = Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0));
        routeText.setText("مسیر پیشنهادی " + route.providerName + " | " + minutes + " دقیقه | "
                + String.format(Locale.US, "%.1f", route.distanceMeters / 1000.0) + " کیلومتر");
        if (map == null) return;
        if (routePolyline != null) map.removePolyline(routePolyline);
        ArrayList<LatLng> points = new ArrayList<>();
        for (RoutePoint point : route.geometry) points.add(new LatLng(point.latitude, point.longitude));
        if (points.size() < 2) {
            points.add(new LatLng(originLatitude, originLongitude));
            for (RouteStep step : route.steps) points.add(new LatLng(step.latitude, step.longitude));
        }
        if (points.size() > 1) {
            routePolyline = new Polyline(points, routeLineStyle());
            map.addPolyline(routePolyline);
            if (!navigationMode) {
                map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
                map.setZoom(12.5f, 0.25f);
            }
        }
    }

    /** Starts real turn-by-turn tracking for the active route: shows the first maneuver right
     *  away and keeps the banner in sync with this activity's own location stream. This engine
     *  instance is independent from MainActivity's (which drives voice guidance), so opening or
     *  closing the map never disturbs the background voice session. */
    private void startTurnByTurn(RouteResult route) {
        Location current = new Location("gps");
        current.setLatitude(originLatitude);
        current.setLongitude(originLongitude);
        current.setBearing(lastBearing);
        navigationEngine.start(route, this, current);
        turnBannerContainer.setVisibility(View.VISIBLE);
        turnDistanceText.setText("");
        if (!navigationEngine.announceCurrentInstruction()) {
            turnInstructionText.setText("به سمت مقصد حرکت کنید");
            turnArrowText.setText("↑");
        }
    }

    /** Live per-tick update: distance to the upcoming maneuver plus the driving HUD line. The
     *  instruction text itself only changes through onInstruction, so it never flickers between
     *  GPS samples. */
    private void updateTurnBanner(Location location) {
        RouteStep step = navigationEngine.currentStep();
        if (step == null) return;
        Location target = new Location("route");
        target.setLatitude(step.latitude);
        target.setLongitude(step.longitude);
        float metersToTurn = location.distanceTo(target);
        turnDistanceText.setText(formatDistance(Math.round(metersToTurn)));
        updateDrivingHud(metersToTurn);
    }

    /** Approximates remaining distance/time by adding the live distance to the next maneuver to
     *  the provider's per-step distances for every maneuver still ahead, then scales the route's
     *  total duration by that same fraction. It is an estimate (no live traffic per segment), but
     *  it moves with the car instead of freezing at the numbers shown when the route was chosen. */
    private void updateDrivingHud(float metersToCurrentTarget) {
        if (selectedRoute == null || selectedRoute.steps.isEmpty()) return;
        int index = navigationEngine.currentStepIndex();
        int remainingMeters = Math.round(metersToCurrentTarget);
        for (int i = index + 1; i < selectedRoute.steps.size(); i++) {
            remainingMeters += selectedRoute.steps.get(i).distanceMeters;
        }
        int totalMeters = Math.max(1, selectedRoute.distanceMeters);
        double fraction = Math.max(0.02, Math.min(1.0, remainingMeters / (double) totalMeters));
        int remainingSeconds = (int) Math.round(selectedRoute.durationSeconds * fraction);
        long arrivalAt = System.currentTimeMillis() + remainingSeconds * 1000L;
        routeText.setText(formatDistance(remainingMeters) + " مانده • "
                + formatDuration(remainingSeconds) + " دیگر • رسیدن ساعت " + etaFormat.format(new Date(arrivalAt)));
    }

    private String formatDistance(int meters) {
        if (meters < 1000) return Math.max(0, meters) + " متر";
        return String.format(Locale.US, "%.1f کیلومتر", meters / 1000.0);
    }

    private String formatDuration(int seconds) {
        int minutes = Math.max(1, Math.round(seconds / 60f));
        return minutes + " دقیقه";
    }

    /** Provider instructions are free-form Persian text (no maneuver-type enum), so the arrow is a
     *  best-effort keyword match rather than an exact turn code. */
    private String arrowForInstruction(String instruction) {
        if (instruction == null) return "↑";
        if (instruction.contains("راست")) return "↗";
        if (instruction.contains("چپ")) return "↖";
        if (instruction.contains("دوربرگردان") || instruction.contains("برگردان")) return "↺";
        if (instruction.contains("خروج")) return "⤴";
        if (instruction.contains("میدان") || instruction.contains("فلکه")) return "↻";
        return "↑";
    }

    /** Re-fetches a route from the current position after an off-route detection and restarts
     *  turn-by-turn on it, instead of silently continuing to track a maneuver the driver has
     *  already left behind. */
    private void recalculateActiveRoute() {
        if (destination == null) return;
        routeRepository.getRoute(originLatitude, originLongitude, destination.latitude, destination.longitude,
                route -> runOnUiThread(() -> {
                    selectedRoute = route;
                    showRoutePreview(route);
                    startTurnByTurn(route);
                }),
                error -> runOnUiThread(() -> turnInstructionText.setText("بازیابی مسیر انجام نشد: " + error)));
    }

    @Override public void onInstruction(RouteStep step) {
        runOnUiThread(() -> {
            String instruction = step.instruction == null || step.instruction.isEmpty()
                    ? "ادامه در مسیر" : step.instruction;
            turnInstructionText.setText(instruction);
            turnArrowText.setText(arrowForInstruction(instruction));
        });
    }

    @Override public void onOffRoute() {
        runOnUiThread(() -> {
            turnInstructionText.setText("در حال محاسبه مجدد مسیر...");
            turnArrowText.setText("↻");
        });
        recalculateActiveRoute();
    }

    @Override public void onArrived() {
        runOnUiThread(() -> {
            turnInstructionText.setText("به مقصد رسیدید");
            turnDistanceText.setText("");
            turnArrowText.setText("●");
            routeText.setText("سفر به پایان رسید.");
        });
    }

    private void focusOrigin() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, "مکان گوشی خاموش است. آن را روشن کنید.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        if (navigationMode) followVehicle = true;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location latest = gps != null ? gps : network;
            if (latest != null) {
                originLatitude = latest.getLatitude();
                originLongitude = latest.getLongitude();
                showCurrentMarker();
            }
        }
        if (map == null) return;
        map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
        map.setZoom(15f, 0.25f);
    }

    private boolean isLocationEnabled() {
        return locationManager != null && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    private void showCurrentMarker() {
        if (map == null) return;
        if (navigationMode) {
            showVehicleMarker(lastBearing);
            return;
        }
        if (currentMarker != null) map.removeMarker(currentMarker);
        currentMarker = new Marker(new LatLng(originLatitude, originLongitude), markerStyle(0xff2e8b57));
        map.addMarker(currentMarker);
    }

    private void showVehicleMarker(float bearing) {
        if (map == null) return;
        if (vehicleMarker != null) map.removeMarker(vehicleMarker);
        vehicleMarker = new Marker(drivingPosition(), vehicleMarkerStyle(bearing));
        map.addMarker(vehicleMarker);
    }

    private LatLng drivingPosition() {
        if (selectedRoute == null || selectedRoute.geometry.isEmpty()) {
            return new LatLng(originLatitude, originLongitude);
        }
        RoutePoint nearest = null;
        float nearestMeters = Float.MAX_VALUE;
        Location current = new Location("gps");
        current.setLatitude(originLatitude);
        current.setLongitude(originLongitude);
        for (RoutePoint point : selectedRoute.geometry) {
            Location candidate = new Location("route");
            candidate.setLatitude(point.latitude);
            candidate.setLongitude(point.longitude);
            float meters = current.distanceTo(candidate);
            if (meters < nearestMeters) {
                nearestMeters = meters;
                nearest = point;
            }
        }
        return nearest != null && nearestMeters <= 45f
                ? new LatLng(nearest.latitude, nearest.longitude)
                : new LatLng(originLatitude, originLongitude);
    }

    private void showRouteOverview() {
        if (map == null || selectedRoute == null || selectedRoute.geometry.isEmpty()) return;
        followVehicle = false;
        map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
        map.setZoom(12.5f, 0.25f);
    }

    private void stopNavigationFromMap() {
        navigationEngine.stop();
        Intent result = new Intent();
        result.putExtra(RESULT_STOP_NAVIGATION, true);
        setResult(RESULT_OK, result);
        finish();
    }

    private void saveSelectedPlace() {
        if (destination == null) {
            Toast.makeText(this, "ابتدا یک مقصد انتخاب کنید.", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(destination.name);
        new AlertDialog.Builder(this).setTitle("ذخیره مکان")
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = destination.name;
                    placeStore.upsert(new SavedPlace(name, "custom_" + System.currentTimeMillis(), destination.latitude,
                            destination.longitude, destination.address, System.currentTimeMillis(), true));
                    Toast.makeText(this, "مکان ذخیره شد.", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("انصراف", null).show();
    }

    private void startSelectedDestination() {
        if (destination == null) { Toast.makeText(this, "ابتدا مقصد را انتخاب کنید.", Toast.LENGTH_SHORT).show(); return; }
        Intent result = new Intent();
        result.putExtra(RESULT_LATITUDE, destination.latitude);
        result.putExtra(RESULT_LONGITUDE, destination.longitude);
        result.putExtra(RESULT_NAME, destination.name);
        result.putExtra(RESULT_ADDRESS, destination.address);
        result.putExtra(RESULT_OPEN_NAVIGATION_MAP, true);
        result.putExtra(RESULT_ROUTE_INDEX, Math.max(0, routeOptions.indexOf(selectedRoute)));
        setResult(RESULT_OK, result);
        finish();
    }

    private MarkerStyle markerStyle(int color) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        canvas.drawCircle(32f, 32f, 22f, paint);
        paint.setColor(0xffffffff);
        canvas.drawCircle(32f, 32f, 9f, paint);
        MarkerStyleBuilder builder = new MarkerStyleBuilder();
        builder.setSize(34f);
        builder.setBitmap(BitmapUtils.createBitmapFromAndroidBitmap(bitmap));
        return builder.buildStyle();
    }

    private MarkerStyle vehicleMarkerStyle(float bearing) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.rotate(bearing, 48f, 48f);
        Path arrow = new Path();
        arrow.moveTo(48f, 8f);
        arrow.lineTo(76f, 76f);
        arrow.lineTo(48f, 62f);
        arrow.lineTo(20f, 76f);
        arrow.close();
        paint.setColor(0xff176b87);
        canvas.drawPath(arrow, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(0xffffffff);
        canvas.drawPath(arrow, paint);
        MarkerStyleBuilder builder = new MarkerStyleBuilder();
        builder.setSize(46f);
        builder.setBitmap(BitmapUtils.createBitmapFromAndroidBitmap(bitmap));
        return builder.buildStyle();
    }

    private LineStyle routeLineStyle() {
        LineStyleBuilder builder = new LineStyleBuilder();
        builder.setColor(new Color((short) 23, (short) 107, (short) 135, (short) 230));
        builder.setWidth(9f);
        builder.setStretchFactor(0f);
        return builder.buildStyle();
    }

    @Override protected void onResume() {
        super.onResume();
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        if (isLocationEnabled()) {
            long minTimeMs = navigationMode ? 1000L : 2500L;
            float minDistanceM = navigationMode ? 4f : 8f;
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, this);
        }
    }

    @Override protected void onPause() {
        if (locationManager != null) locationManager.removeUpdates(this);
        super.onPause();
    }

    @Override public void onLocationChanged(Location location) {
        originLatitude = location.getLatitude();
        originLongitude = location.getLongitude();
        if (location.hasBearing()) lastBearing = location.getBearing();
        runOnUiThread(() -> {
            showCurrentMarker();
            if (navigationMode && navigationEngine.isNavigating()) {
                navigationEngine.onLocation(location);
                updateTurnBanner(location);
            }
            if (navigationMode && followVehicle && map != null) {
                map.moveCamera(drivingPosition(), 0.35f);
                map.setZoom(17f, 0.35f);
            }
        });
    }
}
