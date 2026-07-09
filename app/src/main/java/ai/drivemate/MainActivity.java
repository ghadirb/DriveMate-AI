package ai.drivemate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

import ai.drivemate.ai.AiAssistant;
import ai.drivemate.location.DeviceLocationTracker;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.storage.PlaceStore;
import ai.drivemate.voice.Command;
import ai.drivemate.voice.VoiceCommandParser;
import ai.drivemate.voice.VoiceGuidancePlayer;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 10;
    private static final int REQ_SPEECH = 20;

    private TextView statusText;
    private TextView listText;
    private PlaceStore placeStore;
    private VoiceGuidancePlayer voicePlayer;
    private DeviceLocationTracker locationTracker;
    private RouteRepository routeRepository;
    private VoiceCommandParser commandParser;
    private AiAssistant aiAssistant;
    private String lastInstruction = "start_navigation";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        listText = findViewById(R.id.listText);
        placeStore = new PlaceStore(this);
        voicePlayer = new VoiceGuidancePlayer(this);
        locationTracker = new DeviceLocationTracker(this);
        routeRepository = new RouteRepository(
                new NeshanRoutingProvider(BuildConfig.NESHAN_API_KEY),
                new MapIrRoutingProvider(BuildConfig.MAPIR_API_KEY)
        );
        commandParser = new VoiceCommandParser();
        aiAssistant = new AiAssistant(BuildConfig.AI_API_KEY);

        wireButtons();
        requestCorePermissions();
        refreshList();
        voicePlayer.play("welcome");
    }

    private void wireButtons() {
        findViewById(R.id.voiceButton).setOnClickListener(v -> startVoiceInput());
        findViewById(R.id.saveButton).setOnClickListener(v -> saveCurrentPlace("مکان جدید", "custom"));
        findViewById(R.id.homeButton).setOnClickListener(v -> navigateToKnownPlace("home"));
        findViewById(R.id.workButton).setOnClickListener(v -> navigateToKnownPlace("work"));
        findViewById(R.id.favoritesButton).setOnClickListener(v -> showPlaces(true));
        findViewById(R.id.recentButton).setOnClickListener(v -> showRecent());
        findViewById(R.id.settingsButton).setOnClickListener(v -> cycleVolume());
    }

    private void requestCorePermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
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

    private void startVoiceInput() {
        voicePlayer.play("listening");
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "فرمان رانندگی را بگویید");
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception ex) {
            setStatus("تشخیص گفتار روی این دستگاه فعال نیست.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                handleVoiceText(results.get(0));
            }
        }
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
            case VOLUME_UP:
                voicePlayer.increaseVolume();
                voicePlayer.play("voice_louder");
                setStatus("صدای راهنما بیشتر شد.");
                break;
            case VOLUME_DOWN:
                voicePlayer.decreaseVolume();
                voicePlayer.play("voice_lower");
                setStatus("صدای راهنما کمتر شد.");
                break;
            case REPEAT:
                voicePlayer.play(lastInstruction);
                break;
            case ASK_AI:
                askAi(text);
                break;
            default:
                voicePlayer.play("command_unknown");
                setStatus("فرمان را متوجه نشدم.");
        }
    }

    private void saveCurrentPlace(String name, String kind) {
        Location location = locationTracker.getLastLocation();
        if (location == null) {
            setStatus("هنوز موقعیت GPS آماده نیست.");
            voicePlayer.play("gps_lost");
            return;
        }
        SavedPlace place = new SavedPlace(
                name,
                kind,
                location.getLatitude(),
                location.getLongitude(),
                "آدرس تقریبی: " + String.format(Locale.US, "%.5f, %.5f", location.getLatitude(), location.getLongitude()),
                System.currentTimeMillis(),
                true
        );
        placeStore.upsert(place);
        voicePlayer.play("home".equals(kind) ? "home_saved" : "work".equals(kind) ? "work_saved" : "place_saved");
        setStatus(name + " ذخیره شد.");
        refreshList();
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
        Location origin = locationTracker.getLastLocation();
        if (origin == null) {
            setStatus("برای شروع مسیر، GPS باید آماده باشد.");
            voicePlayer.play("gps_lost");
            return;
        }
        setStatus("در حال دریافت مسیر به " + destination.name + "...");
        voicePlayer.play("searching_route");
        routeRepository.getRoute(origin.getLatitude(), origin.getLongitude(), destination.latitude, destination.longitude,
                route -> runOnUiThread(() -> {
                    placeStore.addRecent(destination);
                    lastInstruction = "start_navigation";
                    voicePlayer.play("start_navigation");
                    setStatus("مسیر آماده است. سرویس: " + route.providerName + "، فاصله تقریبی: " + route.distanceMeters + " متر");
                    refreshList();
                }),
                error -> runOnUiThread(() -> {
                    voicePlayer.play("api_error");
                    setStatus("خطا در دریافت مسیر: " + error);
                }));
    }

    private void askAi(String question) {
        setStatus("در حال آماده کردن پاسخ هوشمند...");
        aiAssistant.answer(question, answer -> runOnUiThread(() -> setStatus(answer)));
    }

    private void cycleVolume() {
        voicePlayer.increaseVolume();
        setStatus("تنظیم صدا تغییر کرد.");
        voicePlayer.play("voice_louder");
    }

    private void showPlaces(boolean favoritesOnly) {
        StringBuilder builder = new StringBuilder();
        for (SavedPlace place : placeStore.allPlaces()) {
            if (!favoritesOnly || place.favorite) {
                builder.append("• ").append(place.name).append(" - ").append(place.address).append("\n");
            }
        }
        listText.setText(builder.length() == 0 ? "هنوز مقصد محبوبی ذخیره نشده است." : builder.toString());
    }

    private void showRecent() {
        StringBuilder builder = new StringBuilder();
        for (SavedPlace place : placeStore.recentPlaces()) {
            builder.append("• ").append(place.name).append("\n");
        }
        listText.setText(builder.length() == 0 ? "هنوز مقصد اخیری وجود ندارد." : builder.toString());
    }

    private void refreshList() {
        showPlaces(false);
    }

    private void setStatus(String message) {
        statusText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
