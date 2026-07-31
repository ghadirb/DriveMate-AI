package ai.drivemate;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.carto.graphics.Color;
import com.carto.styles.LineStyle;
import com.carto.styles.LineStyleBuilder;
import com.carto.styles.MarkerStyle;
import com.carto.styles.MarkerStyleBuilder;
import com.carto.utils.BitmapUtils;
import com.google.android.material.card.MaterialCardView;

import org.neshan.common.model.LatLng;
import org.neshan.mapsdk.MapView;
import org.neshan.mapsdk.model.Marker;
import org.neshan.mapsdk.model.Polyline;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.LaneGuidance;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.TrafficIncident;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.SpeedLimitPoint;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NavigationEngine;
import ai.drivemate.traffic.TrafficIncidentProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.OpenRouteServiceRoutingProvider;
import ai.drivemate.routing.OverpassPoiProvider;
import ai.drivemate.routing.PlaceSearchRepository;
import ai.drivemate.routing.PoiCategory;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.settings.NightModeManager;
import ai.drivemate.storage.PlaceStore;

/** Map UI is isolated from the driving activity; it returns a selected destination to the existing engine. */
public class MapActivity extends Activity implements LocationListener, NavigationEngine.Listener {
    public static final String EXTRA_ORIGIN_LATITUDE = "origin_latitude";
    public static final String EXTRA_ORIGIN_LONGITUDE = "origin_longitude";
    public static final String EXTRA_NESHAN_KEY = "neshan_key";
    public static final String EXTRA_MAPIR_KEY = "mapir_key";
    public static final String EXTRA_TOMTOM_KEY = "tomtom_key";
    public static final String EXTRA_OPENROUTESERVICE_KEY = "openrouteservice_key";
    public static final String RESULT_LATITUDE = "destination_latitude";
    public static final String RESULT_LONGITUDE = "destination_longitude";
    public static final String RESULT_NAME = "destination_name";
    public static final String RESULT_ADDRESS = "destination_address";
    public static final String RESULT_START_VOICE = "start_voice";
    public static final String RESULT_OPEN_NAVIGATION_MAP = "open_navigation_map";
    public static final String RESULT_ROUTE_INDEX = "route_index";
    public static final String RESULT_STOP_NAVIGATION = "stop_navigation";
    /** ArrayList<String> of "lat,lng,name" for every intermediate stop added on this screen, in
     *  visit order, so MainActivity can request the same multi-stop route for real navigation. */
    public static final String RESULT_WAYPOINTS = "destination_waypoints";
    public static final String RESULT_MAIN_TAB = "main_tab";
    public static final String EXTRA_NAVIGATION_MODE = "navigation_mode";
    public static final String EXTRA_DESTINATION_LATITUDE = "navigation_destination_latitude";
    public static final String EXTRA_DESTINATION_LONGITUDE = "navigation_destination_longitude";
    public static final String EXTRA_DESTINATION_NAME = "navigation_destination_name";
    public static final String EXTRA_DESTINATION_ADDRESS = "navigation_destination_address";
    public static final String EXTRA_NAVIGATION_WAYPOINTS = "navigation_waypoints";
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
    private View destinationInfoContainer;
    private View mapRoutePanel;
    private TextView destinationText;
    private TextView routeText;
    private EditText searchText;
    private Button searchClearButton;
    private ProgressBar searchProgress;
    private ScrollView searchResultsPanel;
    private LinearLayout searchResultsContent;
    private HorizontalScrollView routeOptionsScroll;
    private LinearLayout routeOptionsRow;
    private final List<Polyline> alternateRoutePolylines = new ArrayList<>();
    private SavedPlace destination;
    /** Intermediate stops the driver added on this screen, in visit order, between origin and
     *  the selected destination. Empty for a plain single-destination trip (the default). */
    private final List<SavedPlace> routeWaypoints = new ArrayList<>();
    private final List<Marker> waypointMarkers = new ArrayList<>();
    private double originLatitude;
    private double originLongitude;
    private PlaceSearchRepository placeSearchRepository;
    private RouteRepository routeRepository;
    private PlaceStore placeStore;
    private LocationManager locationManager;
    private boolean navigationMode;
    private boolean followVehicle = true;
    private float lastBearing;
    private boolean hasHeading;
    private boolean navigationCameraEnabled;
    private int navigationRouteIndex;
    private final NavigationEngine navigationEngine = new NavigationEngine();
    private final SimpleDateFormat etaFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private View turnBannerContainer;
    private TextView turnArrowText;
    private TextView turnDistanceText;
    private TextView turnInstructionText;
    private TextView turnExpandIcon;
    private TextView roadSpeedLimitText;
    private ScrollView turnStepsScroll;
    private LinearLayout turnStepsContent;
    private boolean turnStepsExpanded;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSuggestionSearch;
    private int activeSearchRequest;
    private boolean selectingSearchResult;
    private boolean orientationWarningLogged;
    private PoiCategory activeNearbyCategory;
    private final List<Marker> nearbyMarkers = new ArrayList<>();
    private final EnumSet<PoiCategory> enabledPoiLayers = EnumSet.noneOf(PoiCategory.class);
    private final Map<PoiCategory, List<SavedPlace>> poiLayerPlaces = new EnumMap<>(PoiCategory.class);
    private int activePoiLayerRequest;
    /** Progressive nationwide widening for enabled POI layers: each category starts at the same
     *  tight "اطراف من" radius as before (see refreshPoiLayers), then every
     *  POI_LAYER_EXPANSION_INTERVAL_MS widens to the next ring in POI_LAYER_EXPANSION_RADII_METERS,
     *  using OpenStreetMap only (never Neshan/map.ir, which are not radius-controllable and would
     *  just repeat the same nearby result at extra API-key cost). This spreads a wide/whole-country
     *  style search out over minutes instead of firing one enormous query that would overload the
     *  public Overpass mirrors or dump thousands of markers on the map at once - and it always goes
     *  through OverpassPoiProvider's existing shared request lock, so widening rings never bypass
     *  the 700ms-minimum-gap / mirror-failover protection already in place for every other caller. */
    private static final double[] POI_LAYER_EXPANSION_RADII_METERS = {45_000d, 120_000d, 260_000d, 520_000d, 1_000_000d};
    private static final int[] POI_LAYER_EXPANSION_ITEM_CAPS = {60, 150, 300, 450, 600};
    private static final long POI_LAYER_EXPANSION_INTERVAL_MS = 75_000L;
    private static final int POI_LAYER_MAX_MARKERS_PER_CATEGORY = 600;
    private final Map<PoiCategory, Integer> poiLayerExpansionStage = new EnumMap<>(PoiCategory.class);
    private final Map<PoiCategory, Set<String>> poiLayerKnownIds = new EnumMap<>(PoiCategory.class);
    private final Handler poiExpansionHandler = new Handler(Looper.getMainLooper());
    private final OverpassPoiProvider layerExpansionProvider = new OverpassPoiProvider();
    private final List<SpeedLimitPoint> routeSpeedLimits = new ArrayList<>();
    private int speedLimitRequestId;
    private final List<Marker> speedLimitMarkers = new ArrayList<>();
    /** Live TomTom point incidents (accident/closure/roadworks/hazard) for the active route -
     *  a separate, refreshable layer from the mostly-static OSM speed-limit markers above. */
    private final List<Marker> trafficIncidentMarkers = new ArrayList<>();
    private List<TrafficIncident> routeTrafficIncidents = new ArrayList<>();
    private TrafficIncidentProvider trafficIncidentProvider;
    private int trafficIncidentRequestId;
    private final Handler trafficIncidentHandler = new Handler(Looper.getMainLooper());
    private final Runnable trafficIncidentRefresh = () -> { if (selectedRoute != null) loadRouteTrafficIncidents(selectedRoute); };
    private static final long TRAFFIC_INCIDENT_REFRESH_MS = 90_000L;
    /** Row of per-lane tiles shown right under the turn banner; see renderLaneGuidance. */
    private LinearLayout laneGuidanceRow;
    /** Backing list for the "نمایش روی نقشه" button on the search-results panel; holds
     *  whatever the last plain-text search returned so the button can plot it on demand. */
    private final List<SavedPlace> lastSearchResultsForMap = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(NightModeManager.wrap(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        NightModeManager.applyWindowBrightness(this);
        placeStore = new PlaceStore(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        originLatitude = getIntent().getDoubleExtra(EXTRA_ORIGIN_LATITUDE, Double.NaN);
        originLongitude = getIntent().getDoubleExtra(EXTRA_ORIGIN_LONGITUDE, Double.NaN);
        if (Double.isNaN(originLatitude) || Double.isNaN(originLongitude)) {
            // The caller had no GPS fix yet (e.g. app just launched). Falling straight back to
            // the hardcoded Tehran default made every "nearby" POI search (fuel, etc.) run around
            // Tehran instead of the driver's real position until a fresh GPS fix eventually landed
            // in onResume. Try the device's last-known fix first - it is still far closer to the
            // truth than a fixed point on the other side of the country.
            Location lastKnown = bestKnownDeviceLocation();
            originLatitude = lastKnown != null ? lastKnown.getLatitude() : DEFAULT_LATITUDE;
            originLongitude = lastKnown != null ? lastKnown.getLongitude() : DEFAULT_LONGITUDE;
        }
        navigationMode = getIntent().getBooleanExtra(EXTRA_NAVIGATION_MODE, false);
        navigationRouteIndex = Math.max(0, getIntent().getIntExtra(EXTRA_NAVIGATION_ROUTE_INDEX, 0));
        restoreNavigationWaypoints();
        String neshanKey = getIntent().getStringExtra(EXTRA_NESHAN_KEY);
        String mapIrKey = getIntent().getStringExtra(EXTRA_MAPIR_KEY);
        String tomtomKey = getIntent().getStringExtra(EXTRA_TOMTOM_KEY);
        String openRouteServiceKey = getIntent().getStringExtra(EXTRA_OPENROUTESERVICE_KEY);
        NeshanRoutingProvider neshan = new NeshanRoutingProvider(neshanKey);
        MapIrRoutingProvider mapIr = new MapIrRoutingProvider(mapIrKey);
        placeSearchRepository = new PlaceSearchRepository(neshan, mapIr, tomtomKey);
        trafficIncidentProvider = new TrafficIncidentProvider(tomtomKey);
        routeRepository = new RouteRepository(neshan, mapIr,
                new OpenRouteServiceRoutingProvider(openRouteServiceKey));

        destinationInfoContainer = findViewById(R.id.mapDestinationInfo);
        mapRoutePanel = findViewById(R.id.mapRoutePanel);
        destinationText = findViewById(R.id.mapDestinationText);
        routeText = findViewById(R.id.mapRouteText);
        searchText = findViewById(R.id.mapSearchText);
        searchClearButton = findViewById(R.id.mapSearchClearButton);
        searchProgress = findViewById(R.id.mapSearchProgress);
        searchResultsPanel = findViewById(R.id.searchResultsPanel);
        searchResultsContent = findViewById(R.id.searchResultsContent);
        routeOptionsScroll = findViewById(R.id.routeOptionsScroll);
        routeOptionsRow = findViewById(R.id.routeOptionsRow);
        turnBannerContainer = findViewById(R.id.turnBannerContainer);
        turnArrowText = findViewById(R.id.turnArrowText);
        turnDistanceText = findViewById(R.id.turnDistanceText);
        turnInstructionText = findViewById(R.id.turnInstructionText);
        turnExpandIcon = findViewById(R.id.turnExpandIcon);
        laneGuidanceRow = findViewById(R.id.laneGuidanceRow);
        roadSpeedLimitText = findViewById(R.id.roadSpeedLimitText);
        turnStepsScroll = findViewById(R.id.turnStepsScroll);
        turnStepsContent = findViewById(R.id.turnStepsContent);
        turnBannerContainer.setOnClickListener(v -> toggleTurnSteps());
        destinationText.setOnClickListener(v -> showWaypointManager());
        wireControls();
        restorePoiLayerPreferences();
        initializeMap();
        if (map != null && !enabledPoiLayers.isEmpty()) refreshPoiLayers();
        if (map != null && isSpeedLimitLayerEnabled() && !navigationMode) loadNearbySpeedLimits();
        if (navigationMode) {
            navigationCameraEnabled = true;
            findViewById(R.id.startMapNavigationButton).setVisibility(View.GONE);
            SavedPlace active = new SavedPlace(getIntent().getStringExtra(EXTRA_DESTINATION_NAME), "active_navigation",
                    getIntent().getDoubleExtra(EXTRA_DESTINATION_LATITUDE, 0d),
                    getIntent().getDoubleExtra(EXTRA_DESTINATION_LONGITUDE, 0d),
                    getIntent().getStringExtra(EXTRA_DESTINATION_ADDRESS), System.currentTimeMillis(), false);
            if (active.latitude != 0d && active.longitude != 0d) selectDestinationWithOptions(active);
            findViewById(R.id.stopMapNavigationButton).setVisibility(View.VISIBLE);
            findViewById(R.id.drivingOverviewButton).setVisibility(View.VISIBLE);
            findViewById(R.id.navigationCameraButton).setVisibility(View.VISIBLE);
            findViewById(R.id.routeOptionsButton).setVisibility(View.GONE);
            findViewById(R.id.routeWaypointsButton).setVisibility(View.GONE);
            findViewById(R.id.saveMapPlaceButton).setVisibility(View.GONE);
            findViewById(R.id.routeOptionsScroll).setVisibility(View.GONE);
            findViewById(R.id.mapSearchBarRow).setVisibility(View.GONE);
            findViewById(R.id.savedPlacesButton).setVisibility(View.GONE);
            findViewById(R.id.nearMeButton).setVisibility(View.GONE);
        }
    }

    private void wireControls() {
        findViewById(R.id.mapSearchButton).setOnClickListener(v -> searchDestinations());
        searchClearButton.setOnClickListener(v -> {
            searchText.setText("");
            searchText.requestFocus();
            showRecentSearches();
        });
        findViewById(R.id.mapCloseButton).setOnClickListener(v -> finish());
        findViewById(R.id.myLocationButton).setOnClickListener(v -> focusOrigin());
        findViewById(R.id.savedPlacesButton).setOnClickListener(v -> chooseSavedPlace());
        findViewById(R.id.nearMeButton).setOnClickListener(v -> showNearMeCategories());
        findViewById(R.id.mapLayersButton).setOnClickListener(v -> showMapLayersDialog());
        findViewById(R.id.speedLimitButton).setOnClickListener(v -> toggleSpeedLimitLayer());
        findViewById(R.id.saveMapPlaceButton).setOnClickListener(v -> saveSelectedPlace());
        findViewById(R.id.routeOptionsButton).setOnClickListener(v -> centerOnSelectedRoute());
        findViewById(R.id.routeWaypointsButton).setOnClickListener(v -> showWaypointManager());
        findViewById(R.id.routeActionsButton).setOnClickListener(v -> showRouteActions());
        findViewById(R.id.drivingOverviewButton).setOnClickListener(v -> showRouteOverview());
        View navigationCameraButton = findViewById(R.id.navigationCameraButton);
        navigationCameraButton.setTooltipText("نمای رانندگی و دنبال کردن خودرو");
        navigationCameraButton.setOnClickListener(v -> enableNavigationCamera());
        findViewById(R.id.mapTabDashboardButton).setOnClickListener(v -> returnToMainTab("dashboard"));
        findViewById(R.id.mapTabMapButton).setAlpha(1f);
        findViewById(R.id.mapTabSavedButton).setOnClickListener(v -> returnToMainTab("saved"));
        findViewById(R.id.mapTabProfileButton).setOnClickListener(v -> returnToMainTab("profile"));
        findViewById(R.id.stopMapNavigationButton).setOnClickListener(v -> stopNavigationFromMap());
        findViewById(R.id.startMapNavigationButton).setOnClickListener(v -> {
            if (navigationMode) finish(); else startSelectedDestination();
        });
        searchText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchDestinations(); return true; }
            return false;
        });
        searchText.setOnFocusChangeListener((view, focused) -> {
            if (focused && searchText.getText().toString().trim().isEmpty()) showRecentSearches();
        });
        searchText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                String term = value == null ? "" : value.toString().trim();
                searchClearButton.setVisibility(term.isEmpty() ? View.GONE : View.VISIBLE);
                if (selectingSearchResult) return;
                activeSearchRequest++;
                if (pendingSuggestionSearch != null) searchHandler.removeCallbacks(pendingSuggestionSearch);
                if (term.isEmpty()) {
                    setSearchLoading(false);
                    showRecentSearches();
                    return;
                }
                if (term.length() < 2) {
                    hideSearchResults();
                    return;
                }
                pendingSuggestionSearch = () -> performSearch(term, false);
                searchHandler.postDelayed(pendingSuggestionSearch, 350L);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        refreshSpeedLimitButton();
    }

    private void returnToMainTab(String tab) {
        Intent result = new Intent();
        result.putExtra(RESULT_MAIN_TAB, tab);
        setResult(RESULT_OK, result);
        finish();
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
                        SavedPlace tapped = new SavedPlace(
                                "مقصد", "map_pin", latitude, longitude,
                                String.format(Locale.US, "%.6f, %.6f", latitude, longitude), System.currentTimeMillis(), false);
                        // A destination already picked: ask whether this new point is a stop along
                        // the way or a replacement destination, instead of always replacing it -
                        // this is the only behavior change versus before, and only once a
                        // destination exists (first pick on a fresh screen is unchanged).
                        if (destination == null) selectDestinationWithOptions(tapped);
                        else if (navigationMode) offerNavigationWaypoint(tapped);
                        else offerMapPointChoice(tapped);
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
            showRecentSearches();
            return;
        }
        if (pendingSuggestionSearch != null) searchHandler.removeCallbacks(pendingSuggestionSearch);
        performSearch(term, true);
    }

    private void performSearch(String term, boolean userInitiated) {
        activeNearbyCategory = null;
        clearNearbyMarkers();
        final int requestId = ++activeSearchRequest;
        setSearchLoading(true);
        if (userInitiated) routeText.setText("در حال جست‌وجوی مقصد...");
        placeSearchRepository.searchAll(term, originLatitude, originLongitude,
                places -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != activeSearchRequest) return;
                    setSearchLoading(false);
                    showSearchResults(places, term);
                }),
                error -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != activeSearchRequest) return;
                    setSearchLoading(false);
                    if (userInitiated) routeText.setText(error);
                    hideSearchResults();
                }));
    }

    private void showSearchResults(List<SavedPlace> places, String query) {
        if (places == null || places.isEmpty()) {
            lastSearchResultsForMap.clear();
            searchResultsContent.removeAllViews();
            addSectionTitle("نتیجه‌ای پیدا نشد");
            showSearchResultsPanel();
            return;
        }
        lastSearchResultsForMap.clear();
        lastSearchResultsForMap.addAll(places);
        searchResultsContent.removeAllViews();
        addShowAllOnMapButton(places.size(), this::showAllResultsOnMap);
        LinkedHashMap<String, List<SavedPlace>> groups = new LinkedHashMap<>();
        for (SavedPlace place : places) {
            String group = placeGroup(place);
            List<SavedPlace> groupItems = groups.get(group);
            if (groupItems == null) {
                groupItems = new ArrayList<>();
                groups.put(group, groupItems);
            }
            groupItems.add(place);
        }
        for (Map.Entry<String, List<SavedPlace>> entry : groups.entrySet()) {
            addSectionTitle(entry.getKey());
            for (SavedPlace place : entry.getValue()) addSearchResultCard(place, query);
        }
        showSearchResultsPanel();
    }

    private void showRecentSearches() {
        if (navigationMode) return;
        activeNearbyCategory = null;
        clearNearbyMarkers();
        lastSearchResultsForMap.clear();
        List<SavedPlace> recent = placeStore.recentPlaces();
        if (recent == null || recent.isEmpty()) {
            hideSearchResults();
            return;
        }
        searchResultsContent.removeAllViews();
        addSectionTitle("جست‌وجوهای اخیر");
        int limit = Math.min(6, recent.size());
        for (int i = 0; i < limit; i++) addSearchResultCard(recent.get(i), "");
        showSearchResultsPanel();
    }

    /** Entry point for the "اطراف من" button: lets the driver pick a POI type, then searches
     *  and shows it exactly like a text search (same result cards, same tap-to-select-destination
     *  behavior) but also drops a marker for every nearby match on the map itself. */
    private void showNearMeCategories() {
        PoiCategory[] categories = PoiCategory.values();
        String[] items = new String[categories.length];
        for (int i = 0; i < categories.length; i++) items[i] = categories[i].display();
        new AlertDialog.Builder(this)
                .setTitle("اطراف من")
                .setItems(items, (dialog, which) -> searchNearbyCategory(categories[which]))
                .show();
    }

    /** Settings only exposes layers that this renderer can actually apply. */
    private void showMapLayersDialog() {
        String[] items = {
                "مکان‌های اطراف روی نقشه",
                isSpeedLimitLayerEnabled() ? "نمایش محدودیت سرعت OSM: روشن" : "نمایش محدودیت سرعت OSM: خاموش",
                "پاک‌کردن همهٔ لایه‌های مکان"
        };
        new AlertDialog.Builder(this)
                .setTitle("تنظیمات نقشه")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showPoiLayerSelection();
                    else if (which == 1) {
                        boolean enabled = !isSpeedLimitLayerEnabled();
                        getSharedPreferences("map_layers", MODE_PRIVATE).edit()
                                .putBoolean("speed_limit_osm", enabled).apply();
                        if (!enabled) {
                            routeSpeedLimits.clear();
                            clearSpeedLimitMarkers();
                            roadSpeedLimitText.setVisibility(View.GONE);
                        } else if (selectedRoute != null) {
                            loadRouteSpeedLimits(selectedRoute);
                        } else {
                            loadNearbySpeedLimits();
                        }
                        refreshSpeedLimitButton();
                        Toast.makeText(this, enabled
                                ? "نمایش و هشدار محدودیت‌های ثبت‌شدهٔ OSM فعال شد."
                                : "نمایش و هشدار محدودیت‌های ثبت‌شدهٔ OSM غیرفعال شد.", Toast.LENGTH_LONG).show();
                    } else clearPoiLayers();
                })
                .show();
    }

    private void toggleSpeedLimitLayer() {
        boolean enabled = !isSpeedLimitLayerEnabled();
        getSharedPreferences("map_layers", MODE_PRIVATE).edit().putBoolean("speed_limit_osm", enabled).apply();
        if (!enabled) {
            routeSpeedLimits.clear();
            clearSpeedLimitMarkers();
            roadSpeedLimitText.setVisibility(View.GONE);
        } else if (selectedRoute != null) {
            loadRouteSpeedLimits(selectedRoute);
        } else {
            loadNearbySpeedLimits();
        }
        refreshSpeedLimitButton();
        Toast.makeText(this, enabled ? "نمایش محدودیت سرعت فعال شد." : "نمایش محدودیت سرعت غیرفعال شد.",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshSpeedLimitButton() {
        View button = findViewById(R.id.speedLimitButton);
        if (button == null) return;
        boolean enabled = isSpeedLimitLayerEnabled();
        button.setSelected(enabled);
        button.setContentDescription(enabled ? "نمایش محدودیت سرعت: روشن" : "نمایش محدودیت سرعت: خاموش");
    }

    private boolean isSpeedLimitLayerEnabled() {
        return getSharedPreferences("map_layers", MODE_PRIVATE).getBoolean("speed_limit_osm", true);
    }

    private void showPoiLayerSelection() {
        PoiCategory[] categories = PoiCategory.values();
        String[] labels = new String[categories.length];
        boolean[] checked = new boolean[categories.length];
        for (int index = 0; index < categories.length; index++) {
            labels[index] = categories[index].display();
            checked[index] = enabledPoiLayers.contains(categories[index]);
        }
        new AlertDialog.Builder(this)
                .setTitle("نمایش مکان‌ها روی نقشه")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("اعمال", (dialog, which) -> {
                    enabledPoiLayers.clear();
                    for (int index = 0; index < categories.length; index++) {
                        if (checked[index]) enabledPoiLayers.add(categories[index]);
                    }
                    savePoiLayerPreferences();
                    refreshPoiLayers();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void clearPoiLayers() {
        enabledPoiLayers.clear();
        poiLayerPlaces.clear();
        poiLayerExpansionStage.clear();
        poiLayerKnownIds.clear();
        poiExpansionHandler.removeCallbacksAndMessages(null);
        activePoiLayerRequest++;
        clearNearbyMarkers();
        savePoiLayerPreferences();
        Toast.makeText(this, "لایه‌های مکان از نقشه حذف شدند.", Toast.LENGTH_SHORT).show();
    }

    private void refreshPoiLayers() {
        clearNearbyMarkers();
        poiLayerPlaces.clear();
        poiLayerExpansionStage.clear();
        poiLayerKnownIds.clear();
        poiExpansionHandler.removeCallbacksAndMessages(null);
        int requestId = ++activePoiLayerRequest;
        if (enabledPoiLayers.isEmpty()) return;
        routeText.setText("در حال آماده‌سازی لایه‌های مکان...");
        for (PoiCategory category : enabledPoiLayers) {
            poiLayerExpansionStage.put(category, 0);
            poiLayerKnownIds.put(category, new HashSet<>());
            placeSearchRepository.searchAll(category.searchTerm, originLatitude, originLongitude,
                    places -> runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || requestId != activePoiLayerRequest) return;
                        List<SavedPlace> initial = places == null ? new ArrayList<>() : new ArrayList<>(places);
                        poiLayerPlaces.put(category, initial);
                        for (SavedPlace place : initial) poiLayerKnownIds.get(category).add(place.kind);
                        renderPoiLayer(category, initial);
                        scheduleNextPoiLayerExpansion(category, requestId);
                    }),
                    error -> Log.w("DriveMateMap", "POI layer unavailable for " + category.name() + ": " + error));
        }
    }

    /** Widens one category's coverage by one ring after POI_LAYER_EXPANSION_INTERVAL_MS, merges only
     *  the genuinely new OSM results (deduped by their stable OSM-element-id kind string) into the
     *  existing markers, and re-schedules itself for the next ring - stopping once the category is
     *  disabled, this refresh cycle is stale, the ring list is exhausted, or
     *  POI_LAYER_MAX_MARKERS_PER_CATEGORY is reached. */
    private void scheduleNextPoiLayerExpansion(PoiCategory category, int requestId) {
        int nextStage = poiLayerExpansionStage.getOrDefault(category, 0) + 1;
        if (nextStage >= POI_LAYER_EXPANSION_RADII_METERS.length) return;
        poiExpansionHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || requestId != activePoiLayerRequest
                    || !enabledPoiLayers.contains(category)) return;
            List<SavedPlace> known = poiLayerPlaces.get(category);
            if (known != null && known.size() >= POI_LAYER_MAX_MARKERS_PER_CATEGORY) return;
            new Thread(() -> {
                List<SavedPlace> widened;
                try {
                    widened = layerExpansionProvider.searchNearby(category.searchTerm, originLatitude, originLongitude,
                            POI_LAYER_EXPANSION_RADII_METERS[nextStage], POI_LAYER_EXPANSION_ITEM_CAPS[nextStage]);
                } catch (Exception exception) {
                    Log.w("DriveMateMap", "POI layer expansion failed for " + category.name() + ": " + exception.getMessage());
                    return;
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != activePoiLayerRequest
                            || !enabledPoiLayers.contains(category)) return;
                    poiLayerExpansionStage.put(category, nextStage);
                    Set<String> ids = poiLayerKnownIds.get(category);
                    List<SavedPlace> current = poiLayerPlaces.get(category);
                    if (ids == null || current == null || widened == null) { scheduleNextPoiLayerExpansion(category, requestId); return; }
                    ArrayList<SavedPlace> freshOnly = new ArrayList<>();
                    for (SavedPlace place : widened) {
                        if (current.size() + freshOnly.size() >= POI_LAYER_MAX_MARKERS_PER_CATEGORY) break;
                        if (ids.add(place.kind)) freshOnly.add(place);
                    }
                    if (!freshOnly.isEmpty()) {
                        current.addAll(freshOnly);
                        addPoiLayerMarkers(category, freshOnly);
                        routeText.setText("لایه‌های فعال: " + enabledPoiLayers.size() + " | " + nearbyMarkers.size() + " مکان روی نقشه");
                    }
                    scheduleNextPoiLayerExpansion(category, requestId);
                });
            }).start();
        }, POI_LAYER_EXPANSION_INTERVAL_MS);
    }

    /** Draws only the newly-discovered places from a widening ring (not the whole category again),
     *  so an expansion tick never re-adds duplicate markers for places already on the map. */
    private void addPoiLayerMarkers(PoiCategory category, List<SavedPlace> newPlaces) {
        if (map == null) return;
        MarkerStyle style = poiMarkerStyle(category.icon);
        for (SavedPlace place : newPlaces) {
            Marker marker = new Marker(new LatLng(place.latitude, place.longitude), style);
            map.addMarker(marker);
            nearbyMarkers.add(marker);
        }
    }

    private void renderPoiLayer(PoiCategory category, List<SavedPlace> places) {
        if (map == null || places == null) return;
        MarkerStyle style = poiMarkerStyle(category.icon);
        // Capped at POI_LAYER_MAX_MARKERS_PER_CATEGORY rather than the initial 60-item search
        // ceiling: this method is also used to redraw the FULL cumulative set once progressive
        // expansion (see scheduleNextPoiLayerExpansion) has grown a category beyond its first batch,
        // e.g. when onResume redraws cached markers after the map's rendering surface lost them.
        int limit = Math.min(POI_LAYER_MAX_MARKERS_PER_CATEGORY, places.size());
        for (int index = 0; index < limit; index++) {
            SavedPlace place = places.get(index);
            Marker marker = new Marker(new LatLng(place.latitude, place.longitude), style);
            map.addMarker(marker);
            nearbyMarkers.add(marker);
        }
        routeText.setText("لایه‌های فعال: " + enabledPoiLayers.size() + " | " + nearbyMarkers.size() + " مکان روی نقشه");
    }

    private void savePoiLayerPreferences() {
        StringBuilder values = new StringBuilder();
        for (PoiCategory category : enabledPoiLayers) {
            if (values.length() > 0) values.append(',');
            values.append(category.name());
        }
        getSharedPreferences("map_layers", MODE_PRIVATE).edit().putString("poi_categories", values.toString()).apply();
    }

    private void restorePoiLayerPreferences() {
        String saved = getSharedPreferences("map_layers", MODE_PRIVATE).getString("poi_categories", "");
        if (saved == null || saved.trim().isEmpty()) return;
        for (String name : saved.split(",")) {
            try { enabledPoiLayers.add(PoiCategory.valueOf(name)); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private void searchNearbyCategory(PoiCategory category) {
        if (pendingSuggestionSearch != null) searchHandler.removeCallbacks(pendingSuggestionSearch);
        activeNearbyCategory = category;
        searchText.setText("");
        closeSearchUi();
        setSearchLoading(true);
        routeText.setText("در حال جست‌وجوی " + category.label + " در اطراف شما...");
        final int requestId = ++activeSearchRequest;
        placeSearchRepository.searchAll(category.searchTerm, originLatitude, originLongitude,
                places -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != activeSearchRequest) return;
                    setSearchLoading(false);
                    showNearbyResults(places, category);
                }),
                error -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != activeSearchRequest) return;
                    setSearchLoading(false);
                    routeText.setText(category.label + " در اطراف پیدا نشد: " + error);
                }));
    }

    private void showNearbyResults(List<SavedPlace> places, PoiCategory category) {
        clearNearbyMarkers();
        searchResultsContent.removeAllViews();
        if (places == null || places.isEmpty()) {
            addSectionTitle(category.display() + " - موردی در اطراف پیدا نشد");
            showSearchResultsPanel();
            routeText.setText("موردی برای " + category.label + " در اطراف پیدا نشد.");
            return;
        }
        List<SavedPlace> sorted = new ArrayList<>(places);
        sorted.sort(Comparator.comparingDouble(place ->
                distanceKm(originLatitude, originLongitude, place.latitude, place.longitude)));
        addShowAllOnMapButton(sorted.size(), () -> showNearbyMarkers(sorted, category));
        addSectionTitle(category.display() + " در اطراف شما");
        for (SavedPlace place : sorted) addSearchResultCard(place, "");
        showSearchResultsPanel();
        routeText.setText(sorted.size() + " مورد " + category.label + " پیدا شد. نزدیک‌ترین "
                + formatDistance(sorted.get(0)) + " است.");
    }

    private void showNearbyMarkers(List<SavedPlace> places, PoiCategory category) {
        if (map == null) return;
        MarkerStyle style = poiMarkerStyle(category.icon);
        // Matches renderPoiLayer's cap so "اطراف من" and a map POI layer never show a different
        // count of pins for the exact same category/location result set (PlaceSearchRepository
        // caps nearby results at 60).
        int limit = Math.min(60, places.size());
        for (int i = 0; i < limit; i++) {
            SavedPlace place = places.get(i);
            Marker marker = new Marker(new LatLng(place.latitude, place.longitude), style);
            map.addMarker(marker);
            nearbyMarkers.add(marker);
        }
        SavedPlace nearest = places.get(0);
        map.moveCamera(new LatLng(nearest.latitude, nearest.longitude), 0.3f);
        map.setZoom(14f, 0.3f);
    }

    private void clearNearbyMarkers() {
        if (map != null) for (Marker marker : nearbyMarkers) map.removeMarker(marker);
        nearbyMarkers.clear();
    }

    /** Same round-badge style as other markers, with the category emoji drawn on top so POI
     *  markers are visually distinct from the plain destination/vehicle dots. */
    private MarkerStyle poiMarkerStyle(String emoji) {
        Bitmap bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(0xffffffff);
        canvas.drawCircle(36f, 36f, 30f, circle);
        circle.setStyle(Paint.Style.STROKE);
        circle.setStrokeWidth(3f);
        circle.setColor(0xff176b87);
        canvas.drawCircle(36f, 36f, 30f, circle);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(34f);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float y = 36f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(emoji, 36f, y, text);
        MarkerStyleBuilder builder = new MarkerStyleBuilder();
        builder.setSize(38f);
        builder.setBitmap(BitmapUtils.createBitmapFromAndroidBitmap(bitmap));
        return builder.buildStyle();
    }

    /** Tappable banner shown above the grouped result list; plots the given places (search
     *  results or an "اطراف من" category) as markers in one tap and reveals the map by hiding
     *  the results panel, so both flows behave identically. Hidden entirely if the map failed
     *  to load. */
    private void addShowAllOnMapButton(int count, Runnable plotAction) {
        if (map == null) return;
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(dp(2));
        card.setRadius(dp(14));
        card.setCardBackgroundColor(0xff176b87);
        card.setRippleColor(ColorStateList.valueOf(0x33ffffff));
        card.setClickable(true);
        card.setFocusable(true);
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(2), 0, dp(9));
        searchResultsContent.addView(card, cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(11), dp(12), dp(11));
        card.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView icon = new TextView(this);
        icon.setText("\ud83d\udccd");
        icon.setTextSize(19f);
        row.addView(icon, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText("نمایش " + count + " نتیجه روی نقشه");
        label.setTextColor(0xffffffff);
        label.setTextSize(15f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.setOnClickListener(view -> {
            plotAction.run();
            hideSearchResults();
        });
    }

    /** Plots every result from the last text search as a numbered marker (same order as the
     *  list below) and frames the camera so all of them fit on screen at once. Reuses the same
     *  nearbyMarkers list/clear path as the "اطراف من" flow, so the two never leave stray pins
     *  behind for each other. */
    private void showAllResultsOnMap() {
        if (map == null || lastSearchResultsForMap.isEmpty()) return;
        clearNearbyMarkers();
        int limit = Math.min(30, lastSearchResultsForMap.size());
        double minLatitude = Double.MAX_VALUE, maxLatitude = -Double.MAX_VALUE;
        double minLongitude = Double.MAX_VALUE, maxLongitude = -Double.MAX_VALUE;
        for (int i = 0; i < limit; i++) {
            SavedPlace place = lastSearchResultsForMap.get(i);
            Marker marker = new Marker(new LatLng(place.latitude, place.longitude), numberedMarkerStyle(i + 1));
            map.addMarker(marker);
            nearbyMarkers.add(marker);
            minLatitude = Math.min(minLatitude, place.latitude);
            maxLatitude = Math.max(maxLatitude, place.latitude);
            minLongitude = Math.min(minLongitude, place.longitude);
            maxLongitude = Math.max(maxLongitude, place.longitude);
        }
        double centerLatitude = (minLatitude + maxLatitude) / 2d;
        double centerLongitude = (minLongitude + maxLongitude) / 2d;
        double spanDegrees = Math.max(maxLatitude - minLatitude, maxLongitude - minLongitude);
        map.moveCamera(new LatLng(centerLatitude, centerLongitude), 0.3f);
        map.setZoom(zoomForSpan(spanDegrees), 0.3f);
        Toast.makeText(this, limit + " مورد روی نقشه نمایش داده شد.", Toast.LENGTH_SHORT).show();
    }

    /** Rough zoom-from-bounding-box heuristic: the Neshan map SDK used here has no fitBounds
     *  call, so this picks a fixed zoom step from the span in degrees, the same fixed-zoom
     *  approach the activity already uses elsewhere (e.g. showRouteOverview). */
    private float zoomForSpan(double spanDegrees) {
        if (spanDegrees <= 0.003d) return 15f;
        if (spanDegrees <= 0.01d) return 14f;
        if (spanDegrees <= 0.03d) return 12.5f;
        if (spanDegrees <= 0.08d) return 11f;
        if (spanDegrees <= 0.2d) return 9.5f;
        if (spanDegrees <= 0.5d) return 8f;
        if (spanDegrees <= 1.2d) return 6.5f;
        return 5f;
    }

    /** Same round-badge look as poiMarkerStyle but filled with the brand color and a number
     *  instead of a category emoji, so plain text-search pins read as "list item #N" rather
     *  than being confused with a POI-category marker from "اطراف من". */
    private MarkerStyle numberedMarkerStyle(int number) {
        Bitmap bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(0xff176b87);
        canvas.drawCircle(36f, 36f, 30f, circle);
        circle.setStyle(Paint.Style.STROKE);
        circle.setStrokeWidth(3f);
        circle.setColor(0xffffffff);
        canvas.drawCircle(36f, 36f, 30f, circle);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xffffffff);
        text.setTextSize(30f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float y = 36f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(String.valueOf(number), 36f, y, text);
        MarkerStyleBuilder builder = new MarkerStyleBuilder();
        builder.setSize(38f);
        builder.setBitmap(BitmapUtils.createBitmapFromAndroidBitmap(bitmap));
        return builder.buildStyle();
    }

    private void addSectionTitle(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(0xff3f5362);
        header.setTextSize(14f);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setPadding(dp(8), dp(10), dp(8), dp(4));
        searchResultsContent.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addSearchResultCard(SavedPlace place, String query) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(dp(2));
        card.setRadius(dp(14));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0xffd9e2e8);
        card.setCardBackgroundColor(0xffffffff);
        card.setRippleColor(ColorStateList.valueOf(0x223d8fb0));
        card.setClickable(true);
        card.setFocusable(true);
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(3), 0, dp(7));
        searchResultsContent.addView(card, cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView icon = new TextView(this);
        icon.setText(placeIcon(place));
        icon.setGravity(android.view.Gravity.CENTER);
        icon.setTextSize(23f);
        icon.setTextColor(0xff176b87);
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(46)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), 0, dp(4), 0);
        row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(highlight(place.name == null ? "مکان بدون نام" : place.name, query));
        title.setTextColor(0xff16222b);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        content.addView(title);

        String hierarchy = administrativeHierarchy(place);
        if (!hierarchy.isEmpty()) {
            TextView subtitle = new TextView(this);
            subtitle.setText(highlight(hierarchy, query));
            subtitle.setTextColor(0xff566a78);
            subtitle.setTextSize(13f);
            subtitle.setMaxLines(3);
            subtitle.setPadding(0, dp(3), 0, 0);
            content.addView(subtitle);
        }

        TextView metadata = new TextView(this);
        metadata.setText("\u2570 " + formatDistance(place) + " - " + placeTypeLabel(place));
        metadata.setTextColor(0xff71838e);
        metadata.setTextSize(12f);
        metadata.setPadding(0, dp(6), 0, 0);
        content.addView(metadata);

        card.setOnClickListener(view -> {
            placeStore.addRecent(place);
            selectingSearchResult = true;
            searchText.setText(place.name == null ? "" : place.name);
            selectingSearchResult = false;
            closeSearchUi();
            selectDestinationWithOptions(place);
        });
        if (query == null || query.trim().isEmpty()) {
            card.setOnLongClickListener(view -> {
                editRecentSearch(place);
                return true;
            });
        }
    }

    private void editRecentSearch(SavedPlace place) {
        new AlertDialog.Builder(this)
                .setTitle("جست‌وجوی اخیر")
                .setItems(new String[]{"ویرایش نام", "حذف از تاریخچه"}, (dialog, action) -> {
                    if (action == 1) {
                        placeStore.removeRecent(place);
                        showRecentSearches();
                        return;
                    }
                    EditText input = new EditText(this);
                    input.setSingleLine(true);
                    input.setText(place.name);
                    new AlertDialog.Builder(this)
                            .setTitle("ویرایش نام مکان")
                            .setView(input)
                            .setPositiveButton("ذخیره", (saveDialog, which) -> {
                                String name = input.getText().toString().trim();
                                if (!name.isEmpty()) placeStore.renameRecent(place, name);
                                showRecentSearches();
                            })
                            .setNegativeButton("انصراف", null)
                            .show();
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void showSearchResultsPanel() {
        if (searchResultsPanel.getVisibility() != View.VISIBLE) {
            searchResultsPanel.setAlpha(0f);
            searchResultsPanel.setVisibility(View.VISIBLE);
            searchResultsPanel.animate().alpha(1f).setDuration(160L).start();
        }
        searchResultsPanel.post(() -> searchResultsPanel.fullScroll(View.FOCUS_UP));
    }

    private void hideSearchResults() {
        if (searchResultsPanel.getVisibility() != View.VISIBLE) return;
        searchResultsPanel.animate().alpha(0f).setDuration(120L).withEndAction(() -> {
            searchResultsPanel.setVisibility(View.GONE);
            searchResultsPanel.setAlpha(1f);
        }).start();
    }

    private void closeSearchUi() {
        if (pendingSuggestionSearch != null) searchHandler.removeCallbacks(pendingSuggestionSearch);
        activeSearchRequest++;
        setSearchLoading(false);
        hideSearchResults();
        searchText.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchText.getWindowToken(), 0);
    }

    private void setSearchLoading(boolean loading) {
        searchProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private String placeGroup(SavedPlace place) {
        String type = placeTypeLabel(place);
        return "مکان‌های " + type;
    }

    private String placeTypeLabel(SavedPlace place) {
        if (activeNearbyCategory != null) return activeNearbyCategory.label;
        String value = normalizeForUi((place.name == null ? "" : place.name) + " "
                + (place.address == null ? "" : place.address) + " " + (place.kind == null ? "" : place.kind));
        if (value.contains("پمپ بنزین") || value.contains("جایگاه سوخت")) return "سوخت";
        if (value.contains("بیمارستان") || value.contains("درمانگاه") || value.contains("داروخانه")) return "درمانی";
        if (value.contains("رستوران") || value.contains("کافه")) return "غذا و نوشیدنی";
        if (value.contains("روستا") || value.contains("village")) return "روستا";
        if (value.contains("شهر") || value.contains("بخش") || value.contains("استان")
                || value.contains("city") || value.contains("county")) return "شهر و منطقه";
        return "مکان‌ها";
    }

    private String placeIcon(SavedPlace place) {
        if (activeNearbyCategory != null) return activeNearbyCategory.icon;
        String type = placeTypeLabel(place);
        if ("سوخت".equals(type)) return "\u26fd";
        if ("درمانی".equals(type)) return "\u2695";
        if ("غذا و نوشیدنی".equals(type)) return "\u2615";
        if ("روستا".equals(type)) return "\u2302";
        if ("شهر و منطقه".equals(type)) return "\u25c9";
        return "\u25cf";
    }

    private String administrativeHierarchy(SavedPlace place) {
        String address = place.address == null ? "" : place.address.trim();
        String type = placeTypeLabel(place);
        String prefix = "";
        if ("روستا".equals(type) && !normalizeForUi(address).contains("روستا")) prefix = "روستای " + place.name;
        else if ("شهر و منطقه".equals(type) && !normalizeForUi(address).contains("شهر")) prefix = "شهر یا منطقه";
        if (address.isEmpty()) return prefix;
        String[] parts = address.split("[،,]");
        StringBuilder hierarchy = new StringBuilder(prefix);
        for (int i = 0; i < parts.length && i < 4; i++) {
            String part = parts[i].trim();
            if (part.isEmpty() || part.equals(place.name)) continue;
            if (hierarchy.length() > 0) hierarchy.append('\n');
            hierarchy.append(part);
        }
        return hierarchy.toString();
    }

    private String formatDistance(SavedPlace place) {
        double distanceKm = distanceKm(originLatitude, originLongitude, place.latitude, place.longitude);
        if (distanceKm < 1d) return Math.max(1, (int) Math.round(distanceKm * 1000d)) + " متر تا شما";
        return String.format(Locale.US, "%.1f کیلومتر تا شما", distanceKm);
    }

    private double distanceKm(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double value = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371d * 2d * Math.atan2(Math.sqrt(value), Math.sqrt(1d - value));
    }

    private CharSequence highlight(String value, String query) {
        SpannableString styled = new SpannableString(value == null ? "" : value);
        if (query == null || query.trim().isEmpty()) return styled;
        String lowerValue = normalizeForHighlight(styled.toString());
        String lowerQuery = normalizeForHighlight(query.trim());
        int start = lowerValue.indexOf(lowerQuery);
        if (start >= 0) {
            int end = start + lowerQuery.length();
            styled.setSpan(new ForegroundColorSpan(0xff087e8b), start, end, 0);
            styled.setSpan(new StyleSpan(Typeface.BOLD), start, end, 0);
        }
        return styled;
    }

    private String normalizeForUi(String value) {
        if (value == null) return "";
        return value.replace('\u064a', '\u06cc').replace('\u0649', '\u06cc').replace('\u0643', '\u06a9')
                .replace("\u200c", " ").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeForHighlight(String value) {
        if (value == null) return "";
        return value.replace('\u064a', '\u06cc').replace('\u0649', '\u06cc').replace('\u0643', '\u06a9')
                .replace("\u200c", " ").toLowerCase(Locale.ROOT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        if (mapRoutePanel != null) mapRoutePanel.setVisibility(View.VISIBLE);
        if (destinationInfoContainer != null) destinationInfoContainer.setVisibility(View.VISIBLE);
        findViewById(R.id.routeOptionsButton).setVisibility(navigationMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.saveMapPlaceButton).setVisibility(navigationMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.routeWaypointsButton).setVisibility(!navigationMode && !routeWaypoints.isEmpty() ? View.VISIBLE : View.GONE);
        destinationText.setText(waypointLabelSuffix(place.name));
        routeText.setText("در حال آماده‌سازی مسیرهای پیشنهادی...");
        if (routeOptionsScroll != null) routeOptionsScroll.setVisibility(View.GONE);
        showDestinationMarker(place);
        if (routeWaypoints.isEmpty()) {
            routeRepository.getRoutes(originLatitude, originLongitude, place.latitude, place.longitude,
                    routes -> runOnUiThread(() -> showRouteOptions(routes)),
                    error -> runOnUiThread(() -> routeText.setText("دریافت مسیر انجام نشد: " + error)));
        } else {
            requestRouteWithWaypoints();
        }
    }

    private String waypointLabelSuffix(String name) {
        return routeWaypoints.isEmpty() ? name : name + " (" + routeWaypoints.size() + " توقف میانی)";
    }

    /** Long-press on the map with a destination already selected: ask whether the tapped point is
     *  an intermediate stop along the current trip or a full replacement destination, instead of
     *  always replacing the destination as before. */
    private void offerMapPointChoice(SavedPlace tapped) {
        new AlertDialog.Builder(this)
                .setTitle("این نقطه چه نقشی داشته باشد؟")
                .setItems(new String[]{"افزودن به‌عنوان توقف میانی", "تغییر مقصد به این نقطه"}, (dialog, which) -> {
                    if (which == 0) addWaypoint(tapped); else selectDestinationWithOptions(tapped);
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    /** In active navigation, send the new stop back to MainActivity so its real navigation engine
     *  reroutes through it as well; changing only this map preview would leave spoken guidance stale. */
    private void offerNavigationWaypoint(SavedPlace tapped) {
        new AlertDialog.Builder(this)
                .setTitle("افزودن توقف میانی")
                .setMessage("مسیر از توقف انتخابی عبور کند و دوباره محاسبه شود؟")
                .setPositiveButton("افزودن و محاسبه مسیر", (dialog, which) -> {
                    routeWaypoints.add(tapped);
                    Intent result = new Intent();
                    result.putExtra(RESULT_LATITUDE, destination.latitude);
                    result.putExtra(RESULT_LONGITUDE, destination.longitude);
                    result.putExtra(RESULT_NAME, destination.name);
                    result.putExtra(RESULT_ADDRESS, destination.address);
                    result.putExtra(RESULT_OPEN_NAVIGATION_MAP, true);
                    result.putExtra(RESULT_ROUTE_INDEX, 0);
                    result.putStringArrayListExtra(RESULT_WAYPOINTS, encodeWaypoints());
                    setResult(RESULT_OK, result);
                    finish();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void showRouteActions() {
        if (destination == null) return;
        if (navigationMode) {
            new AlertDialog.Builder(this)
                    .setTitle(destination.name)
                    .setItems(new String[]{"نمای کامل مسیر", "افزودن توقف با نگه داشتن روی نقشه", "مدیریت توقف‌های میانی"}, (dialog, which) -> {
                        if (which == 0) showRouteOverview();
                        else if (which == 1) Toast.makeText(this, "روی نقطهٔ دلخواه نقشه کمی نگه دارید، سپس افزودن توقف را انتخاب کنید.", Toast.LENGTH_LONG).show();
                        else showWaypointManager();
                    }).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(destination.name)
                .setItems(new String[]{"نمای کامل مسیر", "ذخیره مکان", "مدیریت توقف‌های میانی"}, (dialog, which) -> {
                    if (which == 0) centerOnSelectedRoute();
                    else if (which == 1) saveSelectedPlace();
                    else showWaypointManager();
                }).show();
    }

    private void addWaypoint(SavedPlace place) {
        routeWaypoints.add(place);
        drawWaypointMarkers();
        findViewById(R.id.routeWaypointsButton).setVisibility(navigationMode ? View.GONE : View.VISIBLE);
        Toast.makeText(this, "توقف اضافه شد: " + place.name, Toast.LENGTH_SHORT).show();
        if (destination != null) {
            destinationText.setText(waypointLabelSuffix(destination.name));
            requestRouteWithWaypoints();
        }
    }

    /** Same destination, now routed through every stop in routeWaypoints (visit order). */
    private void requestRouteWithWaypoints() {
        if (destination == null) return;
        routeText.setText("در حال آماده‌سازی مسیر با توقف‌های میانی...");
        if (routeOptionsScroll != null) routeOptionsScroll.setVisibility(View.GONE);
        routeRepository.getRoutes(originLatitude, originLongitude, waypointCoordinates(), destination.latitude, destination.longitude,
                routes -> runOnUiThread(() -> showRouteOptions(routes)),
                error -> runOnUiThread(() -> routeText.setText("دریافت مسیر با توقف میانی انجام نشد: " + error)));
    }

    private void requestRouteWithoutWaypoints() {
        if (destination == null) return;
        routeRepository.getRoutes(originLatitude, originLongitude, destination.latitude, destination.longitude,
                routes -> runOnUiThread(() -> showRouteOptions(routes)),
                error -> runOnUiThread(() -> routeText.setText("دریافت مسیر انجام نشد: " + error)));
    }

    private List<RoutePoint> waypointCoordinates() {
        List<RoutePoint> points = new ArrayList<>();
        for (SavedPlace place : routeWaypoints) points.add(new RoutePoint(place.latitude, place.longitude));
        return points;
    }

    private void restoreNavigationWaypoints() {
        ArrayList<String> encoded = getIntent().getStringArrayListExtra(EXTRA_NAVIGATION_WAYPOINTS);
        if (encoded == null) return;
        for (String value : encoded) {
            SavedPlace point = decodeWaypoint(value);
            if (point != null) routeWaypoints.add(point);
        }
    }

    private ArrayList<String> encodeWaypoints() {
        ArrayList<String> encoded = new ArrayList<>();
        for (SavedPlace point : routeWaypoints) {
            encoded.add(point.latitude + "," + point.longitude + "," + point.name.replace(",", " "));
        }
        return encoded;
    }

    private SavedPlace decodeWaypoint(String value) {
        if (value == null) return null;
        String[] parts = value.split(",", 3);
        if (parts.length < 2) return null;
        try {
            double latitude = Double.parseDouble(parts[0]);
            double longitude = Double.parseDouble(parts[1]);
            if (Double.isNaN(latitude) || Double.isNaN(longitude)) return null;
            String name = parts.length == 3 && !parts[2].trim().isEmpty() ? parts[2].trim() : "توقف میانی";
            return new SavedPlace(name, "waypoint", latitude, longitude, "", System.currentTimeMillis(), false);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void drawWaypointMarkers() {
        if (map == null) return;
        for (Marker marker : waypointMarkers) map.removeMarker(marker);
        waypointMarkers.clear();
        for (int i = 0; i < routeWaypoints.size(); i++) {
            SavedPlace stop = routeWaypoints.get(i);
            Marker marker = new Marker(new LatLng(stop.latitude, stop.longitude), numberedMarkerStyle(i + 1));
            map.addMarker(marker);
            waypointMarkers.add(marker);
        }
    }

    /** Entry point for managing stops: long-press the destination name at the top of the screen. */
    private void showWaypointManager() {
        if (destination == null) {
            Toast.makeText(this, "ابتدا یک مقصد انتخاب کنید.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (routeWaypoints.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("توقف‌های میانی")
                    .setMessage("هنوز توقفی اضافه نشده است. برای افزودن، روی نقطه دلخواه روی نقشه کمی فشار دهید و «افزودن به‌عنوان توقف میانی» را انتخاب کنید.")
                    .setPositiveButton("متوجه شدم", null)
                    .show();
            return;
        }
        String[] names = new String[routeWaypoints.size()];
        for (int i = 0; i < routeWaypoints.size(); i++) names[i] = (i + 1) + ". " + routeWaypoints.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("توقف‌های میانی (برای حذف انتخاب کنید)")
                .setItems(names, (dialog, which) -> confirmRemoveWaypoint(which))
                .setNegativeButton("بستن", null)
                .show();
    }

    private void confirmRemoveWaypoint(int index) {
        if (index < 0 || index >= routeWaypoints.size()) return;
        SavedPlace stop = routeWaypoints.get(index);
        new AlertDialog.Builder(this)
                .setTitle(stop.name)
                .setMessage("این توقف حذف شود؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    routeWaypoints.remove(index);
                    drawWaypointMarkers();
                    findViewById(R.id.routeWaypointsButton).setVisibility(!navigationMode && !routeWaypoints.isEmpty() ? View.VISIBLE : View.GONE);
                    if (destination != null) destinationText.setText(waypointLabelSuffix(destination.name));
                    if (routeWaypoints.isEmpty()) requestRouteWithoutWaypoints(); else requestRouteWithWaypoints();
                })
                .setNegativeButton("انصراف", null)
                .show();
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
        if (mapRoutePanel != null) mapRoutePanel.setVisibility(View.VISIBLE);
        if (destinationInfoContainer != null) destinationInfoContainer.setVisibility(View.VISIBLE);
        findViewById(R.id.routeOptionsButton).setVisibility(navigationMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.saveMapPlaceButton).setVisibility(navigationMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.routeWaypointsButton).setVisibility(!navigationMode && !routeWaypoints.isEmpty() ? View.VISIBLE : View.GONE);
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
        // Keep every provider alternative visible. The route service already decides whether
        // alternatives are distinct; collapsing them here can hide a legitimate choice.
        routeOptions = routes == null ? new ArrayList<>() : new ArrayList<>(routes);
        if (routeOptions.isEmpty()) {
            routeText.setText("مسیر قابل استفاده‌ای پیدا نشد.");
            routeOptionsScroll.setVisibility(View.GONE);
            return;
        }
        selectedRoute = routeOptions.get(navigationMode
                ? Math.min(navigationRouteIndex, routeOptions.size() - 1) : 0);
        showRoutePreview(selectedRoute);
        if (navigationMode) startTurnByTurn(selectedRoute);
        else renderRouteChips();
    }

    /** Every alternative is drawn on the map itself instead of behind a dialog; the driver taps a
     *  chip at the bottom of the screen to switch which one is highlighted, then continues with
     *  the existing bottom-panel buttons — nothing opens a separate screen. */
    private void renderRouteChips() {
        if (routeOptionsRow == null || routeOptionsScroll == null) return;
        routeOptionsRow.removeAllViews();
        if (routeOptions.size() <= 1) {
            routeOptionsScroll.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < routeOptions.size(); i++) {
            RouteResult route = routeOptions.get(i);
            boolean isSelected = route == selectedRoute;
            int minutes = Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0));
            MaterialCardView card = new MaterialCardView(this);
            card.setClickable(true);
            card.setFocusable(true);
            card.setUseCompatPadding(true);
            card.setCardElevation(dp(isSelected ? 4 : 1));
            card.setRadius(dp(10));
            card.setStrokeWidth(dp(isSelected ? 2 : 1));
            card.setStrokeColor(getColor(isSelected ? R.color.drivemate_green : R.color.drivemate_border));
            card.setCardBackgroundColor(getColor(isSelected ? R.color.drivemate_panel_bg : R.color.white));
            card.setContentDescription("مسیر " + (i + 1) + ", " + minutes + " دقیقه، "
                    + String.format(Locale.US, "%.1f", route.distanceMeters / 1000.0) + " کیلومتر");

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(12), dp(8), dp(12), dp(8));

            TextView title = new TextView(this);
            title.setText(isSelected ? "مسیر " + (i + 1) + "  |  پیشنهادی" : "مسیر " + (i + 1));
            title.setTextSize(14f);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setTextColor(getColor(R.color.drivemate_text));

            TextView details = new TextView(this);
            details.setText(minutes + " دقیقه  •  " + String.format(Locale.US, "%.1f", route.distanceMeters / 1000.0) + " کیلومتر");
            details.setTextSize(12f);
            details.setTextColor(getColor(R.color.drivemate_muted));
            details.setPadding(0, dp(3), 0, 0);

            content.addView(title);
            content.addView(details);
            card.addView(content);
            // Two alternatives fit side-by-side on ordinary phones; a third remains reachable
            // with a short horizontal swipe instead of being hidden behind a full-width card.
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(144), ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8));
            card.setOnClickListener(v -> {
                selectedRoute = route;
                showRoutePreview(route);
                renderRouteChips();
            });
            routeOptionsRow.addView(card, params);
        }
        routeOptionsScroll.setVisibility(View.VISIBLE);
    }

    /** Approximate fit-bounds using only camera primitives this SDK build is known to support
     *  (moveCamera + setZoom), rather than an unverified bounds API. */
    private void centerOnSelectedRoute() {
        if (map == null || selectedRoute == null || selectedRoute.geometry.isEmpty()) return;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        for (RoutePoint point : selectedRoute.geometry) {
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLng = Math.min(minLng, point.longitude);
            maxLng = Math.max(maxLng, point.longitude);
        }
        double centerLat = (minLat + maxLat) / 2d;
        double centerLng = (minLng + maxLng) / 2d;
        double spanKm = Math.max(distanceKm(minLat, minLng, maxLat, maxLng), 0.3d);
        float zoom = (float) Math.max(10.5d, Math.min(15.5d, 15.5d - Math.log(spanKm) / Math.log(1.55d)));
        map.moveCamera(new LatLng(centerLat, centerLng), 0.3f);
        map.setZoom(zoom, 0.3f);
    }

    private void showRoutePreview(RouteResult route) {
        int minutes = Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0));
        routeText.setText("مسیر پیشنهادی " + route.providerName + " | " + minutes + " دقیقه | "
                + String.format(Locale.US, "%.1f", route.distanceMeters / 1000.0) + " کیلومتر");
        loadRouteSpeedLimits(route);
        loadRouteTrafficIncidents(route);
        drawAllRoutes();
        if (map != null && !navigationMode) {
            map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
            map.setZoom(12.5f, 0.25f);
        }
    }

    /** Draws every fetched alternative on the map at once (selected route prominent, the rest
     *  dimmed) instead of a single polyline behind a picker dialog. */
    private void drawAllRoutes() {
        if (map == null) return;
        for (Polyline polyline : alternateRoutePolylines) map.removePolyline(polyline);
        alternateRoutePolylines.clear();
        if (routePolyline != null) {
            map.removePolyline(routePolyline);
            routePolyline = null;
        }
        List<RouteResult> routesToDraw = routeOptions.isEmpty() && selectedRoute != null
                ? java.util.Collections.singletonList(selectedRoute) : routeOptions;
        for (RouteResult route : routesToDraw) {
            ArrayList<LatLng> points = routePoints(route);
            if (points.size() < 2) continue;
            boolean isSelected = route == selectedRoute;
            Polyline polyline = new Polyline(points, isSelected ? routeLineStyle() : alternateRouteLineStyle());
            map.addPolyline(polyline);
            if (isSelected) routePolyline = polyline; else alternateRoutePolylines.add(polyline);
        }
    }

    private ArrayList<LatLng> routePoints(RouteResult route) {
        ArrayList<LatLng> points = new ArrayList<>();
        for (RoutePoint point : route.geometry) points.add(new LatLng(point.latitude, point.longitude));
        if (points.size() < 2) {
            points.add(new LatLng(originLatitude, originLongitude));
            for (RouteStep step : route.steps) points.add(new LatLng(step.latitude, step.longitude));
        }
        return points;
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
        turnStepsExpanded = false;
        turnStepsScroll.setVisibility(View.GONE);
        turnExpandIcon.setText("▾");
        if (!navigationEngine.announceCurrentInstruction()) {
            turnInstructionText.setText("به سمت مقصد حرکت کنید");
            turnArrowText.setText("↑");
            renderLaneGuidance(null);
        }
        scheduleTrafficIncidentRefresh();
        enableNavigationCamera();
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

    /** The lookup is route-scoped, not location-scoped, so following the vehicle never causes a
     * network request per GPS sample. Values are community-mapped OSM maxspeed tags, not an
     * official enforcement feed. */
    private void loadRouteSpeedLimits(RouteResult route) {
        routeSpeedLimits.clear();
        clearSpeedLimitMarkers();
        roadSpeedLimitText.setVisibility(View.GONE);
        if (!isSpeedLimitLayerEnabled() || route == null || route.geometry.size() < 2) return;
        roadSpeedLimitText.setText("محدودیت سرعت: در حال بررسی OSM");
        roadSpeedLimitText.setVisibility(View.VISIBLE);
        final int requestId = ++speedLimitRequestId;
        new Thread(() -> {
            try {
                List<SpeedLimitPoint> found = layerExpansionProvider.speedLimitsNear(route.geometry);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != speedLimitRequestId) return;
                    routeSpeedLimits.clear();
                    if (found != null) routeSpeedLimits.addAll(found);
                    if (routeSpeedLimits.isEmpty() && route.providerSpeedLimits != null) {
                        routeSpeedLimits.addAll(route.providerSpeedLimits);
                    }
                    renderSpeedLimitMarkers();
                    updateRoadSpeedLimit(originLatitude, originLongitude);
                });
            } catch (Exception error) {
                Log.w("DriveMateSpeed", "Map speed-limit lookup failed: " + error.getMessage());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != speedLimitRequestId) return;
                    routeSpeedLimits.clear();
                    if (route.providerSpeedLimits != null) routeSpeedLimits.addAll(route.providerSpeedLimits);
                    // Stage three: a visible unknown state, without enabling any speed alert.
                    renderSpeedLimitMarkers();
                    updateRoadSpeedLimit(originLatitude, originLongitude);
                });
            }
        }).start();
    }

    /** Lets the map-layers toggle show something immediately even before any destination/route is
     *  picked - mirrors the POI-layer behaviour (search near current position) instead of staying
     *  invisible until a route exists, which was the actual bug behind "nothing appears on the
     *  map": the layer previously only ever populated from an active route. */
    private void loadNearbySpeedLimits() {
        if (!isSpeedLimitLayerEnabled() || Double.isNaN(originLatitude) || Double.isNaN(originLongitude)) return;
        roadSpeedLimitText.setText("محدودیت سرعت: در حال بررسی OSM");
        roadSpeedLimitText.setVisibility(View.VISIBLE);
        final int requestId = ++speedLimitRequestId;
        final double latitude = originLatitude;
        final double longitude = originLongitude;
        List<RoutePoint> box = new ArrayList<>();
        box.add(new RoutePoint(latitude - 0.02d, longitude - 0.02d));
        box.add(new RoutePoint(latitude + 0.02d, longitude + 0.02d));
        new Thread(() -> {
            try {
                List<SpeedLimitPoint> found = layerExpansionProvider.speedLimitsNear(box);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != speedLimitRequestId) return;
                    routeSpeedLimits.clear();
                    if (found != null) routeSpeedLimits.addAll(found);
                    renderSpeedLimitMarkers();
                    updateRoadSpeedLimit(latitude, longitude);
                });
            } catch (Exception error) {
                Log.w("DriveMateSpeed", "Nearby speed-limit lookup failed: " + error.getMessage());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != speedLimitRequestId) return;
                    roadSpeedLimitText.setText("محدودیت سرعت: نامشخص (دادهٔ OSM در دسترس نیست)");
                    roadSpeedLimitText.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private void clearSpeedLimitMarkers() {
        if (map != null) for (Marker marker : speedLimitMarkers) map.removeMarker(marker);
        speedLimitMarkers.clear();
    }

    /** Draws every mapped OSM maxspeed point as an actual marker (not just the single nearest-value
     *  text badge this layer used to be limited to) - a European-style speed-limit disc so it reads
     *  instantly as "speed limit" rather than a generic pin. This is what was genuinely missing:
     *  toggling the layer previously updated data but never rendered anything onto the map canvas. */
    private void renderSpeedLimitMarkers() {
        clearSpeedLimitMarkers();
        if (map == null || !isSpeedLimitLayerEnabled() || routeSpeedLimits.isEmpty()) return;
        for (SpeedLimitPoint point : routeSpeedLimits) {
            Marker marker = new Marker(new LatLng(point.latitude, point.longitude), speedLimitMarkerStyle(point.kilometersPerHour));
            map.addMarker(marker);
            speedLimitMarkers.add(marker);
        }
    }

    /** European-style speed-limit sign (red ring, white face, black number) so it reads instantly
     *  as a speed limit rather than a generic colored pin. */
    private MarkerStyle speedLimitMarkerStyle(int kilometersPerHour) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setColor(0xffe53935);
        canvas.drawCircle(32f, 32f, 30f, ring);
        Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        face.setColor(0xffffffff);
        canvas.drawCircle(32f, 32f, 24f, face);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xff000000);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(kilometersPerHour >= 100 ? 20f : 24f);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float y = 32f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(String.valueOf(kilometersPerHour), 32f, y, text);
        MarkerStyleBuilder builder = new MarkerStyleBuilder();
        builder.setSize(30f);
        builder.setBitmap(BitmapUtils.createBitmapFromAndroidBitmap(bitmap));
        return builder.buildStyle();
    }

    /** Live, point-based traffic-incident markers (accident/closure/roadworks/hazard) for the
     *  active route from TomTom's incident feed - the map-screen counterpart to MainActivity's
     *  spoken announceTrafficIncident. Route-scoped fetch, refreshed periodically only while
     *  turn-by-turn is running (see scheduleTrafficIncidentRefresh), exactly like
     *  loadRouteSpeedLimits above. Silently produces nothing when no TomTom key is configured. */
    private void loadRouteTrafficIncidents(RouteResult route) {
        if (trafficIncidentProvider == null || !trafficIncidentProvider.hasKey() || route == null
                || route.geometry.size() < 2) {
            clearTrafficIncidentMarkers();
            return;
        }
        final int requestId = ++trafficIncidentRequestId;
        final List<RoutePoint> geometry = route.geometry;
        new Thread(() -> {
            try {
                List<TrafficIncident> found = trafficIncidentProvider.incidentsNear(geometry);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != trafficIncidentRequestId) return;
                    routeTrafficIncidents = found == null ? new ArrayList<>() : found;
                    renderTrafficIncidentMarkers();
                });
            } catch (Exception error) {
                Log.w("DriveMateTraffic", "Map traffic-incident lookup failed: " + error.getMessage());
            }
        }).start();
    }

    /** Reschedules itself only while turn-by-turn is active, and stops on its own once navigation
     *  ends (arrival, off-route dead-end, or leaving the screen) - same cadence approach as
     *  MainActivity's scheduleTrafficIncidentCheck. */
    private void scheduleTrafficIncidentRefresh() {
        trafficIncidentHandler.removeCallbacks(trafficIncidentRefresh);
        if (navigationEngine.isNavigating() && trafficIncidentProvider != null && trafficIncidentProvider.hasKey()) {
            trafficIncidentHandler.postDelayed(trafficIncidentRefresh, TRAFFIC_INCIDENT_REFRESH_MS);
        }
    }

    private void clearTrafficIncidentMarkers() {
        if (map != null) for (Marker marker : trafficIncidentMarkers) map.removeMarker(marker);
        trafficIncidentMarkers.clear();
    }

    /** Draws each live incident as a colored warning-triangle marker, visually distinct from the
     *  round red speed-limit signs so the two layers are never confused at a glance. */
    private void renderTrafficIncidentMarkers() {
        clearTrafficIncidentMarkers();
        if (map == null) return;
        for (TrafficIncident incident : routeTrafficIncidents) {
            Marker marker = new Marker(new LatLng(incident.latitude, incident.longitude), trafficIncidentMarkerStyle(incident.type));
            map.addMarker(marker);
            trafficIncidentMarkers.add(marker);
        }
        scheduleTrafficIncidentRefresh();
    }

    private MarkerStyle trafficIncidentMarkerStyle(TrafficIncident.Type type) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int color;
        switch (type) {
            case ACCIDENT: color = 0xffd32f2f; break;
            case ROAD_CLOSED: color = 0xff424242; break;
            case ROADWORK: color = 0xfffb8c00; break;
            default: color = 0xfff9a825; break;
        }
        Paint triangleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        triangleFill.setColor(color);
        Path triangle = new Path();
        triangle.moveTo(32f, 4f);
        triangle.lineTo(60f, 58f);
        triangle.lineTo(4f, 58f);
        triangle.close();
        canvas.drawPath(triangle, triangleFill);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xffffffff);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(26f);
        canvas.drawText("!", 32f, 50f, text);
        MarkerStyleBuilder builder = new MarkerStyleBuilder();
        builder.setSize(30f);
        builder.setBitmap(BitmapUtils.createBitmapFromAndroidBitmap(bitmap));
        return builder.buildStyle();
    }

    /** Renders explicit provider per-lane guidance (see LaneGuidance) as a row of small tiles
     *  directly under the turn banner: bright tiles are the lanes the provider marked valid for
     *  the current maneuver, dim tiles are not. Hidden whenever there is nothing genuinely
     *  actionable to show (single lane, or every lane equally valid/invalid) - never a guess. */
    private void renderLaneGuidance(LaneGuidance lanes) {
        if (laneGuidanceRow == null) return;
        laneGuidanceRow.removeAllViews();
        if (lanes == null || !lanes.hasUsefulGuidance()) {
            laneGuidanceRow.setVisibility(View.GONE);
            return;
        }
        int size = dp(40);
        for (int i = 0; i < lanes.indications.size(); i++) {
            boolean valid = i < lanes.validForManeuver.size() && Boolean.TRUE.equals(lanes.validForManeuver.get(i));
            TextView tile = new TextView(this);
            tile.setText(laneGlyph(lanes.indications.get(i)));
            tile.setTextSize(20f);
            tile.setTextColor(valid ? getColor(R.color.drivemate_blue) : 0xffcfd8dc);
            tile.setTypeface(Typeface.DEFAULT_BOLD);
            tile.setGravity(android.view.Gravity.CENTER);
            tile.setBackgroundResource(valid ? R.drawable.lane_tile_active : R.drawable.lane_tile_inactive);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(dp(4), 0, dp(4), 0);
            tile.setLayoutParams(params);
            laneGuidanceRow.addView(tile);
        }
        laneGuidanceRow.setVisibility(View.VISIBLE);
    }

    private String laneGlyph(String indication) {
        if (indication == null) return "↑";
        String value = indication.toLowerCase(Locale.ROOT);
        if (value.contains("uturn")) return "↺";
        if (value.contains("left")) return "↖";
        if (value.contains("right")) return "↗";
        return "↑";
    }

    private void updateRoadSpeedLimit(double latitude, double longitude) {
        if (!isSpeedLimitLayerEnabled()) {
            roadSpeedLimitText.setVisibility(View.GONE);
            return;
        }
        SpeedLimitPoint closest = null;
        double closestMeters = Double.MAX_VALUE;
        for (SpeedLimitPoint item : routeSpeedLimits) {
            double meters = distanceMeters(latitude, longitude, item.latitude, item.longitude);
            if (meters < closestMeters) { closestMeters = meters; closest = item; }
        }
        if (closest == null || closestMeters > 110d) {
            roadSpeedLimitText.setText("محدودیت: نامشخص (دادهٔ OSM)");
        } else {
            roadSpeedLimitText.setText("محدودیت ثبت‌شده: " + closest.kilometersPerHour + " کیلومتر/ساعت (" + closest.source + ")");
        }
        roadSpeedLimitText.setVisibility(View.VISIBLE);
    }

    private double distanceMeters(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double value = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371000d * 2d * Math.atan2(Math.sqrt(value), Math.sqrt(1d - value));
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
                    routeOptions = new ArrayList<>();
                    routeOptions.add(route);
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
            renderLaneGuidance(step.lanes);
            if (turnStepsExpanded) renderTurnSteps();
        });
    }

    @Override public void onOffRoute() {
        runOnUiThread(() -> {
            turnInstructionText.setText("در حال محاسبه مجدد مسیر...");
            turnArrowText.setText("↻");
            renderLaneGuidance(null);
        });
        recalculateActiveRoute();
    }

    @Override public void onArrived() {
        runOnUiThread(() -> {
            turnInstructionText.setText("به مقصد رسیدید");
            turnDistanceText.setText("");
            turnArrowText.setText("●");
            routeText.setText("سفر به پایان رسید.");
            renderLaneGuidance(null);
        });
        trafficIncidentHandler.removeCallbacks(trafficIncidentRefresh);
    }

    /** Best-effort synchronous device location: the last cached GPS fix, or the last cached
     *  network fix if GPS has none, or null if permission is missing or neither exists yet. Used
     *  as a same-frame fallback so the map never has to sit at a fixed Tehran point while waiting
     *  for a fresh live GPS update to arrive. */
    private Location bestKnownDeviceLocation() {
        if (locationManager == null
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            return gps != null ? gps : network;
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private void focusOrigin() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, "مکان گوشی خاموش است. آن را روشن کنید.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        if (navigationMode) {
            followVehicle = true;
            navigationCameraEnabled = true;
        }
        Location latest = bestKnownDeviceLocation();
        if (latest != null) {
            originLatitude = latest.getLatitude();
            originLongitude = latest.getLongitude();
            showCurrentMarker();
        }
        if (navigationMode && navigationCameraEnabled) {
            updateNavigationCamera();
            return;
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
        vehicleMarker = new Marker(drivingPosition(), vehicleMarkerStyle(navigationCameraEnabled ? 0f : bearing));
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
        navigationCameraEnabled = false;
        applyMapOrientation(0f, 90f, 0.2f);
        map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
        map.setZoom(12.5f, 0.25f);
    }

    /** Restores the driver-first viewport: the vehicle stays below center and the road points up. */
    private void enableNavigationCamera() {
        if (!navigationMode) return;
        navigationCameraEnabled = true;
        followVehicle = true;
        updateNavigationCamera();
    }

    private void updateNavigationCamera() {
        if (map == null || !navigationMode || !navigationCameraEnabled) return;
        float heading = navigationHeading();
        LatLng vehiclePosition = drivingPosition();
        LatLng cameraTarget = pointAhead(vehiclePosition, heading, 68d);
        // Neshan applies this as map rotation; invert the travel heading so the road ahead is up.
        applyMapOrientation(-heading, 58f, 0.28f);
        map.moveCamera(cameraTarget, 0.28f);
        map.setZoom(17.25f, 0.28f);
    }

    private float navigationHeading() {
        LatLng position = drivingPosition();
        if (selectedRoute != null) {
            int nearestIndex = -1;
            float nearestDistance = Float.MAX_VALUE;
            for (int i = 0; i < selectedRoute.geometry.size(); i++) {
                RoutePoint point = selectedRoute.geometry.get(i);
                Location from = new Location("route");
                from.setLatitude(position.getLatitude());
                from.setLongitude(position.getLongitude());
                Location to = new Location("route");
                to.setLatitude(point.latitude);
                to.setLongitude(point.longitude);
                float distance = from.distanceTo(to);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestIndex = i;
                }
            }
            if (nearestIndex >= 0) {
                Location from = new Location("route");
                from.setLatitude(position.getLatitude());
                from.setLongitude(position.getLongitude());
                float accumulated = 0f;
                for (int i = nearestIndex + 1; i < selectedRoute.geometry.size(); i++) {
                    RoutePoint point = selectedRoute.geometry.get(i);
                    Location to = new Location("route");
                    to.setLatitude(point.latitude);
                    to.setLongitude(point.longitude);
                    accumulated += from.distanceTo(to);
                    if (accumulated >= 28f) return from.bearingTo(to);
                    from = to;
                }
            }
        }
        return lastBearing;
    }

    private void toggleTurnSteps() {
        if (selectedRoute == null || selectedRoute.steps.isEmpty()) return;
        turnStepsExpanded = !turnStepsExpanded;
        if (turnStepsExpanded) renderTurnSteps();
        turnStepsScroll.setVisibility(turnStepsExpanded ? View.VISIBLE : View.GONE);
        turnExpandIcon.setText(turnStepsExpanded ? "▴" : "▾");
    }

    /** Renders every remaining maneuver inline in the same card the driver already tapped, instead
     *  of opening a separate screen; the current maneuver is highlighted so it reads like a live
     *  itinerary rather than a static list. */
    private void renderTurnSteps() {
        if (turnStepsContent == null || selectedRoute == null) return;
        turnStepsContent.removeAllViews();
        int current = navigationMode
                ? Math.min(navigationEngine.currentStepIndex(), selectedRoute.steps.size() - 1) : -1;
        for (int i = 0; i < selectedRoute.steps.size(); i++) {
            RouteStep step = selectedRoute.steps.get(i);
            String instruction = step.instruction == null || step.instruction.trim().isEmpty()
                    ? "ادامه مسیر" : step.instruction;
            boolean isCurrent = i == current;
            TextView row = new TextView(this);
            row.setText(formatDistance(Math.max(0, step.distanceMeters)) + "  •  " + instruction);
            row.setTextSize(15f);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setTextColor(isCurrent ? 0xff176b87 : 0xff2c3e46);
            row.setTypeface(Typeface.DEFAULT, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            if (isCurrent) row.setBackgroundColor(0xffeaf7f1);
            turnStepsContent.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private LatLng pointAhead(LatLng origin, float bearing, double meters) {
        double radians = Math.toRadians(bearing);
        double latitude = origin.getLatitude() + (meters * Math.cos(radians) / 111320d);
        double longitude = origin.getLongitude() + (meters * Math.sin(radians)
                / (111320d * Math.max(0.1d, Math.cos(Math.toRadians(origin.getLatitude())))));
        return new LatLng(latitude, longitude);
    }

    /**
     * mobile-sdk exposes these through its Carto-backed MapView. Reflection keeps releases using
     * an older Neshan artifact operational: they retain follow mode instead of crashing.
     */
    private void applyMapOrientation(float heading, float tilt, float durationSeconds) {
        if (map == null) return;
        try {
            map.getClass().getMethod("setBearing", float.class, float.class)
                    .invoke(map, heading, durationSeconds);
        } catch (Exception ignored) {
            logMissingOrientationSupport();
        }
        try {
            map.getClass().getMethod("setTilt", float.class, float.class)
                    .invoke(map, tilt, durationSeconds);
        } catch (Exception ignored) {
            logMissingOrientationSupport();
        }
    }

    private void logMissingOrientationSupport() {
        if (orientationWarningLogged) return;
        orientationWarningLogged = true;
        Log.w("DriveMateMap", "Navigation camera orientation is unavailable in this SDK build.");
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
        result.putStringArrayListExtra(RESULT_WAYPOINTS, encodeWaypoints());
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

    /** Muted style for alternative routes drawn alongside the selected one, so the highlighted
     *  route reads clearly without hiding the others entirely. */
    private LineStyle alternateRouteLineStyle() {
        LineStyleBuilder builder = new LineStyleBuilder();
        builder.setColor(new Color((short) 150, (short) 162, (short) 170, (short) 200));
        builder.setWidth(6f);
        builder.setStretchFactor(0f);
        return builder.buildStyle();
    }

    @Override protected void onResume() {
        super.onResume();
        if (NightModeManager.refreshIfChanged(this)) return;
        NightModeManager.applyWindowBrightness(this);
        // Some native map SDK builds tear down and rebuild their rendering surface across an
        // onPause/onResume cycle (e.g. leaving to another app and coming back) without telling the
        // app, silently dropping every previously-added marker even though this activity instance
        // (and its in-memory poiLayerPlaces cache) survives untouched. Re-adding the cached markers
        // here is cheap (no network call, just the same places already fetched) and fixes POI layers
        // appearing to vanish after leaving and reopening the map.
        redrawCachedPoiLayers();
        if (!routeSpeedLimits.isEmpty()) renderSpeedLimitMarkers();
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        if (isLocationEnabled()) {
            long minTimeMs = navigationMode ? 1000L : 2500L;
            float minDistanceM = navigationMode ? 4f : 8f;
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, this);
        }
    }

    private void redrawCachedPoiLayers() {
        if (map == null || enabledPoiLayers.isEmpty()) return;
        clearNearbyMarkers();
        for (PoiCategory category : enabledPoiLayers) {
            List<SavedPlace> cached = poiLayerPlaces.get(category);
            if (cached != null && !cached.isEmpty()) renderPoiLayer(category, cached);
        }
    }

    @Override protected void onPause() {
        if (locationManager != null) locationManager.removeUpdates(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        poiExpansionHandler.removeCallbacksAndMessages(null);
        trafficIncidentHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onLocationChanged(Location location) {
        originLatitude = location.getLatitude();
        originLongitude = location.getLongitude();
        if (location.hasBearing()) {
            lastBearing = location.getBearing();
            hasHeading = true;
        }
        runOnUiThread(() -> {
            showCurrentMarker();
            if (selectedRoute != null) updateRoadSpeedLimit(location.getLatitude(), location.getLongitude());
            if (navigationMode && navigationEngine.isNavigating()) {
                navigationEngine.onLocation(location);
                updateTurnBanner(location);
            }
            if (navigationMode && followVehicle && navigationCameraEnabled) {
                updateNavigationCamera();
            } else if (navigationMode && followVehicle && map != null) {
                map.moveCamera(drivingPosition(), 0.35f);
                map.setZoom(17f, 0.35f);
            }
        });
    }
}
