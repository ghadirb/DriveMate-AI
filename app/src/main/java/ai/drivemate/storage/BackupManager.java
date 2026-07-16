package ai.drivemate.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.TripRecord;

/** A portable, user-owned JSON backup. No data is uploaded without a user-selected destination. */
public class BackupManager {
    public static final String MIME_TYPE = "application/json";
    private static final String FILE_PREFIX = "drivemate-backup-";
    private static final String AUTO_FILE = "drivemate-auto-backup.json";
    private final Context context;
    private final PlaceStore placeStore;
    private final TripStore tripStore;

    public BackupManager(Context context, PlaceStore placeStore, TripStore tripStore) {
        this.context = context.getApplicationContext();
        this.placeStore = placeStore;
        this.tripStore = tripStore;
    }

    public synchronized File writeAutomaticSnapshot() throws Exception {
        File documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documents == null) throw new IllegalStateException("External app storage is unavailable");
        File directory = new File(documents, "backups");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create backup directory");
        File target = new File(directory, AUTO_FILE);
        writeFile(target);
        return target;
    }

    public synchronized File createShareSnapshot() throws Exception {
        File directory = new File(context.getCacheDir(), "shared-backups");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create backup directory");
        File target = new File(directory, suggestedFileName());
        writeFile(target);
        return target;
    }

    public String suggestedFileName() {
        return FILE_PREFIX + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json";
    }

    public synchronized void exportTo(Uri destination) throws Exception {
        try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IllegalStateException("Cannot open backup destination");
            output.write(snapshot().toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    public synchronized void restoreFrom(Uri source) throws Exception {
        StringBuilder content = new StringBuilder();
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IllegalArgumentException("Cannot open backup file");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) content.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
        }
        restore(new JSONObject(content.toString()));
    }

    private void writeFile(File target) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(snapshot().toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("Cannot replace backup");
        if (!temporary.renameTo(target)) throw new IllegalStateException("Cannot finalize backup");
    }

    private JSONObject snapshot() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "DriveMateBackup");
        root.put("version", 1);
        root.put("createdAt", System.currentTimeMillis());
        root.put("places", placesToJson(placeStore.allPlaces()));
        root.put("recentPlaces", placesToJson(placeStore.recentPlaces()));
        root.put("trips", tripsToJson(tripStore.recent(60)));
        SharedPreferences settings = context.getSharedPreferences("drivemate_settings", Context.MODE_PRIVATE);
        root.put("settings", new JSONObject().put("background_navigation", settings.getBoolean("background_navigation", true)));
        return root;
    }

    private void restore(JSONObject root) throws Exception {
        if (!"DriveMateBackup".equals(root.optString("format")) || root.optInt("version") != 1) {
            throw new IllegalArgumentException("Unsupported DriveMate backup file");
        }
        List<SavedPlace> places = placesFromJson(root.optJSONArray("places"));
        List<SavedPlace> recent = placesFromJson(root.optJSONArray("recentPlaces"));
        List<TripRecord> trips = tripsFromJson(root.optJSONArray("trips"));
        placeStore.restore(places, recent);
        tripStore.restore(trips);
        JSONObject settings = root.optJSONObject("settings");
        if (settings != null) {
            context.getSharedPreferences("drivemate_settings", Context.MODE_PRIVATE).edit()
                    .putBoolean("background_navigation", settings.optBoolean("background_navigation", true)).apply();
        }
    }

    private JSONArray placesToJson(List<SavedPlace> places) throws Exception {
        JSONArray array = new JSONArray();
        for (SavedPlace place : places) array.put(place.toJson());
        return array;
    }

    private JSONArray tripsToJson(List<TripRecord> trips) throws Exception {
        JSONArray array = new JSONArray();
        for (TripRecord trip : trips) array.put(trip.toJson());
        return array;
    }

    private List<SavedPlace> placesFromJson(JSONArray values) {
        List<SavedPlace> result = new ArrayList<>();
        if (values == null) return result;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null) result.add(SavedPlace.fromJson(value));
        }
        return result;
    }

    private List<TripRecord> tripsFromJson(JSONArray values) {
        List<TripRecord> result = new ArrayList<>();
        if (values == null) return result;
        for (int i = 0; i < values.length() && i < 60; i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null) result.add(TripRecord.fromJson(value));
        }
        return result;
    }
}
