package ai.drivemate.map;

import android.graphics.Bitmap;

/** Provider-neutral marker image for the OpenStreetMap renderer. */
public final class MarkerStyle {
    public final Bitmap bitmap;

    public MarkerStyle(Bitmap bitmap) {
        this.bitmap = bitmap;
    }
}
