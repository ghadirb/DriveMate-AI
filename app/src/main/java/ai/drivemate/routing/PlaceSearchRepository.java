package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ai.drivemate.model.SavedPlace;

/**
 * Combines both providers and ranks Persian-normalized exact name/address matches before
 * merely nearby fuzzy matches. This prevents an unrelated result from hiding a village search.
 */
public class PlaceSearchRepository {
    public interface SuccessCallback { void onSuccess(SavedPlace place); }
    public interface SearchCallback { void onSuccess(List<SavedPlace> places); }
    public interface ErrorCallback { void onError(String message); }

    private final NeshanRoutingProvider neshan;
    private final MapIrSearchProvider mapir;
    private final TomTomPoiProvider tomtom;
    private final OverpassPoiProvider overpass;
    private final NominatimSearchProvider nominatim;

    public PlaceSearchRepository(NeshanRoutingProvider neshan, MapIrRoutingProvider mapIrRoutingProvider,
                                  String tomtomApiKey) {
        this.neshan = neshan;
        this.mapir = new MapIrSearchProvider(mapIrRoutingProvider);
        this.tomtom = new TomTomPoiProvider(tomtomApiKey);
        this.overpass = new OverpassPoiProvider();
        this.nominatim = new NominatimSearchProvider();
    }

    public void setTomTomApiKey(String apiKey) { tomtom.setApiKey(apiKey); }
    public void setTomTomEnabled(boolean enabled) { tomtom.setEnabled(enabled); }

    /** Logs the running result count right after each provider stage runs, so a logcat capture
     *  shows exactly which provider(s) actually contributed to a given search - log-only, does
     *  not affect the search result itself. */
    private void logStage(String stage, int runningCount) {
        android.util.Log.i("DriveMateSearch", "stage=" + stage + " runningResultCount=" + runningCount);
    }

    public void search(String term, double latitude, double longitude, SuccessCallback success, ErrorCallback error) {
        searchAll(term, latitude, longitude, places -> success.onSuccess(places.get(0)), error);
    }

    public void searchAll(String term, double latitude, double longitude, SearchCallback success, ErrorCallback error) {
        new Thread(() -> {
            ArrayList<SavedPlace> results = new ArrayList<>();
            ArrayList<String> failures = new ArrayList<>();

            if (isNearbyPoiQuery(term)) {
                searchNearbyPoi(term, latitude, longitude, results, failures);
                rankNearby(results, latitude, longitude);
                // Matches Overpass's own widened item cap (60) - the old 30 ceiling here silently
                // discarded half of what a wide-radius OSM-first search could actually return.
                if (!results.isEmpty()) {
                    success.onSuccess(results.subList(0, Math.min(60, results.size())));
                } else {
                    error.onError("مکان نزدیکی پیدا نشد. دوباره تلاش کنید.");
                }
                return;
            }

            // Free nationwide search is first; paid providers are reserved for sparse OSM results.
            try { addUnique(results, nominatim.search(term, latitude, longitude)); }
            catch (Exception exception) { failures.add("OpenStreetMap (Nominatim) search: " + messageOf(exception)); }
            logStage("nominatim", results.size());
            if (results.size() < 5) {
                try { addUnique(results, overpass.searchNearby(term, latitude, longitude)); }
                catch (Exception exception) { failures.add("OpenStreetMap nearby: " + messageOf(exception)); }
                logStage("overpass", results.size());
            }
            // map.ir next: it already runs on the routing key you're paying for regardless, so
            // this costs nothing extra beyond what's already being spent on navigation - it's
            // tried before Neshan/TomTom, which are separate paid keys with their own quotas.
            if (results.size() < 3 && mapir.isConfigured()) {
                try { addUnique(results, mapir.search(term, latitude, longitude)); }
                catch (Exception exception) { failures.add("map.ir search: " + messageOf(exception)); }
                logStage("mapir", results.size());
            }
            if (results.size() < 3) {
                try { addUnique(results, searchNeshanGeocoding(term)); }
                catch (Exception exception) { failures.add("Neshan geocoding: " + messageOf(exception)); }
                logStage("neshan_geocoding", results.size());
                if (results.size() < 3) {
                    try { addUnique(results, searchNeshan(term, latitude, longitude)); }
                    catch (Exception exception) { failures.add("Neshan search: " + messageOf(exception)); }
                    logStage("neshan_nearby", results.size());
                }
                if (results.size() < 3) {
                    try { addUnique(results, searchNeshan(term, 32.4279d, 53.6880d)); }
                    catch (Exception exception) { failures.add("Neshan Iran-wide search: " + messageOf(exception)); }
                    logStage("neshan_iran_wide", results.size());
                }
            }
            if (results.size() < 5 && tomtom.isConfigured()) {
                try { addUnique(results, tomtom.searchNearby(term, latitude, longitude)); }
                catch (Exception exception) { failures.add("TomTom nearby: " + messageOf(exception)); }
                logStage("tomtom", results.size());
            }
            if (!failures.isEmpty()) {
                android.util.Log.w("DriveMateSearch", "provider failures: " + join(failures));
            }
            rank(results, term, latitude, longitude);
            results = keepBestMatchTier(results, term);
            if (!results.isEmpty()) success.onSuccess(results.subList(0, Math.min(12, results.size())));
            else error.onError("مکان موردنظر پیدا نشد. نام یا آدرس را دقیق‌تر وارد کنید.");
        }).start();
    }

    /**
     * Category queries need a different policy from a named address. Keeping this local avoids
     * a handful of country-wide results (for example six fuel stations) hiding nearby stations.
     *
     * OpenStreetMap (Overpass + Nominatim) needs no API key, has no per-key quota, and is not
     * subject to Neshan/map.ir's business-registration bias (denser in some provinces, e.g.
     * Tehran/central-west, than others, e.g. Mashhad/the northeast) - so it is tried FIRST for
     * every category search. Neshan and map.ir are only queried afterward, and only if OSM's own
     * combined results are still thin: this skips their API-key usage entirely on the common case
     * where OSM alone already has enough, and avoids waiting on their failure/timeout (Neshan's
     * key can reject with HTTP 485, map.ir's endpoint can be unreachable for minutes at a time -
     * both seen in the field) when they were never going to be needed anyway.
     */
    private static final int OSM_SUFFICIENT_RESULT_COUNT = 15;

    private void searchNearbyPoi(String term, double latitude, double longitude, List<SavedPlace> results,
                                 List<String> failures) {
        ProviderOutcome overpassOutcome = new ProviderOutcome();
        ProviderOutcome nominatimOutcome = new ProviderOutcome();
        Thread overpassThread = runProvider(() -> overpass.searchNearby(term, latitude, longitude), overpassOutcome);
        Thread nominatimThread = runProvider(() -> nominatim.search(term, latitude, longitude), nominatimOutcome);
        joinQuietly(overpassThread);
        joinQuietly(nominatimThread);
        mergeOutcome(results, failures, overpassOutcome, "OpenStreetMap nearby");
        mergeOutcome(results, failures, nominatimOutcome, "OpenStreetMap (Nominatim) nearby");
        logStage("nearby_osm", results.size());

        if (results.size() < OSM_SUFFICIENT_RESULT_COUNT && mapir.isConfigured()) {
            try { addUnique(results, mapir.search(term, latitude, longitude)); }
            catch (Exception exception) { failures.add("map.ir nearby: " + messageOf(exception)); }
            logStage("nearby_mapir", results.size());
        }
        if (results.size() < OSM_SUFFICIENT_RESULT_COUNT) {
            try { addUnique(results, searchNeshan(term, latitude, longitude)); }
            catch (Exception exception) { failures.add("Neshan nearby: " + messageOf(exception)); }
            logStage("nearby_neshan", results.size());
        }
        // TomTom is optional and only needed if the combined result set is still thin; kept
        // sequential and last since it is the least-often-needed provider (isConfigured() is
        // false whenever no TomTom key was supplied at all, which is the common case).
        if (results.size() < 10 && tomtom.isConfigured()) {
            try { addUnique(results, tomtom.searchNearby(term, latitude, longitude)); }
            catch (Exception exception) { failures.add("TomTom nearby: " + messageOf(exception)); }
        }
    }

    /** Holds one provider thread's result so it can be merged back on the calling thread only
     *  after every provider thread has finished - addUnique/hasNearDuplicate are not thread-safe
     *  to call concurrently from multiple provider threads against the same shared list. */
    private static final class ProviderOutcome {
        volatile List<SavedPlace> places;
        volatile Exception error;
    }

    private interface ProviderCall {
        List<SavedPlace> call() throws Exception;
    }

    private Thread runProvider(ProviderCall call, ProviderOutcome outcome) {
        Thread thread = new Thread(() -> {
            try { outcome.places = call.call(); }
            catch (Exception exception) { outcome.error = exception; }
        });
        thread.start();
        return thread;
    }

    private void joinQuietly(Thread thread) {
        try { thread.join(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void mergeOutcome(List<SavedPlace> results, List<String> failures, ProviderOutcome outcome, String label) {
        if (outcome.error != null) failures.add(label + ": " + messageOf(outcome.error));
        else addUnique(results, outcome.places);
    }

    private List<SavedPlace> searchNeshanGeocoding(String term) throws Exception {
        String key = neshan.apiKey();
        if (key == null) throw new IllegalStateException("Neshan API key is not configured.");
        String url = "https://api.neshan.org/v4/geocoding?address=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name());
        JSONObject body = RoutingHttp.getJson(url, "Api-Key", key);
        JSONObject location = body.optJSONObject("location");
        if (location == null || !isInIran(location.optDouble("y"), location.optDouble("x"))) return Collections.emptyList();
        String address = body.optString("formatted_address", body.optString("address", term));
        String title = body.optString("title", "");
        if (title.trim().isEmpty()) title = address;
        return Collections.singletonList(place(title, "search_geocoding", location.optDouble("y"), location.optDouble("x"), address));
    }

    private List<SavedPlace> searchNeshan(String term, double latitude, double longitude) throws Exception {
        String key = neshan.apiKey();
        if (key == null) throw new IllegalStateException("Neshan API key is not configured.");
        String url = "https://api.neshan.org/v1/search?term=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name())
                + "&lat=" + latitude + "&lng=" + longitude;
        JSONObject body = RoutingHttp.getJson(url, "Api-Key", key);
        JSONArray items = body.optJSONArray("items");
        ArrayList<SavedPlace> results = new ArrayList<>();
        if (items != null) for (int i = 0; i < items.length() && i < 12; i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject location = item == null ? null : item.optJSONObject("location");
            if (location != null && isInIran(location.optDouble("y"), location.optDouble("x"))) {
                results.add(place(item.optString("title", term), searchKind(item), location.optDouble("y"),
                        location.optDouble("x"), item.optString("address", term)));
            }
        }
        return results;
    }

    private void rank(List<SavedPlace> places, String query, double originLatitude, double originLongitude) {
        final String normalizedQuery = normalize(query);
        places.sort(Comparator.comparingInt((SavedPlace place) -> -score(place, normalizedQuery, originLatitude, originLongitude))
                .thenComparing(place -> place.name == null ? "" : place.name));
    }

    private void rankNearby(List<SavedPlace> places, double originLatitude, double originLongitude) {
        places.sort(Comparator.comparingDouble((SavedPlace place) ->
                        distanceKm(originLatitude, originLongitude, place.latitude, place.longitude))
                .thenComparing(place -> place.name == null ? "" : normalize(place.name)));
    }

    /**
     * Category queries (fuel, food, repair, health, etc.) must always take the nearby-first path
     * below, or a distant same-name result can beat a genuinely close one - see rankNearby/
     * keepBestMatchTier. The keyword list mirrors every PoiCategory the "اطراف من" button offers
     * (so a category never silently regresses to the country-wide address search again the way
     * "کافی‌شاپ" once did) plus common category words the driver may type that are not one of
     * the app's own quick-pick categories.
     */
    private boolean isNearbyPoiQuery(String value) {
        String term = normalize(value);
        if (containsAny(term,
                "\u067e\u0645\u067e", "\u0628\u0646\u0632\u06cc\u0646", "fuel", "gas station", "petrol",
                "\u0631\u0633\u062a\u0648\u0631\u0627\u0646", "\u06a9\u0627\u0641\u0647", "\u06a9\u0627\u0641\u06cc", "restaurant", "cafe", "coffee",
                "\u062f\u0627\u0631\u0648\u062e\u0627\u0646\u0647", "pharmacy", "\u0628\u06cc\u0645\u0627\u0631\u0633\u062a\u0627\u0646", "hospital",
                "\u067e\u0627\u0631\u06a9\u06cc\u0646\u06af", "parking", "\u0628\u0627\u0646\u06a9", "bank",
                "\u0627\u0633\u062a\u0631\u0627\u062d\u062a", "rest area", "services", "cng",
                "\u062a\u0639\u0645\u06cc\u0631\u06af\u0627\u0647", "\u0645\u06a9\u0627\u0646\u06cc\u06a9\u06cc", "mechanic", "\u067e\u0646\u0686\u0631", "tyre", "tire",
                "\u0628\u0627\u062a\u0631\u06cc", "\u0627\u0645\u062f\u0627\u062f", "\u062f\u0631\u0645\u0627\u0646\u06af\u0627\u0647", "clinic",
                "\u06a9\u0644\u0627\u0646\u062a\u0631\u06cc", "police", "\u0645\u0633\u062c\u062f", "mosque",
                "\u0633\u0631\u0648\u06cc\u0633", "toilet", "restroom", "\u062e\u0648\u062f\u067e\u0631\u062f\u0627\u0632", "atm",
                "\u0633\u0648\u067e\u0631\u0645\u0627\u0631\u06a9\u062a", "\u0645\u06cc\u0646\u06cc \u0645\u0627\u0631\u06a9\u062a", "supermarket", "market",
                "\u0641\u0631\u0648\u0634\u06af\u0627\u0647", "\u0646\u0627\u0646\u0648\u0627\u06cc\u06cc", "bakery", "\u06a9\u0627\u0631\u0648\u0627\u0634", "car wash",
                "\u0647\u062a\u0644", "hotel", "\u0645\u0633\u0627\u0641\u0631\u062e\u0627\u0646\u0647", "\u062a\u0631\u0645\u06cc\u0646\u0627\u0644", "terminal",
                "\u0627\u06cc\u0633\u062a\u06af\u0627\u0647", "station", "\u062e\u0634\u06a9\u0634\u0648\u06cc\u06cc", "\u0622\u0631\u0627\u06cc\u0634\u06af\u0627\u0647",
                "\u0644\u0648\u0627\u0632\u0645 \u06cc\u062f\u06a9\u06cc", "\u0645\u06cc\u0648\u0647 \u0641\u0631\u0648\u0634\u06cc")) {
            return true;
        }
        // Only the query-contains-category direction is checked (never the reverse): a short
        // query like "پارک" must not be swallowed by the longer "پارکینگ" category term.
        for (PoiCategory category : PoiCategory.values()) {
            String categoryTerm = normalize(category.searchTerm);
            String categoryLabel = normalize(category.label);
            if ((!categoryTerm.isEmpty() && term.contains(categoryTerm))
                    || (!categoryLabel.isEmpty() && term.contains(categoryLabel))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... values) {
        for (String item : values) if (value.contains(item)) return true;
        return false;
    }

    /**
     * A typo-tolerant provider may return a nearby but unrelated name before an exact village.
     * Do not show fuzzy matches at all while there is a true exact, prefix, or whole-word match.
     */
    private ArrayList<SavedPlace> keepBestMatchTier(List<SavedPlace> places, String rawQuery) {
        String query = normalize(rawQuery);
        int bestTier = 0;
        for (SavedPlace place : places) bestTier = Math.max(bestTier, matchTier(place, query));
        ArrayList<SavedPlace> filtered = new ArrayList<>();
        for (SavedPlace place : places) {
            if (bestTier == 0 || matchTier(place, query) == bestTier) filtered.add(place);
        }
        return filtered;
    }

    private int matchTier(SavedPlace place, String query) {
        String name = normalize(place.name);
        String address = normalize(place.address);
        if (name.equals(query) || address.equals(query)) return 3;
        if (name.startsWith(query) || address.startsWith(query)) return 2;
        if (hasWholePhrase(name, query) || hasWholePhrase(address, query)) return 1;
        return 0;
    }

    private boolean hasWholePhrase(String text, String phrase) {
        return (" " + text + " ").contains(" " + phrase + " ");
    }

    private int score(SavedPlace place, String query, double originLatitude, double originLongitude) {
        String name = normalize(place.name);
        String address = normalize(place.address);
        int score = 0;
        if (name.equals(query)) score += 12000;
        else if (address.equals(query)) score += 11000;
        else if (name.startsWith(query)) score += 7000;
        else if (address.startsWith(query)) score += 6000;
        else if (containsWholeQueryWord(name, query)) score += 5000;
        else if (containsWholeQueryWord(address, query)) score += 4000;
        else if (name.contains(query)) score += 800;
        else if (address.contains(query)) score += 400;
        for (String token : query.split(" ")) {
            if (token.length() < 2) continue;
            if (name.equals(token)) score += 4000;
            else if (name.startsWith(token)) score += 1600;
            else if (name.contains(token) || address.contains(token)) score += 700;
        }
        double distanceKm = distanceKm(originLatitude, originLongitude, place.latitude, place.longitude);
        return score + administrativeImportance(place) + Math.max(0, 250 - (int) Math.min(250, distanceKm));
    }

    private boolean containsWholeQueryWord(String text, String query) {
        return hasWholePhrase(text, query);
    }

    /** Provider responses do not currently expose population, so administrative type is the stable tie-breaker. */
    private int administrativeImportance(SavedPlace place) {
        String value = normalize(place.name + " " + place.address);
        if (value.contains("استان")) return 120;
        if (value.contains("شهرستان")) return 100;
        if (value.contains("شهر")) return 80;
        if (value.contains("بخش")) return 60;
        if (value.contains("دهستان")) return 40;
        if (value.contains("روستا")) return 20;
        return 0;
    }

    private void addUnique(List<SavedPlace> target, List<SavedPlace> additions) {
        if (additions == null) return;
        for (SavedPlace place : additions) {
            if (!hasNearDuplicate(target, place)) target.add(place);
        }
    }

    /**
     * Providers rarely agree on the exact coordinate for the same POI (different centroid or
     * entrance point), so an exact-key match misses genuine duplicates. Treat two results as the
     * same place when the normalized name matches and they sit within typical provider variance.
     */
    private boolean hasNearDuplicate(List<SavedPlace> target, SavedPlace candidate) {
        String candidateName = normalize(candidate.name);
        for (SavedPlace existing : target) {
            if (candidateName.isEmpty() || !normalize(existing.name).equals(candidateName)) continue;
            if (distanceKm(existing.latitude, existing.longitude, candidate.latitude, candidate.longitude) <= 0.15d) return true;
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replace('\u064A', '\u06CC').replace('\u0649', '\u06CC').replace('\u0643', '\u06A9')
                .replace('\u06C0', '\u0647').replace("\u200C", " ").replace("\u0640", "")
                .replaceAll("[\u064B-\u065F]", "").replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private double distanceKm(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double a = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    private String join(List<String> items) {
        if (items == null || items.isEmpty()) return "No provider result.";
        return String.join(" | ", items);
    }

    private String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Unknown error" : message;
    }

    private String searchKind(JSONObject item) {
        String kind = item.optString("type", item.optString("category", item.optString("class", "place")));
        return "search_" + (kind == null || kind.trim().isEmpty() ? "place" : kind.trim());
    }

    private SavedPlace place(String name, String kind, double latitude, double longitude, String address) {
        String uniqueKind = kind + "_" + Math.round(latitude * 100000d) + "_" + Math.round(longitude * 100000d);
        return new SavedPlace(name, uniqueKind, latitude, longitude,
                address, System.currentTimeMillis(), false);
    }

    private boolean isInIran(double latitude, double longitude) {
        return latitude >= 24.0d && latitude <= 40.5d && longitude >= 44.0d && longitude <= 64.5d;
    }
}
