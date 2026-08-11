package ai.drivemate.map;

import android.graphics.Bitmap;

/** Provider-neutral marker image for the OpenStreetMap renderer. */
public final class MarkerStyle {
    public final Bitmap bitmap;
    /** True for markers whose icon should be centered on the geo-position (a rotating vehicle
     *  arrow, where the drawn shape's true center must sit exactly on the GPS point so it doesn't
     *  visibly swing/drift off-position as it rotates). False (the default) keeps the classic
     *  pin behavior - anchored at the bottom tip, for markers like POIs/destinations that "point
     *  down" at their exact spot. */
    public final boolean centered;

    public MarkerStyle(Bitmap bitmap) {
        this(bitmap, false);
    }

    public MarkerStyle(Bitmap bitmap, boolean centered) {
        this.bitmap = bitmap;
        this.centered = centered;
    }
}
