package com.dsh.remote;

import android.content.Context;

/** Selectable UI themes for the app shell. */
public final class ThemePalette {

    public final int bg, bar, text, dim, accent;
    public final String id;

    ThemePalette(String id, int bg, int bar, int text, int dim, int accent) {
        this.id = id;
        this.bg = bg;
        this.bar = bar;
        this.text = text;
        this.dim = dim;
        this.accent = accent;
    }

    public static final String[] IDS = {"midnight", "paper"};
    public static final String[] NAMES = {"深色", "浅色"};

    public static ThemePalette get(Context c) {
        String id = Prefs.get(c).getString(Prefs.THEME, "midnight");
        switch (id == null ? "midnight" : id) {
            case "paper":
                return new ThemePalette("paper", 0xFFF2F3F7, 0xFFFFFFFF, 0xFF1A1C22, 0xFF6B7280, 0xFF4455DD);
            default:
                return new ThemePalette("midnight", 0xFF0E0F1A, 0xFF141726, 0xFFE8EAF2, 0xFF8A90A6, 0xFF5865F2);
        }
    }

    public static int indexOf(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    /** System bars follow the theme instead of staying black. */
    public static void applySystemBars(android.view.Window w, int bg, int bar) {
        w.setStatusBarColor(bar);
        w.setNavigationBarColor(bg);
        int lum = (((bar >> 16) & 255) + ((bar >> 8) & 255) + (bar & 255)) / 3;
        int flags=w.getDecorView().getSystemUiVisibility();
        int light=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(lum>140?flags|light:flags&~light);
    }
}
