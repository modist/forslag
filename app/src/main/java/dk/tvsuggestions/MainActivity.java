package dk.tvsuggestions;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * What is included in your streaming subscriptions right now, from TMDb's discover API (catalogue data by
 * JustWatch): a row of movies and a row of series, most popular first, with the services in res/values/config.xml.
 * Only the region, the language and the provider ids are sent, with the key. OK opens the service's app.
 */
public class MainActivity extends Activity {
    static final String TAG = "Forslag";
    private static final long BACKDROP_DELAY_MS = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int accent;
    private Tmdb tmdb;
    private Images images;
    private ImageView backdrop;
    private TextView title, meta, overview, status, moviesLabel, seriesLabel;
    private LinearLayout movies, series;
    private boolean loading;
    /** When the rows were last filled; they are kept as they are (and the focus with them) until the cache is stale. */
    private long loadedAt;
    private String pendingBackdrop = "";
    private final Runnable showBackdrop = () -> images.load(backdrop, "w780", pendingBackdrop);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        accent = getColor(R.color.accent);
        tmdb = new Tmdb(BuildConfig.TMDB_KEY, new File(getCacheDir(), "tmdb"), getResources().getInteger(R.integer.cache_hours));
        images = new Images(tmdb);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.45f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View side = new View(this);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF2000000, 0x99000000, 0x33000000}));
        root.addView(side, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0xCC000000, 0xFF000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(24), dp(48), dp(14));
        // The rows run on to the right edge of the screen, past the padding.
        col.setClipToPadding(false);
        col.setClipChildren(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        col.addView(header, new LinearLayout.LayoutParams(-1, dp(116)));
        title = oneLine(text(30, Typeface.BOLD, 0xFFFFFFFF));
        header.addView(title);
        meta = oneLine(text(15, Typeface.NORMAL, accent));
        meta.setPadding(0, dp(6), 0, dp(8));
        header.addView(meta);
        overview = text(15, Typeface.NORMAL, 0xCCFFFFFF);
        overview.setMaxLines(2);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(0, 1.15f);
        header.addView(overview, new LinearLayout.LayoutParams(Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f), -2));

        moviesLabel = rowLabel(col, R.string.movies);
        movies = row(col);
        seriesLabel = rowLabel(col, R.string.series);
        series = row(col);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        status = oneLine(text(12, Typeface.NORMAL, 0x80FFFFFF));
        col.addView(status);
        TextView credit = oneLine(text(11, Typeface.NORMAL, 0x66FFFFFF));
        credit.setText(R.string.attribution);
        credit.setPadding(0, dp(2), 0, 0);
        col.addView(credit);

        status.setText(R.string.loading);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        long stale = getResources().getInteger(R.integer.cache_hours) * 3_600_000L;
        if (loadedAt == 0 || System.currentTimeMillis() - loadedAt > stale) load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        images.shutdown();
    }

    /** Everything that comes back from one load, built off the main thread. */
    private static final class Result {
        List<Suggestions.Title> movies = new ArrayList<>(), series = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        int error;
    }

    private void load() {
        if (loading) return;
        if (BuildConfig.TMDB_KEY.isEmpty()) {
            status.setText(R.string.no_key);
            return;
        }
        loading = true;
        String region = getString(R.string.region);
        String language = getString(R.string.language);
        int perRow = getResources().getInteger(R.integer.per_row);
        int minVotes = getResources().getInteger(R.integer.min_votes);
        List<Suggestions.Service> services = new ArrayList<>();
        for (String line : getResources().getStringArray(R.array.services)) {
            Suggestions.Service s = Suggestions.parseService(line);
            if (s != null) services.add(s);
        }
        new Thread(() -> {
            Result r = new Result();
            try {
                // A service can be on TMDb's movie list, its TV list or both.
                for (boolean tv : new boolean[] {false, true}) {
                    JSONArray providers = tmdb.json(Suggestions.providersPath(tv, region, language)).optJSONArray("results");
                    if (providers != null) Suggestions.match(services, providers);
                }
                List<Suggestions.Service> found = new ArrayList<>();
                for (Suggestions.Service s : services) {
                    if (s.providerId >= 0) found.add(s);
                    else r.unmatched.add(s.names[0]);
                }
                Map<String, Set<Integer>> streams = new ConcurrentHashMap<>();
                // When TMDb can't say, the title is kept: a rental now and then beats empty rows while offline.
                Suggestions.Check check = (t, s) -> {
                    Set<Integer> on = streamsOn(t, region, streams);
                    return on == null || on.contains(s.providerId);
                };
                for (boolean tv : new boolean[] {false, true}) {
                    List<List<Suggestions.Title>> lists = new ArrayList<>();
                    for (Suggestions.Service s : found) {
                        List<Suggestions.Title> l = new ArrayList<>();
                        try {
                            l = Suggestions.parseDiscover(tmdb.json(
                                    Suggestions.discoverPath(tv, s.providerId, region, language, minVotes)), tv);
                        } catch (Tmdb.KeyRefused e) {
                            throw e;
                        } catch (Exception e) {
                            Log.w(TAG, "discover " + s.label() + ": " + e);
                        }
                        lists.add(l);
                    }
                    // Each service's share of the row, plus a few for titles that turn out to be rentals, checked
                    // in parallel; any further title is checked when mix reaches it.
                    int share = found.isEmpty() ? 0 : perRow / found.size() + 6;
                    ExecutorService pool = Executors.newFixedThreadPool(6);
                    for (List<Suggestions.Title> l : lists) {
                        for (Suggestions.Title t : l.subList(0, Math.min(share, l.size()))) {
                            pool.execute(() -> streamsOn(t, region, streams));
                        }
                    }
                    pool.shutdown();
                    pool.awaitTermination(60, TimeUnit.SECONDS);
                    List<Suggestions.Title> mixed = Suggestions.mix(lists, found, perRow, check);
                    if (tv) r.series = mixed;
                    else r.movies = mixed;
                }
                if (found.isEmpty()) r.error = R.string.no_services;
                else if (r.movies.isEmpty() && r.series.isEmpty()) r.error = R.string.nothing;
            } catch (Tmdb.KeyRefused e) {
                r.error = R.string.bad_key;
            } catch (Exception e) {
                Log.w(TAG, "load: " + e);
                r.error = R.string.offline;
            }
            main.post(() -> show(r));
        }).start();
    }

    /** The providers that include t in a subscription here, or null when TMDb can't say. */
    private Set<Integer> streamsOn(Suggestions.Title t, String region, Map<String, Set<Integer>> known) {
        String key = (t.tv ? "tv" : "movie") + t.id;
        Set<Integer> hit = known.get(key);
        if (hit != null) return hit;
        try {
            Set<Integer> ids = Suggestions.flatrate(tmdb.json(Suggestions.watchPath(t)), region);
            known.put(key, ids);
            return ids;
        } catch (Exception e) {
            Log.w(TAG, "watch providers " + key + ": " + e);
            return null;
        }
    }

    private void show(Result r) {
        loading = false;
        boolean any = !r.movies.isEmpty() || !r.series.isEmpty();
        if (any) {
            loadedAt = System.currentTimeMillis();
            fill(movies, moviesLabel, r.movies);
            fill(series, seriesLabel, r.series);
            View first = movies.getChildCount() > 0 ? movies.getChildAt(0) : series.getChildAt(0);
            if (first != null) first.requestFocus();
        }
        StringBuilder line = new StringBuilder(r.error != 0 ? getString(r.error)
                : getString(R.string.updated, new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date())));
        if (!r.unmatched.isEmpty()) line.append("  ·  ").append(getString(R.string.unmatched, TextUtils.join(", ", r.unmatched)));
        status.setText(line);
    }

    private void fill(LinearLayout row, TextView label, List<Suggestions.Title> titles) {
        row.removeAllViews();
        int visible = titles.isEmpty() ? View.GONE : View.VISIBLE;
        label.setVisibility(visible);
        ((View) row.getParent()).setVisibility(visible);
        for (Suggestions.Title t : titles) row.addView(tile(t));
    }

    private View tile(Suggestions.Title t) {
        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setPadding(dp(3), dp(3), dp(3), dp(3));
        StateListDrawable frame = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(8));
        on.setColor(accent);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(dp(8));
        off.setColor(0x1FFFFFFF);
        frame.addState(new int[] {android.R.attr.state_focused}, on);
        frame.addState(new int[] {}, off);
        tile.setBackground(frame);

        // The name shows until the poster arrives, and stays for a title without one.
        TextView name = text(13, Typeface.NORMAL, 0xCCFFFFFF);
        name.setGravity(Gravity.CENTER);
        name.setPadding(dp(6), 0, dp(6), 0);
        name.setText(t.name);
        tile.addView(name, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setClipToOutline(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(6));
        poster.setBackground(shape);
        tile.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        if (!t.poster.isEmpty()) images.load(poster, "w185", t.poster);

        tile.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(120).start();
            if (focused) describe(t);
        });
        tile.setOnClickListener(v -> open(t));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(135));
        lp.setMargins(0, 0, dp(12), 0);
        tile.setLayoutParams(lp);
        return tile;
    }

    private void describe(Suggestions.Title t) {
        title.setText(t.name);
        List<String> parts = new ArrayList<>();
        if (!t.year.isEmpty()) parts.add(t.year);
        if (t.rating > 0) parts.add(String.format(Locale.ROOT, "★ %.1f", t.rating));
        List<String> names = new ArrayList<>();
        for (Suggestions.Service s : t.services) names.add(s.label());
        parts.add(TextUtils.join(", ", names));
        meta.setText(TextUtils.join("  ·  ", parts));
        overview.setText(t.overview);
        // Wait for the focus to settle before fetching a large picture while the D-pad is held.
        main.removeCallbacks(showBackdrop);
        if (t.backdrop.isEmpty()) {
            backdrop.setTag(null);
            backdrop.setImageDrawable(null);
        } else {
            pendingBackdrop = t.backdrop;
            main.postDelayed(showBackdrop, BACKDROP_DELAY_MS);
        }
    }

    /** Opens the first of the title's services whose app is installed; TMDb has no ids those apps accept. */
    private void open(Suggestions.Title t) {
        PackageManager pm = getPackageManager();
        for (Suggestions.Service s : t.services) {
            if (s.pkg.isEmpty()) continue;
            Intent i = pm.getLeanbackLaunchIntentForPackage(s.pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(s.pkg);
            if (i == null) continue;
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception e) {
                Log.w(TAG, "open " + s.pkg + ": " + e);
            }
        }
        if (!t.services.isEmpty()) {
            Toast.makeText(this, getString(R.string.not_installed, t.services.get(0).label()), Toast.LENGTH_SHORT).show();
        }
    }

    private TextView rowLabel(LinearLayout col, int text) {
        TextView t = text(16, Typeface.BOLD, 0xE6FFFFFF);
        t.setText(text);
        t.setPadding(0, dp(8), 0, dp(2));
        col.addView(t);
        return t;
    }

    private LinearLayout row(LinearLayout col) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        // Room for the focused tile to grow without being cut.
        scroll.setPadding(dp(6), dp(6), dp(48), dp(6));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        scroll.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(-6), 0, dp(-48), 0);
        col.addView(scroll, lp);
        return row;
    }

    private static TextView oneLine(TextView t) {
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView text(float sp, int style, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, style));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
