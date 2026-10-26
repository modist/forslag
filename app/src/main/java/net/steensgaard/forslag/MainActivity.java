package net.steensgaard.forslag;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * What is included in your streaming subscriptions right now, from TMDb's discover API (catalogue data by
 * JustWatch) and DRTV's and Viaplay's own catalogues: a row of movies and a row of series, each with genre tabs, with
 * the services in res/values/config.xml, a Top rated switch and a Sort button. Only the region, the language, the
 * genre, the rating and the provider or title ids go to TMDb, with the key; Wikidata gets the TMDb ids of the titles
 * on Netflix and Disney+, and Movie of the Night one title's id when Watch is pressed on it. OK on a title shows its
 * detail page, with a Watch button per service that opens the title in that app where there is a link to it, and a
 * Back button that (like the remote's) returns to the rows. Every answer is cached for cache_hours.
 */
public class MainActivity extends Activity {
    static final String TAG = "Forslag";
    private static final long BACKDROP_DELAY_MS = 300;
    /** The width of the poster on the detail page, and of the buttons below it, in dp. */
    private static final int POSTER_W = 180;
    /** How many actors the detail page names. */
    private static final int CAST_MAX = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int accent;
    private Tmdb tmdb;
    private Images images;
    private ImageView backdrop;
    private TextView title, meta, overview, status;
    private Section movies, series;
    private String region, language;
    private int perRow, minVotes;
    /** "Top rated" on: only titles rated at least topMin, in both rows. Kept between launches. */
    private volatile boolean topRated;
    private double topMin;
    private SharedPreferences prefs;
    private TextView topToggle, sortButton, refreshButton;
    /** How the rows are ordered: one of Suggestions.ORDERS. Kept between launches. */
    private String order;
    private boolean loading;
    /** The load running was started by Refresh, which keeps the focus when it is done. */
    private boolean refreshing;
    /** When the rows were last filled; they are kept as they are (and the focus with them) until the cache is stale. */
    private long loadedAt;
    /** The services TMDb, DRTV or Viaplay can give titles for; set by the first load, read by the genre tabs. */
    private volatile List<Suggestions.Service> found = Collections.emptyList();
    private final Map<String, Set<Integer>> streams = new ConcurrentHashMap<>();
    /** Wikidata's Netflix and Disney+ ids by title, kept on disk for cache_hours; see linkIds. */
    private final Object idsLock = new Object();
    private JSONObject ids;
    private String pendingBackdrop = "";
    private final Runnable showBackdrop = () -> images.load(backdrop, pendingBackdrop);
    /** The header and the rows, kept from taking the focus while the detail page is up. */
    private LinearLayout content;
    /** The detail page OK on a title shows over the rows, until Back. */
    private FrameLayout detail;
    private ImageView detailBackdrop, detailPoster;
    private TextView detailTitle, detailMeta, detailCast, detailOverview, backButton;
    /** The Watch buttons, one per service that has the title, and Back below them. */
    private LinearLayout detailButtons;
    /** The title on the detail page, and the tile the focus goes back to when it closes. */
    private Suggestions.Title detailOf;
    private View returnTo;

    /** The movie or series row: its genre tabs, its tiles, and the rows already fetched, by genre. */
    private final class Section {
        final boolean tv;
        final LinearLayout row;
        final List<TextView> tabs = new ArrayList<>();
        final Map<String, List<Suggestions.Title>> fetched = new HashMap<>();
        Suggestions.Genre genre;
        /** The key of the row on screen (genre and filter), or null while one is loading. */
        String showing;
        /** Bumped on every choice, so a slow answer for a tab that was left is dropped. */
        int generation;

        Section(LinearLayout col, boolean tv, int label) {
            this.tv = tv;
            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(0, dp(8), 0, dp(2));
            TextView name = text(16, Typeface.BOLD, 0xE6FFFFFF);
            name.setText(label);
            name.setPadding(0, 0, dp(16), 0);
            head.addView(name);
            HorizontalScrollView scroll = new HorizontalScrollView(MainActivity.this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = new LinearLayout(MainActivity.this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            scroll.addView(strip);
            head.addView(scroll, new LinearLayout.LayoutParams(0, -2, 1f));
            strip.addView(tab(null));
            for (Suggestions.Genre g : Suggestions.GENRES) {
                if (g.offered(tv)) strip.addView(tab(g));
            }
            col.addView(head);
            row = row(col);
            mark();
        }

        private TextView tab(Suggestions.Genre g) {
            TextView t = text(13, Typeface.NORMAL, 0xFFFFFFFF);
            t.setText(g == null ? R.string.genre_all : genreLabel(g));
            t.setPadding(dp(12), dp(4), dp(12), dp(4));
            t.setBackground(tabBackground());
            t.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}},
                    new int[] {0xFF15171C, 0xFFFFFFFF}));
            // Usable once the first load knows the services.
            t.setFocusable(false);
            t.setTag(g);
            // A genre is fetched only on OK, so moving across the tabs costs no API calls.
            t.setOnClickListener(v -> choose(g));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(6), 0);
            t.setLayoutParams(lp);
            tabs.add(t);
            return t;
        }

        void enableTabs() {
            for (TextView t : tabs) t.setFocusable(true);
        }

        void mark() {
            for (TextView t : tabs) t.setSelected(t.getTag() == genre);
        }

        void reset() {
            fetched.clear();
            genre = null;
            showing = null;
            generation++;
            mark();
        }

        void choose(Suggestions.Genre g) {
            final boolean top = topRated;
            final String key = key(g, top);
            if (key.equals(showing)) return;
            genre = g;
            mark();
            List<Suggestions.Title> known = fetched.get(key);
            if (known != null) {
                fill(this, known);
                showing = key;
                return;
            }
            showing = null;
            final int asked = ++generation;
            placeholder(this, R.string.loading);
            new Thread(() -> {
                List<Suggestions.Title> titles;
                try {
                    titles = buildRow(tv, g, top);
                } catch (Exception e) {
                    Log.w(TAG, "genre " + key + ": " + e);
                    titles = null;
                }
                final List<Suggestions.Title> result = titles;
                main.post(() -> {
                    if (asked != generation || isDestroyed()) return;
                    if (result == null) {
                        placeholder(this, R.string.offline);
                        return;
                    }
                    fetched.put(key, result);
                    fill(this, result);
                    showing = key;
                });
            }).start();
        }
    }

    /** What a section keeps a fetched row under: its genre, and whether only top rated titles were asked for. */
    private static String key(Suggestions.Genre g, boolean top) {
        return (g == null ? "all" : g.key) + (top ? ":top" : "");
    }

    /** The next order on OK: popular, newest, rating, popular. Only re-sorts what the rows hold; no API calls. */
    private void nextOrder() {
        int i = Arrays.asList(Suggestions.ORDERS).indexOf(order);
        order = Suggestions.ORDERS[(i + 1) % Suggestions.ORDERS.length];
        prefs.edit().putString("order", order).apply();
        showOrder();
        for (Section s : new Section[] {movies, series}) {
            List<Suggestions.Title> shown = s.showing == null ? null : s.fetched.get(s.showing);
            if (shown != null) fill(s, shown);
        }
    }

    private void showOrder() {
        int label = order.equals(Suggestions.NEWEST) ? R.string.order_newest
                : order.equals(Suggestions.RATING) ? R.string.order_rating : R.string.order_popular;
        sortButton.setText(getString(R.string.sort, getString(label)));
    }

    /** Turns "Top rated" on or off and fetches both rows again, each in the genre it shows. */
    private void toggleTopRated() {
        topRated = !topRated;
        topToggle.setSelected(topRated);
        prefs.edit().putBoolean("top_rated", topRated).apply();
        for (Section s : new Section[] {movies, series}) s.choose(s.genre);
    }

    /**
     * Asks every source again now, cache or not: TMDb, DRTV, Viaplay and Wikidata (pictures aside), and fills both
     * rows anew on All. A source that can't be reached keeps its last answer.
     */
    private void refresh() {
        if (loading) return;
        tmdb.refresh();
        streams.clear();
        refreshing = true;
        status.setText(R.string.loading);
        load();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        accent = getColor(R.color.accent);
        tmdb = new Tmdb(BuildConfig.TMDB_KEY, new File(getCacheDir(), "tmdb"), getResources().getInteger(R.integer.cache_hours));
        images = new Images(tmdb);
        region = getString(R.string.region);
        language = getString(R.string.language);
        perRow = getResources().getInteger(R.integer.per_row);
        minVotes = getResources().getInteger(R.integer.min_votes);
        topMin = Double.parseDouble(getString(R.string.top_rated_min));
        prefs = getSharedPreferences("forslag", MODE_PRIVATE);
        topRated = prefs.getBoolean("top_rated", false);
        order = prefs.getString("order", Suggestions.POPULAR);

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
        content = col;

        FrameLayout top = new FrameLayout(this);
        col.addView(top, new LinearLayout.LayoutParams(-1, dp(112)));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        top.addView(header, new FrameLayout.LayoutParams(-1, -1));
        title = oneLine(text(30, Typeface.BOLD, 0xFFFFFFFF));
        header.addView(title, new LinearLayout.LayoutParams(Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f), -2));
        topToggle = text(13, Typeface.NORMAL, 0xFFFFFFFF);
        topToggle.setText(getString(R.string.top_rated, getString(R.string.top_rated_min)));
        topToggle.setPadding(dp(12), dp(4), dp(12), dp(4));
        topToggle.setBackground(tabBackground());
        topToggle.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}},
                new int[] {0xFF15171C, 0xFFFFFFFF}));
        topToggle.setSelected(topRated);
        topToggle.setFocusable(false);
        topToggle.setOnClickListener(v -> toggleTopRated());
        sortButton = text(13, Typeface.NORMAL, 0xFFFFFFFF);
        sortButton.setPadding(dp(12), dp(4), dp(12), dp(4));
        sortButton.setBackground(tabBackground());
        sortButton.setFocusable(false);
        sortButton.setOnClickListener(v -> nextOrder());
        showOrder();
        refreshButton = text(13, Typeface.NORMAL, 0xFFFFFFFF);
        refreshButton.setText(R.string.refresh);
        refreshButton.setPadding(dp(12), dp(4), dp(12), dp(4));
        refreshButton.setBackground(tabBackground());
        // Usable from the start, so a first load that failed can be tried again.
        refreshButton.setFocusable(true);
        refreshButton.setOnClickListener(v -> refresh());
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.setMargins(0, 0, dp(8), 0);
        controls.addView(refreshButton, gap);
        controls.addView(sortButton, gap);
        controls.addView(topToggle);
        top.addView(controls, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        meta = oneLine(text(15, Typeface.NORMAL, accent));
        meta.setPadding(0, dp(6), 0, dp(8));
        header.addView(meta);
        overview = text(15, Typeface.NORMAL, 0xCCFFFFFF);
        overview.setMaxLines(2);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(0, 1.15f);
        header.addView(overview, new LinearLayout.LayoutParams(Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f), -2));

        movies = new Section(col, false, R.string.movies);
        series = new Section(col, true, R.string.series);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        status = oneLine(text(12, Typeface.NORMAL, 0x80FFFFFF));
        col.addView(status);
        TextView credit = oneLine(text(11, Typeface.NORMAL, 0x66FFFFFF));
        credit.setText(R.string.attribution);
        credit.setPadding(0, dp(2), 0, 0);
        col.addView(credit);

        buildDetail(root);
        status.setText(R.string.loading);
        setContentView(root);
    }

    /**
     * The detail page: a large backdrop; on the left the poster with the Watch buttons and Back stacked below it, on
     * the right the title, its details and the plot, which may run to the bottom of the screen. Hidden at first.
     */
    private void buildDetail(FrameLayout root) {
        detail = new FrameLayout(this);
        detail.setBackgroundColor(0xFF000000);
        detail.setVisibility(View.GONE);
        detailBackdrop = new ImageView(this);
        detailBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        detailBackdrop.setAlpha(0.6f);
        detail.addView(detailBackdrop, new FrameLayout.LayoutParams(-1, -1));
        // Dark behind the text, lighter to the right of it, where the backdrop shows.
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF2000000, 0xD9000000, 0xB3000000, 0x33000000}));
        detail.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(64), dp(48), dp(64), dp(40));
        detail.addView(body, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(POSTER_W), -1);
        llp.setMargins(0, 0, dp(40), 0);
        body.addView(left, llp);
        detailPoster = new ImageView(this);
        detailPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        detailPoster.setClipToOutline(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        detailPoster.setBackground(shape);
        left.addView(detailPoster, new LinearLayout.LayoutParams(-1, dp(250)));
        // The buttons scroll with the focus when a title is on more services than fit below the poster.
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(18), 0, dp(4));
        detailButtons = new LinearLayout(this);
        detailButtons.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(detailButtons);
        left.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        backButton = button();
        backButton.setText(R.string.back);
        backButton.setOnClickListener(v -> hideDetail());

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        // Half the screen: about 65 characters a line, which reads well from the sofa.
        body.addView(words, new LinearLayout.LayoutParams(Math.round(getResources().getDisplayMetrics().widthPixels * 0.5f), -1));
        detailTitle = text(30, Typeface.BOLD, 0xFFFFFFFF);
        detailTitle.setMaxLines(2);
        detailTitle.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(detailTitle);
        detailMeta = oneLine(text(16, Typeface.NORMAL, accent));
        detailMeta.setPadding(0, dp(8), 0, dp(16));
        words.addView(detailMeta);
        // The actors, once TMDb has answered; hidden until then and for a title it knows none for.
        detailCast = text(15, Typeface.NORMAL, 0xB3FFFFFF);
        detailCast.setMaxLines(2);
        detailCast.setEllipsize(TextUtils.TruncateAt.END);
        detailCast.setLineSpacing(0, 1.15f);
        detailCast.setPadding(0, 0, 0, dp(16));
        words.addView(detailCast);
        detailOverview = text(16, Typeface.NORMAL, 0xDDFFFFFF);
        detailOverview.setEllipsize(TextUtils.TruncateAt.END);
        detailOverview.setLineSpacing(0, 1.2f);
        // The plot gets the rest of the height, down to the bottom border: as many lines as fit, then an ellipsis.
        detailOverview.addOnLayoutChangeListener((v, l, top, r, bottom, ol, ot, or, ob) -> {
            int lines = Math.max(1, (bottom - top) / detailOverview.getLineHeight());
            if (detailOverview.getMaxLines() != lines) detailOverview.post(() -> detailOverview.setMaxLines(lines));
        });
        words.addView(detailOverview, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(detail, new FrameLayout.LayoutParams(-1, -1));
    }

    /** The buttons for t, stacked below the poster: Watch on each service that has it, in the order of config.xml, then Back. */
    private void fillButtons(Suggestions.Title t) {
        detailButtons.removeAllViews();
        for (Suggestions.Service s : t.services) {
            if (s.pkg.isEmpty()) continue;
            TextView b = button();
            Drawable icon = appIcon(s.pkg);
            if (icon == null) {
                b.setText(getString(R.string.watch_on, s.label()));
            } else {
                b.setText(s.label());
                b.setCompoundDrawablesRelative(icon, null, null, null);
                b.setCompoundDrawablePadding(dp(10));
            }
            fitText(b, dp(POSTER_W) - b.getPaddingStart() - b.getPaddingEnd()
                    - (icon == null ? 0 : dp(20) + b.getCompoundDrawablePadding()));
            b.setTag(s);
            b.setOnClickListener(v -> open(t, s));
            detailButtons.addView(b, stacked(dp(6)));
        }
        if (backButton.getParent() != null) ((ViewGroup) backButton.getParent()).removeView(backButton);
        detailButtons.addView(backButton, stacked(detailButtons.getChildCount() > 0 ? dp(12) : 0));
    }

    /** A smaller size, down to 12sp, for a service name too long for its button ("Amazon Prime Video"). */
    private void fitText(TextView b, int room) {
        float sp = 15;
        while (sp > 12 && b.getPaint().measureText(b.getText().toString()) > room) {
            sp -= 0.5f;
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
    }

    /** The app's own icon, button-sized, or null when it is not installed. */
    private Drawable appIcon(String pkg) {
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            d.setBounds(0, 0, dp(20), dp(20));
            return d;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** A full-width place in the stack of buttons, below a gap. */
    private LinearLayout.LayoutParams stacked(int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (detailButtons.getChildCount() > 0) lp.setMargins(0, gap, 0, 0);
        return lp;
    }

    /** The button the focus starts on: the first service whose app is installed, else the first service, else Back. */
    private View firstButton() {
        PackageManager pm = getPackageManager();
        for (int i = 0; i < detailButtons.getChildCount(); i++) {
            View b = detailButtons.getChildAt(i);
            if (b.getTag() instanceof Suggestions.Service && intent(detailOf, (Suggestions.Service) b.getTag(), 2, pm) != null) {
                return b;
            }
        }
        return detailButtons.getChildAt(0);
    }

    /** A button on the detail page: filled with the accent while focused. */
    private TextView button() {
        TextView b = oneLine(text(15, Typeface.BOLD, 0xFFFFFFFF));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setFocusable(true);
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, pill(accent, 0xFFFFFFFF));
        d.addState(new int[] {}, pill(0x33FFFFFF, 0));
        b.setBackground(d);
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {0xFF15171C, 0xFFFFFFFF}));
        return b;
    }

    /** OK on a tile: the title's detail page, with the focus on Watch on its first installed service. */
    private void showDetail(Suggestions.Title t, View from) {
        detailOf = t;
        returnTo = from;
        detailTitle.setText(t.name);
        showDetails(t);
        if (t.details == null && t.id > 0) fetchDetails(t);
        detailOverview.setText(t.overview);
        detailOverview.setVisibility(t.overview.isEmpty() ? View.GONE : View.VISIBLE);
        detailPoster.setVisibility(t.poster.isEmpty() ? View.GONE : View.VISIBLE);
        if (!t.poster.isEmpty()) images.load(detailPoster, t.poster);
        if (t.backdrop.isEmpty()) {
            detailBackdrop.setTag(null);
            detailBackdrop.setImageDrawable(null);
        } else {
            images.load(detailBackdrop, t.backdrop);
        }
        fillButtons(t);
        detail.setVisibility(View.VISIBLE);
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        firstButton().requestFocus();
    }

    /**
     * The detail page's year and rating, with a series' seasons and episodes or a movie's runtime, and the cast, once
     * they are known.
     */
    private void showDetails(Suggestions.Title t) {
        String line = metaLine(t, false);
        Suggestions.Details d = t.details;
        if (d != null && d.seasons > 0) {
            line += "  ·  " + getResources().getQuantityString(R.plurals.seasons, d.seasons, d.seasons);
            if (d.episodes > 0) line += "  ·  " + getResources().getQuantityString(R.plurals.episodes, d.episodes, d.episodes);
        }
        if (d != null && d.runtime > 0) {
            line += "  ·  " + (d.runtime >= 60 ? getString(R.string.runtime_hours, d.runtime / 60, d.runtime % 60)
                    : getString(R.string.runtime_minutes, d.runtime));
        }
        detailMeta.setText(line);
        boolean cast = d != null && !d.cast.isEmpty();
        detailCast.setText(cast ? getString(R.string.cast, TextUtils.join(", ", d.cast)) : "");
        detailCast.setVisibility(cast ? View.VISIBLE : View.GONE);
    }

    /**
     * Seasons or runtime, and cast, from TMDb: one request when a detail page opens (none while browsing), cached for cache_hours
     * like every answer. Shown if the page still shows the title when the answer comes.
     */
    private void fetchDetails(Suggestions.Title t) {
        new Thread(() -> {
            Suggestions.Details d;
            try {
                d = Suggestions.parseDetails(tmdb.json(Suggestions.detailsPath(t, language)), t.tv, CAST_MAX);
            } catch (Exception e) {
                Log.w(TAG, "details " + t.id + ": " + e);
                return;
            }
            main.post(() -> {
                t.details = d;
                if (t == detailOf && !isDestroyed()) showDetails(t);
            });
        }).start();
    }

    /** Back to the rows, on the tile the detail page came from (or the first, if the rows were filled meanwhile). */
    private void hideDetail() {
        detail.setVisibility(View.GONE);
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = returnTo != null && returnTo.isAttachedToWindow() ? returnTo : firstTile();
        if (back != null) back.requestFocus();
        detailOf = null;
        returnTo = null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && detail.getVisibility() == View.VISIBLE) {
            if (e.getAction() == KeyEvent.ACTION_UP) hideDetail();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private View firstTile() {
        return movies.row.getChildCount() > 0 ? movies.row.getChildAt(0) : series.row.getChildAt(0);
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
        boolean top;
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
        List<Suggestions.Service> services = new ArrayList<>();
        for (String line : getResources().getStringArray(R.array.services)) {
            Suggestions.Service s = Suggestions.parseService(line);
            if (s != null) services.add(s);
        }
        final boolean top = topRated;
        new Thread(() -> {
            Result r = new Result();
            r.top = top;
            try {
                // A service can be on TMDb's movie list, its TV list or both.
                for (boolean tv : new boolean[] {false, true}) {
                    JSONArray providers = tmdb.json(Suggestions.providersPath(tv, region, language)).optJSONArray("results");
                    if (providers != null) Suggestions.match(services, providers);
                }
                List<Suggestions.Service> usable = new ArrayList<>();
                for (Suggestions.Service s : services) {
                    if (s.own() || s.providerId >= 0) usable.add(s);
                    else r.unmatched.add(s.names[0]);
                }
                found = usable;
                r.movies = buildRow(false, null, top);
                r.series = buildRow(true, null, top);
                if (usable.isEmpty()) r.error = R.string.no_services;
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

    /**
     * One row: each service's titles (of one genre, or all), mixed in turns, with the TMDb titles checked against
     * what each service really streams, and links looked up. Runs off the main thread.
     */
    private List<Suggestions.Title> buildRow(boolean tv, Suggestions.Genre genre, boolean top) throws Exception {
        double minRating = top ? topMin : 0;
        List<Suggestions.Service> services = found;
        List<List<Suggestions.Title>> lists = new ArrayList<>();
        for (Suggestions.Service s : services) {
            List<Suggestions.Title> l = new ArrayList<>();
            try {
                if (s.source.equals(Suggestions.FROM_DRTV)) {
                    // DR's lists are not by genre; the tab keeps the titles DR tags with it.
                    l = Suggestions.parseDrtv(tmdb.open(Suggestions.drtvListUrl(
                            getString(tv ? R.string.drtv_series : R.string.drtv_movies))), tv);
                    if (genre != null) l = genre.filter(l);
                } else if (s.source.equals(Suggestions.FROM_VIAPLAY)) {
                    l = viaplay(tv, genre);
                } else {
                    l = Suggestions.parseDiscover(tmdb.json(
                            Suggestions.discoverPath(tv, s.providerId, region, language, minVotes, genre, minRating)), tv);
                }
            } catch (Tmdb.KeyRefused e) {
                throw e;
            } catch (Exception e) {
                Log.w(TAG, "list " + s.label() + ": " + e);
            }
            // TMDb was asked for these ratings already; DR's and Viaplay's own lists are filtered here.
            if (minRating > 0 && s.own()) l = Suggestions.rated(l, minRating);
            lists.add(l);
        }
        // Each service's share of the row, plus a few for titles that turn out to be rentals, checked in parallel;
        // any further title is checked when mix reaches it.
        int share = services.isEmpty() ? 0 : perRow / services.size() + 6;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (int i = 0; i < lists.size(); i++) {
            if (services.get(i).own()) continue;
            List<Suggestions.Title> l = lists.get(i);
            for (Suggestions.Title t : l.subList(0, Math.min(share, l.size()))) {
                pool.execute(() -> streamsOn(t));
            }
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        // DR's and Viaplay's own lists are what they stream. When TMDb can't say, the title is kept: a rental now
        // and then beats empty rows while offline.
        List<Suggestions.Title> mixed = Suggestions.mix(lists, services, perRow, (t, s) -> {
            if (s.own()) return true;
            Set<Integer> on = streamsOn(t);
            return on == null || on.contains(s.providerId);
        });
        linkIds(mixed);
        return mixed;
    }

    /**
     * Viaplay's most popular films or series in the subscription, from its own catalogue: its genre page where it
     * has one for the genre, else its "all" pages filtered by each title's genres. A few pages, about 30 titles.
     */
    private List<Suggestions.Title> viaplay(boolean tv, Suggestions.Genre genre) throws Exception {
        JSONObject all = tmdb.open(Suggestions.viaplayAllUrl(tv));
        String url = genre == null ? null : Suggestions.viaplayGenreUrl(all, genre);
        JSONObject page = genre == null || url == null ? all : tmdb.open(url);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String now = iso.format(new Date());
        List<Suggestions.Title> out = new ArrayList<>();
        for (int pages = 1; ; pages++) {
            List<Suggestions.Title> got = Suggestions.parseViaplay(page, tv, now);
            out.addAll(genre != null && url == null ? genre.filter(got) : got);
            String next = Suggestions.viaplayNext(page);
            if (next == null || pages >= 3 || out.size() >= perRow) return out;
            page = tmdb.open(next);
        }
    }

    /**
     * Netflix and Disney+ ids from Wikidata for the titles on those services, so OK can open the title itself.
     * One query per row; without an answer OK opens the app.
     */
    private void linkIds(List<Suggestions.Title> row) {
        long now = System.currentTimeMillis();
        List<Suggestions.Title> ask = new ArrayList<>();
        synchronized (idsLock) {
            JSONObject known = ids();
            for (Suggestions.Title t : row) {
                if (t.id <= 0) continue;
                boolean wanted = false;
                for (Suggestions.Service s : t.services) {
                    wanted |= s.pkg.equals(Suggestions.NETFLIX) || s.pkg.equals(Suggestions.DISNEY);
                }
                if (!wanted) continue;
                JSONObject e = known.optJSONObject(idKey(t));
                if (e != null && now - e.optLong("at") < tmdb.freshMs() && e.optLong("at") >= tmdb.refreshedAt()) {
                    t.netflixId = e.optString("n", "");
                    t.disneyId = e.optString("d", "");
                } else {
                    ask.add(t);
                }
            }
        }
        if (ask.isEmpty()) return;
        try {
            Suggestions.applyWikidata(tmdb.open("https://query.wikidata.org/sparql?format=json&query="
                    + URLEncoder.encode(Suggestions.wikidataQuery(ask), "UTF-8")), ask);
            synchronized (idsLock) {
                JSONObject known = ids();
                for (Suggestions.Title t : ask) {
                    known.put(idKey(t), new JSONObject().put("n", t.netflixId).put("d", t.disneyId).put("at", now));
                }
                saveIds(now);
            }
        } catch (Exception e) {
            Log.w(TAG, "wikidata: " + e);
        }
    }

    private static String idKey(Suggestions.Title t) {
        return (t.tv ? "t" : "m") + t.id;
    }

    /** The Netflix and Disney+ ids asked so far, by title, also "none"; read from disk once. */
    private JSONObject ids() {
        if (ids == null) {
            try {
                ids = new JSONObject(new String(Files.readAllBytes(idsFile().toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                ids = new JSONObject();
            }
        }
        return ids;
    }

    /** Writes the ids back, without the ones older than cache_hours, through a temporary file. */
    private void saveIds(long now) {
        try {
            JSONObject keep = new JSONObject();
            for (java.util.Iterator<String> it = ids.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject e = ids.optJSONObject(k);
                if (e != null && now - e.optLong("at") < tmdb.freshMs()) keep.put(k, e);
            }
            ids = keep;
            File tmp = new File(getFilesDir(), "wikidata-ids.json.tmp");
            Files.write(tmp.toPath(), keep.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(idsFile())) tmp.delete();
        } catch (Exception e) {
            Log.w(TAG, "save ids: " + e);
        }
    }

    private File idsFile() {
        return new File(getFilesDir(), "wikidata-ids.json");
    }

    /** The providers that include t in a subscription here, or null when TMDb can't say. */
    private Set<Integer> streamsOn(Suggestions.Title t) {
        String key = (t.tv ? "tv" : "movie") + t.id;
        Set<Integer> hit = streams.get(key);
        if (hit != null) return hit;
        try {
            Set<Integer> ids = Suggestions.flatrate(tmdb.json(Suggestions.watchPath(t)), region);
            streams.put(key, ids);
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
            for (Section s : new Section[] {movies, series}) {
                s.reset();
                s.fetched.put(key(null, r.top), s.tv ? r.series : r.movies);
                fill(s, s.tv ? r.series : r.movies);
                s.showing = key(null, r.top);
                s.enableTabs();
                topToggle.setFocusable(true);
                sortButton.setFocusable(true);
            }
            // After Refresh the focus stays on the button.
            View first = firstTile();
            if (first != null && !refreshing) first.requestFocus();
        }
        refreshing = false;
        StringBuilder line = new StringBuilder(r.error != 0 ? getString(r.error)
                : getString(R.string.updated, new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date())));
        if (!r.unmatched.isEmpty()) line.append("  ·  ").append(getString(R.string.unmatched, TextUtils.join(", ", r.unmatched)));
        status.setText(line);
    }

    private void fill(Section s, List<Suggestions.Title> titles) {
        if (titles.isEmpty()) {
            placeholder(s, R.string.empty_genre);
            return;
        }
        s.row.removeAllViews();
        for (Suggestions.Title t : Suggestions.sorted(titles, order)) s.row.addView(tile(t));
        ((HorizontalScrollView) s.row.getParent()).scrollTo(0, 0);
    }

    /** A line of text in place of the tiles, as tall as they are, so the screen does not jump. */
    private void placeholder(Section s, int text) {
        s.row.removeAllViews();
        TextView t = text(14, Typeface.NORMAL, 0x99FFFFFF);
        t.setText(text);
        t.setGravity(Gravity.CENTER_VERTICAL);
        s.row.addView(t, new LinearLayout.LayoutParams(-2, dp(135)));
    }

    private int genreLabel(Suggestions.Genre g) {
        switch (g.key) {
            case "drama": return R.string.genre_drama;
            case "comedy": return R.string.genre_comedy;
            case "crime": return R.string.genre_crime;
            case "action": return R.string.genre_action;
            case "scifi": return R.string.genre_scifi;
            case "documentary": return R.string.genre_documentary;
            case "entertainment": return R.string.genre_entertainment;
            case "family": return R.string.genre_family;
            default: return R.string.genre_animation;
        }
    }

    /** Selected: filled with the accent. Focused: outlined. */
    private StateListDrawable tabBackground() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_selected, android.R.attr.state_focused}, pill(accent, 0xFFFFFFFF));
        d.addState(new int[] {android.R.attr.state_focused}, pill(0x40FFFFFF, 0xFFFFFFFF));
        d.addState(new int[] {android.R.attr.state_selected}, pill(accent, 0));
        d.addState(new int[] {}, pill(0x1FFFFFFF, 0));
        return d;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
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
        if (!t.poster.isEmpty()) images.load(poster, t.poster);

        tile.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(120).start();
            if (focused) describe(t);
        });
        tile.setOnClickListener(v -> showDetail(t, v));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(135));
        lp.setMargins(0, 0, dp(12), 0);
        tile.setLayoutParams(lp);
        return tile;
    }

    /** Year and rating, and the services that have the title unless the detail page's buttons show them. */
    private static String metaLine(Suggestions.Title t, boolean services) {
        List<String> parts = new ArrayList<>();
        if (!t.year.isEmpty()) parts.add(t.year);
        if (t.rating > 0) parts.add(String.format(Locale.ROOT, "★ %.1f", t.rating));
        if (services) {
            List<String> names = new ArrayList<>();
            for (Suggestions.Service s : t.services) names.add(s.label());
            parts.add(TextUtils.join(", ", names));
        }
        return TextUtils.join("  ·  ", parts);
    }

    private void describe(Suggestions.Title t) {
        title.setText(t.name);
        meta.setText(metaLine(t, true));
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

    /**
     * Opens the title on service s: a link to it where there is one (Netflix, Disney+, HBO Max, Prime Video, DRTV,
     * Viaplay), else the app's search for it (Prime Video, HBO Max), else the app at its home screen. When s has no
     * link yet and is HBO Max, Netflix, Disney+ or Prime Video, Movie of the Night is asked first, once per title
     * (its answer covers every service): one request per press, never while browsing.
     */
    private void open(Suggestions.Title t, Suggestions.Service s) {
        PackageManager pm = getPackageManager();
        if (!t.linksAsked && t.id > 0 && !BuildConfig.MOTN_KEY.isEmpty()) {
            if (link(t, s) == null && Suggestions.motnService(s.pkg) != null) {
                t.linksAsked = true;
                new Thread(() -> {
                    Map<String, String> found = Collections.emptyMap();
                    try {
                        found = Suggestions.parseMotn(tmdb.open(Suggestions.motnPath(t, region), "X-API-Key",
                                BuildConfig.MOTN_KEY), region);
                    } catch (Exception e) {
                        Log.w(TAG, "movie of the night: " + e);
                    }
                    final Map<String, String> links = found;
                    main.post(() -> {
                        t.links.putAll(links);
                        if (!isDestroyed()) open(t, s);
                    });
                }).start();
                return;
            }
        }
        // First a link to the title itself, then the app's search for it (Prime Video, HBO Max), then the app.
        for (int step = 0; step < 3; step++) {
            Intent i = intent(t, s, step, pm);
            if (i == null) continue;
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception e) {
                Log.w(TAG, "open " + s.pkg + ": " + e);
            }
        }
        Toast.makeText(this, getString(R.string.not_installed, s.label()), Toast.LENGTH_SHORT).show();
    }

    /** What open() tries for t on s at a step: 0 a link to the title, 1 the app's search for it, 2 the app; null if none. */
    private static Intent intent(Suggestions.Title t, Suggestions.Service s, int step, PackageManager pm) {
        if (s.pkg.isEmpty()) return null;
        Intent i = step == 0 ? link(t, s) : step == 1 ? search(t, s) : pm.getLeanbackLaunchIntentForPackage(s.pkg);
        if (i == null && step == 2) i = pm.getLaunchIntentForPackage(s.pkg);
        return i == null || i.resolveActivity(pm) == null ? null : i;
    }

    /**
     * A link the app opens the title from, as tested on a Philips Android TV; null when there is no id. Netflix,
     * Disney+ and HBO Max ignore a link sent to the screen they are on, so CLEAR_TOP opens that screen afresh.
     */
    private static Intent link(Suggestions.Title t, Suggestions.Service s) {
        String url;
        if (t.links.containsKey(s.pkg)) {
            url = t.links.get(s.pkg);
        } else if (s.pkg.equals(Suggestions.NETFLIX) && !t.netflixId.isEmpty()) {
            url = "https://www.netflix.com/title/" + t.netflixId;
        } else if (s.pkg.equals(Suggestions.DISNEY) && !t.disneyId.isEmpty()) {
            url = "https://www.disneyplus.com/" + (t.tv ? "series/" : "movies/") + Suggestions.slug(t.name) + "/" + t.disneyId;
        } else if (s.pkg.equals(Suggestions.DRTV) && !t.drPath.isEmpty()) {
            url = "https://www.dr.dk/drtv" + t.drPath;
        } else if (s.pkg.equals(Suggestions.VIAPLAY) && !t.viaplayPath.isEmpty()) {
            url = "https://viaplay.dk" + t.viaplayPath;
        } else {
            return null;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(s.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // DRTV restarts at its front page when its running screen is opened afresh, and without CLEAR_TOP it drops
        // the link on a cold start; SINGLE_TOP hands the link to the running screen instead, and both cases work.
        if (s.pkg.equals(Suggestions.DRTV)) i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // Netflix opens the title only when it is told where the link came from.
        if (s.pkg.equals(Suggestions.NETFLIX)) i.putExtra("source", "30");
        return i;
    }

    /**
     * The app's search for the title, where the app takes a search link (tested: Prime Video, HBO Max), for a title
     * without a link of its own. Better than the app itself, which comes back on whatever it showed last.
     */
    private static Intent search(Suggestions.Title t, Suggestions.Service s) {
        String url = Suggestions.searchUrl(s.pkg, t.name);
        return url == null ? null : new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(s.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private LinearLayout row(LinearLayout col) {
        HorizontalScrollView scroll = new HorizontalScrollView(this) {
            /** One tile early: the tile next to the focused one stays in view too, so the row moves on the one before last. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                Rect wider = new Rect(rect);
                wider.inset(-dp(92 + 12), 0);
                return super.computeScrollDeltaToGetChildRectOnScreen(wider);
            }
        };
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
