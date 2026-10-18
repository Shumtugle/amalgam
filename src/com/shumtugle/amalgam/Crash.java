package com.shumtugle.amalgam;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * If the game falls over, the reason is written down; the next start shows it, so it can be
 * copied and sent instead of guessed at.
 */
final class Crash {
    private Crash() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "last-crash.txt");
    }

    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("version " + version(app) + ", Android " + Build.VERSION.RELEASE
                            + " (" + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
                    pw.println("thread " + t.getName());
                    e.printStackTrace(pw);
                    pw.flush();
                    FileOutputStream out = new FileOutputStream(file(app));
                    out.write(sw.toString().getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) {
                }
                if (before != null) before.uncaughtException(t, e);
            }
        });
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** The report left by the last crash, or null. */
    static String pending(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = in.read(b);
            in.close();
            return new String(b, 0, Math.max(0, n), "UTF-8");
        } catch (Exception e) {
            return "the report could not be read";
        }
    }

    /** Shows the report with a way to copy it; continuing clears it and starts the game. */
    static void show(final Activity a, final String report, final Runnable resume) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.BLACK);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(a);
        title.setText("The game stopped last time. This is why:");
        title.setTextColor(0xFFE0E0E8);
        title.setTextSize(17);
        box.addView(title);

        TextView body = new TextView(a);
        body.setText(report);
        body.setTextColor(0xFFA8B0C0);
        body.setTextSize(12);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(body);
        box.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.END);
        Button copy = new Button(a);
        copy.setText("Copy");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("report", report));
            }
        });
        Button go = new Button(a);
        go.setText("Continue");
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                file(a).delete();
                resume.run();
            }
        });
        row.addView(copy);
        row.addView(go);
        box.addView(row);
        a.setContentView(box);
    }
}
