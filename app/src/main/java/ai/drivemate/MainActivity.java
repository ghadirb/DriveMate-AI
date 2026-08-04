package ai.drivemate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.drivemate.ai.AiAssistant;
import ai.drivemate.ai.DrivingIntelligenceCoordinator;
import ai.drivemate.ai.OnlineSpeechClient;
import ai.drivemate.ai.RuntimeKeys;
import ai.drivemate.ai.SmartDriveCompanion;
import ai.drivemate.location.AddressResolver;
import ai.drivemate.location.DeviceLocationTracker;
import ai.drivemate.location.SharedLocationParser;
import ai.drivemate.model.LaneGuidance;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteSafetyAlert;
import ai.drivemate.model.RouteSpeedZone;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.SpeedLimitPoint;
import ai.drivemate.model.TrafficIncident;
import ai.drivemate.model.TripRecord;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.OpenRouteServiceRoutingProvider;
import ai.drivemate.routing.NavigationEngine;
import ai.drivemate.routing.OverpassPoiProvider;
import ai.drivemate.routing.PlaceSearchRepository;
import ai.drivemate.routing.PoiCategory;
import ai.drivemate.routing.RouteCache;
import ai.drivemate.routing.RouteCurveAnalyzer;
import ai.drivemate.routing.RoutePatternAnalyzer;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.settings.NightModeManager;
import ai.drivemate.storage.PlaceStore;
import ai.drivemate.storage.TripStore;
import ai.drivemate.storage.BackupManager;
import ai.drivemate.traffic.TrafficIncidentProvider;
import ai.drivemate.voice.Command;
import ai.drivemate.voice.LocalSpeechRecognizer;
import ai.drivemate.voice.VoiceCommandParser;
import ai.drivemate.voice.VoiceGuidancePlayer;
import ai.drivemate.weather.WeatherHazardProvider;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 10;
    private static final int REQ_EXPORT_BACKUP = 11;
    private static final int REQ_IMPORT_BACKUP = 12;
    private static final int REQ_MAP = 13;
    private static final int REQ_EXPORT_TRIP_REPORTS = 14;
    private static final int MIN_RECORDED_TRIP_DISTANCE_METERS = 100;
    private static final long TRAFFIC_CHECK_INTERVAL_MS = 8 * 60_000L;
    private static final long WEATHER_CHECK_INTERVAL_MS = 20 * 60_000L;
    /** More frequent than the 8-minute aggregate reroute check: a live point incident (accident,
     *  closure, roadworks) can appear or clear faster than it would be worth fully rerouting for. */
    private static final long TRAFFIC_INCIDENT_CHECK_INTERVAL_MS = 4 * 60_000L;
    private static final int TRAFFIC_REROUTE_MIN_GAIN_SECONDS = 180;
    private static final String PREFS_SETTINGS = "drivemate_settings";
    /** Deliberately a separate file from PREFS_SETTINGS and excluded from cloud backup/device
     *  transfer (see backup_rules.xml / data_extraction_rules.xml) - this flag exists purely to
     *  detect "first run on this device", so restoring it from a backup would wrongly skip the
     *  onboarding dialog on a fresh install just because an old install once dismissed it. */
    private static final String PREFS_DEVICE_LOCAL = "drivemate_device_local";
    private static final String KEY_INTELLIGENCE_MODE = "driving_intelligence_mode";
    private static final String KEY_INTELLIGENCE_ONBOARDING_SHOWN = "intelligence_onboarding_shown_v2";
    public static final String ACTION_VOICE_FROM_NOTIFICATION = "ai.drivemate.action.VOICE_FROM_NOTIFICATION";

    private TextView statusText;
    private TextView aiStatusText;
    private TextView analysisTitleText;
    private TextView analysisBodyText;
    private View analysisPanel;
    private View tripStatsPanel;
    private TextView tripEtaText;
    private TextView tripRemainingText;
    private TextView tripElapsedText;
    private TextView tripSpeedText;
    private final SimpleDateFormat tripEtaFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private TextView listText;
    private TextView savedPlacesTabText;
    private ScrollView dashboardPage;
    private ScrollView savedPlacesPage;
    private ScrollView profilePage;
    private ScrollView tripHistoryPage;
    private LinearLayout tripHistoryContent;
    private Button voiceButton;
    private Button notificationButton;
    private Button intelligenceButton;
    private PlaceStore placeStore;
    private TripStore tripStore;
    private BackupManager backupManager;
    private VoiceGuidancePlayer voicePlayer;
    private DeviceLocationTracker locationTracker;
    /** True while the "GPS unavailable" status is showing, so repeated onProviderDisabled calls
     *  (GPS and network can each fire independently) don't spam setStatus. */
    private boolean gpsWarningActive;
    private NeshanRoutingProvider neshanRoutingProvider;
    private MapIrRoutingProvider mapIrRoutingProvider;
    private OpenRouteServiceRoutingProvider openRouteServiceRoutingProvider;
    private RouteRepository routeRepository;
    private PlaceSearchRepository placeSearchRepository;
    private VoiceCommandParser commandParser;
    private AiAssistant aiAssistant;
    private DrivingIntelligenceCoordinator intelligenceCoordinator;
    private OnlineSpeechClient onlineSpeechClient;
    private LocalSpeechRecognizer localSpeechRecognizer;
    private SmartDriveCompanion smartCompanion;
    private final NavigationEngine navigationEngine = new NavigationEngine();
    private RouteResult activeRoute;
    /** Ordered intermediate stops for the active trip. Kept through reroutes and map reopening. */
    private List<RoutePoint> activeWaypoints = new ArrayList<>();
    /** Community-mapped OpenStreetMap speed camera / speed bump / police-checkpoint points along
     *  the active route. Neshan and map.ir do not expose this through any public developer API,
     *  so it is the only source available; see OverpassPoiProvider for coverage caveats. */
    private final OverpassPoiProvider hazardProvider = new OverpassPoiProvider();
    private List<double[]> activeRouteHazards = new ArrayList<>();
    private boolean[] activeRouteHazardAnnounced = new boolean[0];
    private int hazardFetchRequestId;
    /** Sharp curves (pure geometry), railway crossings, school zones, best-effort OSM-tagged
     *  accident-prone points, tunnels, narrow bridges and steep grades along the active route. */
    private List<RouteSafetyAlert> activeRouteSafetyAlerts = new ArrayList<>();
    private boolean[] activeRouteSafetyAlertAnnounced = new boolean[0];
    private int safetyAlertFetchRequestId;
    /** Live OpenWeatherMap fog/wind check near the driver's current position; disabled cleanly if
     *  no key is configured (see WeatherHazardProvider.hasKey). */
    private final WeatherHazardProvider weatherHazardProvider = new WeatherHazardProvider(BuildConfig.OPENWEATHERMAP_API_KEY);
    private int weatherCheckRequestId;
    private long lastWeatherWarningAt;
    /** Live TomTom traffic-incident feed (accident, closure, roadworks, other hazard) near the
     *  active route; disabled cleanly if no key is configured (see TrafficIncidentProvider.hasKey).
     *  Refreshed periodically (not just once per route) since these change while a live incident
     *  clears or a new one appears - unlike the mostly-static OSM hazard/safety-alert sets above. */
    private final TrafficIncidentProvider trafficIncidentProvider = new TrafficIncidentProvider(BuildConfig.TOMTOM_API_KEY);
    private List<TrafficIncident> activeRouteTrafficIncidents = new ArrayList<>();
    /** Keyed by the provider's own incident id (not array index) since this list is periodically
     *  refreshed rather than fixed for the whole trip. */
    private final java.util.Set<String> announcedTrafficIncidentIds = new java.util.HashSet<>();
    private int trafficIncidentFetchRequestId;
    /** Numeric OSM maxspeed tags near the active route. Never treated as an official legal feed. */
    private List<SpeedLimitPoint> activeRouteSpeedLimits = new ArrayList<>();
    private int speedLimitFetchRequestId;
    private long lastSpeedLimitWarningAt;
    private int lastWarnedMappedSpeedLimit;
    /** Same maxspeed points as activeRouteSpeedLimits, projected onto the route's own distance
     *  axis and sorted ascending, so an upcoming lower-speed zone can be announced before the
     *  driver reaches it instead of only reacting once already inside it. See RouteSpeedZone. */
    private List<RouteSpeedZone> activeSpeedZones = new ArrayList<>();
    private boolean[] activeSpeedZoneAnnounced = new boolean[0];
    private double[] activeRouteCumulativeDistances = new double[0];
    private RuntimeKeys runtimeKeys = new RuntimeKeys();
    private String lastInstruction = "start_navigation";
    private String lastInstructionText = "";
    private SavedPlace activeDestination;
    private int lastTrafficEtaSeconds;
    private long lastTrafficEtaMeasuredAt;
    private long routeRequestSequence;
    private int pendingRouteOptionIndex;
    private boolean recordingOnlineSpeech;
    private boolean recordingLocalSpeech;
    private boolean runtimeKeysLoading = true;
    private boolean voiceRequestedWhileKeysLoad;
    private long tripStartedAt;
    private int activeTripDistanceMeters;
    private double activeTripOriginLatitude = Double.NaN;
    private double activeTripOriginLongitude = Double.NaN;
    private Location lastTripLocation;
    private long initialGuidanceHeldUntil;
    private SavedPlace pendingSuggestionPlace;
    private PoiCategory pendingSuggestionCategory;
    private final RoutePatternAnalyzer routePatternAnalyzer = new RoutePatternAnalyzer();
    private long nextPatternSuggestionCheckAt;
    private boolean patternSuggestionDialogShowing;
    private String dismissedPatternSuggestionKey;
    private long dismissedPatternSuggestionUntil;
    private final android.os.Handler voiceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable automaticStop = this::finishOnlineRecording;
    private final Runnable trafficCheck = this::checkTrafficAndMaybeReroute;
    private final Runnable weatherCheck = this::checkWeatherAlong;
    private final Runnable trafficIncidentCheck = () -> fetchRouteTrafficIncidents(activeRoute);
    private final Runnable tripAnalysisHide = this::hideTripAnalysis;
    private final BroadcastReceiver navigationStopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { onNavigationStopBroadcastReceived(); }
    };
    /** Points at whichever MainActivity instance actually owns the live GPS listener, turn-by-turn
     *  engine and voice guidance for the current trip - normally the same instance the whole time,
     *  but after the app is closed and reopened while a background trip is still running (see
     *  onDestroy), the *old* activity instance keeps driving navigation while a brand-new instance
     *  is created for the UI. Static so every instance in this process shares one answer to "is a
     *  trip already running, and if so, whose is it". Cleared once that owner's own stopNavigation
     *  or finishTrip actually ends the trip. */
    private static java.lang.ref.WeakReference<MainActivity> activeSessionOwner;
    /** True on a freshly (re)created instance that found activeSessionOwner already driving a
     *  trip in onCreate: this instance mirrors the destination/route for display and forwards the
     *  map and stop actions to the real owner instead of running a second, duplicate trip. */
    private boolean observingBackgroundSession;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(NightModeManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        NightModeManager.applyWindowBrightness(this);

        statusText = findViewById(R.id.statusText);
        aiStatusText = findViewById(R.id.aiStatusText);
        analysisPanel = findViewById(R.id.analysisPanel);
        analysisTitleText = findViewById(R.id.analysisTitleText);
        analysisBodyText = findViewById(R.id.analysisBodyText);
        tripStatsPanel = findViewById(R.id.tripStatsPanel);
        tripEtaText = findViewById(R.id.tripEtaText);
        tripRemainingText = findViewById(R.id.tripRemainingText);
        tripElapsedText = findViewById(R.id.tripElapsedText);
        tripSpeedText = findViewById(R.id.tripSpeedText);
        listText = findViewById(R.id.listText);
        savedPlacesTabText = findViewById(R.id.savedPlacesTabText);
        dashboardPage = findViewById(R.id.dashboardPage);
        savedPlacesPage = findViewById(R.id.savedPlacesPage);
        profilePage = findViewById(R.id.profilePage);
        tripHistoryPage = findViewById(R.id.tripHistoryPage);
        tripHistoryContent = findViewById(R.id.tripHistoryContent);
        voiceButton = findViewById(R.id.voiceButton);
        notificationButton = findViewById(R.id.notificationButton);
        intelligenceButton = findViewById(R.id.intelligenceButton);
        placeStore = new PlaceStore(this);
        tripStore = new TripStore(this);
        backupManager = new BackupManager(this, placeStore, tripStore);
        writeAutomaticBackup();
        voicePlayer = new VoiceGuidancePlayer(this);
        locationTracker = new DeviceLocationTracker(this);
        neshanRoutingProvider = new NeshanRoutingProvider(BuildConfig.NESHAN_API_KEY);
        mapIrRoutingProvider = new MapIrRoutingProvider(BuildConfig.MAPIR_API_KEY);
        openRouteServiceRoutingProvider = new OpenRouteServiceRoutingProvider(BuildConfig.OPENROUTESERVICE_API_KEY);
        routeRepository = new RouteRepository(neshanRoutingProvider, mapIrRoutingProvider, openRouteServiceRoutingProvider);
        placeSearchRepository = new PlaceSearchRepository(neshanRoutingProvider, mapIrRoutingProvider,
                BuildConfig.TOMTOM_API_KEY);
        commandParser = new VoiceCommandParser();
        aiAssistant = new AiAssistant(BuildConfig.AI_API_KEY);
        intelligenceCoordinator = new DrivingIntelligenceCoordinator(aiAssistant);
        intelligenceCoordinator.setMode(readIntelligenceMode());
        onlineSpeechClient = new OnlineSpeechClient(this, BuildConfig.AI_API_KEY);
        localSpeechRecognizer = new LocalSpeechRecognizer(this);
        smartCompanion = new SmartDriveCompanion((event, facts) -> runOnUiThread(() -> handleSmartEvent(event, facts)));

        wireButtons();
        requestCorePermissions();
        refreshList();
        voicePlayer.announce("welcome", "به همراه راننده خوش آمدید.");
        loadRuntimeKeys();
        promptEnableLocationIfNeeded();
        locationTracker.setUpdateListener(new DeviceLocationTracker.UpdateListener() {
            @Override public void onLocationUpdate(Location location) {
                gpsWarningActive = false;
                navigationEngine.onLocation(location);
                smartCompanion.onLocation(location);
                recordTripLocation(location);
                updateTripStats(location);
                maybeSuggestRecurringDestination(location);
                boolean movingNow = isCurrentlyMoving(location);
                checkRouteHazards(location, movingNow);
                checkRouteSafetyAlerts(location, movingNow);
                checkTrafficIncidentsProximity(location);
                checkUpcomingSpeedZone(location);
                checkRouteSpeedLimit(location);
            }

            @Override public void onLocationAvailabilityChanged(boolean available) {
                runOnUiThread(() -> {
                    // Never stop navigationEngine here: it should keep tracking against the last
                    // known fix so a GPS blip mid-trip doesn't end the trip or drop guidance.
                    // While this instance is only mirroring a background trip driven by another,
                    // older MainActivity instance (see resumeBackgroundSessionIfAny), that owner
                    // instance has its own, separate DeviceLocationTracker and already reports GPS
                    // loss/recovery for the real trip - reporting it again here from this mirror's
                    // own, redundant location listener would just duplicate the toast/status for
                    // the exact same physical GPS event (seen twice, ~16ms apart, in the 2026-08-02
                    // reopen-during-background-trip test log).
                    if (observingBackgroundSession) return;
                    if (!available && !gpsWarningActive) {
                        gpsWarningActive = true;
                        setStatus("موقعیت مکانی در دسترس نیست، لطفاً GPS را روشن کنید.");
                    } else if (available && gpsWarningActive) {
                        gpsWarningActive = false;
                        setStatus("موقعیت مکانی دوباره در دسترس است.");
                    }
                });
            }
        });
        handleSharedIntent(getIntent());
        registerNavigationReceiver();
        refreshNotificationButton();
        refreshIntelligenceButton();
        refreshAiStatus();
        resumeBackgroundSessionIfAny();
        voiceHandler.postDelayed(this::maybeShowIntelligenceOnboarding, 500L);
        if (ACTION_VOICE_FROM_NOTIFICATION.equals(getIntent().getAction())) voiceHandler.postDelayed(this::toggleVoiceInput, 350L);
    }

    /** Runs once per onCreate. If the app was closed and reopened while a trip was still running
     *  in the background (see onDestroy), the *previous* MainActivity instance is still alive and
     *  still driving GPS/voice for that trip - it just has no visible UI anymore. Rather than this
     *  new instance starting a second, duplicate navigation session (double voice guidance, double
     *  GPS tracking), it mirrors the owner's destination/route for display and forwards "نقشه" and
     *  "توقف" to the real owner. If no background trip is running, this is a no-op and the app
     *  looks exactly as it always has. */
    private void resumeBackgroundSessionIfAny() {
        MainActivity owner = activeSessionOwner == null ? null : activeSessionOwner.get();
        if (owner == null || owner == this || !owner.navigationEngine.isNavigating() || owner.activeDestination == null) return;
        observingBackgroundSession = true;
        activeDestination = owner.activeDestination;
        activeRoute = owner.activeRoute;
        activeWaypoints = new ArrayList<>(owner.activeWaypoints);
        setStatus("مسیریابی به " + activeDestination.name + " همچنان در پس‌زمینه در حال اجراست.");
        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (NightModeManager.refreshIfChanged(this)) return;
        NightModeManager.applyWindowBrightness(this);
    }

    private void wireButtons() {
        voiceButton.setOnClickListener(v -> toggleVoiceInput());
        findViewById(R.id.mapButton).setOnClickListener(v -> openMap());
        findViewById(R.id.saveButton).setOnClickListener(v -> promptSaveCurrentPlace());
        findViewById(R.id.homeButton).setOnClickListener(v -> openHomeOrWork("home", "خانه"));
        findViewById(R.id.workButton).setOnClickListener(v -> openHomeOrWork("work", "محل کار"));
        findViewById(R.id.favoritesButton).setOnClickListener(v -> showPlaces(true));
        findViewById(R.id.recentButton).setOnClickListener(v -> showRecent());
        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettingsMenu());
        intelligenceButton.setOnClickListener(v -> showIntelligenceModeDialog());
        findViewById(R.id.stopButton).setOnClickListener(v -> requestStopNavigation("مسیریابی متوقف شد."));
        notificationButton.setOnClickListener(v -> toggleBackgroundNavigation());
        findViewById(R.id.backupButton).setOnClickListener(v -> showBackupDialog());
        findViewById(R.id.tabDashboardButton).setOnClickListener(v -> selectMainTab(0));
        findViewById(R.id.tabMapButton).setOnClickListener(v -> openMap());
        findViewById(R.id.tabSavedButton).setOnClickListener(v -> selectMainTab(1));
        findViewById(R.id.tabProfileButton).setOnClickListener(v -> selectMainTab(2));
        findViewById(R.id.addSavedPlaceTabButton).setOnClickListener(v -> promptSaveCurrentPlace());
        findViewById(R.id.manageSavedPlacesTabButton).setOnClickListener(v -> choosePlace(new ArrayList<>(placeStore.allPlaces())));
        findViewById(R.id.profileSettingsButton).setOnClickListener(v -> showSettingsMenu());
        findViewById(R.id.profileSubscriptionButton).setOnClickListener(v -> showSubscriptionInfo());
        findViewById(R.id.profileBackupButton).setOnClickListener(v -> showBackupDialog());
        findViewById(R.id.profileTripsButton).setOnClickListener(v -> showTripHistory());
        findViewById(R.id.profileAboutButton).setOnClickListener(v -> showAboutDialog());
        findViewById(R.id.closeTripHistoryButton).setOnClickListener(v -> selectMainTab(2));
        findViewById(R.id.exportTripReportsButton).setOnClickListener(v -> exportTripReports());
        selectMainTab(0);
    }

    private void requestCorePermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            locationTracker.start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            locationTracker.start();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MAP && resultCode == RESULT_OK && data != null) {
            String requestedTab = data.getStringExtra(MapActivity.RESULT_MAIN_TAB);
            if (requestedTab != null) {
                selectMainTab("saved".equals(requestedTab) ? 1 : "profile".equals(requestedTab) ? 2 : 0);
            } else if (data.getBooleanExtra(MapActivity.RESULT_START_VOICE, false)) {
                toggleVoiceInput();
            } else if (data.getBooleanExtra(MapActivity.RESULT_STOP_NAVIGATION, false)) {
                requestStopNavigation("مسیریابی متوقف شد.");
            } else if (data.hasExtra(MapActivity.RESULT_LATITUDE) && data.hasExtra(MapActivity.RESULT_LONGITUDE)) {
                SavedPlace destination = new SavedPlace(
                        data.getStringExtra(MapActivity.RESULT_NAME), "map_" + System.currentTimeMillis(),
                        data.getDoubleExtra(MapActivity.RESULT_LATITUDE, 0d),
                        data.getDoubleExtra(MapActivity.RESULT_LONGITUDE, 0d),
                        data.getStringExtra(MapActivity.RESULT_ADDRESS), System.currentTimeMillis(), false);
                int selectedRouteIndex = Math.max(0, data.getIntExtra(MapActivity.RESULT_ROUTE_INDEX, 0));
                pendingRouteOptionIndex = selectedRouteIndex;
                List<RoutePoint> waypoints = decodeWaypoints(data.getStringArrayListExtra(MapActivity.RESULT_WAYPOINTS));
                startNavigation(destination, waypoints);
                if (data.getBooleanExtra(MapActivity.RESULT_OPEN_NAVIGATION_MAP, false)) {
                    openNavigationMap(destination, selectedRouteIndex, waypoints);
                }
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_BACKUP) {
            new Thread(() -> {
                try {
                    backupManager.exportTo(uri);
                    runOnUiThread(() -> setStatus("پشتیبان در محل انتخاب‌شده ذخیره شد."));
                } catch (Exception error) {
                    runOnUiThread(() -> setStatus("ذخیره پشتیبان انجام نشد."));
                }
            }).start();
        } else if (requestCode == REQ_EXPORT_TRIP_REPORTS) {
            new Thread(() -> {
                try {
                    writeTripReportCsv(uri);
                    runOnUiThread(() -> setStatus("گزارش سفرها ذخیره شد."));
                } catch (Exception error) {
                    runOnUiThread(() -> setStatus("ذخیره گزارش سفرها انجام نشد."));
                }
            }).start();
        } else if (requestCode == REQ_IMPORT_BACKUP) {
            confirmRestore(uri);
        }
    }

    private void openMap() {
        if ((navigationEngine.isNavigating() || observingBackgroundSession) && activeDestination != null) {
            openNavigationMap(activeDestination);
            return;
        }
        Intent intent = new Intent(this, MapActivity.class);
        Location location = locationTracker.getLastLocation();
        if (location != null) {
            intent.putExtra(MapActivity.EXTRA_ORIGIN_LATITUDE, location.getLatitude());
            intent.putExtra(MapActivity.EXTRA_ORIGIN_LONGITUDE, location.getLongitude());
        }
        // The encrypted runtime payload may contain only AI keys. Keep routing keys injected
        // by GitHub Actions available to the map as a fallback.
        intent.putExtra(MapActivity.EXTRA_NESHAN_KEY, routingKey("NESHAN_API_KEY", BuildConfig.NESHAN_API_KEY));
        intent.putExtra(MapActivity.EXTRA_MAPIR_KEY, routingKey("MAPIR_API_KEY", BuildConfig.MAPIR_API_KEY));
        intent.putExtra(MapActivity.EXTRA_TOMTOM_KEY, BuildConfig.TOMTOM_API_KEY);
        intent.putExtra(MapActivity.EXTRA_OPENROUTESERVICE_KEY, BuildConfig.OPENROUTESERVICE_API_KEY);
        startActivityForResult(intent, REQ_MAP);
    }

    private void openNavigationMap(SavedPlace destination) {
        openNavigationMap(destination, 0, activeWaypoints);
    }

    private void openNavigationMap(SavedPlace destination, int selectedRouteIndex) {
        openNavigationMap(destination, selectedRouteIndex, activeWaypoints);
    }

    private void openNavigationMap(SavedPlace destination, int selectedRouteIndex, List<RoutePoint> waypoints) {
        Intent intent = new Intent(this, MapActivity.class);
        Location location = locationTracker.getLastLocation();
        if (location != null) {
            intent.putExtra(MapActivity.EXTRA_ORIGIN_LATITUDE, location.getLatitude());
            intent.putExtra(MapActivity.EXTRA_ORIGIN_LONGITUDE, location.getLongitude());
        }
        intent.putExtra(MapActivity.EXTRA_NESHAN_KEY, routingKey("NESHAN_API_KEY", BuildConfig.NESHAN_API_KEY));
        intent.putExtra(MapActivity.EXTRA_MAPIR_KEY, routingKey("MAPIR_API_KEY", BuildConfig.MAPIR_API_KEY));
        intent.putExtra(MapActivity.EXTRA_TOMTOM_KEY, BuildConfig.TOMTOM_API_KEY);
        intent.putExtra(MapActivity.EXTRA_OPENROUTESERVICE_KEY, BuildConfig.OPENROUTESERVICE_API_KEY);
        intent.putExtra(MapActivity.EXTRA_NAVIGATION_MODE, true);
        intent.putExtra(MapActivity.EXTRA_DESTINATION_LATITUDE, destination.latitude);
        intent.putExtra(MapActivity.EXTRA_DESTINATION_LONGITUDE, destination.longitude);
        intent.putExtra(MapActivity.EXTRA_DESTINATION_NAME, destination.name);
        intent.putExtra(MapActivity.EXTRA_DESTINATION_ADDRESS, destination.address);
        intent.putExtra(MapActivity.EXTRA_NAVIGATION_ROUTE_INDEX, selectedRouteIndex);
        intent.putStringArrayListExtra(MapActivity.EXTRA_NAVIGATION_WAYPOINTS, encodeWaypoints(waypoints));
        startActivityForResult(intent, REQ_MAP);
    }

    private ArrayList<String> encodeWaypoints(List<RoutePoint> waypoints) {
        ArrayList<String> result = new ArrayList<>();
        if (waypoints == null) return result;
        for (RoutePoint point : waypoints) result.add(point.latitude + "," + point.longitude + ",توقف میانی");
        return result;
    }

    private List<RoutePoint> decodeWaypoints(ArrayList<String> encoded) {
        ArrayList<RoutePoint> result = new ArrayList<>();
        if (encoded == null) return result;
        for (String value : encoded) {
            if (value == null) continue;
            String[] parts = value.split(",", 3);
            if (parts.length < 2) continue;
            try {
                result.add(new RoutePoint(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])));
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private String routingKey(String name, String buildConfigFallback) {
        String runtimeValue = runtimeKeys == null ? null : runtimeKeys.get(name);
        return runtimeValue == null || runtimeValue.trim().isEmpty() ? buildConfigFallback : runtimeValue;
    }

    private void showBackupDialog() {
        String[] options = {
                "ساخت نسخه خودکار در گوشی",
                "ذخیره دستی یا Google Drive",
                "ارسال فایل پشتیبان",
                "بازگردانی از فایل"
        };
        new AlertDialog.Builder(this).setTitle("پشتیبان‌گیری و بازیابی")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) createLocalBackup();
                    else if (which == 1) exportBackup();
                    else if (which == 2) shareBackup();
                    else importBackup();
                }).show();
    }

    private void createLocalBackup() {
        new Thread(() -> {
            try {
                backupManager.writeAutomaticSnapshot();
                runOnUiThread(() -> setStatus("نسخه پشتیبان در حافظه برنامه ساخته شد."));
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("ساخت پشتیبان انجام نشد."));
            }
        }).start();
    }

    private void exportBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType(BackupManager.MIME_TYPE);
        intent.putExtra(Intent.EXTRA_TITLE, backupManager.suggestedFileName());
        startActivityForResult(intent, REQ_EXPORT_BACKUP);
    }

    private void shareBackup() {
        new Thread(() -> {
            try {
                File file = backupManager.createShareSnapshot();
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
                runOnUiThread(() -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType(BackupManager.MIME_TYPE);
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "ارسال پشتیبان DriveMate"));
                });
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("آماده‌سازی فایل پشتیبان انجام نشد."));
            }
        }).start();
    }

    private void importBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(BackupManager.MIME_TYPE);
        startActivityForResult(intent, REQ_IMPORT_BACKUP);
    }

    private void confirmRestore(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("بازگردانی پشتیبان")
                .setMessage("مکان‌ها، مقصدهای اخیر، سفرها و تنظیمات فعلی با نسخه پشتیبان جایگزین می‌شوند.")
                .setPositiveButton("بازگردانی", (dialog, which) -> new Thread(() -> {
                    try {
                        backupManager.restoreFrom(uri);
                        backupManager.writeAutomaticSnapshot();
                        runOnUiThread(() -> {
                            refreshNotificationButton();
                            refreshList();
                            setStatus("اطلاعات از پشتیبان بازگردانی شد.");
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> setStatus("فایل پشتیبان معتبر نیست یا بازگردانی نشد."));
                    }
                }).start())
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void writeAutomaticBackup() {
        new Thread(() -> {
            try { backupManager.writeAutomaticSnapshot(); }
            catch (Exception ignored) { }
        }).start();
    }

    private void toggleVoiceInput() {
        if (recordingOnlineSpeech) {
            finishOnlineRecording();
            return;
        }
        if (recordingLocalSpeech) {
            localSpeechRecognizer.cancel();
            restoreVoiceButton();
            return;
        }
        if (runtimeKeysLoading && !onlineSpeechClient.canUseOnlineSpeech()) {
            if (!startLocalVoiceRecognition()) {
                voiceRequestedWhileKeysLoad = true;
                setStatus("در حال آماده‌سازی سرویس گفتار...");
            }
            return;
        }
        if (onlineSpeechClient.canUseOnlineSpeech() && onlineSpeechClient.startRecording()) {
            recordingOnlineSpeech = true;
            voicePlayer.announce("listening", "در حال گوش دادن هستم.");
            voiceButton.setText("در حال ضبط... برای پایان لمس کنید");
            setStatus("در حال ضبط با کیفیت بالا است. پس از ۱۰ ثانیه خودکار ارسال می‌شود.");
            voiceHandler.postDelayed(automaticStop, 10000L);
            return;
        }
        if (!startLocalVoiceRecognition()) {
            setStatus("سرویس گفتار آنلاین آماده نیست و تشخیص گفتار آفلاین فارسی روی این گوشی در دسترس نیست.");
        }
    }

    private boolean startLocalVoiceRecognition() {
        boolean started = localSpeechRecognizer.start(new LocalSpeechRecognizer.Callback() {
            @Override public void onText(String text) { runOnUiThread(() -> {
                restoreVoiceButton();
                handleVoiceText(text);
            }); }
            @Override public void onError() { runOnUiThread(() -> {
                restoreVoiceButton();
                setStatus("تشخیص گفتار آفلاین انجام نشد.");
            }); }
        });
        if (!started) return false;
        recordingLocalSpeech = true;
        voiceButton.setText("در حال گوش دادن آفلاین...");
        setStatus("تشخیص گفتار آفلاین فعال است.");
        return true;
    }

    private void finishOnlineRecording() {
        if (!recordingOnlineSpeech) return;
        recordingOnlineSpeech = false;
        voiceHandler.removeCallbacks(automaticStop);
        voiceButton.setEnabled(false);
        voiceButton.setText("در حال پردازش صدا...");
        setStatus("صدا در حال پردازش است.");
        onlineSpeechClient.stopAndTranscribe(new OnlineSpeechClient.TextCallback() {
            @Override public void onResult(String text) { runOnUiThread(() -> {
                restoreVoiceButton();
                if (text == null || text.trim().isEmpty()) setStatus("پاسخ صوتی خالی بود؛ دوباره ضبط کنید.");
                else handleVoiceText(text);
            }); }
            @Override public void onError(String message) { runOnUiThread(() -> {
                restoreVoiceButton();
                setStatus("پردازش صدا انجام نشد. لطفاً اتصال اینترنت را بررسی کنید.");
            }); }
        });
    }

    private void restoreVoiceButton() {
        recordingLocalSpeech = false;
        voiceButton.setEnabled(true);
        voiceButton.setText("مقصد را بگویید");
    }

    private void handleVoiceText(String text) {
        Command command = commandParser.parse(text);
        setStatus("شنیدم: " + text);

        switch (command.type) {
            case SAVE_HOME:
                saveCurrentPlace("خانه", "home");
                break;
            case SAVE_WORK:
                saveCurrentPlace("محل کار", "work");
                break;
            case NAVIGATE_HOME:
                navigateToKnownPlace("home");
                break;
            case NAVIGATE_WORK:
                navigateToKnownPlace("work");
                break;
            case NAVIGATE_NAMED_PLACE:
                SavedPlace requestedPlace = placeStore.findByNameInText(text);
                if (requestedPlace != null) startNavigation(requestedPlace);
                else searchAndNavigate(cleanDestinationText(text));
                break;
            case FIND_FUEL:
                searchAndNavigate("پمپ بنزین");
                break;
            case FIND_REST:
                searchAndNavigate("مجتمع خدماتی");
                break;
            case FIND_PLACE:
                suggestNearbyPlace(command.poiCategory);
                break;
            case CONFIRM_SUGGESTION:
                confirmPendingSuggestion();
                break;
            case DECLINE_SUGGESTION:
                declinePendingSuggestion();
                break;
            case FUEL_REFILLED:
                smartCompanion.resetFuelDistance();
                voicePlayer.announce("fuel_refilled", "باشه، شمارش مسافت از آخرین سوخت‌گیری از نو شروع شد.");
                setStatus("شمارش مسافت سوخت بازنشانی شد.");
                break;
            case VOLUME_UP:
                voicePlayer.increaseVolume();
                voicePlayer.announce("voice_louder", "صدای راهنما بیشتر شد.");
                setStatus("صدای راهنما بیشتر شد.");
                break;
            case VOLUME_DOWN:
                voicePlayer.decreaseVolume();
                voicePlayer.announce("voice_lower", "صدای راهنما کمتر شد.");
                setStatus("صدای راهنما کمتر شد.");
                break;
            case REPEAT:
                if (isFullIntelligenceMode() && !lastInstructionText.isEmpty()) {
                    speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                            "راهنمای قبلی مسیر را با یک جمله فارسی کوتاه و طبیعی تکرار کن: " + lastInstructionText,
                            lastInstruction, lastInstructionText, 10_000L);
                } else {
                    voicePlayer.play(lastInstruction);
                }
                break;
            case ASK_APP_NAME:
                voicePlayer.speak("من «همراه راننده» هستم؛ دستیار هوشمند رانندگی شما.");
                setStatus("من همراه راننده هستم.");
                break;
            case ASK_AI:
                askAi(text);
                break;
            default:
                SavedPlace namedPlace = placeStore.findByNameInText(text);
                if (namedPlace != null) {
                    startNavigation(namedPlace);
                } else if (commandParser.isExplicitQuestion(text)) {
                    askAi(text);
                } else {
                    searchAndNavigate(cleanDestinationText(text));
                }
        }
    }

    private void promptSaveCurrentPlace() {
        final EditText input = new EditText(this);
        input.setHint("مثلاً خانه مادر، باشگاه، مدرسه");
        input.setText("مکان جدید");
        new AlertDialog.Builder(this)
                .setTitle("نام مکان ذخیره‌شده")
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> saveCurrentPlace(input.getText().toString(), "custom_" + System.currentTimeMillis()))
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void saveCurrentPlace(String name, String kind) {
        Location location = locationTracker.getLastLocation();
        if (location == null) {
            setStatus("هنوز موقعیت GPS آماده نیست.");
            voicePlayer.announce("gps_lost", "موقعیت مکانی هنوز در دسترس نیست.");
            return;
        }
        String finalName = (name == null || name.trim().isEmpty()) ? "مکان جدید" : name.trim();
        new Thread(() -> {
            String address = AddressResolver.resolve(this, location.getLatitude(), location.getLongitude());
            SavedPlace place = new SavedPlace(finalName, kind, location.getLatitude(), location.getLongitude(), address, System.currentTimeMillis(), true);
            placeStore.upsert(place);
            writeAutomaticBackup();
            runOnUiThread(() -> {
                String savedFallback = "home".equals(kind) ? "آدرس خانه ذخیره شد." : "work".equals(kind) ? "آدرس محل کار ذخیره شد." : finalName + " ذخیره شد.";
                voicePlayer.announce("home".equals(kind) ? "home_saved" : "work".equals(kind) ? "work_saved" : "place_saved", savedFallback);
                setStatus(finalName + " ذخیره شد.");
                refreshList();
            });
        }).start();
    }

    private void navigateToKnownPlace(String kind) {
        SavedPlace place = placeStore.findByKind(kind);
        if (place == null) {
            setStatus(("home".equals(kind) ? "خانه" : "محل کار") + " هنوز ذخیره نشده است.");
            return;
        }
        startNavigation(place);
    }

    private void startNavigation(SavedPlace destination) {
        startNavigation(destination, new ArrayList<>());
    }

    private void startNavigation(SavedPlace destination, List<RoutePoint> waypoints) {
        startNavigation(destination, waypoints, false);
    }

    /** Guards against ever running two live navigation sessions (and so two overlapping streams of
     *  spoken guidance) at once. This only matters in the narrow window where a background trip is
     *  still owned by an older, no-longer-visible MainActivity instance (see onDestroy /
     *  resumeBackgroundSessionIfAny) and the driver starts a *new* trip from the current, visible
     *  instance - e.g. picking a different destination while "observing" a background session.
     *  Ends the old instance's trip cleanly first so this instance can become the sole owner. */
    private void stopAnyOtherActiveSessionBeforeStartingHere() {
        MainActivity owner = activeSessionOwner == null ? null : activeSessionOwner.get();
        if (owner != null && owner != this && owner.navigationEngine.isNavigating()) {
            owner.stopNavigation("مسیر جدیدی انتخاب شد؛ مسیریابی پیشین متوقف شد.");
        }
        observingBackgroundSession = false;
    }

    /** Re-routing keeps the original trip clock and GPS distance so the final report covers the
     *  whole journey rather than only its last recalculated segment. */
    private void startNavigation(SavedPlace destination, List<RoutePoint> waypoints, boolean preserveTripProgress) {
        stopAnyOtherActiveSessionBeforeStartingHere();
        intelligenceCoordinator.cancelAll();
        final long requestSequence = ++routeRequestSequence;
        final int preferredRouteIndex = pendingRouteOptionIndex;
        pendingRouteOptionIndex = 0;
        voiceHandler.removeCallbacks(tripAnalysisHide);
        hideTripAnalysis();
        if (!routeRepository.hasConfiguredProvider()) {
            setStatus("سرویس مسیریابی آماده نیست. اتصال اینترنت و تنظیمات برنامه را بررسی کنید.");
            return;
        }
        Location origin = locationTracker.getLastLocation();
        if (origin == null) {
            setStatus("برای شروع مسیر، GPS باید آماده باشد.");
            voicePlayer.announce("gps_lost", "موقعیت مکانی هنوز در دسترس نیست.");
            return;
        }
        setStatus("در حال دریافت مسیر به " + destination.name + "...");
        showRouteAnalysisLoading(destination);
        if (!isFullIntelligenceMode()) {
            voicePlayer.announce("searching_route", "در حال یافتن مسیر هستم.");
        }
        final double originLatitude = origin.getLatitude();
        final double originLongitude = origin.getLongitude();
        final List<RoutePoint> requestedWaypoints = waypoints == null ? new ArrayList<>() : new ArrayList<>(waypoints);
        routeRepository.getRoutes(originLatitude, originLongitude, requestedWaypoints, destination.latitude, destination.longitude,
                routes -> runOnUiThread(() -> {
                    if (routes == null || routes.isEmpty()) return;
                    RouteResult route = routes.get(Math.min(preferredRouteIndex, routes.size() - 1));
                    if (requestSequence != routeRequestSequence) return;
                    placeStore.addRecent(destination);
                    observingBackgroundSession = false;
                    activeSessionOwner = new java.lang.ref.WeakReference<>(MainActivity.this);
                    activeDestination = destination;
                    activeRoute = route;
                    RouteCache.store(route, destination.latitude, destination.longitude);
                    activeWaypoints = new ArrayList<>(requestedWaypoints);
                    if (!preserveTripProgress || tripStartedAt == 0L) {
                        tripStartedAt = System.currentTimeMillis();
                        activeTripDistanceMeters = 0;
                        activeTripOriginLatitude = originLatitude;
                        activeTripOriginLongitude = originLongitude;
                    }
                    lastTripLocation = new Location(origin);
                    smartCompanion.start();
                    fetchRouteHazards(route);
                    fetchRouteSafetyAlerts(route);
                    fetchRouteTrafficIncidents(route);
                    startBackgroundNavigation();
                    lastTrafficEtaSeconds = route.durationSeconds;
                    lastTrafficEtaMeasuredAt = System.currentTimeMillis();
                    String firstRouteInstruction = route.steps.isEmpty() ? "<none>" : route.steps.get(0).instruction;
                    android.util.Log.i("DriveMateRoute", "provider=" + route.providerName + " steps=" + route.steps.size()
                            + " first=" + firstRouteInstruction);
                    navigationEngine.start(route, new NavigationEngine.Listener() {
                        @Override public void onInstruction(RouteStep step) {
                            runOnUiThread(() -> announceRouteStep(step));
                        }
                        @Override public void onOffRoute() {
                            runOnUiThread(() -> rerouteFromCurrentLocation());
                        }
                        @Override public void onArrived() {
                            runOnUiThread(() -> finishTrip(destination));
                        }
                        @Override public void onWaypointReached(RouteStep step, int ordinal) {
                            runOnUiThread(() -> announceWaypointReached(ordinal));
                        }
                    }, origin);
                    initialGuidanceHeldUntil = System.currentTimeMillis() + 2_600L;
                    lastInstruction = "start_navigation";
                    lastInstructionText = "مسیر به " + destination.name + " آماده است.";
                    setStatus("مسیر آماده است. فاصله تقریبی: " + route.distanceMeters + " متر");
                    showTripAnalysis(route, destination);
                    voiceHandler.postDelayed(() -> {
                        if (requestSequence != routeRequestSequence || activeDestination != destination
                                || !navigationEngine.isNavigating()) return;
                        announceTripStart(route, destination);
                    }, 2_600L);
                    scheduleTrafficCheck();
                    checkWeatherAlong();
                    scheduleTrafficIncidentCheck();
                    refreshList();
                }),
                error -> runOnUiThread(() -> {
                    if (requestSequence != routeRequestSequence) return;
                    hideTripAnalysis();
                    voicePlayer.announce("api_error", "در دریافت مسیر خطایی رخ داد.");
                    setStatus("خطا در دریافت مسیر: " + error);
                }));
    }

    /** Learns recurring destinations purely from on-device trip history and, when the driver is
     *  not already navigating, offers to start the same trip if today's weekday and time of day
     *  match a repeated pattern closely enough. Runs at most every few minutes and never nags
     *  again about the same suggestion for a couple of hours after the driver declines it. */
    private void maybeSuggestRecurringDestination(Location location) {
        if (location == null || activeRoute != null || patternSuggestionDialogShowing) return;
        long now = System.currentTimeMillis();
        if (now < nextPatternSuggestionCheckAt) return;
        nextPatternSuggestionCheckAt = now + 5 * 60_000L;
        final double latitude = location.getLatitude();
        final double longitude = location.getLongitude();
        new Thread(() -> {
            List<TripRecord> trips = tripStore.recent(60);
            RoutePatternAnalyzer.Suggestion suggestion = routePatternAnalyzer.suggestForNow(trips, now, latitude, longitude);
            if (suggestion == null) return;
            final String key = suggestion.place.name + "@" + Math.round(suggestion.place.latitude * 1000d)
                    + "," + Math.round(suggestion.place.longitude * 1000d);
            if (key.equals(dismissedPatternSuggestionKey) && now < dismissedPatternSuggestionUntil) return;
            runOnUiThread(() -> showRecurringDestinationSuggestion(suggestion, key));
        }).start();
    }

    private void showRecurringDestinationSuggestion(RoutePatternAnalyzer.Suggestion suggestion, String key) {
        if (activeRoute != null || patternSuggestionDialogShowing || isFinishing()) return;
        patternSuggestionDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("مسیر تکراری")
                .setMessage("احتمالاً الان به «" + suggestion.place.name + "» می‌روید.\n" + suggestion.reason)
                .setPositiveButton("شروع مسیریابی", (dialog, which) -> {
                    patternSuggestionDialogShowing = false;
                    startNavigation(suggestion.place);
                })
                .setNegativeButton("نه، الان نه", (dialog, which) -> {
                    patternSuggestionDialogShowing = false;
                    dismissedPatternSuggestionKey = key;
                    dismissedPatternSuggestionUntil = System.currentTimeMillis() + 2 * 60 * 60_000L;
                })
                .setOnCancelListener(dialog -> patternSuggestionDialogShowing = false)
                .setCancelable(true)
                .show();
    }

    /** Keeps the dashboard's trip card current on every GPS sample instead of only right after
     *  the route is chosen, so ETA, remaining distance, elapsed time and speed never go stale
     *  while a trip is active. */
    private void updateTripStats(Location location) {
        if (tripStatsPanel == null) return;
        if (!navigationEngine.isNavigating() || activeRoute == null || activeDestination == null) {
            tripStatsPanel.setVisibility(View.GONE);
            return;
        }
        tripStatsPanel.setVisibility(View.VISIBLE);
        RouteStep step = navigationEngine.currentStep();
        int remainingMeters;
        if (step != null) {
            Location target = new Location("route");
            target.setLatitude(step.latitude);
            target.setLongitude(step.longitude);
            remainingMeters = Math.round(location.distanceTo(target));
            int index = navigationEngine.currentStepIndex();
            for (int i = index + 1; i < activeRoute.steps.size(); i++) remainingMeters += activeRoute.steps.get(i).distanceMeters;
        } else {
            remainingMeters = activeRoute.distanceMeters;
        }
        int totalMeters = Math.max(1, activeRoute.distanceMeters);
        double fraction = Math.max(0.02, Math.min(1.0, remainingMeters / (double) totalMeters));
        int remainingSeconds = (int) Math.round(activeRoute.durationSeconds * fraction);
        long arrivalAt = System.currentTimeMillis() + remainingSeconds * 1000L;
        int elapsedMinutes = tripStartedAt == 0L ? 0 : Math.max(0, (int) ((System.currentTimeMillis() - tripStartedAt) / 60_000L));
        float speedKmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        tripEtaText.setText("رسیدن ساعت " + tripEtaFormat.format(new java.util.Date(arrivalAt))
                + " • " + formatTripDistance(remainingMeters) + " مانده");
        tripRemainingText.setText(Math.max(1, Math.round(remainingSeconds / 60f)) + " دقیقه مانده");
        tripElapsedText.setText(elapsedMinutes + " دقیقه طی شده");
        tripSpeedText.setText(Math.round(speedKmh) + " کیلومتر/ساعت");
    }

    private String formatTripDistance(int meters) {
        if (meters < 1000) return Math.max(0, meters) + " متر";
        return String.format(Locale.US, "%.1f کیلومتر", meters / 1000.0);
    }

    /** Adds only plausible GPS movement samples, avoiding jumps caused by a weak location fix. */
    private void recordTripLocation(Location location) {
        if (location == null || !navigationEngine.isNavigating() || tripStartedAt == 0L) return;
        if (location.hasAccuracy() && location.getAccuracy() > 45f) return;
        if (lastTripLocation == null) {
            lastTripLocation = new Location(location);
            return;
        }
        float delta = lastTripLocation.distanceTo(location);
        boolean hasMovingSpeed = location.hasSpeed() && location.getSpeed() >= 0.8f;
        boolean movementIsPlausible = (hasMovingSpeed && delta >= 3f) || (!location.hasSpeed() && delta >= 12f);
        if (movementIsPlausible && delta <= 1_500f) activeTripDistanceMeters += Math.round(delta);
        lastTripLocation = new Location(location);
    }

    private TripRecord buildTripRecord(SavedPlace destination, boolean completed) {
        if (destination == null || activeRoute == null || tripStartedAt == 0L) return null;
        if (activeTripDistanceMeters < MIN_RECORDED_TRIP_DISTANCE_METERS) return null;
        long endedAt = System.currentTimeMillis();
        return new TripRecord(destination.name, activeTripOriginLatitude, activeTripOriginLongitude,
                destination.latitude, destination.longitude, activeRoute.distanceMeters, activeRoute.durationSeconds,
                tripStartedAt, endedAt, activeTripDistanceMeters, activeRoute.providerName,
                activeWaypoints.size(), completed);
    }

    private void saveTripRecord(TripRecord record) {
        if (record == null) return;
        tripStore.add(record);
        writeAutomaticBackup();
    }

    private String formatTripDuration(int seconds) {
        int minutes = Math.max(0, seconds / 60);
        int hours = minutes / 60;
        return hours > 0 ? hours + " ساعت و " + (minutes % 60) + " دقیقه" : Math.max(1, minutes) + " دقیقه";
    }

    private String tripDistanceLabel(TripRecord record) {
        if (record.traveledDistanceMeters > 0) return formatTripDistance(record.traveledDistanceMeters) + " طی‌شده";
        return formatTripDistance(record.distanceMeters) + " برآورد مسیر";
    }

    private String tripDateLabel(long time) {
        return new SimpleDateFormat("EEEE، yyyy/MM/dd - HH:mm", new Locale("fa", "IR"))
                .format(new java.util.Date(time));
    }

    private String tripCoordinateLabel(double latitude, double longitude) {
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }

    private void showTripCompletionReport(TripRecord record) {
        if (record == null || isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(record.completed ? "گزارش پایان سفر" : "گزارش مسیریابی متوقف‌شده")
                .setMessage(tripDetailText(record))
                .setPositiveButton("بستن", null)
                .setNeutralButton("تاریخچه سفرها", (dialog, which) -> showTripHistory())
                .show();
    }

    private void showTripHistory() {
        dashboardPage.setVisibility(View.GONE);
        savedPlacesPage.setVisibility(View.GONE);
        profilePage.setVisibility(View.GONE);
        tripHistoryPage.setVisibility(View.VISIBLE);
        ((Button) findViewById(R.id.tabDashboardButton)).setAlpha(0.62f);
        ((Button) findViewById(R.id.tabSavedButton)).setAlpha(0.62f);
        ((Button) findViewById(R.id.tabProfileButton)).setAlpha(1f);
        ((Button) findViewById(R.id.tabMapButton)).setAlpha(0.62f);
        renderTripHistory();
    }

    private void renderTripHistory() {
        tripHistoryContent.removeAllViews();
        List<TripRecord> records = tripStore.recent(60);
        if (records.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("هنوز سفری با حرکت واقعی ثبت نشده است.");
            empty.setTextColor(getColor(R.color.drivemate_muted));
            empty.setTextSize(15f);
            empty.setPadding(0, dp(16), 0, dp(8));
            tripHistoryContent.addView(empty);
            return;
        }
        for (TripRecord record : records) {
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(8));
            card.setCardElevation(dp(2));
            card.setUseCompatPadding(true);
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> showTripDetail(record, true));

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(16), dp(14), dp(16), dp(14));
            TextView destination = new TextView(this);
            destination.setText(record.destinationName);
            destination.setTextColor(getColor(R.color.drivemate_text));
            destination.setTextSize(17f);
            destination.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            TextView metadata = new TextView(this);
            int elapsed = record.endedAt > record.startedAt
                    ? (int) ((record.endedAt - record.startedAt) / 1000L) : record.durationSeconds;
            metadata.setText(tripDateLabel(record.startedAt) + "\n" + tripDistanceLabel(record)
                    + " | " + formatTripDuration(elapsed));
            metadata.setTextColor(getColor(R.color.drivemate_muted));
            metadata.setTextSize(14f);
            metadata.setPadding(0, dp(6), 0, 0);
            body.addView(destination);
            body.addView(metadata);
            card.addView(body);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, dp(10));
            tripHistoryContent.addView(card, cardParams);
        }
    }

    private void showTripDetail(TripRecord record, boolean allowDelete) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("جزئیات سفر")
                .setMessage(tripDetailText(record)).setPositiveButton("بستن", null);
        if (allowDelete) {
            builder.setNegativeButton("حذف", (dialog, which) -> {
                tripStore.remove(record.startedAt);
                writeAutomaticBackup();
                renderTripHistory();
            });
        }
        builder.show();
    }

    private String tripDetailText(TripRecord record) {
        int elapsed = record.endedAt > record.startedAt
                ? (int) ((record.endedAt - record.startedAt) / 1000L) : record.durationSeconds;
        String status = record.endedAt == 0L ? "ثبت قدیمی (برنامه‌ریزی‌شده)"
                : record.completed ? "رسیدن به مقصد" : "مسیریابی متوقف شد";
        return "مسیر: موقعیت شروع GPS ← " + record.destinationName
                + "\nمبدا: " + tripCoordinateLabel(record.originLatitude, record.originLongitude)
                + "\nمقصد: " + tripCoordinateLabel(record.destinationLatitude, record.destinationLongitude)
                + "\nتاریخ شروع: " + tripDateLabel(record.startedAt)
                + (record.endedAt > 0 ? "\nپایان: " + tripDateLabel(record.endedAt) : "")
                + "\nوضعیت: " + status
                + "\nمسافت: " + tripDistanceLabel(record)
                + "\nمدت: " + formatTripDuration(elapsed)
                + "\nبرآورد اولیه: " + formatTripDistance(record.distanceMeters) + " و "
                + formatTripDuration(record.durationSeconds)
                + (record.waypointCount > 0 ? "\nتوقف میانی: " + record.waypointCount : "");
    }

    private void confirmClearTrips() {
        new AlertDialog.Builder(this).setTitle("پاک کردن گزارش سفرها")
                .setMessage("همه گزارش‌های سفر از گوشی و نسخه پشتیبان بعدی حذف می‌شوند.")
                .setPositiveButton("پاک کردن", (dialog, which) -> {
                    tripStore.clear();
                    writeAutomaticBackup();
                    setStatus("گزارش سفرها پاک شد.");
                    renderTripHistory();
                })
                .setNegativeButton("انصراف", null).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void exportTripReports() {
        if (tripStore.recent(1).isEmpty()) {
            setStatus("گزارشی برای ذخیره وجود ندارد.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "drivemate-trip-reports.csv");
        startActivityForResult(intent, REQ_EXPORT_TRIP_REPORTS);
    }

    private void writeTripReportCsv(Uri uri) throws Exception {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("destination,origin_latitude,origin_longitude,destination_latitude,destination_longitude,start,end,status,distance_meters,planned_distance_meters,duration_seconds,waypoints\n");
        for (TripRecord record : tripStore.recent(60)) {
            int duration = record.endedAt > record.startedAt
                    ? (int) ((record.endedAt - record.startedAt) / 1000L) : record.durationSeconds;
            int distance = record.traveledDistanceMeters > 0 ? record.traveledDistanceMeters : record.distanceMeters;
            csv.append(csvValue(record.destinationName)).append(',')
                    .append(record.originLatitude).append(',').append(record.originLongitude).append(',')
                    .append(record.destinationLatitude).append(',').append(record.destinationLongitude).append(',')
                    .append(csvValue(tripDateLabel(record.startedAt))).append(',')
                    .append(csvValue(record.endedAt > 0 ? tripDateLabel(record.endedAt) : "")).append(',')
                    .append(csvValue(record.completed ? "completed" : "stopped_or_legacy")).append(',')
                    .append(distance).append(',').append(record.distanceMeters).append(',').append(duration).append(',')
                    .append(record.waypointCount).append('\n');
        }
        try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Cannot open report destination");
            output.write(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private String csvValue(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private void openHomeOrWork(String kind, String defaultName) {
        SavedPlace place = placeStore.findByKind(kind);
        if (place != null) { startNavigation(place); return; }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(defaultName);
        input.setSelectAllOnFocus(false);
        new AlertDialog.Builder(this)
                .setTitle("ذخیره " + defaultName)
                .setMessage("نام دلخواه را وارد کنید؛ موقعیت فعلی GPS ذخیره می‌شود.")
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> saveCurrentPlace(input.getText().toString(), kind))
                .setNegativeButton("انصراف", null)
                .show();
    }

    /** Shows only provider-confirmed route facts; it never invents traffic or alternative routes. */
    private void showRouteAnalysisLoading(SavedPlace destination) {
        if (analysisPanel == null) return;
        analysisTitleText.setText("DriveMate AI | تحلیل مسیر");
        analysisBodyText.setText("در حال تحلیل مسیر تا " + destination.name + "...\n"
                + "بررسی زمان رسیدن\n"
                + "آماده‌سازی راهنمای صوتی\n"
                + "پایش دوره‌ای ترافیک و مسیر جایگزین پس از شروع سفر");
        analysisPanel.setVisibility(View.VISIBLE);
    }

    private void showTripAnalysis(RouteResult route, SavedPlace destination) {
        if (analysisPanel == null) return;
        int minutes = Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0));
        double kilometers = route.distanceMeters / 1000.0;
        analysisTitleText.setText("DriveMate AI | تحلیل مسیر");
        analysisBodyText.setText("بررسی مسیر انجام شد\n"
                + "زمان تقریبی: " + minutes + " دقیقه\n"
                + "مسافت: " + String.format(Locale.US, "%.1f", kilometers) + " کیلومتر\n"
                + "مسیر پیشنهادی از " + route.providerName + " آماده است. پایش دوره‌ای ترافیک فعال است.");
        analysisPanel.setVisibility(View.VISIBLE);
        voiceHandler.removeCallbacks(tripAnalysisHide);
        voiceHandler.postDelayed(tripAnalysisHide, 2_800L);
    }

    private void hideTripAnalysis() {
        if (analysisPanel != null) analysisPanel.setVisibility(View.GONE);
    }

    private void announceTripStart(RouteResult route, SavedPlace destination) {
        int minutes = Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0));
        String firstInstruction = firstRouteInstruction(route);
        String fallback = "مسیر " + destination.name + " آماده است. حدود " + minutes + " دقیقه زمان دارد. "
                + (firstInstruction.isEmpty() ? "با احتیاط حرکت کنید." : firstInstruction);
        String prompt = "شروع سفر به " + destination.name + " است. زمان تقریبی " + minutes
                + " دقیقه است. " + (firstInstruction.isEmpty() ? "" : "نخستین راهنما: " + firstInstruction + ". ")
                + "در یک یا دو جمله فارسی بسیار کوتاه و طبیعی بگو: خودت را همراه راننده معرفی کن، وضعیت مسیر را پایش می‌کنی، "
                + "و در صورت وجود، نخستین راهنما را دقیق بگو. از ادعای ترافیک یا مسیر جایگزین بدون داده خودداری کن.";
        if (isFullIntelligenceMode()) {
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING, prompt, null, fallback, 20_000L);
        } else {
            boolean spoken = voicePlayer.speak(fallback);
            setStatus(spoken ? "خلاصه مسیر با راهنمای محلی پخش شد."
                    : "خلاصه مسیر آماده شد، ولی صدای راهنما در دسترس نیست (TTS دستگاه فعال نیست).");
        }
    }

    private String firstRouteInstruction(RouteResult route) {
        if (route.steps == null || route.steps.isEmpty()) return "";
        String instruction = route.steps.get(0).instruction == null ? "" : route.steps.get(0).instruction.trim();
        if (instruction.length() > 110) instruction = instruction.substring(0, 110);
        return instruction;
    }

    private void finishTrip(SavedPlace destination) {
        if (activeDestination == null) return;
        TripRecord tripReport = buildTripRecord(destination, true);
        saveTripRecord(tripReport);
        int minutes = tripStartedAt == 0L ? 0 : Math.max(1, (int) ((System.currentTimeMillis() - tripStartedAt) / 60_000L));
        int reportDistance = activeTripDistanceMeters > 0 ? activeTripDistanceMeters
                : activeRoute == null ? 0 : activeRoute.distanceMeters;
        double kilometers = reportDistance / 1000.0;
        String fallback = "به مقصد رسیدید. سفر حدود " + minutes + " دقیقه و "
                + String.format(Locale.US, "%.1f", kilometers) + " کیلومتر بود.";
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "سفر به " + destination.name + " پایان یافته است. مدت سفر " + minutes + " دقیقه و مسافت "
                        + String.format(Locale.US, "%.1f", kilometers)
                        + " کیلومتر است. یک پیام فارسی کوتاه، طبیعی و دوستانه برای پایان سفر بگو.",
                "destination_arrived", fallback, 15_000L);
        setStatus("به " + destination.name + " رسیدید.");
        activeDestination = null;
        activeRoute = null;
        activeWaypoints = new ArrayList<>();
        ++hazardFetchRequestId;
        ++speedLimitFetchRequestId;
        ++safetyAlertFetchRequestId;
        ++weatherCheckRequestId;
        ++trafficIncidentFetchRequestId;
        activeRouteHazards = new ArrayList<>();
        activeRouteHazardAnnounced = new boolean[0];
        activeRouteSpeedLimits = new ArrayList<>();
        activeSpeedZones = new ArrayList<>();
        activeSpeedZoneAnnounced = new boolean[0];
        activeRouteCumulativeDistances = new double[0];
        activeRouteSafetyAlerts = new ArrayList<>();
        activeRouteSafetyAlertAnnounced = new boolean[0];
        activeRouteTrafficIncidents = new ArrayList<>();
        announcedTrafficIncidentIds.clear();
        tripStartedAt = 0L;
        activeTripDistanceMeters = 0;
        activeTripOriginLatitude = Double.NaN;
        activeTripOriginLongitude = Double.NaN;
        lastTripLocation = null;
        initialGuidanceHeldUntil = 0L;
        smartCompanion.stop();
        voiceHandler.removeCallbacks(trafficCheck);
        voiceHandler.removeCallbacks(weatherCheck);
        voiceHandler.removeCallbacks(trafficIncidentCheck);
        stopBackgroundNavigation();
        hideTripAnalysis();
        if (tripStatsPanel != null) tripStatsPanel.setVisibility(View.GONE);
        showTripCompletionReport(tripReport);
    }

    private void searchAndNavigate(String term) {
        Location location = locationTracker.getLastLocation();
        if (location == null) { setStatus("برای پیدا کردن مقصد، GPS باید آماده باشد."); return; }
        if (term.isEmpty()) { speakShort("نام مقصد را دوباره بگویید."); return; }
        setStatus("در حال پیدا کردن " + term + "...");
        placeSearchRepository.search(term, location.getLatitude(), location.getLongitude(),
                place -> runOnUiThread(() -> startNavigation(place)),
                error -> runOnUiThread(() -> { setStatus(error); speakShort("مقصد پیدا نشد. نام آن را دوباره بگویید."); }));
    }

    private void loadRuntimeKeys() {
        new Thread(() -> {
            runtimeKeys = RuntimeKeys.fetch(new String[]{
                    "https://abrehamrahi.ir/o/public/eUFcsXOX",
                    "https://gist.githubusercontent.com/ghadirb/626a804df3009e49045a2948dad89fe5/raw/c93c06d1b2f38c65ee30f092c134a89998326d12/keys.txt"
            }, BuildConfig.KEYS_DECRYPTION_SECRET);
            aiAssistant.setRuntimeKeys(runtimeKeys);
            onlineSpeechClient.setRuntimeKeys(runtimeKeys);
            neshanRoutingProvider.setApiKey(runtimeKeys.get("NESHAN_API_KEY"));
            mapIrRoutingProvider.setApiKey(runtimeKeys.get("MAPIR_API_KEY"));
            StringBuilder found = new StringBuilder();
            for (String name : new String[]{"GAPGPT_API_KEY", "LIARA_API_KEY", "AI_API_KEY", "NESHAN_API_KEY", "MAPIR_API_KEY"}) {
                if (runtimeKeys.has(name)) found.append(name).append(' ');
            }
            android.util.Log.d("DriveMateKeys", found.length() == 0
                    ? "no runtime keys were parsed from either URL — online AI/TTS will always fall back to offline text"
                    : "runtime keys parsed: " + found);
            runOnUiThread(() -> {
                runtimeKeysLoading = false;
                boolean onlineReady = onlineSpeechClient.canUseOnlineSpeech();
                refreshAiStatus();
                setStatus(onlineReady ? "سرویس‌های آنلاین آماده شدند." : "سرویس آنلاین در دسترس نیست؛ تشخیص گفتار گوشی فعال است.");
                if (voiceRequestedWhileKeysLoad) {
                    voiceRequestedWhileKeysLoad = false;
                    toggleVoiceInput();
                }
            });
        }).start();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedIntent(intent);
        if (ACTION_VOICE_FROM_NOTIFICATION.equals(intent.getAction())) voiceHandler.postDelayed(this::toggleVoiceInput, 350L);
    }

    private void handleSharedIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction()) || intent.getType() == null || !intent.getType().startsWith("text/")) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) return;
        setStatus("در حال خواندن مکان اشتراک‌گذاری‌شده...");
        SharedLocationParser.resolve(this, text, new SharedLocationParser.Callback() {
            @Override public void onResolved(SavedPlace place) { runOnUiThread(() -> promptSaveSharedPlace(place)); }
            @Override public void onFailure() { runOnUiThread(() -> setStatus("مختصات این مکان پیدا نشد. لینک کامل مکان را اشتراک‌گذاری کنید.")); }
        });
    }

    private void promptSaveSharedPlace(SavedPlace place) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(place.name);
        new AlertDialog.Builder(this)
                .setTitle("ذخیره مکان اشتراک‌گذاری‌شده")
                .setMessage(place.address)
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    placeStore.upsert(new SavedPlace(name.isEmpty() ? "مکان اشتراک‌گذاری‌شده" : name, place.kind, place.latitude, place.longitude, place.address, System.currentTimeMillis(), true));
                    writeAutomaticBackup();
                    voicePlayer.announce("place_saved", "مکان ذخیره شد.");
                    setStatus("مکان ذخیره شد.");
                    refreshList();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void promptEnableLocationIfNeeded() {
        if (!locationTracker.isLocationEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("فعال‌سازی مکان گوشی")
                    .setMessage("برای ذخیره و مسیریابی، مکان/GPS گوشی را روشن کنید.")
                    .setPositiveButton("باز کردن تنظیمات", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("بعداً", null)
                    .show();
        }
    }

    private void askAi(String question) {
        requestIntelligence(DrivingIntelligenceCoordinator.Priority.CONVERSATION, question,
                "پاسخ آنلاین در دسترس نیست.", true, 45_000L);
    }

    private void requestIntelligence(DrivingIntelligenceCoordinator.Priority priority, String prompt, String fallback,
                                     boolean onlineInEconomy, long expiresInMs) {
        setStatus("در حال آماده کردن پاسخ صوتی...");
        intelligenceCoordinator.request(priority, prompt, drivingContext(), fallback, onlineInEconomy, expiresInMs,
                (id, text, online) -> runOnUiThread(() -> {
                    onlineSpeechClient.stopPlayback();
                    voicePlayer.interrupt();
                    if (online) {
                        speakShort(text);
                    } else {
                        boolean spoken = voicePlayer.speak(text);
                        setStatus(spoken ? "پاسخ آفلاین پخش شد."
                                : "پاسخ آفلاین آماده شد، ولی صدای محلی در دسترس نیست (TTS دستگاه فعال نیست).");
                    }
                }));
    }

    private boolean isFullIntelligenceMode() {
        return readIntelligenceMode() == DrivingIntelligenceCoordinator.Mode.FULL;
    }

    /** Uses the local clip immediately in economy mode; full mode gives online AI/TTS first refusal. */
    private void speakDrivingEvent(DrivingIntelligenceCoordinator.Priority priority, String prompt, String clipName,
                                   String fallback, long expiresInMs) {
        // The route engine immediately speaks the first real maneuver; avoid a second generic
        // "start moving" prompt that would delay the actionable instruction.
        if ("start_navigation".equals(clipName) && navigationEngine.hasActionableCurrentInstruction()) return;
        // Always clear whatever is currently playing - local WAV/TTS or an online clip - before
        // starting a new announcement. Individual playback paths already did this pairwise
        // (speakShort, playDrivingFallback), but the economy-mode direct path and the full-mode
        // online-TTS-fallback path each only stopped their own kind, not the other's. Switching
        // intelligence mode mid-trip could then leave a leftover clip from the old mode overlapping
        // a fresh one from the new mode, playing simultaneously.
        voicePlayer.interrupt();
        onlineSpeechClient.stopPlayback();
        if (isFullIntelligenceMode()) {
            setStatus("\u062f\u0631 \u062d\u0627\u0644 \u0622\u0645\u0627\u062f\u0647 \u06a9\u0631\u062f\u0646 \u067e\u0627\u0633\u062e \u0635\u0648\u062a\u06cc \u0647\u0648\u0634\u0645\u0646\u062f...");
            final AtomicBoolean delivered = new AtomicBoolean(false);
            final long watchdogDelay = priority == DrivingIntelligenceCoordinator.Priority.SAFETY ? 2_000L : 3_750L;
            voiceHandler.postDelayed(() -> {
                if (!delivered.compareAndSet(false, true)) return;
                playOnlineTtsFallback(clipName, fallback);
            }, watchdogDelay);
            intelligenceCoordinator.request(priority, prompt, drivingContext(), fallback, false, expiresInMs,
                    (id, text, online) -> runOnUiThread(() -> {
                        if (!delivered.compareAndSet(false, true)) return;
                        if (online) {
                            speakShort(text, clipName, fallback);
                        } else {
                            playOnlineTtsFallback(clipName, fallback);
                        }
                    }));
        } else if (clipName != null) {
            voicePlayer.announce(clipName, fallback);
        } else {
            voicePlayer.speak(fallback);
        }
    }

    private boolean playDrivingFallback(String clipName, String fallback) {
        onlineSpeechClient.stopPlayback();
        voicePlayer.interrupt();
        return clipName != null ? voicePlayer.announce(clipName, fallback) : voicePlayer.speak(fallback);
    }

    /** Uses online TTS for a deterministic fallback sentence before resorting to a packaged WAV. */
    private void playOnlineTtsFallback(String clipName, String fallback) {
        final AtomicBoolean finished = new AtomicBoolean(false);
        Runnable localFallback = () -> {
            if (!finished.compareAndSet(false, true)) return;
            boolean played = playDrivingFallback(clipName, fallback);
            setStatus(!played ? "صدای آنلاین در دسترس نبود و صدای محلی هم فعال نیست (TTS دستگاه فعال نیست)."
                    : clipName == null
                    ? "صدای آنلاین در دسترس نبود؛ راهنمای محلی پخش شد."
                    : "صدای آنلاین در دسترس نبود؛ هشدار WAV پخش شد.");
        };
        setStatus("پاسخ به‌موقع نرسید؛ در حال دریافت صدای آنلاین...");
        voiceHandler.postDelayed(localFallback, 2500L);
        onlineSpeechClient.speak(fallback, new OnlineSpeechClient.SpeechCallback() {
            @Override public void onPlayed() { runOnUiThread(() -> {
                if (finished.compareAndSet(false, true)) setStatus("راهنمای مسیر با صدای آنلاین پخش شد.");
            }); }
            @Override public void onError() { runOnUiThread(localFallback); }
        });
    }

    private void playPreparedOrRequest(String key, DrivingIntelligenceCoordinator.Priority priority, String prompt,
                                       String fallback, boolean onlineInEconomy, long expiresInMs) {
        String prepared = intelligenceCoordinator.consumePrepared(key);
        if (prepared != null) speakShort(prepared);
        else requestIntelligence(priority, prompt, fallback, onlineInEconomy, expiresInMs);
    }

    private void handleSmartEvent(String event, String facts) {
        if (!navigationEngine.isNavigating()) return;
        if ("speed".equals(event)) {
            requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "رویداد ایمنی GPS: " + facts + " یک هشدار فارسی بسیار کوتاه و آرام برای کاهش سرعت بگو.",
                    "لطفاً سرعت خود را کم کنید.", false, 12_000L);
            return;
        }
        if ("slow".equals(event)) {
            requestIntelligence(DrivingIntelligenceCoordinator.Priority.DRIVING,
                    "رویداد رانندگی: " + facts + " یک هشدار کوتاه و بدون ادعای ترافیک زنده بگو.",
                    "حرکت مسیر کند است؛ با احتیاط ادامه دهید.", false, 20_000L);
            return;
        }
        if ("rest_prepare".equals(event)) {
            intelligenceCoordinator.prefetch("rest-reminder", DrivingIntelligenceCoordinator.Priority.DRIVING,
                    "حدود دو ساعت رانندگی پیوسته نزدیک است. یک یادآوری فارسی کوتاه، آرام و عملی برای استراحت در محل امن بگو.",
                    drivingContext(), 25 * 60_000L);
            return;
        }
        if ("rest".equals(event)) {
            playPreparedOrRequest("rest-reminder", DrivingIntelligenceCoordinator.Priority.DRIVING,
                    "یادآوری ایمنی: بیش از دو ساعت رانندگی پیوسته بدون توقف ده دقیقه‌ای ثبت شده است. یک هشدار فارسی کوتاه و عملی برای استراحت بگو.",
                    "حدود دو ساعت رانندگی کرده‌اید؛ در اولین محل امن کمی استراحت کنید.", false, 25_000L);
            return;
        }
        if ("fatigue".equals(event)) {
            requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "هشدار ایمنی غیرپزشکی: " + facts + " در یک جمله کوتاه و آرام پیشنهاد توقف در محل امن بده؛ ادعای تشخیص پزشکی نکن.",
                    "رانندگی پیوسته طولانی شده است؛ در اولین محل امن توقف و استراحت کنید.", false, 25_000L);
            return;
        }
        if ("fuel_low_guess".equals(event)) {
            suggestFuelStop();
            return;
        }
        switch (event) {
            case "traffic_reroute":
                rerouteForTraffic();
                break;
            case "fuel_check":
                voicePlayer.speak("حدود نود دقیقه از شروع سفر گذشته است. اگر نیاز به سوخت‌گیری دارید، بگویید پمپ بنزین.");
                break;
            case "fatigue_offline":
                voicePlayer.speak("بیش از دو ساعت است در حال رانندگی هستید. بهتر است در اولین فرصت استراحت کنید. برای پیدا کردن نزدیک‌ترین استراحتگاه بگویید «استراحتگاه».");
                break;
            default:
                break;
        }
    }

    private void rerouteForTraffic() {
        if (activeDestination == null) return;
        setStatus("ترافیک پایدار؛ در حال بررسی مسیر جایگزین...");
        startNavigation(activeDestination, activeWaypoints, true);
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "حرکت مسیر برای مدتی کند بوده است و مسیر جایگزین در حال بررسی است. یک پیام کوتاه و طبیعی بگو.",
                "alternative_route", "در حال بررسی مسیر جایگزین هستم.", 15_000L);
    }

    private void findNearbyForCompanion(String term, String facts) {
        Location location = locationTracker.getLastLocation();
        if (location == null) { voicePlayer.speak("مکان نزدیک بدون GPS قابل پیدا کردن نیست."); return; }
        placeSearchRepository.search(term, location.getLatitude(), location.getLongitude(), place -> runOnUiThread(() -> {
            Location found = new Location("poi");
            found.setLatitude(place.latitude);
            found.setLongitude(place.longitude);
            int meters = Math.round(location.distanceTo(found));
            voicePlayer.speak("نزدیک‌ترین " + term + ": " + place.name + "، حدود " + meters + " متر فاصله.");
        }), error -> runOnUiThread(() -> voicePlayer.speak("مکان نزدیک تأیید نشد.")));
    }

    /** Entry point for every "نزدیک‌ترین X کجاست؟" style voice command (FIND_PLACE). Looks up the
     *  nearest match and speaks a distance-based suggestion; the driver confirms with "بله"/"باشه"
     *  (CONFIRM_SUGGESTION) to actually start navigation, matching the requested dialogue style. */
    private void suggestNearbyPlace(PoiCategory category) {
        if (category == null) return;
        Location location = locationTracker.getLastLocation();
        if (location == null) { setStatus("برای پیدا کردن " + category.label + "، GPS باید آماده باشد."); return; }
        setStatus("در حال پیدا کردن " + category.label + " در اطراف...");
        boolean nightPriority = isLateNight()
                && (category == PoiCategory.HOSPITAL || category == PoiCategory.CLINIC || category == PoiCategory.PHARMACY);
        // Real opening hours aren't available from the search provider; biasing the query text
        // toward "شبانه روزی" (24-hour) listings is an honest approximation, not verified live data.
        String term = nightPriority ? category.searchTerm + " شبانه روزی" : category.searchTerm;
        placeSearchRepository.searchAll(term, location.getLatitude(), location.getLongitude(),
                places -> runOnUiThread(() -> announceNearbySuggestion(category, places, location, nightPriority)),
                error -> runOnUiThread(() -> { setStatus(error); voicePlayer.speak(category.label + " در اطراف پیدا نشد."); }));
    }

    private void announceNearbySuggestion(PoiCategory category, List<SavedPlace> places, Location origin, boolean nightPriority) {
        if (places == null || places.isEmpty()) {
            voicePlayer.speak(category.label + " نزدیکی پیدا نشد.");
            return;
        }
        List<SavedPlace> sorted = new ArrayList<>(places);
        sorted.sort(Comparator.comparingDouble(place ->
                distanceKm(origin.getLatitude(), origin.getLongitude(), place.latitude, place.longitude)));
        SavedPlace nearest = sorted.get(0);
        double km = distanceKm(origin.getLatitude(), origin.getLongitude(), nearest.latitude, nearest.longitude);
        int etaMinutes = Math.max(1, (int) Math.round(km / 40.0 * 60.0));
        pendingSuggestionPlace = nearest;
        pendingSuggestionCategory = category;
        String distancePhrase = km < 1d ? Math.round(km * 1000) + " متر" : String.format(Locale.US, "%.1f کیلومتر", km);
        String message;
        if (category == PoiCategory.RESTAURANT || category == PoiCategory.COFFEE_SHOP) {
            message = sorted.size() + " " + category.label + " در نزدیکی مسیر است. نزدیک‌ترین حدود "
                    + etaMinutes + " دقیقه دیگر است. مسیر عوض شود؟";
        } else {
            message = "نزدیک‌ترین " + category.label + " " + distancePhrase + " فاصله دارد. مسیر عوض شود؟";
        }
        if (nightPriority) message += " (بر اساس عنوان شبانه‌روزی ثبت‌شده؛ ساعت کاری واقعی تأیید نشده است.)";
        setStatus(message);
        voicePlayer.speak(message);
    }

    private void confirmPendingSuggestion() {
        if (pendingSuggestionPlace == null) { voicePlayer.speak("در حال حاضر پیشنهادی برای تأیید وجود ندارد."); return; }
        SavedPlace place = pendingSuggestionPlace;
        pendingSuggestionPlace = null;
        pendingSuggestionCategory = null;
        startNavigation(place);
    }

    private void declinePendingSuggestion() {
        if (pendingSuggestionPlace == null) return;
        pendingSuggestionPlace = null;
        pendingSuggestionCategory = null;
        voicePlayer.speak("باشه، مسیر تغییر نمی‌کند.");
    }

    private boolean isLateNight() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 23 || hour < 6;
    }

    private double distanceKm(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double value = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371d * 2d * Math.atan2(Math.sqrt(value), Math.sqrt(1d - value));
    }

    /** Picks the nearest place for a proactive (non-voice-triggered) suggestion, arms the pending
     *  confirmation just like a voice-triggered one, and returns a Persian clause to fold into the
     *  existing "rest"/"fatigue"/"fuel_low_guess" spoken messages. Empty string if none found. */
    private String nearestPlaceClause(List<SavedPlace> places, Location origin, PoiCategory category) {
        if (places == null || places.isEmpty()) return "";
        List<SavedPlace> sorted = new ArrayList<>(places);
        sorted.sort(Comparator.comparingDouble(place ->
                distanceKm(origin.getLatitude(), origin.getLongitude(), place.latitude, place.longitude)));
        SavedPlace nearest = sorted.get(0);
        double km = distanceKm(origin.getLatitude(), origin.getLongitude(), nearest.latitude, nearest.longitude);
        pendingSuggestionPlace = nearest;
        pendingSuggestionCategory = category;
        String distancePhrase = km < 1d ? Math.round(km * 1000) + " متر" : String.format(Locale.US, "%.1f کیلومتر", km);
        return " نزدیک‌ترین " + category.label + " (" + nearest.name + ") " + distancePhrase
                + " فاصله دارد؛ برای مسیریابی به آنجا بگویید بله.";
    }

    /** Proactive version of the "rest" smart-event: same 2-hour reminder as before, now with the
     *  nearest restaurant/rest option looked up and offered for confirmation. */
    private void suggestRestStop() {
        Location location = locationTracker.getLastLocation();
        String baseFallback = "حدود دو ساعت رانندگی کرده‌اید؛ در اولین محل امن کمی استراحت کنید.";
        if (location == null) {
            playPreparedOrRequest("rest-reminder", DrivingIntelligenceCoordinator.Priority.DRIVING,
                    "یادآوری ایمنی: بیش از دو ساعت رانندگی پیوسته بدون توقف ده دقیقه‌ای ثبت شده است. یک هشدار فارسی کوتاه و عملی برای استراحت بگو.",
                    baseFallback, true, 25_000L);
            return;
        }
        placeSearchRepository.searchAll(PoiCategory.RESTAURANT.searchTerm, location.getLatitude(), location.getLongitude(),
                places -> runOnUiThread(() -> {
                    String clause = nearestPlaceClause(places, location, PoiCategory.RESTAURANT);
                    requestIntelligence(DrivingIntelligenceCoordinator.Priority.DRIVING,
                            "یادآوری ایمنی: بیش از دو ساعت رانندگی پیوسته ثبت شده است." + clause
                                    + " یک هشدار فارسی کوتاه و عملی برای استراحت بگو و همین مکان پیشنهادی را هم در جمله بیاور.",
                            baseFallback + clause, true, 25_000L);
                }),
                error -> runOnUiThread(() -> playPreparedOrRequest("rest-reminder", DrivingIntelligenceCoordinator.Priority.DRIVING,
                        "یادآوری ایمنی: بیش از دو ساعت رانندگی پیوسته ثبت شده. یک هشدار کوتاه بگو.", baseFallback, true, 25_000L)));
    }

    /** Proactive version of the "fatigue" smart-event: same 3-hour safety warning as before, now
     *  paired with the nearest coffee shop for a concrete place to pull over. */
    private void suggestFatigueBreak() {
        Location location = locationTracker.getLastLocation();
        String baseFallback = "رانندگی پیوسته طولانی شده است؛ در اولین محل امن توقف و استراحت کنید.";
        if (location == null) {
            requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "هشدار ایمنی غیرپزشکی: بیش از سه ساعت رانندگی پیوسته ثبت شده است. در یک جمله کوتاه و آرام پیشنهاد توقف در محل امن بده؛ ادعای تشخیص پزشکی نکن.",
                    baseFallback, true, 25_000L);
            return;
        }
        placeSearchRepository.searchAll(PoiCategory.COFFEE_SHOP.searchTerm, location.getLatitude(), location.getLongitude(),
                places -> runOnUiThread(() -> {
                    String clause = nearestPlaceClause(places, location, PoiCategory.COFFEE_SHOP);
                    requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                            "هشدار ایمنی غیرپزشکی: بیش از سه ساعت رانندگی پیوسته ثبت شده است." + clause
                                    + " در یک جمله کوتاه و آرام پیشنهاد توقف بده و همین مکان را بگو؛ ادعای تشخیص پزشکی نکن.",
                            baseFallback + clause, true, 25_000L);
                }),
                error -> runOnUiThread(() -> requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                        "هشدار ایمنی غیرپزشکی: بیش از سه ساعت رانندگی پیوسته ثبت شده است. در یک جمله کوتاه پیشنهاد توقف بده.",
                        baseFallback, true, 25_000L)));
    }

    /** Handles the SmartDriveCompanion "fuel_low_guess" event: an approximate distance-based
     *  reminder (see SmartDriveCompanion's FUEL_GUESS_DISTANCE_METERS), paired with the nearest
     *  real gas station. The driver resets the counter by saying "بنزین زدم". */
    private void suggestFuelStop() {
        Location location = locationTracker.getLastLocation();
        String baseFallback = "مسافت قابل توجهی رانندگی کرده‌اید و ممکن است سوخت کم باشد. اگر سوخت‌گیری کرده‌اید بگویید «بنزین زدم».";
        if (location == null) { voicePlayer.speak(baseFallback); return; }
        placeSearchRepository.searchAll(PoiCategory.FUEL.searchTerm, location.getLatitude(), location.getLongitude(),
                places -> runOnUiThread(() -> {
                    String clause = nearestPlaceClause(places, location, PoiCategory.FUEL);
                    requestIntelligence(DrivingIntelligenceCoordinator.Priority.DRIVING,
                            "یادآوری تقریبی: مسافت زیادی از آخرین سوخت‌گیری تأییدشده رانندگی شده است؛ این تشخیص واقعی سطح سوخت نیست."
                                    + clause + " یک یادآوری فارسی کوتاه و آرام بگو.",
                            baseFallback + clause, true, 25_000L);
                }),
                error -> runOnUiThread(() -> voicePlayer.speak(baseFallback)));
    }

    private void speakShort(String answer) {
        speakShort(answer, null, null);
    }

    /** Plays a generated response, with a known navigation clip as the reliable final fallback. */
    private void speakShort(String answer, String fallbackClip, String fallbackText) {
        String shortAnswer = answer == null ? "" : answer.trim();
        if (shortAnswer.length() > 190) shortAnswer = shortAnswer.substring(0, 190);
        setStatus("در حال دریافت صدای آنلاین...");
        final String finalAnswer = shortAnswer;
        voicePlayer.interrupt();
        onlineSpeechClient.stopPlayback();
        onlineSpeechClient.speak(finalAnswer, new OnlineSpeechClient.SpeechCallback() {
            @Override public void onPlayed() { runOnUiThread(() -> setStatus("پاسخ هوشمند با صدای آنلاین پخش شد.")); }
            @Override public void onError() { runOnUiThread(() -> {
                if (fallbackClip != null) {
                    voicePlayer.announce(fallbackClip, fallbackText);
                    setStatus("\u0635\u062f\u0627\u06cc \u0622\u0646\u0644\u0627\u06cc\u0646 \u062f\u0631 \u062f\u0633\u062a\u0631\u0633 \u0646\u06cc\u0633\u062a\u061b \u0647\u0634\u062f\u0627\u0631 WAV \u067e\u062e\u0634 \u0634\u062f.");
                    return;
                }
                voicePlayer.speak(finalAnswer);
                setStatus("پاسخ صوتی با صدای گوشی پخش شد.");
            }); }
        });
    }

    private String cleanDestinationText(String text) {
        return text == null ? "" : text.replaceFirst("^(برو|به|مسیریابی)\\s+", "").trim();
    }

    /** Builds the full context sent to the AI model: current trip status, every saved place, a
     *  longer trip history and the driver's most frequently visited destinations. This lets the
     *  model act like a companion who genuinely knows the driver's routine, not just the active
     *  destination. Precise GPS coordinates are still never sent - only names and summaries the
     *  driver has already chosen to save or that come from destinations they navigated to. */
    private String drivingContext() {
        StringBuilder context = new StringBuilder();
        if (activeDestination != null) {
            context.append("مقصد فعلی: ").append(activeDestination.name).append(". ");
            if (activeRoute != null) {
                context.append("فاصله کل مسیر حدود ").append(Math.round(activeRoute.distanceMeters / 1000f))
                        .append(" کیلومتر و زمان تقریبی ").append(Math.max(1, activeRoute.durationSeconds / 60))
                        .append(" دقیقه است. ");
            }
        }
        ArrayList<SavedPlace> places = new ArrayList<>(placeStore.allPlaces());
        if (!places.isEmpty()) {
            context.append("همه مکان‌های ذخیره‌شده کاربر: ");
            for (int i = 0; i < places.size(); i++) context.append(places.get(i).name).append(i == places.size() - 1 ? ". " : "، ");
        }
        java.util.List<TripRecord> trips = tripStore.recent(60);
        if (!trips.isEmpty()) {
            int shown = Math.min(trips.size(), 15);
            context.append("تاریخچه سفرهای اخیر کاربر (از جدید به قدیم): ");
            for (int i = 0; i < shown; i++) context.append(trips.get(i).destinationName).append(i == shown - 1 ? ". " : "، ");
            String frequent = mostFrequentDestinations(trips, 3);
            if (!frequent.isEmpty()) context.append("مقصدهایی که کاربر بیشتر از همه به آن‌ها رفته: ").append(frequent).append(". ");
        }
        return context.toString();
    }

    /** Counts destination names across the trip history to surface the driver's routine places
     *  (e.g. work, gym, a relative's home) so the model can reason about habits, not just the
     *  single most recent trip. */
    private String mostFrequentDestinations(java.util.List<TripRecord> trips, int topN) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (TripRecord trip : trips) {
            String name = trip.destinationName == null ? "" : trip.destinationName.trim();
            if (name.isEmpty()) continue;
            counts.merge(name, 1, Integer::sum);
        }
        java.util.List<java.util.Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(topN, sorted.size()); i++) {
            if (sorted.get(i).getValue() < 2) break;
            if (builder.length() > 0) builder.append("، ");
            builder.append(sorted.get(i).getKey()).append(" (").append(sorted.get(i).getValue()).append(" بار)");
        }
        return builder.toString();
    }

    private DrivingIntelligenceCoordinator.Mode readIntelligenceMode() {
        String saved = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
                .getString(KEY_INTELLIGENCE_MODE, DrivingIntelligenceCoordinator.Mode.ECONOMY.name());
        try { return DrivingIntelligenceCoordinator.Mode.valueOf(saved); }
        catch (Exception ignored) { return DrivingIntelligenceCoordinator.Mode.ECONOMY; }
    }

    private void showSettingsMenu() {
        String mode = readIntelligenceMode() == DrivingIntelligenceCoordinator.Mode.FULL ? "هوشمند کامل" : "هوشمند اقتصادی";
        new AlertDialog.Builder(this).setTitle("تنظیمات")
                .setItems(new String[]{"تنظیمات صدا", "هوشمندی رانندگی: " + mode,
                        "نمایش و روشنایی: " + NightModeManager.label(this)}, (dialog, which) -> {
                    if (which == 0) cycleVolume();
                    else if (which == 1) showIntelligenceModeDialog();
                    else showDisplayModeDialog();
                }).show();
    }

    private void showDisplayModeDialog() {
        NightModeManager.Mode current = NightModeManager.readMode(this);
        String[] options = {"خودکار (شب ۱۹ تا ۶)", "همیشه روشن", "همیشه تیره"};
        int checked = current == NightModeManager.Mode.LIGHT ? 1
                : current == NightModeManager.Mode.DARK ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("نمایش و روشنایی")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    NightModeManager.Mode selected = which == 1 ? NightModeManager.Mode.LIGHT
                            : which == 2 ? NightModeManager.Mode.DARK : NightModeManager.Mode.AUTO;
                    NightModeManager.saveMode(this, selected);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void showIntelligenceModeDialog() {
        DrivingIntelligenceCoordinator.Mode current = readIntelligenceMode();
        String[] options = {"هوشمند اقتصادی (پیشنهادی)", "هوشمند کامل"};
        new AlertDialog.Builder(this).setTitle("هوشمندی رانندگی")
                .setSingleChoiceItems(options, current == DrivingIntelligenceCoordinator.Mode.FULL ? 1 : 0, (dialog, which) -> {
                    DrivingIntelligenceCoordinator.Mode selected = which == 1
                            ? DrivingIntelligenceCoordinator.Mode.FULL : DrivingIntelligenceCoordinator.Mode.ECONOMY;
                    selectIntelligenceMode(selected);
                    dialog.dismiss();
                }).setNegativeButton("انصراف", null).show();
    }

    private void maybeShowIntelligenceOnboarding() {
        if (isFinishing() || getSharedPreferences(PREFS_DEVICE_LOCAL, MODE_PRIVATE)
                .getBoolean(KEY_INTELLIGENCE_ONBOARDING_SHOWN, false)) return;
        new AlertDialog.Builder(this)
                .setTitle("هوشمندی رانندگی")
                .setMessage("همراه راننده برای پاسخ‌گویی و تحلیل از دو حالت استفاده می‌کند:\n\n"
                        + "🟢 حالت اقتصادی:\n"
                        + "مصرف کمتر اینترنت و اعتبار هوش مصنوعی\n"
                        + "مناسب برای استفاده روزمره و طولانی\n\n"
                        + "🔵 حالت هوشمند کامل:\n"
                        + "تحلیل‌های دقیق‌تر، پاسخ‌های طبیعی‌تر و استفاده بیشتر از هوش مصنوعی\n"
                        + "مناسب برای تجربه کامل همراه راننده\n\n"
                        + "شما هر زمان می‌توانید این حالت را از داشبورد تغییر دهید.")
                .setPositiveButton("هوشمند کامل", (dialog, which) -> {
                    selectIntelligenceMode(DrivingIntelligenceCoordinator.Mode.FULL);
                    markOnboardingShown();
                })
                .setNegativeButton("اقتصادی", (dialog, which) -> {
                    selectIntelligenceMode(DrivingIntelligenceCoordinator.Mode.ECONOMY);
                    markOnboardingShown();
                })
                .setCancelable(false)
                .show();
    }

    private void markOnboardingShown() {
        getSharedPreferences(PREFS_DEVICE_LOCAL, MODE_PRIVATE).edit()
                .putBoolean(KEY_INTELLIGENCE_ONBOARDING_SHOWN, true).apply();
    }

    private void selectIntelligenceMode(DrivingIntelligenceCoordinator.Mode selected) {
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit()
                .putString(KEY_INTELLIGENCE_MODE, selected.name()).apply();
        intelligenceCoordinator.setMode(selected);
        intelligenceCoordinator.cancelAll();
        writeAutomaticBackup();
        refreshIntelligenceButton();
        refreshAiStatus();
        setStatus(selected == DrivingIntelligenceCoordinator.Mode.FULL
                ? "حالت هوشمند کامل فعال شد." : "حالت هوشمند اقتصادی فعال شد.");
    }

    private void refreshIntelligenceButton() {
        if (intelligenceButton == null) return;
        intelligenceButton.setText(readIntelligenceMode() == DrivingIntelligenceCoordinator.Mode.FULL
                ? "هوشمندی رانندگی: کامل" : "هوشمندی رانندگی: اقتصادی");
    }

    private void refreshAiStatus() {
        if (aiStatusText == null) return;
        if (runtimeKeysLoading) {
            aiStatusText.setText("هوشمندی رانندگی در حال آماده‌سازی");
        } else if (isFullIntelligenceMode() && onlineSpeechClient.canUseOnlineTts()) {
            aiStatusText.setText("تحلیل هوشمند فعال");
        } else if (isFullIntelligenceMode()) {
            aiStatusText.setText("هوشمند کامل؛ TTS آنلاین در دسترس نیست");
        } else {
            aiStatusText.setText("حالت اقتصادی");
        }
    }

    private void cycleVolume() {
        final String[] choices = {"افزایش صدای راهنما", "کاهش صدای راهنما"};
        new AlertDialog.Builder(this).setTitle("تنظیمات صدا").setItems(choices, (d, which) -> {
            if (which == 0) { voicePlayer.increaseVolume(); voicePlayer.announce("voice_louder", "صدای راهنما بیشتر شد."); setStatus("صدای راهنما بیشتر شد."); }
            else { voicePlayer.decreaseVolume(); voicePlayer.announce("voice_lower", "صدای راهنما کمتر شد."); setStatus("صدای راهنما کمتر شد."); }
        }).show();
    }

    private void selectMainTab(int tab) {
        dashboardPage.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        savedPlacesPage.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        profilePage.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        tripHistoryPage.setVisibility(View.GONE);
        ((Button) findViewById(R.id.tabDashboardButton)).setAlpha(tab == 0 ? 1f : 0.62f);
        ((Button) findViewById(R.id.tabSavedButton)).setAlpha(tab == 1 ? 1f : 0.62f);
        ((Button) findViewById(R.id.tabProfileButton)).setAlpha(tab == 2 ? 1f : 0.62f);
        ((Button) findViewById(R.id.tabMapButton)).setAlpha(0.62f);
        if (tab == 1) refreshSavedPlacesTab();
    }

    private void refreshSavedPlacesTab() {
        if (savedPlacesTabText == null) return;
        List<SavedPlace> places = placeStore.allPlaces();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < places.size(); i++) {
            SavedPlace place = places.get(i);
            text.append(i + 1).append(". ").append(place.name);
            if (place.address != null && !place.address.trim().isEmpty()) text.append("\n").append(place.address);
            text.append("\n\n");
        }
        savedPlacesTabText.setText(text.length() == 0 ? "هنوز مکانی ذخیره نشده است." : text.toString().trim());
        savedPlacesTabText.setOnClickListener(v -> choosePlace(new ArrayList<>(placeStore.allPlaces())));
    }

    private void showSubscriptionInfo() {
        new AlertDialog.Builder(this)
                .setTitle("اشتراک")
                .setMessage("مدیریت اشتراک در نسخه بعدی فعال می‌شود. امکانات فعلی برنامه بدون تغییر در دسترس هستند.")
                .setPositiveButton("متوجه شدم", null)
                .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("درباره DriveMate AI")
                .setMessage("دستیار رانندگی فارسی با مسیریابی، راهنمای صوتی، نقشه، مکان‌های ذخیره‌شده و پشتیبان‌گیری محلی.")
                .setPositiveButton("بستن", null)
                .show();
    }

    private void showPlaces(boolean favoritesOnly) {
        ArrayList<SavedPlace> places = new ArrayList<>();
        for (SavedPlace place : placeStore.allPlaces()) if (!favoritesOnly || place.favorite) places.add(place);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < places.size(); i++) {
            SavedPlace place = places.get(i);
            builder.append(i + 1).append(". ").append(place.name).append(" - ").append(place.address).append("\n");
        }
        listText.setText(builder.length() == 0 ? "هنوز مقصدی ذخیره نشده است." : builder.toString());
        listText.setOnClickListener(v -> choosePlace(places));
    }

    private void choosePlace(ArrayList<SavedPlace> places) {
        if (places.isEmpty()) return;
        String[] names = new String[places.size()];
        for (int i = 0; i < places.size(); i++) names[i] = places.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("انتخاب مکان")
                .setItems(names, (d, which) -> editOrNavigatePlace(places.get(which)))
                .show();
    }

    private void editOrNavigatePlace(SavedPlace place) {
        new AlertDialog.Builder(this)
                .setTitle(place.name)
                .setItems(new String[]{"شروع مسیریابی", "ویرایش نام", "حذف مکان"}, (dialog, action) -> {
                    if (action == 0) {
                        startNavigation(place);
                    } else if (action == 1) {
                        editSavedPlaceName(place);
                    } else {
                        confirmDeleteSavedPlace(place);
                    }
                })
                .show();
    }

    private void editSavedPlaceName(SavedPlace place) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(place.name);
        new AlertDialog.Builder(this)
                .setTitle("ویرایش نام مکان")
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    placeStore.upsert(new SavedPlace(name, place.kind, place.latitude, place.longitude,
                            place.address, System.currentTimeMillis(), place.favorite));
                    writeAutomaticBackup();
                    refreshList();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void confirmDeleteSavedPlace(SavedPlace place) {
        new AlertDialog.Builder(this)
                .setTitle("حذف مکان")
                .setMessage("«" + place.name + "» از ذخیره‌ها حذف شود؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    placeStore.delete(place);
                    writeAutomaticBackup();
                    refreshList();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void showRecent() {
        ArrayList<SavedPlace> recent = new ArrayList<>(placeStore.recentPlaces());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            builder.append(i + 1).append(". ").append(recent.get(i).name).append("\n");
        }
        listText.setText(builder.length() == 0 ? "هنوز مقصد اخیری وجود ندارد." : builder.toString());
        listText.setOnClickListener(v -> choosePlace(recent));
    }

    private void refreshList() {
        showPlaces(false);
        refreshSavedPlacesTab();
    }

    private void scheduleTrafficCheck() {
        voiceHandler.removeCallbacks(trafficCheck);
        if (navigationEngine.isNavigating() && activeDestination != null) {
            voiceHandler.postDelayed(trafficCheck, TRAFFIC_CHECK_INTERVAL_MS);
        }
    }

    /**
     * Requests a fresh traffic-aware route at a bounded cadence. The current route is replaced
     * only when the returned ETA beats the elapsed-time-adjusted previous ETA by a useful margin.
     */
    private void checkTrafficAndMaybeReroute() {
        if (!navigationEngine.isNavigating() || activeDestination == null) return;
        Location location = locationTracker.getLastLocation();
        if (location == null) { scheduleTrafficCheck(); return; }
        final SavedPlace destination = activeDestination;
        final int priorEtaSeconds = lastTrafficEtaSeconds;
        final long priorEtaMeasuredAt = lastTrafficEtaMeasuredAt;
        routeRepository.getRoute(location.getLatitude(), location.getLongitude(), activeWaypoints,
                destination.latitude, destination.longitude,
                route -> runOnUiThread(() -> {
                    if (!navigationEngine.isNavigating() || activeDestination != destination) return;
                    long now = System.currentTimeMillis();
                    int elapsedSeconds = priorEtaMeasuredAt == 0L ? 0 : (int) ((now - priorEtaMeasuredAt) / 1000L);
                    int expectedRemaining = Math.max(0, priorEtaSeconds - elapsedSeconds);
                    int gainSeconds = expectedRemaining - route.durationSeconds;
                    boolean materiallyFaster = expectedRemaining >= 300
                            && route.durationSeconds > 0
                            && gainSeconds >= TRAFFIC_REROUTE_MIN_GAIN_SECONDS
                            && gainSeconds * 100 >= expectedRemaining * 12;
                    lastTrafficEtaSeconds = route.durationSeconds;
                    lastTrafficEtaMeasuredAt = now;
                    if (materiallyFaster) replaceRouteForTraffic(route, destination, gainSeconds);
                    else scheduleTrafficCheck();
                }), error -> runOnUiThread(this::scheduleTrafficCheck));
    }

    private void replaceRouteForTraffic(RouteResult route, SavedPlace destination, int gainSeconds) {
        activeRoute = route;
        RouteCache.store(route, destination.latitude, destination.longitude);
        fetchRouteHazards(route);
        fetchRouteSafetyAlerts(route);
        fetchRouteTrafficIncidents(route);
        navigationEngine.start(route, new NavigationEngine.Listener() {
            @Override public void onInstruction(RouteStep step) { runOnUiThread(() -> announceRouteStep(step)); }
            @Override public void onOffRoute() { runOnUiThread(() -> rerouteFromCurrentLocation()); }
            @Override public void onArrived() { runOnUiThread(() -> finishTrip(destination)); }
            @Override public void onWaypointReached(RouteStep step, int ordinal) { runOnUiThread(() -> announceWaypointReached(ordinal)); }
        });
        setStatus("مسیر با ترافیک به‌روزرسانی شد؛ حدود " + Math.max(1, gainSeconds / 60) + " دقیقه سریع‌تر است.");
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "مسیر ترافیک‌محور به " + destination.name + " حدود " + Math.max(1, gainSeconds / 60) + " دقیقه زمان بهتری دارد. یک هشدار صوتی بسیار کوتاه و آرام بگو.",
                "alternative_route", "مسیر سریع‌تری پیدا شد.", 20_000L);
        scheduleTrafficCheck();
    }

    private void setStatus(String message) {
        android.util.Log.i("DriveMateStatus", message);
        statusText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void rerouteFromCurrentLocation() {
        if (activeDestination == null || locationTracker.getLastLocation() == null) return;
        setStatus("از مسیر خارج شدید؛ در حال محاسبه مسیر جدید...");
        startNavigation(activeDestination, activeWaypoints, true);
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                "کاربر از مسیر خارج شده است. یک هشدار خیلی کوتاه و آرام برای ادامه مسیر بگو.",
                "route_recalculated", "از مسیر خارج شدید؛ در حال محاسبه مسیر جدید هستم.", 15_000L);
    }

    private boolean backgroundNavigationEnabled() {
        return getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).getBoolean("background_navigation", true);
    }

    private void toggleBackgroundNavigation() {
        boolean enabled = !backgroundNavigationEnabled();
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit().putBoolean("background_navigation", enabled).apply();
        writeAutomaticBackup();
        if (enabled && navigationEngine.isNavigating()) startBackgroundNavigation();
        else if (!enabled) stopBackgroundNavigation();
        refreshNotificationButton();
        setStatus(enabled ? "اعلان و ادامه مسیریابی در پس‌زمینه فعال شد." : "اعلان و ادامه مسیریابی در پس‌زمینه غیرفعال شد.");
    }

    private void refreshNotificationButton() {
        if (notificationButton != null) notificationButton.setText(backgroundNavigationEnabled() ? "اعلان مسیریابی: روشن" : "اعلان مسیریابی: خاموش");
    }

    private void startBackgroundNavigation() {
        if (!backgroundNavigationEnabled()) return;
        Intent intent = new Intent(this, NavigationForegroundService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private void stopBackgroundNavigation() {
        stopService(new Intent(this, NavigationForegroundService.class));
    }

    /** The service broadcasts ACTION_STOP_BROADCAST once, but every live MainActivity instance
     *  registers its own navigationStopReceiver (see registerNavigationReceiver, called from every
     *  onCreate), so each instance receives that single broadcast independently. Only the instance
     *  actually driving the trip (observingBackgroundSession == false) should run the real teardown
     *  in stopNavigation() below - the real owner's own receiver already does that. A mirroring
     *  instance just clears its own display state instead; calling stopNavigation() on a mirror too
     *  would build a bogus trip report from stats it never tracked and save a second, garbage trip
     *  record on top of the owner's real one. */
    private void onNavigationStopBroadcastReceived() {
        String message = "مسیریابی از اعلان متوقف شد.";
        if (observingBackgroundSession) {
            observingBackgroundSession = false;
            activeDestination = null;
            activeRoute = null;
            activeWaypoints = new ArrayList<>();
            setStatus(message);
            refreshList();
        } else {
            stopNavigation(message);
        }
    }

    /** Stop requests that originate from this instance's own UI (stop button, the map screen's stop
     *  action) must reach wherever the trip is actually running. If this instance is only mirroring
     *  a background trip owned by another, older MainActivity instance (see
     *  resumeBackgroundSessionIfAny), calling stopNavigation() here directly would build a bogus
     *  trip report from stats this instance never tracked while leaving the real GPS/voice session
     *  in the owner instance running untouched - so the stop is forwarded to the real owner first. */
    private void requestStopNavigation(String message) {
        MainActivity owner = activeSessionOwner == null ? null : activeSessionOwner.get();
        if (observingBackgroundSession && owner != null && owner != this) {
            owner.stopNavigation(message);
            observingBackgroundSession = false;
            activeDestination = null;
            activeRoute = null;
            activeWaypoints = new ArrayList<>();
            setStatus(message);
            refreshList();
        } else {
            stopNavigation(message);
        }
    }

    private void stopNavigation(String message) {
        TripRecord tripReport = buildTripRecord(activeDestination, false);
        saveTripRecord(tripReport);
        ++routeRequestSequence;
        ++hazardFetchRequestId;
        ++speedLimitFetchRequestId;
        ++safetyAlertFetchRequestId;
        ++weatherCheckRequestId;
        ++trafficIncidentFetchRequestId;
        activeRouteHazards = new ArrayList<>();
        activeRouteHazardAnnounced = new boolean[0];
        activeRouteSpeedLimits = new ArrayList<>();
        activeSpeedZones = new ArrayList<>();
        activeSpeedZoneAnnounced = new boolean[0];
        activeRouteCumulativeDistances = new double[0];
        activeRouteSafetyAlerts = new ArrayList<>();
        activeRouteSafetyAlertAnnounced = new boolean[0];
        activeRouteTrafficIncidents = new ArrayList<>();
        announcedTrafficIncidentIds.clear();
        lastSpeedLimitWarningAt = 0L;
        lastWarnedMappedSpeedLimit = 0;
        navigationEngine.stop();
        smartCompanion.stop();
        intelligenceCoordinator.cancelAll();
        voiceHandler.removeCallbacks(trafficCheck);
        voiceHandler.removeCallbacks(weatherCheck);
        voiceHandler.removeCallbacks(trafficIncidentCheck);
        voiceHandler.removeCallbacks(tripAnalysisHide);
        activeDestination = null;
        tripStartedAt = 0L;
        activeTripDistanceMeters = 0;
        activeTripOriginLatitude = Double.NaN;
        activeTripOriginLongitude = Double.NaN;
        lastTripLocation = null;
        initialGuidanceHeldUntil = 0L;
        hideTripAnalysis();
        stopBackgroundNavigation();
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "مسیریابی متوقف شده است. یک پیام فارسی کوتاه و طبیعی برای راننده بگو.",
                "stop_navigation", message, 12_000L);
        setStatus(message);
        activeRoute = null;
        activeWaypoints = new ArrayList<>();
        if (tripStatsPanel != null) tripStatsPanel.setVisibility(View.GONE);
        showTripCompletionReport(tripReport);
    }

    private void registerNavigationReceiver() {
        IntentFilter filter = new IntentFilter(NavigationForegroundService.ACTION_STOP_BROADCAST);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(navigationStopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(navigationStopReceiver, filter);
    }

    /**
     * Loads community-mapped OpenStreetMap speed camera / speed bump / police-checkpoint points
     * along the given route in the background. Neither Neshan nor map.ir expose this data through
     * any public developer API, so OpenStreetMap/Overpass is the only source; results are
     * best-effort and depend entirely on what contributors have mapped for that road (see
     * OverpassPoiProvider). This never represents live police presence.
     */
    private void fetchRouteHazards(RouteResult route) {
        activeRouteHazards = new ArrayList<>();
        activeRouteHazardAnnounced = new boolean[0];
        final int requestId = ++hazardFetchRequestId;
        final List<RoutePoint> geometry = route.geometry;
        new Thread(() -> {
            List<double[]> hazards;
            try {
                hazards = hazardProvider.hazardsNear(geometry);
            } catch (Exception exception) {
                android.util.Log.w("DriveMateHazard", "Route hazard lookup failed: " + exception.getMessage());
                return;
            }
            runOnUiThread(() -> {
                if (requestId != hazardFetchRequestId) return;
                activeRouteHazards = hazards == null ? new ArrayList<>() : hazards;
                activeRouteHazardAnnounced = new boolean[activeRouteHazards.size()];
            });
        }).start();
        fetchRouteSpeedLimits(route);
    }

    /**
     * Loads sharp curves (pure geometry, no network), best-effort OpenStreetMap railway
     * crossings / school zones / hazard-tagged points, and OSM way tags for tunnels, narrow
     * bridges and steep inclines along the given route. See RouteSafetyAlert and
     * OverpassPoiProvider for the honesty rules behind each type.
     */
    private void fetchRouteSafetyAlerts(RouteResult route) {
        activeRouteSafetyAlerts = new ArrayList<>();
        activeRouteSafetyAlertAnnounced = new boolean[0];
        final int requestId = ++safetyAlertFetchRequestId;
        final List<RoutePoint> geometry = route.geometry;
        new Thread(() -> {
            ArrayList<RouteSafetyAlert> merged = new ArrayList<>(RouteCurveAnalyzer.sharpCurves(geometry));
            try {
                merged.addAll(hazardProvider.pointSafetyFeaturesNear(geometry));
            } catch (Exception exception) {
                android.util.Log.w("DriveMateSafety", "Point safety feature lookup failed: " + exception.getMessage());
            }
            try {
                merged.addAll(hazardProvider.roadWayFeaturesNear(geometry));
            } catch (Exception exception) {
                android.util.Log.w("DriveMateSafety", "Road way feature lookup failed: " + exception.getMessage());
            }
            runOnUiThread(() -> {
                if (requestId != safetyAlertFetchRequestId) return;
                activeRouteSafetyAlerts = merged;
                activeRouteSafetyAlertAnnounced = new boolean[merged.size()];
            });
        }).start();
    }

    /** Fetches live traffic incidents once for the given route in the background, and reschedules
     *  itself (see scheduleTrafficIncidentCheck) so the list stays current for as long as the trip
     *  runs, unlike the mostly-static OSM hazard/safety-alert lookups above. Silently disables
     *  itself if no key is configured. */
    private void fetchRouteTrafficIncidents(RouteResult route) {
        if (!trafficIncidentProvider.hasKey() || route == null) return;
        final int requestId = ++trafficIncidentFetchRequestId;
        final List<RoutePoint> geometry = route.geometry;
        new Thread(() -> {
            List<TrafficIncident> incidents;
            try {
                incidents = trafficIncidentProvider.incidentsNear(geometry);
            } catch (Exception exception) {
                android.util.Log.w("DriveMateTraffic", "Traffic incident lookup failed: " + exception.getMessage());
                runOnUiThread(() -> { if (requestId == trafficIncidentFetchRequestId) scheduleTrafficIncidentCheck(); });
                return;
            }
            runOnUiThread(() -> {
                if (requestId != trafficIncidentFetchRequestId) return;
                activeRouteTrafficIncidents = incidents == null ? new ArrayList<>() : incidents;
                // Drop announced-ids no longer present so a long trip's repeated refreshes cannot
                // grow this set forever, and so a re-appearing incident can be announced again.
                java.util.Set<String> stillPresent = new java.util.HashSet<>();
                for (TrafficIncident incident : activeRouteTrafficIncidents) stillPresent.add(incident.id);
                announcedTrafficIncidentIds.retainAll(stillPresent);
                scheduleTrafficIncidentCheck();
            });
        }).start();
    }

    private void scheduleTrafficIncidentCheck() {
        voiceHandler.removeCallbacks(trafficIncidentCheck);
        if (navigationEngine.isNavigating() && activeDestination != null && trafficIncidentProvider.hasKey()) {
            voiceHandler.postDelayed(trafficIncidentCheck, TRAFFIC_INCIDENT_CHECK_INTERVAL_MS);
        }
    }

    /** Live point traffic-incident counterpart to checkRouteHazards/checkRouteSafetyAlerts: same
     *  one-per-incident announce-once behavior, but keyed by the provider's own incident id (not
     *  array index) since this list is periodically refreshed rather than fixed for the whole
     *  trip. A wider 700m radius than the static OSM hazards (350m) reflects the more
     *  time-sensitive, higher-severity nature of a live incident. */
    private void checkTrafficIncidentsProximity(Location location) {
        if (location == null || activeRouteTrafficIncidents.isEmpty() || !navigationEngine.isNavigating()) return;
        for (TrafficIncident incident : activeRouteTrafficIncidents) {
            if (announcedTrafficIncidentIds.contains(incident.id)) continue;
            Location incidentLocation = new Location("tomtom_incident");
            incidentLocation.setLatitude(incident.latitude);
            incidentLocation.setLongitude(incident.longitude);
            float meters = location.distanceTo(incidentLocation);
            if (meters > 700f) continue;
            announcedTrafficIncidentIds.add(incident.id);
            announceTrafficIncident(incident, Math.round(meters));
        }
    }

    private void announceTrafficIncident(TrafficIncident incident, int metersAway) {
        String kind;
        String clip = null;
        switch (incident.type) {
            case ACCIDENT: kind = "تصادف"; clip = "danger_ahead"; break;
            case ROAD_CLOSED: kind = "بسته شدن مسیر"; break;
            case ROADWORK: kind = "عملیات راه‌سازی"; break;
            default: kind = "وضعیت غیرعادی مسیر"; break;
        }
        String detail = incident.description.trim().isEmpty() ? "" : " (" + incident.description.trim() + ")";
        String fallback = "بر اساس گزارش زندهٔ ترافیک، " + kind + detail + " حدود " + metersAway
                + " متر جلوتر گزارش شده است؛ با احتیاط پیش بروید.";
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                "گزارش زندهٔ ترافیک از " + kind + detail + " حدود " + metersAway
                        + " متر جلوتر روی مسیر فعلی خبر می‌دهد. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام برای احتیاط بگو؛ ادعای قطعیت نکن.",
                clip, fallback, 12_000L);
    }

    /** Fetch once per route rather than on every GPS update, preserving both battery and the
     * public Overpass service. The map-layer toggle also controls the spoken mapped-speed alert. */
    private void fetchRouteSpeedLimits(RouteResult route) {
        activeRouteSpeedLimits = new ArrayList<>();
        activeSpeedZones = new ArrayList<>();
        activeSpeedZoneAnnounced = new boolean[0];
        activeRouteCumulativeDistances = new double[0];
        final int requestId = ++speedLimitFetchRequestId;
        final List<RoutePoint> geometry = route.geometry;
        new Thread(() -> {
            try {
                List<SpeedLimitPoint> limits = hazardProvider.speedLimitsNear(geometry);
                List<SpeedLimitPoint> resolved = (limits == null || limits.isEmpty()) && route.providerSpeedLimits != null
                        ? new ArrayList<>(route.providerSpeedLimits)
                        : (limits == null ? new ArrayList<>() : limits);
                double[] cumulative = cumulativeDistances(geometry);
                List<RouteSpeedZone> zones = buildOrderedSpeedZones(geometry, cumulative, resolved);
                runOnUiThread(() -> {
                    if (requestId == speedLimitFetchRequestId) {
                        // Priority 1: explicit OSM maxspeed. Priority 2: only use a numeric value
                        // explicitly returned by a routing provider; never infer a legal limit.
                        activeRouteSpeedLimits = resolved;
                        activeRouteCumulativeDistances = cumulative;
                        activeSpeedZones = zones;
                        activeSpeedZoneAnnounced = new boolean[zones.size()];
                    }
                });
            } catch (Exception exception) {
                android.util.Log.w("DriveMateSpeed", "Mapped speed-limit lookup failed: " + exception.getMessage());
                List<SpeedLimitPoint> fallback = route.providerSpeedLimits == null
                        ? new ArrayList<>() : new ArrayList<>(route.providerSpeedLimits);
                double[] cumulative = cumulativeDistances(geometry);
                List<RouteSpeedZone> zones = buildOrderedSpeedZones(geometry, cumulative, fallback);
                runOnUiThread(() -> {
                    if (requestId == speedLimitFetchRequestId) {
                        // OSM unavailable: stage two can still use an explicit provider value.
                        activeRouteSpeedLimits = fallback;
                        activeRouteCumulativeDistances = cumulative;
                        activeSpeedZones = zones;
                        activeSpeedZoneAnnounced = new boolean[zones.size()];
                    }
                });
            }
        }).start();
    }

    private double[] cumulativeDistances(List<RoutePoint> geometry) {
        if (geometry == null || geometry.isEmpty()) return new double[0];
        double[] distances = new double[geometry.size()];
        for (int index = 1; index < geometry.size(); index++) {
            RoutePoint previous = geometry.get(index - 1);
            RoutePoint current = geometry.get(index);
            Location from = new Location("route");
            from.setLatitude(previous.latitude);
            from.setLongitude(previous.longitude);
            Location to = new Location("route");
            to.setLatitude(current.latitude);
            to.setLongitude(current.longitude);
            distances[index] = distances[index - 1] + from.distanceTo(to);
        }
        return distances;
    }

    /** Projects each maxspeed point onto the nearest route-geometry index, then sorts by that
     *  point's cumulative distance so the list reads in travel order regardless of the order
     *  Overpass/the routing provider originally returned them in. */
    private List<RouteSpeedZone> buildOrderedSpeedZones(List<RoutePoint> geometry, double[] cumulative, List<SpeedLimitPoint> limits) {
        ArrayList<RouteSpeedZone> zones = new ArrayList<>();
        if (geometry == null || geometry.isEmpty() || limits == null || limits.isEmpty() || cumulative.length != geometry.size()) {
            return zones;
        }
        for (SpeedLimitPoint limit : limits) {
            int nearestIndex = 0;
            float nearestMeters = Float.MAX_VALUE;
            Location target = new Location("limit");
            target.setLatitude(limit.latitude);
            target.setLongitude(limit.longitude);
            for (int index = 0; index < geometry.size(); index++) {
                RoutePoint point = geometry.get(index);
                Location candidate = new Location("route");
                candidate.setLatitude(point.latitude);
                candidate.setLongitude(point.longitude);
                float meters = target.distanceTo(candidate);
                if (meters < nearestMeters) { nearestMeters = meters; nearestIndex = index; }
            }
            zones.add(new RouteSpeedZone(cumulative[nearestIndex], limit.kilometersPerHour, limit.latitude, limit.longitude, limit.source));
        }
        zones.sort(Comparator.comparingDouble(zone -> zone.distanceMeters));
        return zones;
    }

    private void checkRouteSpeedLimit(Location location) {
        if (!getSharedPreferences("map_layers", MODE_PRIVATE).getBoolean("speed_limit_osm", true)
                || location == null || !location.hasSpeed() || !navigationEngine.isNavigating()
                || activeRouteSpeedLimits.isEmpty()) return;
        SpeedLimitPoint closest = null;
        float closestMeters = Float.MAX_VALUE;
        for (SpeedLimitPoint limit : activeRouteSpeedLimits) {
            Location point = new Location("osm_maxspeed");
            point.setLatitude(limit.latitude);
            point.setLongitude(limit.longitude);
            float meters = location.distanceTo(point);
            if (meters < closestMeters) { closestMeters = meters; closest = limit; }
        }
        if (closest == null || closestMeters > 110f) return;
        float currentKph = location.getSpeed() * 3.6f;
        // GPS speed has normal variance; avoid warning close to the mapped value or repeating it.
        if (currentKph < closest.kilometersPerHour + 7f) return;
        long now = System.currentTimeMillis();
        if (now - lastSpeedLimitWarningAt < 90_000L && lastWarnedMappedSpeedLimit == closest.kilometersPerHour) return;
        lastSpeedLimitWarningAt = now;
        lastWarnedMappedSpeedLimit = closest.kilometersPerHour;
        String fallback = "سرعت شما از محدودیت ثبت‌شدهٔ این مسیر، " + closest.kilometersPerHour + " کیلومتر بر ساعت، بالاتر است.";
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                "محدودیت سرعت ثبت‌شده از " + closest.source + " برای مسیر " + closest.kilometersPerHour
                        + " کیلومتر بر ساعت است و سرعت GPS کاربر حدود " + Math.round(currentKph)
                        + " است. فقط یک هشدار فارسی بسیار کوتاه، آرام و ایمن بگو؛ ادعای تخلف قانونی نکن.",
                "speed_limit_osm", fallback, 12_000L);
    }

    /**
     * Predictive counterpart to checkRouteSpeedLimit: instead of only reacting once the driver is
     * already past a mapped maxspeed point, this looks a short distance ahead along the route's
     * own geometry and announces once, in advance, when the upcoming zone is a meaningfully lower
     * limit than the one just passed (e.g. 90 -> 60). Skips silently if the driver's GPS position
     * cannot be matched closely enough to the route line, rather than guessing.
     */
    private void checkUpcomingSpeedZone(Location location) {
        if (!getSharedPreferences("map_layers", MODE_PRIVATE).getBoolean("speed_limit_osm", true)
                || location == null || !navigationEngine.isNavigating() || activeRoute == null
                || activeSpeedZones.isEmpty()) return;
        List<RoutePoint> geometry = activeRoute.geometry;
        if (geometry == null || geometry.size() < 2 || activeRouteCumulativeDistances.length != geometry.size()) return;
        int nearestIndex = 0;
        float nearestMeters = Float.MAX_VALUE;
        for (int index = 0; index < geometry.size(); index++) {
            RoutePoint point = geometry.get(index);
            Location candidate = new Location("route");
            candidate.setLatitude(point.latitude);
            candidate.setLongitude(point.longitude);
            float meters = location.distanceTo(candidate);
            if (meters < nearestMeters) { nearestMeters = meters; nearestIndex = index; }
        }
        if (nearestMeters > 150f) return;
        double currentProgress = activeRouteCumulativeDistances[nearestIndex];
        Integer currentLimit = null;
        for (RouteSpeedZone zone : activeSpeedZones) {
            if (zone.distanceMeters <= currentProgress + 20d) currentLimit = zone.kilometersPerHour;
            else break;
        }
        if (currentLimit == null) return;
        for (int index = 0; index < activeSpeedZones.size(); index++) {
            if (index >= activeSpeedZoneAnnounced.length || activeSpeedZoneAnnounced[index]) continue;
            RouteSpeedZone zone = activeSpeedZones.get(index);
            double aheadMeters = zone.distanceMeters - currentProgress;
            if (aheadMeters < 30d || aheadMeters > 450d) continue;
            if (zone.kilometersPerHour >= currentLimit - 9) continue;
            activeSpeedZoneAnnounced[index] = true;
            announceUpcomingSpeedZone(zone, (int) Math.round(aheadMeters));
        }
    }

    private void announceUpcomingSpeedZone(RouteSpeedZone zone, int metersAhead) {
        String fallback = "حدود " + metersAhead + " متر دیگر محدودیت سرعت ثبت‌شده به " + zone.kilometersPerHour + " کیلومتر بر ساعت کاهش می‌یابد.";
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                "بر اساس داده " + zone.source + "، حدود " + metersAhead
                        + " متر جلوتر محدودیت سرعت ثبت‌شده مسیر به " + zone.kilometersPerHour
                        + " کیلومتر بر ساعت کاهش پیدا می‌کند. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام برای آماده شدن جهت کاهش سرعت بگو.",
                null, fallback, 10_000L);
    }

    /**
     * Warns the driver once, shortly before arrival, at each known hazard point. Proximity-based
     * only (no route-projection/order check) to keep this simple and robust to GPS noise; each
     * point announces at most once per route thanks to activeRouteHazardAnnounced.
     */
    /** Tracks the last fix used to judge movement for hazard/safety-alert gating (separate from
     *  lastTripLocation, which is trip-distance bookkeeping and gets reset on start/finish). */
    private Location lastAlertMovementLocation;

    /** Location.hasSpeed() is frequently false on real devices (weak fix, just starting to move,
     *  some OEM GPS chips), and relying on it alone silently disables every hazard/curve alert -
     *  exactly the "no speed camera or any warning was announced" regression. Falls back to actual
     *  distance covered since the last fix, mirroring the same fallback recordTripLocation already
     *  uses for trip-distance accumulation. Computed once per location update and shared by every
     *  alert check in that round, not per-alert, so it reflects one consistent movement judgement. */
    private boolean isCurrentlyMoving(Location location) {
        boolean result;
        if (location.hasSpeed()) {
            result = location.getSpeed() >= ALERT_MIN_MOVING_SPEED_MPS;
        } else {
            result = lastAlertMovementLocation != null
                    && lastAlertMovementLocation.distanceTo(location) >= 12f;
        }
        lastAlertMovementLocation = new Location(location);
        return result;
    }

    private void checkRouteHazards(Location location, boolean movingNow) {
        if (location == null || activeRouteHazards.isEmpty() || !navigationEngine.isNavigating()) return;
        for (int index = 0; index < activeRouteHazards.size(); index++) {
            if (index >= activeRouteHazardAnnounced.length || activeRouteHazardAnnounced[index]) continue;
            double[] hazard = activeRouteHazards.get(index);
            if (!isAlertAheadAndRelevant(location, movingNow, hazard[0], hazard[1])) continue;
            activeRouteHazardAnnounced[index] = true;
            announceRouteHazard(hazard[2]);
        }
    }

    private void announceRouteHazard(double type) {
        if (type == OverpassPoiProvider.HAZARD_CAMERA) {
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "داده OpenStreetMap درباره دوربین سرعت در این نزدیکی هشدار داده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                    "speed_camera", "دوربین سرعت احتمالی در این نزدیکی است.", 10_000L);
            smartCompanion.routeHazard("دوربین سرعت (OSM)");
        } else if (type == OverpassPoiProvider.HAZARD_SPEED_BUMP) {
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "داده OpenStreetMap درباره دست‌انداز یا سرعت‌گیر در این نزدیکی هشدار داده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                    "speed_bump_warning", "دست‌انداز یا سرعت‌گیر احتمالی در این نزدیکی است.", 10_000L);
            smartCompanion.routeHazard("دست‌انداز/سرعت‌گیر (OSM)");
        } else if (type == OverpassPoiProvider.HAZARD_TRAFFIC_SIGN) {
            // Static OSM highway=stop / highway=give_way / traffic_sign=* tag only - reuses the
            // existing unused stop_ahead.wav clip, distinct from the police/checkpoint branch below.
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "داده OpenStreetMap به تابلوی ایست یا تقدم عبور در این نزدیکی اشاره کرده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                    "stop_ahead", "تابلوی ایست در این نزدیکی است.", 10_000L);
            smartCompanion.routeHazard("تابلوی ایست (OSM)");
        } else {
            // Static, community-tagged police station / checkpoint location only - never live
            // enforcement presence. Phrased as "احتمالی" (possible) to avoid implying certainty.
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "داده OpenStreetMap به ایست بازرسی یا کلانتری ثبت‌شده در این نزدیکی اشاره کرده است؛ این داده زنده نیست. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                    "danger_ahead", "ایست بازرسی یا کلانتری ثبت‌شده در این نزدیکی است.", 10_000L);
            smartCompanion.routeHazard("ایست بازرسی/پلیس (OSM، غیرزنده)");
        }
    }

    /**
     * Same one-per-route proximity check as checkRouteHazards, for the newer safety-alert set.
     * SCHOOL_ZONE is special: it is skipped (not consumed) outside its approximate active hours so
     * it can still fire later in the same trip once school hours begin.
     */
    private void checkRouteSafetyAlerts(Location location, boolean movingNow) {
        if (location == null || activeRouteSafetyAlerts.isEmpty() || !navigationEngine.isNavigating()) return;
        for (int index = 0; index < activeRouteSafetyAlerts.size(); index++) {
            if (index >= activeRouteSafetyAlertAnnounced.length || activeRouteSafetyAlertAnnounced[index]) continue;
            RouteSafetyAlert alert = activeRouteSafetyAlerts.get(index);
            if (alert.type == RouteSafetyAlert.Type.SCHOOL_ZONE && !isSchoolActiveHour()) continue;
            if (!isAlertAheadAndRelevant(location, movingNow, alert.latitude, alert.longitude)) continue;
            activeRouteSafetyAlertAnnounced[index] = true;
            announceRouteSafetyAlert(alert);
        }
    }

    /** Minimum speed (~2.9 km/h) below which the vehicle is considered parked/stationary, so
     *  proximity-based alerts never fire while standing still - matches the "hasMovingSpeed"
     *  threshold already used elsewhere for the same purpose. */
    private static final float ALERT_MIN_MOVING_SPEED_MPS = 0.8f;
    private static final float ALERT_TRIGGER_RADIUS_METERS = 350f;
    /** How far off the current heading a point can be and still count as "ahead", so alerts
     *  behind or off to the side of the direction of travel are not announced as upcoming. */
    private static final float ALERT_AHEAD_TOLERANCE_DEGREES = 60f;

    /** Shared proximity gate for both checkRouteHazards and checkRouteSafetyAlerts: requires the
     *  vehicle to actually be moving (not parked/stopped, via the shared isCurrentlyMoving result)
     *  and, when a heading is available, requires the alert point to be roughly in front of the
     *  direction of travel rather than behind or to the side - a plain straight-line distance check
     *  alone can't tell "ahead" from "nearby in any direction", which was firing curve/hazard
     *  warnings while stationary. */
    private boolean isAlertAheadAndRelevant(Location location, boolean movingNow, double alertLatitude, double alertLongitude) {
        if (!movingNow) return false;
        Location alertLocation = new Location("route_alert_check");
        alertLocation.setLatitude(alertLatitude);
        alertLocation.setLongitude(alertLongitude);
        if (location.distanceTo(alertLocation) > ALERT_TRIGGER_RADIUS_METERS) return false;
        if (location.hasBearing()) {
            float bearingToAlert = location.bearingTo(alertLocation);
            double headingDiff = Math.abs(angleDifferenceDegrees(location.getBearing(), bearingToAlert));
            if (headingDiff > ALERT_AHEAD_TOLERANCE_DEGREES) return false;
        }
        return true;
    }

    private static double angleDifferenceDegrees(float from, float to) {
        double diff = to - from;
        while (diff > 180d) diff -= 360d;
        while (diff < -180d) diff += 360d;
        return diff;
    }

    /** Rough Iranian school-day approximation (Saturday-Wednesday mornings, Thursday until noon).
     *  Real bell schedules vary by school, grade and season, so this only narrows to "probably
     *  active" - it is never treated as a confirmed schedule. */
    private boolean isSchoolActiveHour() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int day = calendar.get(java.util.Calendar.DAY_OF_WEEK);
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        if (day == java.util.Calendar.FRIDAY) return false;
        if (day == java.util.Calendar.THURSDAY) return hour >= 7 && hour < 12;
        return hour >= 7 && hour < 13;
    }

    private void announceRouteSafetyAlert(RouteSafetyAlert alert) {
        switch (alert.type) {
            case SHARP_CURVE:
                speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                        "پیچ نسبتاً تندی بر اساس هندسه مسیر در جلوی راه تشخیص داده شده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام برای کاهش سرعت بگو.",
                        "dangerous_curve_ahead", "پیچ نسبتاً تند در این نزدیکی است؛ سرعت را کم کنید.", 10_000L);
                break;
            case STEEP_GRADE: {
                String direction = alert.detail > 0 ? "صعودی" : "نزولی";
                String percent = String.valueOf(Math.round(Math.abs(alert.detail)));
                speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                        "داده OpenStreetMap از شیب " + direction + " حدود " + percent
                                + " درصد در این نزدیکی خبر می‌دهد. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                        null, "شیب " + direction + " حدود " + percent + " درصد در این نزدیکی است.", 10_000L);
                break;
            }
            case TUNNEL:
                speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                        "داده OpenStreetMap به تونل در این نزدیکی اشاره کرده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام برای روشن کردن چراغ‌ها بگو.",
                        null, "تونل در این نزدیکی است؛ چراغ‌ها را روشن کنید.", 10_000L);
                break;
            case NARROW_BRIDGE:
                speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                        "داده OpenStreetMap به پل باریک در این نزدیکی اشاره کرده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام برای کاهش سرعت بگو.",
                        null, "پل باریک در این نزدیکی است؛ سرعت را کم کنید.", 10_000L);
                break;
            case RAILWAY_CROSSING:
                speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                        "داده OpenStreetMap به تقاطع راه‌آهن در این نزدیکی اشاره کرده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                        null, "تقاطع راه‌آهن در این نزدیکی است؛ با احتیاط عبور کنید.", 10_000L);
                break;
            case SCHOOL_ZONE:
                speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                        "داده OpenStreetMap به مدرسه در این نزدیکی اشاره کرده و اکنون در بازهٔ ساعتی تقریبی فعالیت مدرسه است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام برای کاهش سرعت و توجه به عابران بگو.",
                        null, "احتمال تردد دانش‌آموزان نزدیک مدرسه در این ساعت است؛ سرعت را کم کنید.", 10_000L);
                break;
            case ACCIDENT_PRONE:
            default:
                speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                        "داده OpenStreetMap این نقطه از مسیر را با یک برچسب خطر عمومی ثبت کرده است؛ این یک آمار رسمی تصادف نیست، فقط برچسب جامعه‌محور است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام برای احتیاط بیشتر بگو.",
                        null, "این بخش از مسیر در OpenStreetMap با برچسب خطر ثبت شده است؛ با احتیاط بیشتری رانندگی کنید.", 10_000L);
                break;
        }
        smartCompanion.routeHazard(alert.type.name());
    }

    private void scheduleWeatherCheck() {
        voiceHandler.removeCallbacks(weatherCheck);
        if (navigationEngine.isNavigating() && activeDestination != null && weatherHazardProvider.hasKey()) {
            voiceHandler.postDelayed(weatherCheck, WEATHER_CHECK_INTERVAL_MS);
        }
    }

    /** Polls OpenWeatherMap for fog/wind near the driver's current position at a bounded cadence
     *  (never on every GPS sample). Silently disables itself if no key is configured or GPS is not
     *  ready yet, and always re-schedules itself so the next check still happens later. */
    private void checkWeatherAlong() {
        if (!navigationEngine.isNavigating() || activeDestination == null) return;
        if (!weatherHazardProvider.hasKey()) return;
        Location location = locationTracker.getLastLocation();
        if (location == null) { scheduleWeatherCheck(); return; }
        final int requestId = ++weatherCheckRequestId;
        final double latitude = location.getLatitude();
        final double longitude = location.getLongitude();
        new Thread(() -> {
            WeatherHazardProvider.Snapshot snapshot;
            try {
                snapshot = weatherHazardProvider.fetch(latitude, longitude);
            } catch (Exception exception) {
                android.util.Log.w("DriveMateWeather", "OpenWeatherMap lookup failed: " + exception.getMessage());
                runOnUiThread(() -> { if (requestId == weatherCheckRequestId) scheduleWeatherCheck(); });
                return;
            }
            runOnUiThread(() -> {
                if (requestId != weatherCheckRequestId) return;
                announceWeatherIfNeeded(snapshot);
                scheduleWeatherCheck();
            });
        }).start();
    }

    /** At most one live-weather warning every 25 minutes, and only for fog/low-visibility or
     *  strong wind - never a routine "weather is fine" announcement. */
    private void announceWeatherIfNeeded(WeatherHazardProvider.Snapshot snapshot) {
        if (snapshot == null || !navigationEngine.isNavigating()) return;
        long now = System.currentTimeMillis();
        if (now - lastWeatherWarningAt < 25 * 60_000L) return;
        if (snapshot.fogLikely) {
            lastWeatherWarningAt = now;
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "گزارش زندهٔ هواشناسی OpenWeatherMap نزدیک موقعیت فعلی، مه یا دید کم (" + snapshot.description
                            + ") را نشان می‌دهد. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام برای احتیاط و کاهش سرعت بگو.",
                    null, "بر اساس گزارش زندهٔ هواشناسی، مه یا دید کم در مسیر گزارش شده است؛ سرعت را کم کنید.", 15_000L);
        } else if (snapshot.strongWindLikely) {
            lastWeatherWarningAt = now;
            int windKph = Math.round((float) (snapshot.windSpeedMetersPerSecond * 3.6d));
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "گزارش زندهٔ هواشناسی OpenWeatherMap نزدیک موقعیت فعلی، سرعت باد حدود " + windKph
                            + " کیلومتر بر ساعت را نشان می‌دهد. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام برای کنترل فرمان بگو.",
                    null, "بر اساس گزارش زندهٔ هواشناسی، باد نسبتاً شدیدی در مسیر گزارش شده است؛ فرمان را محکم نگه دارید.", 15_000L);
        }
    }

    /** Translates an explicit provider lane-guidance object (see LaneGuidance) into a short
     *  Persian clause naming only the lane direction(s) actually recommended for this maneuver -
     *  never inferred, only used when the provider's own response included per-lane validity.
     *  Empty string when there is nothing genuinely actionable to add (single lane, or all/none
     *  valid - see LaneGuidance.hasUsefulGuidance). */
    private String laneGuidanceClause(LaneGuidance lanes) {
        if (lanes == null || !lanes.hasUsefulGuidance()) return "";
        java.util.LinkedHashSet<String> recommended = new java.util.LinkedHashSet<>();
        for (int i = 0; i < lanes.indications.size() && i < lanes.validForManeuver.size(); i++) {
            if (!Boolean.TRUE.equals(lanes.validForManeuver.get(i))) continue;
            String bucket = laneBucket(lanes.indications.get(i));
            if (bucket != null) recommended.add(bucket);
        }
        if (recommended.isEmpty()) return "";
        StringBuilder clause = new StringBuilder("در خط ");
        int i = 0;
        for (String bucket : recommended) {
            if (i > 0) clause.append(i == recommended.size() - 1 ? " یا " : "، ");
            clause.append(bucket);
            i++;
        }
        clause.append(" بمانید.");
        return clause.toString();
    }

    private String laneBucket(String indication) {
        if (indication == null) return null;
        String value = indication.toLowerCase(Locale.ROOT);
        if (value.contains("uturn")) return "دور زدن";
        if (value.contains("left")) return "چپ";
        if (value.contains("right")) return "راست";
        if (value.contains("straight")) return "مستقیم";
        return null;
    }

    private void announceRouteStep(RouteStep step) {
        if (System.currentTimeMillis() < initialGuidanceHeldUntil) {
            lastInstruction = "continue_route";
            lastInstructionText = step.instruction == null ? "" : step.instruction;
            return;
        }
        onlineSpeechClient.stopPlayback();
        String text = step.instruction == null || step.instruction.trim().isEmpty() ? "ادامه مسیر" : step.instruction;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("camera") || text.contains("دوربین")) {
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "داده مسیر درباره دوربین سرعت هشدار داده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                    "speed_camera", "دوربین سرعت در مسیر است.", 10_000L);
            smartCompanion.routeHazard("دوربین سرعت");
            setStatus(text);
            return;
        }
        if (lower.contains("speed bump") || text.contains("دست انداز") || text.contains("سرعت گیر")) {
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "داده مسیر درباره دست‌انداز هشدار داده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                    "speed_bump_warning", "دست انداز در مسیر است.", 10_000L);
            smartCompanion.routeHazard("دست انداز");
            setStatus(text);
            return;
        }
        if (lower.contains("police") || text.contains("پلیس راه") || text.contains("پلیس") || text.contains("ایست بازرسی")) {
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "داده مسیر به پلیس راه یا ایست بازرسی اشاره کرده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                    "police_checkpoint", "پلیس راه یا ایست بازرسی در مسیر است.", 10_000L);
            smartCompanion.routeHazard("پلیس راه");
            setStatus(text);
            return;
        }
        if (lower.contains("left") || text.contains("چپ")) lastInstruction = "turn_left";
        else if (lower.contains("right") || text.contains("راست")) lastInstruction = "turn_right";
        else if (lower.contains("arriv") || text.contains("مقصد")) lastInstruction = "destination_arrived";
        else lastInstruction = "continue_route";
        lastInstructionText = text;
        String laneClause = laneGuidanceClause(step.lanes);
        String fallbackWithLane = laneClause.isEmpty() ? text : text + " " + laneClause;
        String stepPrompt = "دستور مسیریابی فعلی این است: " + text + "."
                + (laneClause.isEmpty() ? "" : " راهنمای خط عبور از داده مسیر: " + laneClause)
                + " آن را در یک جمله فارسی کوتاه، طبیعی و مناسب رانندگی بیان کن"
                + (laneClause.isEmpty() ? "." : "؛ راهنمای خط عبور را هم در همان جمله بگنجان.");
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING, stepPrompt,
                lastInstruction, fallbackWithLane, 10_000L);
        setStatus(text);
    }

    private void announceWaypointReached(int ordinal) {
        // Drop the just-reached stop so a later reroute (rerouteFromCurrentLocation /
        // replaceRouteForTraffic) never re-requests a route back through a place already visited.
        if (ordinal >= 0 && ordinal < activeWaypoints.size()) activeWaypoints.remove(ordinal);
        int humanNumber = ordinal + 1;
        String fallback = "به توقف میانی " + humanNumber + " رسیدید. مسیر به مقصد ادامه دارد.";
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "راننده به توقف میانی شماره " + humanNumber
                        + " رسیده است. یک پیام فارسی کوتاه و طبیعی بگو که مسیر تا مقصد نهایی ادامه دارد.",
                "continue_route", fallback, 12_000L);
        setStatus(fallback);
    }

    /** Swiping the app away from Recents (or otherwise finishing this activity) used to always
     *  tear down navigation, the GPS listener and the background service - even while a trip was
     *  actively running with "اعلان مسیریابی" (background navigation) turned on. That defeated the
     *  whole point of NavigationForegroundService's persistent notification: the app looked like
     *  it "closed navigation" the instant this activity was destroyed, no matter what the driver
     *  actually wanted. Now, while a trip is active and background navigation is enabled, none of
     *  that runs here - GPS updates, the turn-by-turn engine, voice guidance and the AI coordinator
     *  keep running against this activity's own fields. They stay alive because the system
     *  LocationManager still holds a live reference to locationTracker's listener and voiceHandler
     *  is bound to the main-looper (not this activity), and the process itself is kept alive by the
     *  still-running foreground service (see android:stopWithTask="false" on the service in the
     *  manifest). The driver's own "توقف" tap in the notification - or the in-app stop button -
     *  is what actually calls stopNavigation() and performs the full teardown below. */
    @Override protected void onDestroy() {
        boolean keepRunningInBackground = navigationEngine.isNavigating() && backgroundNavigationEnabled();
        // A mirroring instance (observingBackgroundSession) never calls navigationEngine.start()
        // itself, so its own navigationEngine.isNavigating() above is always false - even while a
        // DIFFERENT, still-alive MainActivity instance (activeSessionOwner) is genuinely driving a
        // background trip and owns the shared NavigationForegroundService/notification. Without
        // this check, closing this mirror (e.g. the app's second close while a background trip is
        // running - see 2026-08-02 report) fell into the teardown branch below and called
        // stopBackgroundNavigation(), silently killing the real owner's notification even though
        // navigation itself kept running fine under the owner instance.
        MainActivity owner = activeSessionOwner == null ? null : activeSessionOwner.get();
        boolean anotherInstanceOwnsBackgroundSession = observingBackgroundSession
                && owner != null && owner != this && owner.navigationEngine.isNavigating();
        voiceHandler.removeCallbacks(automaticStop);
        onlineSpeechClient.cancelRecording();
        localSpeechRecognizer.destroy();
        if (!keepRunningInBackground) {
            voiceHandler.removeCallbacks(trafficCheck);
            voiceHandler.removeCallbacks(weatherCheck);
            intelligenceCoordinator.shutdown();
            smartCompanion.stop();
            voicePlayer.shutdown();
            unregisterReceiver(navigationStopReceiver);
            navigationEngine.stop();
            if (!anotherInstanceOwnsBackgroundSession) stopBackgroundNavigation();
            locationTracker.stop();
        }
        super.onDestroy();
    }
}
