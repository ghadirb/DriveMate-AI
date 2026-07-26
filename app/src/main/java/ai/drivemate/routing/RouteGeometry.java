package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteStep;

final class RouteGeometry {
    private RouteGeometry() { }

    static List<RoutePoint> fromRoute(JSONObject route, List<RouteStep> steps, double originLat, double originLng,
                                      double destinationLat, double destinationLng) {
        ArrayList<RoutePoint> result = new ArrayList<>();
        Object geometry = route.opt("geometry");
        if (geometry instanceof String) result.addAll(decodePolyline((String) geometry));
        if (geometry instanceof JSONObject) {
            JSONObject object = (JSONObject) geometry;
            readCoordinates(object.optJSONArray("coordinates"), result);
        }
        JSONObject overview = route.optJSONObject("overview_polyline");
        if (result.isEmpty() && overview != null) result.addAll(decodePolyline(overview.optString("points")));
        if (result.isEmpty()) result.addAll(decodePolyline(route.optString("polyline")));
        if (result.isEmpty()) {
            result.add(new RoutePoint(originLat, originLng));
            for (RouteStep step : steps) result.add(new RoutePoint(step.latitude, step.longitude));
            result.add(new RoutePoint(destinationLat, destinationLng));
        }
        return result;
    }

    private static void readCoordinates(JSONArray coordinates, List<RoutePoint> target) {
        if (coordinates == null) return;
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray point = coordinates.optJSONArray(i);
            if (point != null && point.length() >= 2) target.add(new RoutePoint(point.optDouble(1), point.optDouble(0)));
        }
    }

    static List<RoutePoint> decodePolyline(String encoded) {
        ArrayList<RoutePoint> points = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return points;
        int index = 0, latitude = 0, longitude = 0;
        while (index < encoded.length()) {
            int[] lat = decode(encoded, index); index = lat[1]; latitude += lat[0];
            if (index >= encoded.length()) break;
            int[] lng = decode(encoded, index); index = lng[1]; longitude += lng[0];
            points.add(new RoutePoint(latitude / 1E5, longitude / 1E5));
        }
        return points;
    }

    private static int[] decode(String value, int index) {
        int result = 0, shift = 0, current;
        do { current = value.charAt(index++) - 63; result |= (current & 0x1f) << shift; shift += 5; } while (current >= 0x20 && index < value.length());
        return new int[]{(result & 1) != 0 ? ~(result >> 1) : (result >> 1), index};
    }
}
