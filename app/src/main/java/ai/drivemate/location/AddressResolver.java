package ai.drivemate.location;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class AddressResolver {
    private AddressResolver() { }

    public static String resolve(Context context, double latitude, double longitude) {
        try {
            List<Address> addresses = new Geocoder(context, new Locale("fa", "IR"))
                    .getFromLocation(latitude, longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address address = addresses.get(0);
                String line = address.getAddressLine(0);
                if (line != null && !line.trim().isEmpty()) return line;
            }
        } catch (IOException | IllegalArgumentException ignored) { }
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }
}
