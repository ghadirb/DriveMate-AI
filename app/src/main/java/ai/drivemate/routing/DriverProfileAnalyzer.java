package ai.drivemate.routing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.drivemate.model.TripRecord;

/** Builds an explainable, on-device driving profile for AI context and never contacts a service. */
public final class DriverProfileAnalyzer {
    private DriverProfileAnalyzer() { }

    public static String summarize(List<TripRecord> trips) {
        if (trips == null || trips.isEmpty()) return "هنوز سابقه کافی برای تحلیل عادت رانندگی ثبت نشده است.";
        int completed = 0;
        int totalDistance = 0;
        Map<String, Integer> destinations = new LinkedHashMap<>();
        for (TripRecord trip : trips) {
            if (trip.completed) completed++;
            totalDistance += Math.max(0, trip.traveledDistanceMeters > 0 ? trip.traveledDistanceMeters : trip.distanceMeters);
            String name = trip.destinationName == null ? "" : trip.destinationName.trim();
            if (!name.isEmpty()) destinations.put(name, destinations.containsKey(name) ? destinations.get(name) + 1 : 1);
        }
        String favorite = "";
        int favoriteCount = 0;
        for (Map.Entry<String, Integer> entry : destinations.entrySet()) {
            if (entry.getValue() > favoriteCount) {
                favorite = entry.getKey();
                favoriteCount = entry.getValue();
            }
        }
        int averageMeters = totalDistance / Math.max(1, trips.size());
        StringBuilder result = new StringBuilder("تحلیل محلی عادت رانندگی: ")
                .append(trips.size()).append(" سفر ثبت شده، ")
                .append(completed).append(" سفر کامل، میانگین مسافت ")
                .append(Math.max(1, Math.round(averageMeters / 1000f))).append(" کیلومتر");
        if (favoriteCount >= 2) result.append("؛ مقصد پرتکرار ").append(favorite)
                .append(" با ").append(favoriteCount).append(" سفر");
        result.append(".");
        return result.toString();
    }
}
