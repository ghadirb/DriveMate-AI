package ai.drivemate.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-lane turn guidance for a single upcoming intersection, parsed only from an explicit
 * provider field (currently: map.ir's OSRM-style {@code intersections[0].lanes}) - never
 * inferred from instruction text, road class, or maneuver type. {@code indications} lists each
 * lane left-to-right exactly as the provider ordered them (e.g. "left", "straight", "right");
 * {@code validForManeuver} marks, at the same index, whether that lane is a recommended choice
 * for the current maneuver. Null on a RouteStep whenever the provider's response did not include
 * this data - matching the same honesty rule already used for SpeedLimitPoint.
 */
public final class LaneGuidance {
    public final List<String> indications;
    public final List<Boolean> validForManeuver;

    public LaneGuidance(List<String> indications, List<Boolean> validForManeuver) {
        this.indications = indications == null ? Collections.emptyList() : indications;
        this.validForManeuver = validForManeuver == null ? Collections.emptyList() : validForManeuver;
    }

    /** True only when there is something genuinely actionable to say: at least two lanes, and
     *  a real split between recommended and non-recommended lanes (all-valid or all-invalid
     *  carries no useful choice for the driver). */
    public boolean hasUsefulGuidance() {
        if (indications.size() <= 1) return false;
        boolean anyValid = false;
        boolean anyInvalid = false;
        for (Boolean valid : validForManeuver) {
            if (Boolean.TRUE.equals(valid)) anyValid = true; else anyInvalid = true;
        }
        return anyValid && anyInvalid;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("indications", new JSONArray(indications));
        object.put("validForManeuver", new JSONArray(validForManeuver));
        return object;
    }

    public static LaneGuidance fromJson(JSONObject object) {
        List<String> indications = new ArrayList<>();
        List<Boolean> validForManeuver = new ArrayList<>();
        JSONArray indicationsArray = object.optJSONArray("indications");
        if (indicationsArray != null) {
            for (int i = 0; i < indicationsArray.length(); i++) indications.add(indicationsArray.optString(i));
        }
        JSONArray validArray = object.optJSONArray("validForManeuver");
        if (validArray != null) {
            for (int i = 0; i < validArray.length(); i++) validForManeuver.add(validArray.optBoolean(i));
        }
        return new LaneGuidance(indications, validForManeuver);
    }
}
