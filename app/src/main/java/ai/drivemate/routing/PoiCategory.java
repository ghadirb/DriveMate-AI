package ai.drivemate.routing;

/**
 * Points-of-interest shown under the map's "اطراف من" (near me) button. searchTerm is fed
 * straight into PlaceSearchRepository, the same way FIND_FUEL/FIND_REST voice commands already
 * search "پمپ بنزین"/"مجتمع خدماتی" — no separate nearby-search API endpoint is needed.
 */
public enum PoiCategory {
    FUEL("پمپ بنزین", "\u26fd", "پمپ بنزین"),
    CNG("جایگاه CNG", "\ud83d\udd25", "جایگاه CNG"),
    PARKING("پارکینگ", "\ud83c\udd7f\ufe0f", "پارکینگ"),
    MECHANIC("تعمیرگاه و مکانیکی", "\ud83d\udee0\ufe0f", "تعمیرگاه خودرو"),
    TIRE_REPAIR("پنچرگیری", "\ud83d\udee5\ufe0f", "پنچرگیری"),
    BATTERY("باتری‌سازی", "\ud83d\udd0b", "باتری سازی خودرو"),
    ROADSIDE_ASSIST("امداد خودرو", "\ud83d\ude97", "امداد خودرو"),
    HOSPITAL("بیمارستان", "\ud83c\udfe5", "بیمارستان"),
    CLINIC("درمانگاه", "\ud83d\ude91", "درمانگاه"),
    PHARMACY("داروخانه", "\ud83d\udc8a", "داروخانه"),
    POLICE("کلانتری", "\ud83d\ude93", "کلانتری"),
    RESTAURANT("رستوران", "\u2615", "رستوران"),
    COFFEE_SHOP("کافی‌شاپ", "\ud83e\udd64", "کافی شاپ"),
    MOSQUE("مسجد", "\ud83d\udd4c", "مسجد"),
    RESTROOM("سرویس بهداشتی", "\ud83d\udebb", "سرویس بهداشتی عمومی"),
    ATM("خودپرداز", "\ud83c\udfe7", "خودپرداز بانک");

    public final String label;
    public final String icon;
    public final String searchTerm;

    PoiCategory(String label, String icon, String searchTerm) {
        this.label = label;
        this.icon = icon;
        this.searchTerm = searchTerm;
    }

    public String display() { return icon + " " + label; }
}
