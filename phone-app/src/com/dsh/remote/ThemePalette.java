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

    public static final String[] IDS = {"midnight", "ocean", "violet", "paper", "mist", "fish"};
    public static final String[] NAMES = {"深邃黑", "深海蓝", "紫罗兰", "纸感白", "雾灰蓝", "大肥鱼 · 海盐蓝"};

    public static ThemePalette get(Context c) {
        String id = Prefs.get(c).getString(Prefs.THEME, "midnight");
        switch (id == null ? "midnight" : id) {
            case "fish":
                return new ThemePalette("fish",0xFFF0F6FC,0xFFFFFFFF,0xFF173252,0xFF59718C,0xFF346BC1);
            case "ocean":
                return new ThemePalette("ocean", 0xFF0B1622, 0xFF12222F, 0xFFE6F1F8, 0xFF86A3B8, 0xFF2AA5D6);
            case "violet":
                return new ThemePalette("violet", 0xFF150F24, 0xFF1D1433, 0xFFEDE7FA, 0xFF9C8FC4, 0xFF9B6DFF);
            case "paper":
                return new ThemePalette("paper", 0xFFF2F3F7, 0xFFFFFFFF, 0xFF1A1C22, 0xFF6B7280, 0xFF4455DD);
            case "mist":
                return new ThemePalette("mist", 0xFFE9EEF4, 0xFFF7FAFC, 0xFF16202C, 0xFF5A6B7D, 0xFF2F6FD0);
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
