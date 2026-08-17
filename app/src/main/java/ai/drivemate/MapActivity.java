package ai.drivemate;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
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
import android.view.WindowManager;
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

import com.google.android.material.card.MaterialCardView;

import ai.drivemate.map.LatLng;
import ai.drivemate.map.Marker;
import ai.drivemate.map.MarkerStyle;
import ai.drivemate.map.OsmMapView;
import ai.drivemate.map.Polyline;

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
import ai.drivemate.location.LocationQualityFilter;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NavigationEngine;
import ai.drivemate.traffic.TrafficIncidentProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.OpenRouteServiceRoutingProvider;
import ai.drivemate.routing.TomTomRoutingProvider;
import ai.drivemate.routing.OverpassPoiProvider;
import ai.drivemate.routing.PlaceSearchRepository;
import ai.drivemate.routing.PoiCategory;
import ai.drivemate.routing.RouteCache;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.settings.NightModeManager;
import ai.drivemate.storage.PlaceStore;
import ai.drivemate.storage.TripStore;
import ai.drivemate.model.TripRecord;
import ai.drivemate.ai.RuntimeKeys;

/** Map UI is isolated from the driving activity; it returns a selected destination to the existing engine. */
public class MapActivity extends Activity implements LocationListener, NavigationEngine.Listener {
    private static final int REQUEST_MAP_LOCATION_PERMISSION = 410;
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
    public static final String RESULT_TRIP_COMPLETED = "trip_completed";
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
    private OsmMapView map;
    private NeshanRoutingProvider neshanRoutingProvider;
    private MapIrRoutingProvider mapIrRoutingProvider;
    private TomTomRoutingProvider tomTomRoutingProvider;
    private OpenRouteServiceRoutingProvider openRouteServiceRoutingProvider;
    private boolean remoteRoutingConfigLoading = true;
    private SavedPlace pendingRouteDestination;
    private Marker currentMarker;
    private Marker vehicleMarker;
    private Marker destinationMarker;
    private final List<Marker> tomTomGeometryDebugMarkers = new ArrayList<>();
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
    private static final boolean TOMTOM_GEOMETRY_DEBUG_MARKERS_ENABLED = true;
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
    private TripStore tripStore;
    private LocationManager locationManager;
    /** MainActivity/NavigationForegroundService own the single real NavigationEngine and its
     *  arrival/instruction detection (see the class javadoc). This activity used to have (and
     *  still implements NavigationEngine.Listener from, below) its own full copy of that reactive
     *  UI-update logic, but nothing was ever wiring it to that engine anymore after that move - it
     *  was never actually invoked, which is why this screen could stay showing an active
     *  route/turn banner forever after the driver had actually arrived (no arrival ever fired
     *  here) until the driver switched screens and back. Binding to the service and registering as
     *  a NavigationForegroundService.SessionCallback while this screen is visible reconnects the
     *  exact same UI-update methods to the real, single source of truth. */
    private NavigationForegroundService navigationService;
    private boolean navigationServiceBound;
    private final ServiceConnection navigationServiceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            navigationService = ((NavigationForegroundService.LocalBinder) service).getService();
            navigationServiceBound = true;
            navigationService.addCallback(navigationSessionCallback, false);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            navigationServiceBound = false;
            navigationService = null;
        }
    };

    private final NavigationForegroundService.SessionCallback navigationSessionCallback =
            new NavigationForegroundService.SessionCallback() {
        @Override public void onInstruction(RouteStep step) { MapActivity.this.onInstruction(step); }
        @Override public void onOffRoute() { MapActivity.this.onOffRoute(); }
        @Override public void onArrived(SavedPlace arrivedDestination, TripRecord tripReport) { MapActivity.this.onArrived(); }
        @Override public void onWaypointApproaching(RouteStep step, int waypointOrdinal) {
            MapActivity.this.onWaypointApproaching(step, waypointOrdinal);
        }
        @Override public void onWaypointReached(RouteStep step, int waypointOrdinal) {
            MapActivity.this.onWaypointReached(step, waypointOrdinal);
        }
        @Override public void onWaypointSkipped(RouteStep step, int waypointOrdinal) {
            MapActivity.this.onWaypointSkipped(step, waypointOrdinal);
        }
        @Override public void onInstructionStage(RouteStep step, NavigationEngine.AnnouncementStage stage, int metersRemaining) { }
        @Override public void onRouteReplaced(RouteResult route) {
            runOnUiThread(() -> {
                if (!navigationMode || route == null) return;
                // Remove the old route before committing the new geometry so the map never
                // visually connects stale and fresh routes during a reroute.
                clearNavigationRouteLines();
                // Prevent GPS redraw from resurrecting the stale route during reroute.
                routeOptions = new ArrayList<>();
                selectedRoute = null;
                routeNeedsRefreshFromCurrentLocation = false;
                lastRouteRenderLocation = null;
                lastRouteRenderAt = 0L;
                routeOptions = new ArrayList<>();
                routeOptions.add(route);
                selectedRoute = route;
                routeNeedsRefreshFromCurrentLocation = false;
                showRoutePreview(route);
                startTurnByTurn(route);
            });
        }
        // This activity gets its own location fixes directly from LocationManager (see
        // onLocationChanged below) - it already existed before this binding and drives its own
        // GPS-quality filtering, vehicle marker, and route-line rendering, none of which this
        // service-level callback needs to duplicate.
        @Override public void onLocationUpdate(Location location) {
            // NavigationForegroundService is the authoritative GPS owner. Feed its already-filtered
            // fixes into the map UI as well, so the arrow/polyline cannot stall when MapActivity's
            // independent LocationManager stream is paused or dropped.
            if (location == null) return;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && navigationMode) {
                    authoritativeLocationPending = true;
                    onLocationChanged(new Location(location));
                }
            });
        }
        @Override public void onLocationAvailabilityChanged(boolean available) { }
    };
    private boolean navigationMode;
    private boolean followVehicle = true;
    /** Tracks the "کاربر زد موقعیت من ولی GPS خاموش بود" flow end-to-end so returning from
     *  Settings can react correctly without ever looping back into Settings or the dialog on its
     *  own: LOCATION_DISABLED (dialog just shown) -> LOCATION_SETTINGS_OPENED (driver tapped
     *  "فعال کردن") -> resolved to LOCATION_ENABLED or back to LOCATION_DISABLED once onResume
     *  re-checks, or LOCATION_CANCELLED if the driver dismissed the dialog instead. Only an actual
     *  tap on "موقعیت من" (or the settings launch it leads to) ever changes this - onResume merely
     *  reads it, it never opens Settings or a dialog by itself. */
    private enum LocationGateState { LOCATION_ENABLED, LOCATION_DISABLED, LOCATION_SETTINGS_OPENED, LOCATION_CANCELLED }
    private LocationGateState locationGateState = LocationGateState.LOCATION_ENABLED;
    /** Set while the driver has manually panned/zoomed away from the follow camera mid-navigation
     *  (see resumeFollowVehicle), so incoming location updates skip re-centering until the timer
     *  below fires - otherwise every ~1s GPS update snapped the view straight back to the vehicle
     *  before the driver had a chance to actually look at the road ahead. */
    private final Runnable resumeFollowVehicle = () -> {
        if (!navigationMode) return;
        followVehicle = true;
        navigationCameraEnabled = true;
        updateNavigationCamera();
    };
    private static final long FOLLOW_VEHICLE_RESUME_DELAY_MS = 8_000L;
    private float lastBearing;
    private boolean hasHeading;
    private boolean navigationCameraEnabled;
    private int navigationRouteIndex;
    /** Current turn/step index used only by the map UI renderer. */
    private int displayedStepIndex;
    private final SimpleDateFormat etaFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private long mapNavigationStartedAt;
    /** Snapshot of the origin at the true start of this trip (not updated by reroutes), used so
     *  the completion report reflects distance actually traveled, origin to wherever the trip
     *  ends, rather than the originally planned route distance to the destination. */
    private double tripOriginLatitude = Double.NaN;
    private double tripOriginLongitude = Double.NaN;
    private float tripTraveledDistanceMeters;
    private Location lastTripAccumLocation;
    private boolean tripCompletionShown;
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
    /** True while the GPS-unavailable banner/toast is showing, so GPS and network provider
     *  callbacks (which can each fire independently) don't show it twice. Navigation itself is
     *  never stopped for this - the engine keeps tracking against the last known fix. */
    private boolean gpsWarningActive;
    /** The map receives GPS and network-provider callbacks directly, so retain the last accepted
     * fix here too; otherwise a weak network fix can visibly jump the vehicle/route in alleys. */
    private Location lastAcceptedMapLocation;
    private final LocationQualityFilter mapLocationFilter = new LocationQualityFilter();
    /** True only for a service-owned fix already filtered by the authoritative navigation tracker. */
    private boolean authoritativeLocationPending;
    private boolean routeNeedsRefreshFromCurrentLocation;
    private boolean routeRefreshInFlight;
    private long lastRouteRefreshAttemptAt;
    private static final long ROUTE_REFRESH_RETRY_MS = 15_000L;
    private Location lastRouteRenderLocation;
    private long lastRouteRenderAt;
    private static final long NAVIGATION_ROUTE_REDRAW_INTERVAL_MS = 700L;
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
        tripStore = new TripStore(this);
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
            mapLocationFilter.seed(lastKnown);
            lastAcceptedMapLocation = mapLocationFilter.getLastAcceptedLocation();
        } else {
            Location initialOrigin = new Location("shared_navigation");
            initialOrigin.setLatitude(originLatitude);
            initialOrigin.setLongitude(originLongitude);
            initialOrigin.setTime(System.currentTimeMillis());
            mapLocationFilter.seed(initialOrigin);
            lastAcceptedMapLocation = mapLocationFilter.getLastAcceptedLocation();
        }
        navigationMode = getIntent().getBooleanExtra(EXTRA_NAVIGATION_MODE, false);
        if (navigationMode) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        navigationRouteIndex = Math.max(0, getIntent().getIntExtra(EXTRA_NAVIGATION_ROUTE_INDEX, 0));
        restoreNavigationWaypoints();
        String neshanKey = getIntent().getStringExtra(EXTRA_NESHAN_KEY);
        String mapIrKey = getIntent().getStringExtra(EXTRA_MAPIR_KEY);
        String tomtomKey = getIntent().getStringExtra(EXTRA_TOMTOM_KEY);
        String openRouteServiceKey = getIntent().getStringExtra(EXTRA_OPENROUTESERVICE_KEY);
        neshanRoutingProvider = new NeshanRoutingProvider(neshanKey);
        mapIrRoutingProvider = new MapIrRoutingProvider(mapIrKey);
        tomTomRoutingProvider = new TomTomRoutingProvider(tomtomKey);
        openRouteServiceRoutingProvider = new OpenRouteServiceRoutingProvider(openRouteServiceKey);
        placeSearchRepository = new PlaceSearchRepository(neshanRoutingProvider, tomtomKey);
        trafficIncidentProvider = new TrafficIncidentProvider(tomtomKey);
        trafficIncidentProvider.setEnabled(false);
        routeRepository = new RouteRepository(mapIrRoutingProvider, neshanRoutingProvider,
                openRouteServiceRoutingProvider, tomTomRoutingProvider);

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
        loadRemoteRoutingConfig();
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
            // The bottom tab strip (dashboard/map/saved/profile) has nothing to do with driving
            // and was permanently occupying ~68dp of screen height even in navigation mode -
            // hide it here so the live map gets that space back while a trip is active.
            findViewById(R.id.mapBottomTabs).setVisibility(View.GONE);
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

    /** Refreshes routing credentials and provider switches without relying on APK build values. */
    private void loadRemoteRoutingConfig() {
        new Thread(() -> {
            RuntimeKeys keys = RuntimeKeys.fetchDefault(BuildConfig.KEYS_DECRYPTION_SECRET);
            neshanRoutingProvider.setApiKey(keys.get("NESHAN_API_KEY"));
            mapIrRoutingProvider.setApiKey(keys.get("MAPIR_API_KEY"));
            tomTomRoutingProvider.setApiKey(keys.get("TOMTOM_API_KEY"));
            openRouteServiceRoutingProvider.setApiKey(keys.get("OPENROUTESERVICE_API_KEY"));
            tomTomRoutingProvider.setEnabled(keys.providerEnabled("TOMTOM", true));
            openRouteServiceRoutingProvider.setEnabled(keys.providerEnabled("OPENROUTESERVICE", true));
            neshanRoutingProvider.setEnabled(keys.providerEnabled("NESHAN", true));
            mapIrRoutingProvider.setEnabled(keys.providerEnabled("MAPIR", true));
            placeSearchRepository.setTomTomApiKey(keys.get("TOMTOM_API_KEY"));
            placeSearchRepository.setTomTomEnabled(keys.providerEnabled("TOMTOM", true));
            trafficIncidentProvider.setEnabled(false);
            Log.i("DriveMateKeys", "map routing configured: TomTom=" + tomTomRoutingProvider.isConfigured()
                    + ", map.ir=" + mapIrRoutingProvider.isConfigured() + ", Neshan="
                    + neshanRoutingProvider.isConfigured() + ", ORS="
                    + openRouteServiceRoutingProvider.isConfigured());
            runOnUiThread(() -> {
                remoteRoutingConfigLoading = false;
                if (pendingRouteDestination != null) {
                    SavedPlace pending = pendingRouteDestination;
                    pendingRouteDestination = null;
                    selectDestinationWithOptions(pending);
                }
            });
        }).start();
    }

    private void returnToMainTab(String tab) {
        Log.i("DriveMateSession", "Leaving navigation map for tab=" + tab
                + "; main navigation remains active=" + navigationMode);
        Intent result = new Intent();
        // MainActivity checks RESULT_MAIN_TAB before destination extras. Therefore a live navigation
        // handoff must NOT include RESULT_MAIN_TAB, otherwise MainActivity would return early and
        // never restart its authoritative navigation/voice session. A normal non-navigation map
        // exit still returns the requested tab as before.
        if (navigationMode && destination != null) {
            result.putExtra(RESULT_LATITUDE, destination.latitude);
            result.putExtra(RESULT_LONGITUDE, destination.longitude);
            result.putExtra(RESULT_NAME, destination.name);
            result.putExtra(RESULT_ADDRESS, destination.address);
            result.putExtra(RESULT_ROUTE_INDEX, Math.max(0, navigationRouteIndex));
            result.putStringArrayListExtra(RESULT_WAYPOINTS, encodeWaypoints());
        } else {
            result.putExtra(RESULT_MAIN_TAB, tab);
        }
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onBackPressed() {
        // The Android back gesture/button must use the same handoff as the in-app map exit.
        // Explicitly stopping navigation remains a separate user action.
        if (navigationMode && destination != null) {
            returnToMainTab("dashboard");
            return;
        }
        super.onBackPressed();
    }

    private void initializeMap() {
        if (false) {
            routeText.setText("اجزای لازم برای نمایش نقشه در این نسخه آماده نیست.");
            return;
        }
        try {
            map = new OsmMapView(this);
            ((FrameLayout) findViewById(R.id.mapContainer)).addView(map,
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            LatLng initialCenter = new LatLng(originLatitude, originLongitude);
            // osmdroid computes the camera/projection from the view's actual pixel size; calling
            // moveCamera/setZoom here, in the same pass that just added the view to its parent,
            // can run before that view has been measured and laid out (still zero-sized), which
            // has left the map centered on an undefined area until something else happened to
            // force a redraw. Posting waits for layout to finish first.
            map.post(() -> {
                map.moveCamera(initialCenter, 0f);
                map.setZoom(14f, 0f);
            });
            showCurrentMarker();
            map.setOnUserGestureListener(() -> {
                if (!navigationMode) return;
                // The driver grabbed the map to pan or zoom ahead - stop re-centering under them
                // until they've had a few seconds to actually look, then resume following on its
                // own (or immediately if they tap the recenter button themselves).
                followVehicle = false;
                searchHandler.removeCallbacks(resumeFollowVehicle);
                searchHandler.postDelayed(resumeFollowVehicle, FOLLOW_VEHICLE_RESUME_DELAY_MS);
            });
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
        } catch (RuntimeException | LinkageError error) {
            // A renderer failure must not close the app or block destination search.
            Log.e("DriveMateMap", "OpenStreetMap renderer could not be loaded", error);
            map = null;
            routeText.setText("نقشه آماده نشد؛ جست‌وجو و مکان‌های ذخیره‌شده همچنان در دسترس هستند.");
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
        return new MarkerStyle(bitmap);
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
        return new MarkerStyle(bitmap);
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
        if (!routeRepository.hasConfiguredProvider()) {
            if (remoteRoutingConfigLoading) {
                pendingRouteDestination = place;
                routeText.setText("در حال آماده سازی مسیریابی...");
                return;
            }
            routeText.setText("سرویس مسیریابی در دسترس نیست. اتصال اینترنت و تنظیمات آنلاین را بررسی کنید.");
            return;
        }
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
        RouteResult cached = navigationMode ? RouteCache.get(place.latitude, place.longitude) : null;
        if (cached != null) {
            Log.i("DriveMateMapRoute", "showing cached route provider=" + cached.providerName
                    + " geometry=" + cached.geometry.size());
            showRouteOptions(java.util.Collections.singletonList(cached));
        }
        if (routeWaypoints.isEmpty()) {
            routeRepository.getRoutes(originLatitude, originLongitude, place.latitude, place.longitude,
                    routes -> runOnUiThread(() -> showRouteOptions(routes)),
                    error -> runOnUiThread(() -> handleRouteFetchFailure(error)));
        } else {
            requestRouteWithWaypoints();
        }
    }

    /** Called whenever a live route request fails (almost always: no internet). During active
     *  navigation this used to be exactly what left the screen blank/broken - most visibly when
     *  MapActivity is destroyed and recreated (e.g. tapping the dashboard tab and back) while the
     *  connection is down, since that always has to ask a routing provider for a route it already
     *  had a moment ago. Falls back to RouteCache's last confirmed route for this destination when
     *  one is available; otherwise behaves exactly as before (plain error text, no route drawn). */
    private void handleRouteFetchFailure(String error) {
        RouteResult cached = navigationMode && destination != null
                ? RouteCache.get(destination.latitude, destination.longitude) : null;
        if (cached == null) {
            routeText.setText("دریافت مسیر انجام نشد: " + error);
            return;
        }
        routeOptions = new ArrayList<>();
        routeOptions.add(cached);
        selectedRoute = cached;
        routeNeedsRefreshFromCurrentLocation = true;
        showRoutePreview(cached);
        startTurnByTurn(cached);
        Toast.makeText(this, "اتصال اینترنت برقرار نیست؛ آخرین مسیر ذخیره‌شده نمایش داده شد.", Toast.LENGTH_LONG).show();
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
                error -> runOnUiThread(() -> handleRouteFetchFailure(error)));
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
        StringBuilder routeSources = new StringBuilder();
        for (int index = 0; index < routeOptions.size(); index++) {
            RouteResult option = routeOptions.get(index);
            if (index > 0) routeSources.append(" | ");
            routeSources.append(index).append(':').append(option.providerName)
                    .append(" geometry=").append(option.geometry.size());
        }
        Log.i("DriveMateMapRoute", "received route options " + routeSources);
        selectedRoute = routeOptions.get(navigationMode
                ? Math.min(navigationRouteIndex, routeOptions.size() - 1) : 0);
        if (navigationMode && destination != null) {
            RouteCache.store(selectedRoute, destination.latitude, destination.longitude);
        }
        showRoutePreview(selectedRoute);
        // Previously this only ran in navigationMode, so choosing a destination while just
        // browsing the map (before tapping "start") left the camera exactly where it was - if the
        // destination was outside the current viewport, the marker/route were drawn correctly but
        // invisible, which looked like "the destination wasn't shown" or "showed somewhere else".
        centerOnSelectedRoute();
        if (navigationMode) {
            startTurnByTurn(selectedRoute);
        }
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
        routeText.setText("مسیر پیشنهادی | " + minutes + " دقیقه | "
                + String.format(Locale.US, "%.1f", route.distanceMeters / 1000.0) + " کیلومتر");
        loadRouteSpeedLimits(route);
        loadRouteTrafficIncidents(route);
        drawAllRoutes();
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
            Log.i("DriveMateMapRoute", "draw provider=" + route.providerName + " geometry="
                    + route.geometry.size() + " drawPoints=" + points.size()
                    + " selected=" + (route == selectedRoute));
            if (points.size() < 2) continue;
            boolean isSelected = route == selectedRoute;
            Polyline polyline = new Polyline(points, isSelected);
            map.addPolyline(polyline);
            if (isSelected) routePolyline = polyline; else alternateRoutePolylines.add(polyline);
        }
        drawTomTomGeometryDebugMarkers(selectedRoute);
    }

    private void drawTomTomGeometryDebugMarkers(RouteResult route) {
        if (map == null) return;
        for (Marker marker : tomTomGeometryDebugMarkers) map.removeMarker(marker);
        tomTomGeometryDebugMarkers.clear();
        if (!TOMTOM_GEOMETRY_DEBUG_MARKERS_ENABLED || route == null || !"TomTom".equals(route.providerName)) return;
        addTomTomGeometryDebugMarker(originLatitude, originLongitude, 0xff2e8b57);
        if (destination != null) addTomTomGeometryDebugMarker(destination.latitude, destination.longitude, 0xffd32f2f);
        int size = route.geometry.size();
        int[] sampleIndices = {0, size / 4, size / 2, (size * 3) / 4, size - 1};
        int previous = -1;
        for (int index : sampleIndices) {
            if (index < 0 || index >= size || index == previous) continue;
            RoutePoint point = route.geometry.get(index);
            addTomTomGeometryDebugMarker(point.latitude, point.longitude, 0xffff9800);
            previous = index;
        }
        Log.i("DriveMateTomTom", "map markers origin+destination+geometrySamples="
                + tomTomGeometryDebugMarkers.size() + " geometry=" + size);
    }

    private void addTomTomGeometryDebugMarker(double latitude, double longitude, int color) {
        Marker marker = new Marker(new LatLng(latitude, longitude), markerStyle(color));
        tomTomGeometryDebugMarkers.add(marker);
        map.addMarker(marker);
    }

    private ArrayList<LatLng> routePoints(RouteResult route) {
        ArrayList<LatLng> points = new ArrayList<>();
        int firstPoint = 0;
        if (navigationMode && route == selectedRoute && !route.geometry.isEmpty()) {
            Location current = currentMapLocation();
            int nearestIndex = -1;
            // Keep rendering aligned with the service-owned monotonic map match. A global nearest-vertex
            // search can jump backward to a loop/parallel-road segment after a GPS deviation.
            if (navigationServiceBound && navigationService != null) {
                int segment = navigationService.getNavigationEngine().currentRouteSegmentIndex();
                if (segment >= 0 && segment < route.geometry.size()) { nearestIndex = segment; }
            }
            if (nearestIndex < 0) { nearestIndex = closestRouteGeometryIndex(route.geometry, current); }
            if (nearestIndex >= 0) {
                RoutePoint nearestPoint = route.geometry.get(nearestIndex);
                Location nearestLocation = new Location("route");
                nearestLocation.setLatitude(nearestPoint.latitude);
                nearestLocation.setLongitude(nearestPoint.longitude);
                // Provider geometry is authoritative. Never add a synthetic straight connector
                // from the live GPS point to the route; during reroute it can cross real streets.
                firstPoint = nearestIndex;
            }
        }
        for (int index = firstPoint; index < route.geometry.size(); index++) {
            RoutePoint point = route.geometry.get(index);
            points.add(new LatLng(point.latitude, point.longitude));
        }
        if (points.size() < 2) {
            Location current = navigationMode && route == selectedRoute ? currentMapLocation() : null;
            points.add(current == null ? new LatLng(originLatitude, originLongitude)
                    : new LatLng(current.getLatitude(), current.getLongitude()));
            for (RouteStep step : route.steps) points.add(new LatLng(step.latitude, step.longitude));
        }
        return points;
    }

    private int closestRouteGeometryIndex(List<RoutePoint> geometry, Location current) {
        if (current == null || geometry == null || geometry.isEmpty()) return -1;
        int nearestIndex = -1;
        float nearestMeters = Float.MAX_VALUE;
        for (int index = 0; index < geometry.size(); index++) {
            RoutePoint point = geometry.get(index);
            Location candidate = new Location("route");
            candidate.setLatitude(point.latitude);
            candidate.setLongitude(point.longitude);
            float meters = current.distanceTo(candidate);
            if (meters < nearestMeters) {
                nearestMeters = meters;
                nearestIndex = index;
            }
        }
        return nearestIndex;
    }

    private boolean shouldRedrawNavigationRoute(Location location) {
        if (location == null || selectedRoute == null) return false;
        long now = System.currentTimeMillis();
        if (lastRouteRenderLocation == null || now - lastRouteRenderAt >= NAVIGATION_ROUTE_REDRAW_INTERVAL_MS) {
            lastRouteRenderLocation = new Location(location);
            lastRouteRenderAt = now;
            return true;
        }
        if (lastRouteRenderLocation.distanceTo(location) < 3f) return false;
        lastRouteRenderLocation = new Location(location);
        lastRouteRenderAt = now;
        return true;
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
        // MainActivity owns the single live NavigationEngine; MapActivity only renders the route.
        displayedStepIndex = 0;
        // Guarded: refreshNavigationRouteFrom/recalculateActiveRoute also call this on every
        // reroute, which must not reset the true trip start time/origin/distance-so-far - only a
        // genuinely new trip (mapNavigationStartedAt == 0) initializes these.
        if (mapNavigationStartedAt == 0L) {
            mapNavigationStartedAt = System.currentTimeMillis();
            tripOriginLatitude = originLatitude;
            tripOriginLongitude = originLongitude;
            tripTraveledDistanceMeters = 0f;
            lastTripAccumLocation = null;
        }
        tripCompletionShown = false;
        turnBannerContainer.setVisibility(View.VISIBLE);
        turnDistanceText.setText("");
        turnStepsExpanded = false;
        turnStepsScroll.setVisibility(View.GONE);
        turnExpandIcon.setText("▾");
        displayedStepIndex = 0;
        if (selectedRoute != null && selectedRoute.steps != null && !selectedRoute.steps.isEmpty()) {
            RouteStep first = selectedRoute.steps.get(0);
            turnInstructionText.setText(first.instruction == null || first.instruction.trim().isEmpty()
                    ? "به سمت مقصد حرکت کنید" : first.instruction);
            turnArrowText.setText(arrowForInstruction(first.instruction));
            renderLaneGuidance(first.lanes);
        } else {
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
        if (selectedRoute == null || selectedRoute.steps == null || selectedRoute.steps.isEmpty()) return;
        // The foreground service is authoritative: keep the banner on the exact same maneuver as voice guidance.
        RouteStep step = null;
        if (navigationServiceBound && navigationService != null) {
            step = navigationService.getNavigationEngine().currentStep();
            int authoritativeIndex = navigationService.getNavigationEngine().currentStepIndex();
            if (authoritativeIndex >= 0 && authoritativeIndex < selectedRoute.steps.size()) displayedStepIndex = authoritativeIndex;
        }
        if (step == null) {
            if (displayedStepIndex < 0) displayedStepIndex = 0;
            if (displayedStepIndex >= selectedRoute.steps.size()) displayedStepIndex = selectedRoute.steps.size() - 1;
            while (displayedStepIndex < selectedRoute.steps.size() - 1) {
                RouteStep currentStep = selectedRoute.steps.get(displayedStepIndex);
                Location currentTarget = new Location("route");
                currentTarget.setLatitude(currentStep.latitude);
                currentTarget.setLongitude(currentStep.longitude);
                if (location.distanceTo(currentTarget) > 30f) break;
                displayedStepIndex++;
            }
            step = selectedRoute.steps.get(displayedStepIndex);
        }
        Location target = new Location("route");
        target.setLatitude(step.latitude);
        target.setLongitude(step.longitude);
        float metersToTurn = location.distanceTo(target);
        turnDistanceText.setText(formatDistance(Math.round(metersToTurn)));
        updateDrivingHud(location, metersToTurn);
    }

    /** Approximates remaining distance/time by adding the live distance to the next maneuver to
     *  the provider's per-step distances for every maneuver still ahead, then scales the route's
     *  total duration by that same fraction. It is an estimate (no live traffic per segment), but
     *  it moves with the car instead of freezing at the numbers shown when the route was chosen. */
    private int estimateRemainingRouteMeters(Location location) {
        if (selectedRoute == null || selectedRoute.steps == null || selectedRoute.steps.isEmpty()) return 0;
        int start = Math.max(0, Math.min(displayedStepIndex, selectedRoute.steps.size() - 1));
        Location previous = location;
        double total = 0d;
        for (int i = start; i < selectedRoute.steps.size(); i++) {
            RouteStep step = selectedRoute.steps.get(i);
            Location point = new Location("route");
            point.setLatitude(step.latitude);
            point.setLongitude(step.longitude);
            total += previous.distanceTo(point);
            previous = point;
        }
        if (destination != null) {
            Location end = new Location("destination");
            end.setLatitude(destination.latitude);
            end.setLongitude(destination.longitude);
            if (previous.distanceTo(end) > 5f) total += previous.distanceTo(end);
        }
        return (int) Math.max(0, Math.round(total));
    }

    private void updateDrivingHud(Location location, float metersToCurrentTarget) {
        if (selectedRoute == null || selectedRoute.steps.isEmpty()) return;
        int remainingMeters = estimateRemainingRouteMeters(location);
        if (remainingMeters <= 0) remainingMeters = Math.round(metersToCurrentTarget);
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
        return new MarkerStyle(bitmap);
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
        // The map uses the one route-scoped incident snapshot already loaded above. Periodic
        // refreshes would consume a provider key without affecting local navigation progress.
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
        return new MarkerStyle(bitmap);
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
     *  already left behind. Must include routeWaypoints - a plain origin-to-destination fetch here
     *  would silently drop every remaining intermediate stop from the recomputed route, so a
     *  waypoint reached after any reroute would never be detected or announced again. */
    private void clearNavigationRouteLines() {
        if (map == null) return;
        for (Polyline polyline : alternateRoutePolylines) map.removePolyline(polyline);
        alternateRoutePolylines.clear();
        if (routePolyline != null) {
            map.removePolyline(routePolyline);
            routePolyline = null;
        }
    }

    private void recalculateActiveRoute() {
        Location current = currentMapLocation();
        if (destination == null || current == null) return;
        refreshNavigationRouteFrom(current, true);
    }

    private void refreshNavigationRouteFrom(Location current, boolean force) {
        if (!navigationMode || destination == null || current == null || routeRefreshInFlight) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastRouteRefreshAttemptAt < ROUTE_REFRESH_RETRY_MS) return;
        routeRefreshInFlight = true;
        lastRouteRefreshAttemptAt = now;
        final double latitude = current.getLatitude();
        final double longitude = current.getLongitude();
        routeRepository.getRoute(latitude, longitude, waypointCoordinates(),
                destination.latitude, destination.longitude,
                route -> runOnUiThread(() -> {
                    routeRefreshInFlight = false;
                    if (!navigationMode || destination == null) return;
                    routeOptions = new ArrayList<>();
                    routeOptions.add(route);
                    selectedRoute = route;
                    routeNeedsRefreshFromCurrentLocation = false;
                    showRoutePreview(route);
                    startTurnByTurn(route);
                }),
                error -> runOnUiThread(() -> {
                    routeRefreshInFlight = false;
                    routeNeedsRefreshFromCurrentLocation = true;
                    if (turnInstructionText != null && force) {
                        turnInstructionText.setText("بازیابی مسیر انجام نشد: " + error);
                    }
                }));
    }

    private Location currentMapLocation() {
        if (lastAcceptedMapLocation != null) return new Location(lastAcceptedMapLocation);
        Location known = bestKnownDeviceLocation();
        if (known != null) return known;
        Location fallback = new Location("map_origin");
        fallback.setLatitude(originLatitude);
        fallback.setLongitude(originLongitude);
        return fallback;
    }

    @Override public void onWaypointApproaching(RouteStep step, int ordinal) {
        runOnUiThread(() -> {
            turnInstructionText.setText("توقف میانی " + (ordinal + 1) + " نزدیک است.");
            turnArrowText.setText("●");
            renderLaneGuidance(null);
        });
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
            // Hide stale geometry immediately; the replacement route is drawn only after the
            // routing provider returns real street geometry.
            clearNavigationRouteLines();
            turnInstructionText.setText("در حال محاسبه مجدد مسیر...");
            turnArrowText.setText("↻");
            renderLaneGuidance(null);
        });
        // MainActivity is the authoritative route-fetching UI while it is alive. If it is bound,
        // do not launch a second concurrent reroute request from MapActivity; two responses can
        // otherwise race and make the displayed line jump between different routes. MapActivity
        // remains the fallback rerouter only when no voice-capable Activity is bound.
        if (navigationServiceBound && navigationService != null && !navigationService.hasVoiceCapableCallback()) {
            recalculateActiveRoute();
        }
    }

    @Override public void onWaypointReached(RouteStep step, int ordinal) {
        runOnUiThread(() -> {
            // Match by coordinates, not ordinal: see removeReachedWaypoint in MainActivity for why
            // a route-specific ordinal desyncs from routeWaypoints once any stop has been removed.
            removeReachedWaypoint(step);
            turnInstructionText.setText("به توقف میانی رسیدید؛ مسیر ادامه دارد.");
            turnArrowText.setText("●");
            renderLaneGuidance(null);
            if (destination != null) destinationText.setText(waypointLabelSuffix(destination.name));
            findViewById(R.id.routeWaypointsButton).setVisibility(!navigationMode && !routeWaypoints.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    @Override public void onWaypointSkipped(RouteStep step, int ordinal) {
        runOnUiThread(() -> {
            removeReachedWaypoint(step);
            turnInstructionText.setText("توقف میانی رد شد؛ مسیر به مقصد بعدی ادامه دارد.");
            turnArrowText.setText("↻");
            renderLaneGuidance(null);
            if (destination != null) destinationText.setText(waypointLabelSuffix(destination.name));
        });
    }

    private void removeReachedWaypoint(RouteStep step) {
        Location reached = new Location("reached_waypoint");
        reached.setLatitude(step.latitude);
        reached.setLongitude(step.longitude);
        int closestIndex = -1;
        float closestDistance = Float.MAX_VALUE;
        for (int i = 0; i < routeWaypoints.size(); i++) {
            SavedPlace place = routeWaypoints.get(i);
            Location candidate = new Location("waypoint");
            candidate.setLatitude(place.latitude);
            candidate.setLongitude(place.longitude);
            float distance = reached.distanceTo(candidate);
            if (distance < closestDistance) { closestDistance = distance; closestIndex = i; }
        }
        if (closestIndex >= 0 && closestDistance <= 120f) routeWaypoints.remove(closestIndex);
    }

    /** Minimum distance actually traveled before a trip is considered a real, reportable
     *  completion - stopping moments after selecting a destination (or right where you already
     *  are) should not be treated as "arrived", regardless of how close that happens to be to the
     *  destination. */
    private static final float MIN_REPORTABLE_TRIP_METERS = 80f;

    private boolean tripIsReportable() {
        return tripTraveledDistanceMeters >= MIN_REPORTABLE_TRIP_METERS;
    }

    /** MainActivity runs its own independent NavigationEngine tracking the same trip in the
     *  background, and normally saves the trip itself when it detects arrival - but that instance
     *  can be destroyed by Android before arrival (extended screen-off time while the driver was on
     *  this map screen), silently losing the save entirely. Saving here too, directly from this
     *  activity's own arrival detection, guarantees at least one of the two persists it. The
     *  startedAt + destination name check below skips this if MainActivity's save already landed,
     *  so the trip does not appear twice in history when both fire normally. */
    private void saveTripRecordIfNeeded() {
        if (destination == null || tripStore == null || !tripIsReportable()) return;
        long startedAt = mapNavigationStartedAt > 0 ? mapNavigationStartedAt : System.currentTimeMillis();
        // MainActivity's own tripStartedAt and this activity's mapNavigationStartedAt are each set
        // independently, so they are never exactly equal for the same real trip - a 5-minute
        // window on the same destination is a reliable enough match to avoid a duplicate entry
        // without needing the two engines to share a single clock.
        for (TripRecord existing : tripStore.recent(5)) {
            if (existing.destinationName.equals(destination.name)
                    && Math.abs(existing.startedAt - startedAt) <= 300_000L) return;
        }
        // Distance/duration reflect the actual trip - origin to wherever it ended - not the
        // originally planned route to the destination, which may never have been fully driven.
        int distanceMeters = Math.round(tripTraveledDistanceMeters);
        long elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000L;
        int durationSeconds = (int) Math.max(1L, elapsedSeconds);
        String provider = selectedRoute != null ? selectedRoute.providerName : "";
        double fromLatitude = Double.isNaN(tripOriginLatitude) ? originLatitude : tripOriginLatitude;
        double fromLongitude = Double.isNaN(tripOriginLongitude) ? originLongitude : tripOriginLongitude;
        tripStore.add(new TripRecord(destination.name, fromLatitude, fromLongitude,
                destination.latitude, destination.longitude, distanceMeters, durationSeconds,
                startedAt, System.currentTimeMillis(), distanceMeters, provider, routeWaypoints.size(), true));
    }

    /** Clears the per-trip tracking fields once a trip has been fully handled (report shown or
     *  skipped as too short), so the next startTurnByTurn() call is recognized as a genuinely new
     *  trip rather than continuing to accumulate against the previous one. */
    private void resetTripTracking() {
        mapNavigationStartedAt = 0L;
        tripOriginLatitude = Double.NaN;
        tripOriginLongitude = Double.NaN;
        tripTraveledDistanceMeters = 0f;
        lastTripAccumLocation = null;
    }

    @Override public void onArrived() {
        runOnUiThread(() -> {
            if (tripCompletionShown) return;
            tripCompletionShown = true;
            boolean reportable = tripIsReportable();
            if (reportable) saveTripRecordIfNeeded();
            turnInstructionText.setText("به مقصد رسیدید");
            turnDistanceText.setText("");
            turnArrowText.setText("●");
            routeText.setText("سفر به پایان رسید.");
            renderLaneGuidance(null);
            // The engine has already stopped itself (see NavigationEngine.onLocation), but this
            // activity was still in navigationMode: the follow camera, the tighter GPS update
            // cadence, and the hidden route/save buttons all key off that flag, so without
            // clearing it here the screen stays stuck looking like navigation is still running.
            navigationMode = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            followVehicle = false;
            navigationCameraEnabled = false;
            // Nothing previously removed the drawn route line itself - the map kept showing the
            // route even though navigation had fully stopped, until the activity happened to be
            // recreated (e.g. leaving and returning) and started fresh with no polyline at all.
            if (map != null) {
                for (Polyline polyline : alternateRoutePolylines) map.removePolyline(polyline);
                alternateRoutePolylines.clear();
                if (routePolyline != null) {
                    map.removePolyline(routePolyline);
                    routePolyline = null;
                }
            }
            applyMapOrientation(0f, 0f, 0.3f);
            if (map != null) {
                map.moveCamera(new LatLng(originLatitude, originLongitude), 0.3f);
                map.setZoom(16f, 0.3f);
            }
            findViewById(R.id.mapBottomTabs).setVisibility(View.VISIBLE);
            findViewById(R.id.routeOptionsButton).setVisibility(View.VISIBLE);
            findViewById(R.id.saveMapPlaceButton).setVisibility(View.VISIBLE);
            findViewById(R.id.routeWaypointsButton).setVisibility(!routeWaypoints.isEmpty() ? View.VISIBLE : View.GONE);
            if (reportable) showMapTripCompletionReport();
            resetTripTracking();
        });
        trafficIncidentHandler.removeCallbacks(trafficIncidentRefresh);
    }

    private void showMapTripCompletionReport() {
        int traveledMeters = Math.round(tripTraveledDistanceMeters);
        long elapsedMinutes = mapNavigationStartedAt == 0L ? 0L
                : Math.max(1L, (System.currentTimeMillis() - mapNavigationStartedAt) / 60_000L);
        String destinationName = destination == null ? "مقصد" : destination.name;
        String report = "به " + destinationName + " رسیدید."
                + "\nمدت سفر: " + elapsedMinutes + " دقیقه"
                + (traveledMeters > 0 ? "\nمسافت طی‌شده: " + formatDistance(traveledMeters) : "");
        try {
            new AlertDialog.Builder(this)
                    .setTitle("گزارش پایان سفر")
                    .setMessage(report)
                    .setPositiveButton("بستن و پایان مسیر", (dialog, which) -> returnCompletedTripToMain())
                    .setOnCancelListener(dialog -> returnCompletedTripToMain())
                    .setCancelable(true)
                    .show();
            if (tripStore != null && mapNavigationStartedAt > 0) tripStore.markReportShown(mapNavigationStartedAt);
        } catch (RuntimeException e) {
            // The window may already be invalid right at this moment (activity mid-destruction,
            // screen off for a long time) - leave the report unmarked so pendingReport() below
            // picks it up and shows it on whichever screen the driver opens next, instead of the
            // report being lost entirely because this attempt happened to fail.
        }
    }

    /** Checks for a trip that completed without its report ever being successfully shown (the
     *  driver's screen was off or this activity was not in the foreground at that exact moment)
     *  and shows it now. Called from onResume so it catches up regardless of which screen the
     *  driver opens first after such a trip. */
    private void maybeShowPendingTripReport() {
        if (tripStore == null) return;
        TripRecord pending = tripStore.pendingReport();
        if (pending == null) return;
        long minutes = pending.endedAt <= pending.startedAt ? 0L
                : Math.max(1L, (pending.endedAt - pending.startedAt) / 60_000L);
        String report = "به " + pending.destinationName + " رسیدید."
                + "\nمدت سفر: " + minutes + " دقیقه"
                + (pending.distanceMeters > 0 ? "\nمسیر: " + formatDistance(pending.distanceMeters) : "");
        try {
            new AlertDialog.Builder(this)
                    .setTitle("گزارش پایان سفر")
                    .setMessage(report)
                    .setPositiveButton("باشه", (dialog, which) -> { })
                    .setCancelable(true)
                    .show();
            tripStore.markReportShown(pending.startedAt);
        } catch (RuntimeException ignored) {
        }
    }

    private void returnCompletedTripToMain() {
        Intent result = new Intent();
        result.putExtra(RESULT_TRIP_COMPLETED, true);
        setResult(RESULT_OK, result);
        finish();
    }

    /** Best-effort synchronous device location: the last cached GPS fix, or the last cached
     *  network fix if GPS has none, or null if permission is missing or neither exists yet. Used
     *  as a same-frame fallback so the map never has to sit at a fixed Tehran point while waiting
     *  for a fresh live GPS update to arrive. */
    private Location bestKnownDeviceLocation() {
        if (locationManager == null || !hasLocationPermission()) {
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
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_MAP_LOCATION_PERMISSION);
            return;
        }
        if (!isLocationEnabled()) {
            locationGateState = LocationGateState.LOCATION_DISABLED;
            new AlertDialog.Builder(this)
                    .setTitle("موقعیت مکانی خاموش است")
                    .setMessage("برای نمایش موقعیت شما روی نقشه، ابتدا موقعیت مکانی دستگاه را فعال کنید.")
                    .setPositiveButton("فعال کردن", (d, w) -> {
                        locationGateState = LocationGateState.LOCATION_SETTINGS_OPENED;
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    })
                    .setNegativeButton("انصراف", (d, w) -> locationGateState = LocationGateState.LOCATION_CANCELLED)
                    .setOnCancelListener(d -> locationGateState = LocationGateState.LOCATION_CANCELLED)
                    .show();
            return;
        }
        locationGateState = LocationGateState.LOCATION_ENABLED;
        if (navigationMode) {
            followVehicle = true;
            navigationCameraEnabled = true;
        }
        Location latest = bestKnownDeviceLocation();
        Location accepted = latest == null ? null : mapLocationFilter.filter(latest);
        if (accepted != null) {
            lastAcceptedMapLocation = new Location(accepted);
            originLatitude = accepted.getLatitude();
            originLongitude = accepted.getLongitude();
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MAP_LOCATION_PERMISSION) return;
        if (hasLocationPermission()) {
            focusOrigin();
        } else {
            Toast.makeText(this, "برای نمایش موقعیت فعلی، مجوز مکان را فعال کنید.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isLocationEnabled() {
        return locationManager != null && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
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
        // Prefer the same monotonic map-matched point used by the live engine. This prevents the
        // vehicle arrow from snapping to an earlier loop/parallel-road vertex on noisy GPS fixes.
        if (navigationServiceBound && navigationService != null) {
            RoutePoint snapped = navigationService.getNavigationEngine().snappedRoutePosition();
            if (snapped != null) { return new LatLng(snapped.latitude, snapped.longitude); }
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
        // Flat, north-up bird's-eye view: 0 tilt (not 90 - with the tilt camera now actually
        // implemented, 90 would pitch the map hard rather than flatten it) so "overview" reliably
        // means overview instead of an accidental near-max tilt on top of the driving camera.
        applyMapOrientation(0f, 0f, 0.2f);
        map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
        map.setZoom(12.5f, 0.25f);
    }

    /** Restores the driver-first viewport: the vehicle stays below center and the road points up. */
    private void enableNavigationCamera() {
        if (!navigationMode) return;
        searchHandler.removeCallbacks(resumeFollowVehicle);
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
                ? Math.min(displayedStepIndex, selectedRoute.steps.size() - 1) : -1;
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

    /** Manual "پایان مسیر" tap. Only treated as trip completion (report shown, saved as completed)
     *  when the driver is reasonably close to the destination - stopping while nowhere near it is a
     *  genuine cancellation, not an arrival, and showing an end-of-trip report for that is confusing
     *  and was reported as repeatedly appearing on every stop regardless of actual progress. A
     *  generous radius (not the strict arrival radius) still covers stopping a bit short, e.g.
     *  parking across the street or in a lot near the destination. */
    private static final float MANUAL_STOP_COMPLETION_RADIUS_METERS = 200f;

    private void stopNavigationFromMap() {
        Log.i("DriveMateSession", "Navigation stop explicitly requested from the map screen.");
        boolean wasNavigating = navigationMode && destination != null;
        navigationMode = false;
        followVehicle = false;
        navigationCameraEnabled = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean nearDestination = false;
        if (wasNavigating && destination != null) {
            Location current = currentMapLocation();
            Location destinationLocation = new Location("destination");
            destinationLocation.setLatitude(destination.latitude);
            destinationLocation.setLongitude(destination.longitude);
            nearDestination = current.distanceTo(destinationLocation) <= MANUAL_STOP_COMPLETION_RADIUS_METERS;
        }
        // Proximity to the destination alone is not enough: selecting a destination close to
        // where the driver already is, then stopping immediately, is trivially "near" without any
        // real trip having happened. Require actual distance traveled too.
        boolean reportable = wasNavigating && nearDestination && tripIsReportable();
        if (reportable && !tripCompletionShown) {
            tripCompletionShown = true;
            saveTripRecordIfNeeded();
            showMapTripCompletionReport();
        }
        resetTripTracking();
        Intent result = new Intent();
        result.putExtra(reportable ? RESULT_TRIP_COMPLETED : RESULT_STOP_NAVIGATION, true);
        setResult(RESULT_OK, result);
        if (!reportable) finish();
        // else: showMapTripCompletionReport()'s buttons (returnCompletedTripToMain / cancel) are
        // what actually finish() this screen, so the driver sees the report before it closes.
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
        return new MarkerStyle(bitmap);
    }

    private MarkerStyle vehicleMarkerStyle(float bearing) {
        int size = 148;
        float center = size / 2f;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.rotate(bearing, center, center);

        // Soft translucent halo so the arrow stays readable over both light and dark basemap
        // tiles, and gives the marker a bit of visual weight/presence instead of a bare shape.
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setColor(0x2fffffff);
        canvas.drawCircle(center, center, size * 0.44f, halo);
        Paint haloRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloRing.setStyle(Paint.Style.STROKE);
        haloRing.setStrokeWidth(size * 0.02f);
        haloRing.setColor(0x52ffffff);
        canvas.drawCircle(center, center, size * 0.44f, haloRing);

        // A rounded chevron/paper-airplane silhouette (smooth bezier curves) instead of the
        // previous flat 4-point diamond, drawn symmetrically around the bitmap's true center so
        // it stays put on the GPS point (not the bottom-tip) as it rotates with bearing.
        float tipY = size * 0.16f;
        float shoulderY = size * 0.74f;
        float shoulderX = size * 0.29f;
        float notchY = size * 0.55f;
        Path arrow = new Path();
        arrow.moveTo(center, tipY);
        arrow.quadTo(center + shoulderX * 1.05f, shoulderY * 0.8f, center + shoulderX, shoulderY);
        arrow.quadTo(center, notchY, center - shoulderX, shoulderY);
        arrow.quadTo(center - shoulderX * 1.05f, shoulderY * 0.8f, center, tipY);
        arrow.close();

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x552b2b2b);
        shadow.setMaskFilter(new android.graphics.BlurMaskFilter(size * 0.045f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        canvas.save();
        canvas.translate(0f, size * 0.025f);
        canvas.drawPath(arrow, shadow);
        canvas.restore();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new android.graphics.LinearGradient(center, tipY, center, shoulderY,
                0xff35a7e8, 0xff0d4e78, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawPath(arrow, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(size * 0.042f);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xffffffff);
        canvas.drawPath(arrow, stroke);

        // A small glossy highlight near the nose for a subtle 3D pop.
        Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlight.setColor(0x6bffffff);
        canvas.drawCircle(center, tipY + size * 0.15f, size * 0.045f, highlight);

        return new MarkerStyle(bitmap, true);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!navigationServiceBound) {
            bindService(new Intent(this, NavigationForegroundService.class), navigationServiceConnection, Context.BIND_AUTO_CREATE);
        } else if (navigationService != null) {
            navigationService.addCallback(navigationSessionCallback, false);
        }
        maybeShowPendingTripReport();
        if (NightModeManager.refreshIfChanged(this)) return;
        NightModeManager.applyWindowBrightness(this);
        if (map != null) map.onResume();
        // Some native map SDK builds tear down and rebuild their rendering surface across an
        // onPause/onResume cycle (e.g. leaving to another app and coming back) without telling the
        // app, silently dropping every previously-added marker even though this activity instance
        // (and its in-memory poiLayerPlaces cache) survives untouched. Re-adding the cached markers
        // here is cheap (no network call, just the same places already fetched) and fixes POI layers
        // appearing to vanish after leaving and reopening the map.
        redrawCachedPoiLayers();
        if (!routeSpeedLimits.isEmpty()) renderSpeedLimitMarkers();
        if (locationManager == null || !hasLocationPermission()) return;
        // Requested regardless of isLocationEnabled(): if GPS/network is off right now, this call
        // is a harmless no-op (no updates arrive), and it means updates resume automatically the
        // moment the driver turns location back on - without waiting for another onResume.
        long minTimeMs = navigationMode ? 1000L : 2500L;
        float minDistanceM = navigationMode ? 0f : 8f;
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, this);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMs, minDistanceM, this);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        if (!isLocationEnabled() && !gpsWarningActive) {
            gpsWarningActive = true;
            Toast.makeText(this, "موقعیت مکانی در دسترس نیست، لطفاً GPS را روشن کنید.", Toast.LENGTH_LONG).show();
        }
        // Only react here if a Settings trip was actually launched from the dialog above - never
        // open Settings or show the dialog again on our own, and never assume the driver actually
        // turned it on just because we're back. If it's on now, this reuses focusOrigin() to
        // recenter the marker/camera on the latest fix - it never touches navigationEngine, so an
        // in-progress trip's route/timer/voice queue are completely untouched. If it's still off,
        // do nothing further; the driver can tap "موقعیت من" again whenever they're ready.
        if (locationGateState == LocationGateState.LOCATION_SETTINGS_OPENED) {
            if (isLocationEnabled()) {
                locationGateState = LocationGateState.LOCATION_ENABLED;
                gpsWarningActive = false;
                focusOrigin();
            } else {
                locationGateState = LocationGateState.LOCATION_DISABLED;
            }
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
        // While actively navigating, a pause is very often transient (screen timeout despite
        // FLAG_KEEP_SCREEN_ON not always being honored by every OEM, an incoming call, the
        // notification shade) rather than the driver actually leaving this screen. Cutting
        // location updates here meant this activity's own NavigationEngine instance - which drives
        // arrival detection, turn advancement, and the on-screen state - went completely silent
        // until the next onResume, which is why the map could stay stuck showing an active route
        // long after the trip had actually ended (voiced from MainActivity's separate engine)
        // until the driver happened to leave and return, finally delivering a fresh fix here too.
        if (!navigationMode && locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {
            }
        }
        if (map != null) map.onPause();
        if (navigationService != null) navigationService.removeCallback(navigationSessionCallback);
        super.onPause();
    }

    /** Fired when GPS or network is toggled off (by the driver, or by the OS) while this screen
     *  is open. The map, the drawn route, and turn-by-turn tracking all stay exactly as they are -
     *  only a warning is shown, using the last known fix until updates resume. Never finish() or
     *  stop the navigation engine here. */
    @Override public void onProviderDisabled(String provider) {
        if (isLocationEnabled() || gpsWarningActive) return;
        gpsWarningActive = true;
        Toast.makeText(this, "موقعیت مکانی در دسترس نیست، لطفاً GPS را روشن کنید.", Toast.LENGTH_LONG).show();
        if (navigationMode) roadSpeedLimitText.setVisibility(View.GONE);
    }

    @Override public void onProviderEnabled(String provider) {
        if (gpsWarningActive) {
            gpsWarningActive = false;
            Toast.makeText(this, "موقعیت مکانی دوباره در دسترس است.", Toast.LENGTH_SHORT).show();
        }
        if (routeNeedsRefreshFromCurrentLocation) refreshNavigationRouteFrom(currentMapLocation(), false);
    }

    @Override protected void onDestroy() {
        if (navigationServiceBound) {
            if (navigationService != null) navigationService.removeCallback(navigationSessionCallback);
            try {
                unbindService(navigationServiceConnection);
            } catch (IllegalArgumentException ignored) {
                // Not actually bound (e.g. the service died and never reconnected) - nothing to undo.
            }
            navigationServiceBound = false;
        }
        poiExpansionHandler.removeCallbacksAndMessages(null);
        trafficIncidentHandler.removeCallbacksAndMessages(null);
        searchHandler.removeCallbacksAndMessages(null);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {
            }
        }
        if (map != null) {
            map.onDetach();
            map = null;
        }
        super.onDestroy();
    }

    @Override public void onLocationChanged(Location location) {
        if (isFinishing() || isDestroyed()) return;
        boolean authoritative = authoritativeLocationPending;
        authoritativeLocationPending = false;
        Location accepted = authoritative ? location : mapLocationFilter.filter(location);
        if (accepted == null) return;
        gpsWarningActive = false;
        lastAcceptedMapLocation = new Location(accepted);
        originLatitude = accepted.getLatitude();
        originLongitude = accepted.getLongitude();
        if (accepted.hasBearing()) {
            lastBearing = accepted.getBearing();
            hasHeading = true;
        }
        if (navigationMode && destination != null) {
            if (lastTripAccumLocation != null) tripTraveledDistanceMeters += lastTripAccumLocation.distanceTo(accepted);
            lastTripAccumLocation = new Location(accepted);
        }
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || map == null || !map.isReadyForOverlays()) return;
            showCurrentMarker();
            if (selectedRoute != null) updateRoadSpeedLimit(accepted.getLatitude(), accepted.getLongitude());
            if (navigationMode && destination != null) {
                        if (navigationMode && destination != null) {
                    updateTurnBanner(accepted);
                    if (routeNeedsRefreshFromCurrentLocation) refreshNavigationRouteFrom(accepted, false);
                    if (shouldRedrawNavigationRoute(accepted)) drawAllRoutes();
                }
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
