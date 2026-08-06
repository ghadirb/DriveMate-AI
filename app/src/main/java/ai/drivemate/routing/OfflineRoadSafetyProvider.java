package ai.drivemate.routing;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteSafetyAlert;

/**
 * Primary, offline source for mapped road-safety points. The bundled database is copied to the
 * app database directory on first use, so SQLite can use its lat/lon index without depending on
 * a network connection. Callers may use Overpass only to fill feature types this dataset lacks.
 */
public final class OfflineRoadSafetyProvider {
    private static final String TAG = "DriveMateOfflineSafety";
    private static final String ASSET_NAME = "road_safety_final.db";
    private static final String DATABASE_NAME = "road_safety_final.db";
    private static final double QUERY_CORRIDOR_METERS = 140d;
    private static final double ROUTE_CORRIDOR_METERS = 100d;
    private static final double QUERY_SEGMENT_METERS = 750d;
    private static final String[] SUPPORTED_TYPES = {
            "railway_crossing", "speed_camera", "speed_bump", "stop"
    };

    private final Context applicationContext;
    private final Object databaseLock = new Object();

    public OfflineRoadSafetyProvider(Context context) {
        applicationContext = context.getApplicationContext();
    }

    /**
     * Finds supported alerts close to the route. Query boxes are kept short rather than using one
     * route-wide bounding box, which keeps lat/lon indexed scans selective on long trips.
     */
    public List<RouteSafetyAlert> safetyAlertsNear(List<RoutePoint> geometry) throws IOException {
        if (geometry == null || geometry.size() < 2) return Collections.emptyList();
        SQLiteDatabase database = openDatabase();
        ArrayList<RouteSafetyAlert> results = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        try {
            for (QueryBox box : queryBoxesFor(geometry)) {
                queryBox(database, box, geometry, results, seenIds);
            }
        } finally {
            database.close();
        }
        return results;
    }

    private SQLiteDatabase openDatabase() throws IOException {
        synchronized (databaseLock) {
            File databaseFile = applicationContext.getDatabasePath(DATABASE_NAME);
            if (!databaseFile.exists() || databaseFile.length() == 0L) copyBundledDatabase(databaseFile);
            return SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        }
    }

    private void copyBundledDatabase(File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create offline safety database directory.");
        }
        File temporary = new File(destination.getPath() + ".tmp");
        try (InputStream input = applicationContext.getAssets().open(ASSET_NAME);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (IOException exception) {
            if (temporary.exists()) temporary.delete();
            throw exception;
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Could not replace offline safety database.");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not install offline safety database.");
        }
    }

    private void queryBox(SQLiteDatabase database, QueryBox box, List<RoutePoint> geometry,
                          List<RouteSafetyAlert> results, Set<Long> seenIds) {
        StringBuilder placeholders = new StringBuilder();
        String[] selectionArgs = new String[4 + SUPPORTED_TYPES.length];
        selectionArgs[0] = String.valueOf(box.minLatitude);
        selectionArgs[1] = String.valueOf(box.maxLatitude);
        selectionArgs[2] = String.valueOf(box.minLongitude);
        selectionArgs[3] = String.valueOf(box.maxLongitude);
        for (int index = 0; index < SUPPORTED_TYPES.length; index++) {
            if (index > 0) placeholders.append(',');
            placeholders.append('?');
            selectionArgs[4 + index] = SUPPORTED_TYPES[index];
        }
        Cursor cursor = database.query("alerts",
                new String[]{"id", "type", "lat", "lon"},
                "lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND type IN (" + placeholders + ")",
                selectionArgs, null, null, null);
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                if (!seenIds.add(id)) continue;
                String type = cursor.getString(1);
                double latitude = cursor.getDouble(2);
                double longitude = cursor.getDouble(3);
                RouteSafetyAlert.Type alertType = mapType(type);
                if (alertType == null || !isNearRoute(geometry, latitude, longitude)) continue;
                results.add(new RouteSafetyAlert(alertType, latitude, longitude, 0d));
            }
        } finally {
            cursor.close();
        }
    }

    private List<QueryBox> queryBoxesFor(List<RoutePoint> geometry) {
        ArrayList<QueryBox> boxes = new ArrayList<>();
        RoutePoint first = geometry.get(0);
        QueryBox current = new QueryBox(first.latitude, first.longitude);
        RoutePoint previous = first;
        double segmentLength = 0d;
        for (int index = 1; index < geometry.size(); index++) {
            RoutePoint point = geometry.get(index);
            segmentLength += distanceMeters(previous.latitude, previous.longitude, point.latitude, point.longitude);
            current.include(point.latitude, point.longitude);
            if (segmentLength >= QUERY_SEGMENT_METERS) {
                boxes.add(current.expand(QUERY_CORRIDOR_METERS));
                current = new QueryBox(point.latitude, point.longitude);
                segmentLength = 0d;
            }
            previous = point;
        }
        boxes.add(current.expand(QUERY_CORRIDOR_METERS));
        return boxes;
    }

    private RouteSafetyAlert.Type mapType(String rawType) {
        if ("railway_crossing".equals(rawType)) return RouteSafetyAlert.Type.RAILWAY_CROSSING;
        if ("speed_camera".equals(rawType)) return RouteSafetyAlert.Type.SPEED_CAMERA;
        if ("speed_bump".equals(rawType)) return RouteSafetyAlert.Type.SPEED_BUMP;
        if ("stop".equals(rawType)) return RouteSafetyAlert.Type.STOP_SIGN;
        Log.w(TAG, "Ignoring unsupported offline alert type: " + rawType);
        return null;
    }

    private boolean isNearRoute(List<RoutePoint> geometry, double latitude, double longitude) {
        for (int index = 1; index < geometry.size(); index++) {
            RoutePoint from = geometry.get(index - 1);
            RoutePoint to = geometry.get(index);
            if (distanceToSegmentMeters(latitude, longitude, from, to) <= ROUTE_CORRIDOR_METERS) return true;
        }
        return false;
    }

    /** Local tangent-plane projection is accurate enough for the short route segments involved. */
    private static double distanceToSegmentMeters(double latitude, double longitude, RoutePoint from, RoutePoint to) {
        double latitudeScale = 111_320d;
        double longitudeScale = latitudeScale * Math.max(0.1d, Math.cos(Math.toRadians(latitude)));
        double segmentX = (to.longitude - from.longitude) * longitudeScale;
        double segmentY = (to.latitude - from.latitude) * latitudeScale;
        double pointX = (longitude - from.longitude) * longitudeScale;
        double pointY = (latitude - from.latitude) * latitudeScale;
        double segmentSquared = segmentX * segmentX + segmentY * segmentY;
        if (segmentSquared == 0d) return Math.hypot(pointX, pointY);
        double projection = (pointX * segmentX + pointY * segmentY) / segmentSquared;
        projection = Math.max(0d, Math.min(1d, projection));
        return Math.hypot(pointX - projection * segmentX, pointY - projection * segmentY);
    }

    private static double distanceMeters(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double a = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371000d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    private static final class QueryBox {
        private double minLatitude;
        private double maxLatitude;
        private double minLongitude;
        private double maxLongitude;

        QueryBox(double latitude, double longitude) {
            minLatitude = maxLatitude = latitude;
            minLongitude = maxLongitude = longitude;
        }

        void include(double latitude, double longitude) {
            minLatitude = Math.min(minLatitude, latitude);
            maxLatitude = Math.max(maxLatitude, latitude);
            minLongitude = Math.min(minLongitude, longitude);
            maxLongitude = Math.max(maxLongitude, longitude);
        }

        QueryBox expand(double meters) {
            double latitudePad = meters / 111_320d;
            double meanLatitude = (minLatitude + maxLatitude) / 2d;
            double longitudePad = meters / (111_320d * Math.max(0.1d,
                    Math.cos(Math.toRadians(meanLatitude))));
            QueryBox expanded = new QueryBox(minLatitude - latitudePad, minLongitude - longitudePad);
            expanded.include(maxLatitude + latitudePad, maxLongitude + longitudePad);
            return expanded;
        }
    }
}
