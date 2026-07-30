package ai.drivemate.weather;

import org.json.JSONArray;
import org.json.JSONObject;

import ai.drivemate.routing.RoutingHttp;

/**
 * Reads current fog/visibility and wind conditions near the driver's live GPS position from
 * OpenWeatherMap's Current Weather Data API. This is a live third-party weather feed, not an
 * on-device sensor: an outage, missing key, or empty response simply disables this one warning
 * type without affecting anything else (route hazards, speed limits, voice guidance keep working).
 */
public final class WeatherHazardProvider {

    /** Roughly Beaufort 6 ("strong breeze") and above - the point where handling a light vehicle
     *  starts to be noticeably affected, especially on open highway or bridge sections. */
    private static final double STRONG_WIND_METERS_PER_SECOND = 10.5d;
    private static final double FOG_VISIBILITY_METERS = 1000d;

    private final String apiKey;

    public WeatherHazardProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** Fetches once for the given point; the caller decides how often to poll (see MainActivity's
     *  weather-check cadence) so this never runs on every GPS sample. */
    public Snapshot fetch(double latitude, double longitude) throws Exception {
        if (!hasKey()) throw new IllegalStateException("OpenWeatherMap key is not configured.");
        String url = "https://api.openweathermap.org/data/2.5/weather?lat=" + latitude + "&lon=" + longitude
                + "&units=metric&lang=fa&appid=" + apiKey;
        JSONObject body = RoutingHttp.getJson(url);
        JSONArray weatherArray = body.optJSONArray("weather");
        int conditionId = 0;
        String description = "";
        if (weatherArray != null && weatherArray.length() > 0) {
            JSONObject first = weatherArray.optJSONObject(0);
            if (first != null) {
                conditionId = first.optInt("id", 0);
                description = first.optString("description", "");
            }
        }
        double visibilityMeters = body.optDouble("visibility", 10_000d);
        JSONObject wind = body.optJSONObject("wind");
        double windSpeedMs = wind == null ? 0d : wind.optDouble("speed", 0d);
        boolean fogLikely = isReducedVisibilityConditionId(conditionId) || visibilityMeters < FOG_VISIBILITY_METERS;
        boolean strongWindLikely = windSpeedMs >= STRONG_WIND_METERS_PER_SECOND;
        return new Snapshot(fogLikely, strongWindLikely, windSpeedMs, visibilityMeters, description);
    }

    /** OpenWeatherMap's "Atmosphere" condition-code group: mist(701), smoke(711), haze(721),
     *  dust whirls(731), fog(741), sand(751), dust(761), volcanic ash(762), squalls(771). All of
     *  these reduce visibility enough to warrant the same "مه یا دید کم" warning. */
    private boolean isReducedVisibilityConditionId(int id) {
        return id == 701 || id == 711 || id == 721 || id == 731 || id == 741
                || id == 751 || id == 761 || id == 762 || id == 771;
    }

    public static final class Snapshot {
        public final boolean fogLikely;
        public final boolean strongWindLikely;
        public final double windSpeedMetersPerSecond;
        public final double visibilityMeters;
        public final String description;

        Snapshot(boolean fogLikely, boolean strongWindLikely, double windSpeedMetersPerSecond,
                 double visibilityMeters, String description) {
            this.fogLikely = fogLikely;
            this.strongWindLikely = strongWindLikely;
            this.windSpeedMetersPerSecond = windSpeedMetersPerSecond;
            this.visibilityMeters = visibilityMeters;
            this.description = description == null ? "" : description;
        }
    }
}
