package org.legacywa;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/** Small helpers for building views in code. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static String time(long unixSeconds, boolean withDay) {
        if (unixSeconds <= 0) return "";
        Date d = new Date(unixSeconds * 1000L);
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTime(d);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        String pattern = today || !withDay ? "HH:mm" : "dd.MM. HH:mm";
        if (!today && withDay && now.get(Calendar.YEAR) != then.get(Calendar.YEAR)) pattern = "dd.MM.yy";
        return new SimpleDateFormat(pattern, Locale.GERMANY).format(d);
    }
}
