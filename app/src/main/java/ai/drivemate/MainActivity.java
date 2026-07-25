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
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Button;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
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
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.TripRecord;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.NavigationEngine;
import ai.drivemate.routing.PlaceSearchRepository;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.storage.PlaceStore;
import ai.drivemate.storage.TripStore;
import ai.drivemate.storage.BackupManager;
import ai.drivemate.voice.Command;
import ai.drivemate.voice.LocalSpeechRecognizer;
import ai.drivemate.voice.VoiceCommandParser;
import ai.drivemate.voice.VoiceGuidancePlayer;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 10;
    private static final int REQ_EXPORT_BACKUP = 11;
    private static final int REQ_IMPORT_BACKUP = 12;
    private static final long TRAFFIC_CHECK_INTERVAL_MS = 8 * 60_000L;
    private static final int TRAFFIC_REROUTE_MIN_GAIN_SECONDS = 180;
    private static final String PREFS_SETTINGS = "drivemate_settings";
    private static final String KEY_INTELLIGENCE_MODE = "driving_intelligence_mode";
    public static final String ACTION_VOICE_FROM_NOTIFICATION = "ai.drivemate.action.VOICE_FROM_NOTIFICATION";

    private TextView statusText;
    private TextView listText;
    private Button voiceButton;
    private Button notificationButton;
    private Button intelligenceButton;
    private PlaceStore placeStore;
    private TripStore tripStore;
    private BackupManager backupManager;
    private VoiceGuidancePlayer voicePlayer;
    private DeviceLocationTracker locationTracker;
    private NeshanRoutingProvider neshanRoutingProvider;
    private MapIrRoutingProvider mapIrRoutingProvider;
    private RouteRepository routeRepository;
    private PlaceSearchRepository placeSearchRepository;
    private VoiceCommandParser commandParser;
    private AiAssistant aiAssistant;
    private DrivingIntelligenceCoordinator intelligenceCoordinator;
    private OnlineSpeechClient onlineSpeechClient;
    private LocalSpeechRecognizer localSpeechRecognizer;
    private SmartDriveCompanion smartCompanion;
    private final NavigationEngine navigationEngine = new NavigationEngine();
    private RuntimeKeys runtimeKeys = new RuntimeKeys();
    private String lastInstruction = "start_navigation";
    private String lastInstructionText = "";
    private SavedPlace activeDestination;
    private int lastTrafficEtaSeconds;
    private long lastTrafficEtaMeasuredAt;
    private long routeRequestSequence;
    private boolean recordingOnlineSpeech;
    private boolean recordingLocalSpeech;
    private boolean runtimeKeysLoading = true;
    private boolean voiceRequestedWhileKeysLoad;
    private final android.os.Handler voiceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable automaticStop = this::finishOnlineRecording;
    private final Runnable trafficCheck = this::checkTrafficAndMaybeReroute;
    private final BroadcastReceiver navigationStopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { stopNavigation("مسیریابی از اعلان متوقف شد."); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        listText = findViewById(R.id.listText);
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
        routeRepository = new RouteRepository(neshanRoutingProvider, mapIrRoutingProvider);
        placeSearchRepository = new PlaceSearchRepository(neshanRoutingProvider, mapIrRoutingProvider);
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
        locationTracker.setUpdateListener(location -> {
            navigationEngine.onLocation(location);
            smartCompanion.onLocation(location);
        });
        handleSharedIntent(getIntent());
        registerNavigationReceiver();
        refreshNotificationButton();
        refreshIntelligenceButton();
        if (ACTION_VOICE_FROM_NOTIFICATION.equals(getIntent().getAction())) voiceHandler.postDelayed(this::toggleVoiceInput, 350L);
    }

    private void wireButtons() {
        voiceButton.setOnClickListener(v -> toggleVoiceInput());
        findViewById(R.id.saveButton).setOnClickListener(v -> promptSaveCurrentPlace());
        findViewById(R.id.homeButton).setOnClickListener(v -> openHomeOrWork("home", "خانه"));
        findViewById(R.id.workButton).setOnClickListener(v -> openHomeOrWork("work", "محل کار"));
        findViewById(R.id.favoritesButton).setOnClickListener(v -> showPlaces(true));
        findViewById(R.id.recentButton).setOnClickListener(v -> showRecent());
        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettingsMenu());
        intelligenceButton.setOnClickListener(v -> showIntelligenceModeDialog());
        findViewById(R.id.stopButton).setOnClickListener(v -> stopNavigation("مسیریابی متوقف شد."));
        notificationButton.setOnClickListener(v -> toggleBackgroundNavigation());
        findViewById(R.id.backupButton).setOnClickListener(v -> showBackupDialog());
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
        } else if (requestCode == REQ_IMPORT_BACKUP) {
            confirmRestore(uri);
        }
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
                setStatus("در حال آماده‌سازی سرویس گفتار GapGPT...");
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
        voiceButton.setText("در حال ارسال به GapGPT...");
        setStatus("صدا به GapGPT ارسال شد؛ در صورت خطا لیارا استفاده می‌شود.");
        onlineSpeechClient.stopAndTranscribe(new OnlineSpeechClient.TextCallback() {
            @Override public void onResult(String text) { runOnUiThread(() -> {
                restoreVoiceButton();
                if (text == null || text.trim().isEmpty()) setStatus("پاسخ صوتی خالی بود؛ دوباره ضبط کنید.");
                else handleVoiceText(text);
            }); }
            @Override public void onError(String message) { runOnUiThread(() -> {
                restoreVoiceButton();
                setStatus(message + " لطفاً اتصال و کلیدهای آنلاین را بررسی کنید.");
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
        intelligenceCoordinator.cancelAll();
        final long requestSequence = ++routeRequestSequence;
        if (!routeRepository.hasConfiguredProvider()) {
            setStatus("کلید مسیریابی نشان یا map.ir در این APK موجود نیست. GitHub Secrets را بررسی کنید.");
            return;
        }
        Location origin = locationTracker.getLastLocation();
        if (origin == null) {
            setStatus("برای شروع مسیر، GPS باید آماده باشد.");
            voicePlayer.announce("gps_lost", "موقعیت مکانی هنوز در دسترس نیست.");
            return;
        }
        setStatus("در حال دریافت مسیر به " + destination.name + "...");
        if (!isFullIntelligenceMode()) {
            voicePlayer.announce("searching_route", "در حال یافتن مسیر هستم.");
        }
        final double originLatitude = origin.getLatitude();
        final double originLongitude = origin.getLongitude();
        routeRepository.getRoute(originLatitude, originLongitude, destination.latitude, destination.longitude,
                route -> runOnUiThread(() -> {
                    if (requestSequence != routeRequestSequence) return;
                    placeStore.addRecent(destination);
                    tripStore.add(new TripRecord(destination.name, originLatitude, originLongitude, destination.latitude, destination.longitude,
                            route.distanceMeters, route.durationSeconds, System.currentTimeMillis()));
                    writeAutomaticBackup();
                    activeDestination = destination;
                    smartCompanion.start();
                    startBackgroundNavigation();
                    lastTrafficEtaSeconds = route.durationSeconds;
                    lastTrafficEtaMeasuredAt = System.currentTimeMillis();
                    navigationEngine.start(route, new NavigationEngine.Listener() {
                        @Override public void onInstruction(RouteStep step) {
                            runOnUiThread(() -> announceRouteStep(step));
                        }
                        @Override public void onOffRoute() {
                            runOnUiThread(() -> rerouteFromCurrentLocation());
                        }
                        @Override public void onArrived() {
                            runOnUiThread(() -> {
                                speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                                        "راننده به مقصد " + destination.name + " رسیده است. یک پیام فارسی کوتاه و طبیعی برای پایان سفر بگو.",
                                        "destination_arrived", "به مقصد رسیدید.", 12_000L);
                                setStatus("به " + activeDestination.name + " رسیدید.");
                                activeDestination = null;
                                smartCompanion.stop();
                                voiceHandler.removeCallbacks(trafficCheck);
                                stopBackgroundNavigation();
                            });
                        }
                    }, origin);
                    lastInstruction = "start_navigation";
                    lastInstructionText = "مسیر به " + destination.name + " آماده است.";
                    setStatus("مسیر آماده است. سرویس: " + route.providerName + "، فاصله تقریبی: " + route.distanceMeters + " متر");
                    speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                            "مسیر به " + destination.name + " آماده است. فاصله تقریبی " + route.distanceMeters
                                    + " متر و زمان تقریبی " + route.durationSeconds + " ثانیه است. یک پیام شروع سفر طبیعی، کوتاه و ایمن بگو.",
                            "start_navigation", "مسیر آماده است؛ با احتیاط حرکت کنید.", 15_000L);
                    navigationEngine.announceCurrentInstruction();
                    scheduleTrafficCheck();
                    refreshList();
                }),
                error -> runOnUiThread(() -> {
                    if (requestSequence != routeRequestSequence) return;
                    voicePlayer.announce("api_error", "در دریافت مسیر خطایی رخ داد.");
                    setStatus("خطا در دریافت مسیر: " + error);
                }));
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
                setStatus(onlineReady ? "کلیدهای آنلاین فعال شدند." : "کلید آنلاین دریافت نشد؛ حالت تشخیص گفتار گوشی فعال است.");
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
            @Override public void onFailure() { runOnUiThread(() -> setStatus("مختصات این مکان پیدا نشد. لینک کامل Google Maps یا نشان را اشتراک‌گذاری کنید.")); }
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
                        voicePlayer.speak(text);
                        setStatus("پاسخ آفلاین پخش شد.");
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
        if ("start_navigation".equals(clipName) && navigationEngine.isNavigating()) return;
        if (isFullIntelligenceMode()) {
            setStatus("\u062f\u0631 \u062d\u0627\u0644 \u0622\u0645\u0627\u062f\u0647 \u06a9\u0631\u062f\u0646 \u067e\u0627\u0633\u062e \u0635\u0648\u062a\u06cc \u0647\u0648\u0634\u0645\u0646\u062f...");
            final AtomicBoolean delivered = new AtomicBoolean(false);
            final long watchdogDelay = priority == DrivingIntelligenceCoordinator.Priority.SAFETY ? 2_000L : 3_750L;
            voiceHandler.postDelayed(() -> {
                if (!delivered.compareAndSet(false, true)) return;
                playDrivingFallback(clipName, fallback);
                setStatus("\u067e\u0627\u0633\u062e \u0645\u062f\u0644 \u0628\u0647\u200c\u0645\u0648\u0642\u0639 \u0646\u0631\u0633\u06cc\u062f\u061b \u0647\u0634\u062f\u0627\u0631 \u0622\u0641\u0644\u0627\u06cc\u0646 \u067e\u062e\u0634 \u0634\u062f.");
            }, watchdogDelay);
            intelligenceCoordinator.request(priority, prompt, drivingContext(), fallback, false, expiresInMs,
                    (id, text, online) -> runOnUiThread(() -> {
                        if (!delivered.compareAndSet(false, true)) return;
                        if (online) {
                            speakShort(text, clipName, fallback);
                        } else {
                            playDrivingFallback(clipName, fallback);
                            if (clipName != null) {
                            setStatus("\u0647\u0634\u062f\u0627\u0631 \u0622\u0641\u0644\u0627\u06cc\u0646 WAV \u067e\u062e\u0634 \u0634\u062f.");
                            } else {
                            setStatus("\u0647\u0634\u062f\u0627\u0631 \u0622\u0641\u0644\u0627\u06cc\u0646 \u067e\u062e\u0634 \u0634\u062f.");
                            }
                        }
                    }));
        } else if (clipName != null) {
            voicePlayer.announce(clipName, fallback);
        } else {
            voicePlayer.speak(fallback);
        }
    }

    private void playDrivingFallback(String clipName, String fallback) {
        onlineSpeechClient.stopPlayback();
        voicePlayer.interrupt();
        if (clipName != null) voicePlayer.announce(clipName, fallback);
        else voicePlayer.speak(fallback);
    }

    private void playPreparedOrRequest(String key, DrivingIntelligenceCoordinator.Priority priority, String prompt,
                                       String fallback, long expiresInMs) {
        String prepared = intelligenceCoordinator.consumePrepared(key);
        if (prepared != null) speakShort(prepared);
        else requestIntelligence(priority, prompt, fallback, false, expiresInMs);
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
                    "حدود دو ساعت رانندگی کرده‌اید؛ در اولین محل امن کمی استراحت کنید.", 25_000L);
            return;
        }
        if ("fatigue".equals(event)) {
            requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "هشدار ایمنی غیرپزشکی: " + facts + " در یک جمله کوتاه و آرام پیشنهاد توقف در محل امن بده؛ ادعای تشخیص پزشکی نکن.",
                    "رانندگی پیوسته طولانی شده است؛ در اولین محل امن توقف و استراحت کنید.", false, 25_000L);
            return;
        }
        switch (event) {
            case "speed":
                voicePlayer.announce("speeding_danger", "لطفا سرعت خود را کم کنید.");
                break;
            case "slow":
                voicePlayer.announce("heavy_traffic", "به نظر می‌رسد در مسیر ترافیک سنگینی وجود دارد.");
                break;
            case "traffic_reroute":
                rerouteForTraffic();
                break;
            case "fuel_check":
                voicePlayer.speak("حدود نود دقیقه از شروع سفر گذشته است. اگر نیاز به سوخت‌گیری دارید، بگویید پمپ بنزین.");
                break;
            case "rest":
                askAi("یادآوری ایمنی: بیش از دو ساعت رانندگی پیوسته بدون توقف ده دقیقه‌ای ثبت شده است. یک هشدار فارسی بسیار کوتاه، آرام و عملی برای پیشنهاد استراحت بگو.");
                break;
            case "fatigue":
                askAi("هشدار ایمنی غیرپزشکی: بیش از سه ساعت رانندگی پیوسته بدون توقف ده دقیقه‌ای ثبت شده است. در یک جمله کوتاه و آرام پیشنهاد توقف در محل امن بده؛ ادعای تشخیص پزشکی نکن.");
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
        startNavigation(activeDestination);
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
            @Override public void onPlayed() { runOnUiThread(() -> setStatus("پاسخ هوشمند با TTS آنلاین پخش شد.")); }
            @Override public void onError() { runOnUiThread(() -> {
                if (fallbackClip != null) {
                    voicePlayer.announce(fallbackClip, fallbackText);
                    setStatus("\u0635\u062f\u0627\u06cc \u0622\u0646\u0644\u0627\u06cc\u0646 \u062f\u0631 \u062f\u0633\u062a\u0631\u0633 \u0646\u06cc\u0633\u062a\u061b \u0647\u0634\u062f\u0627\u0631 WAV \u067e\u062e\u0634 \u0634\u062f.");
                    return;
                }
                voicePlayer.speak(finalAnswer);
                setStatus("متن مدل با صدای محلی پخش شد.");
            }); }
        });
    }

    private String cleanDestinationText(String text) {
        return text == null ? "" : text.replaceFirst("^(برو|به|مسیریابی)\\s+", "").trim();
    }

    private String drivingContext() {
        StringBuilder context = new StringBuilder();
        if (activeDestination != null) context.append("مقصد فعلی: ").append(activeDestination.name).append(". ");
        ArrayList<SavedPlace> places = new ArrayList<>(placeStore.allPlaces());
        if (!places.isEmpty()) {
            context.append("مکان‌های ذخیره‌شده: ");
            for (int i = 0; i < places.size() && i < 6; i++) context.append(places.get(i).name).append(i == Math.min(places.size(), 6) - 1 ? ". " : "، ");
        }
        java.util.List<TripRecord> trips = tripStore.recent(5);
        if (!trips.isEmpty()) {
            context.append("مقصدهای سفر اخیر: ");
            for (int i = 0; i < trips.size(); i++) context.append(trips.get(i).destinationName).append(i == trips.size() - 1 ? "." : "، ");
        }
        return context.toString();
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
                .setItems(new String[]{"تنظیمات صدا", "هوشمندی رانندگی: " + mode}, (dialog, which) -> {
                    if (which == 0) cycleVolume(); else showIntelligenceModeDialog();
                }).show();
    }

    private void showIntelligenceModeDialog() {
        DrivingIntelligenceCoordinator.Mode current = readIntelligenceMode();
        String[] options = {"هوشمند اقتصادی (پیشنهادی)", "هوشمند کامل"};
        new AlertDialog.Builder(this).setTitle("هوشمندی رانندگی")
                .setSingleChoiceItems(options, current == DrivingIntelligenceCoordinator.Mode.FULL ? 1 : 0, (dialog, which) -> {
                    DrivingIntelligenceCoordinator.Mode selected = which == 1
                            ? DrivingIntelligenceCoordinator.Mode.FULL : DrivingIntelligenceCoordinator.Mode.ECONOMY;
                    getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit()
                            .putString(KEY_INTELLIGENCE_MODE, selected.name()).apply();
                    intelligenceCoordinator.setMode(selected);
                    intelligenceCoordinator.cancelAll();
                    writeAutomaticBackup();
                    refreshIntelligenceButton();
                    setStatus(selected == DrivingIntelligenceCoordinator.Mode.FULL
                            ? "حالت هوشمند کامل فعال شد." : "حالت هوشمند اقتصادی فعال شد.");
                    dialog.dismiss();
                }).setNegativeButton("انصراف", null).show();
    }

    private void refreshIntelligenceButton() {
        if (intelligenceButton == null) return;
        intelligenceButton.setText(readIntelligenceMode() == DrivingIntelligenceCoordinator.Mode.FULL
                ? "هوشمندی رانندگی: کامل" : "هوشمندی رانندگی: اقتصادی");
    }

    private void cycleVolume() {
        final String[] choices = {"افزایش صدای راهنما", "کاهش صدای راهنما"};
        new AlertDialog.Builder(this).setTitle("تنظیمات صدا").setItems(choices, (d, which) -> {
            if (which == 0) { voicePlayer.increaseVolume(); voicePlayer.announce("voice_louder", "صدای راهنما بیشتر شد."); setStatus("صدای راهنما بیشتر شد."); }
            else { voicePlayer.decreaseVolume(); voicePlayer.announce("voice_lower", "صدای راهنما کمتر شد."); setStatus("صدای راهنما کمتر شد."); }
        }).show();
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
        final EditText input = new EditText(this);
        input.setText(place.name);
        new AlertDialog.Builder(this)
                .setTitle("ویرایش یا مسیریابی")
                .setView(input)
                .setPositiveButton("مسیریابی", (d, w) -> startNavigation(place))
                .setNeutralButton("ذخیره نام", (d, w) -> {
                    placeStore.upsert(new SavedPlace(input.getText().toString(), place.kind, place.latitude, place.longitude, place.address, System.currentTimeMillis(), place.favorite));
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
        routeRepository.getRoute(location.getLatitude(), location.getLongitude(), destination.latitude, destination.longitude,
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
        navigationEngine.start(route, new NavigationEngine.Listener() {
            @Override public void onInstruction(RouteStep step) { runOnUiThread(() -> announceRouteStep(step)); }
            @Override public void onOffRoute() { runOnUiThread(() -> rerouteFromCurrentLocation()); }
            @Override public void onArrived() { runOnUiThread(() -> {
                speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                        "راننده به مقصد " + destination.name + " رسیده است. یک پیام فارسی کوتاه و طبیعی برای پایان سفر بگو.",
                        "destination_arrived", "به مقصد رسیدید.", 12_000L);
                setStatus("به " + destination.name + " رسیدید.");
                activeDestination = null;
                smartCompanion.stop();
                voiceHandler.removeCallbacks(trafficCheck);
                stopBackgroundNavigation();
            }); }
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
        startNavigation(activeDestination);
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

    private void stopNavigation(String message) {
        navigationEngine.stop();
        smartCompanion.stop();
        intelligenceCoordinator.cancelAll();
        voiceHandler.removeCallbacks(trafficCheck);
        activeDestination = null;
        stopBackgroundNavigation();
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "مسیریابی متوقف شده است. یک پیام فارسی کوتاه و طبیعی برای راننده بگو.",
                "stop_navigation", message, 12_000L);
        setStatus(message);
    }

    private void registerNavigationReceiver() {
        IntentFilter filter = new IntentFilter(NavigationForegroundService.ACTION_STOP_BROADCAST);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(navigationStopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(navigationStopReceiver, filter);
    }

    private void announceRouteStep(RouteStep step) {
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
        if (lower.contains("left") || text.contains("چپ")) lastInstruction = "turn_left";
        else if (lower.contains("right") || text.contains("راست")) lastInstruction = "turn_right";
        else if (lower.contains("arriv") || text.contains("مقصد")) lastInstruction = "destination_arrived";
        else lastInstruction = "continue_route";
        lastInstructionText = text;
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "دستور مسیریابی فعلی این است: " + text + ". آن را در یک جمله فارسی کوتاه، طبیعی و مناسب رانندگی بیان کن.",
                lastInstruction, text, 10_000L);
        setStatus(text);
    }

    @Override protected void onDestroy() {
        voiceHandler.removeCallbacks(automaticStop);
        voiceHandler.removeCallbacks(trafficCheck);
        onlineSpeechClient.cancelRecording();
        localSpeechRecognizer.destroy();
        intelligenceCoordinator.shutdown();
        smartCompanion.stop();
        voicePlayer.shutdown();
        unregisterReceiver(navigationStopReceiver);
        navigationEngine.stop();
        stopBackgroundNavigation();
        locationTracker.stop();
        super.onDestroy();
    }
}
