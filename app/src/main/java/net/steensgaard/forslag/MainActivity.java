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
 * the services in res/values/config.xml, a Top rated switch, an Age button and a Sort button. Only the region, the language, the
 * genre, the rating, the age label and the provider or title ids go to TMDb, with the key; Wikidata gets the TMDb ids of the titles
 * on Netflix and Disney+, and Movie of the Night one title's id when Watch is pressed on it. OK on a title shows its
 * detail page, with a Watch button per service that opens the title in that app where there is a link to it, and a
 * Back button that (like the remote's) returns to the rows. Every answer is cached for cache_hours.
 */
public class MainActivity extends Activity {
    static final String TAG = "Forslag";
    private static final long BACKDROP_DELAY_MS = 300;
    private static final long AGE_DELAY_MS = 700;
    /** The same for the genre tabs, which turn on and off one at a time. */
    private static final long GENRE_DELAY_MS = 700;
    /** The width of the poster on the detail page, and of the buttons below it, in dp. */
    private static final int POSTER_W = 180;
    /** A poster tile in the rows, in dp: small enough that the top of a third row shows under two. */
    private static final int TILE_W = 86, TILE_H = 127;
    /** How many actors the detail page names. */
    private static final int CAST_MAX = 6;
    /** How many of an actor's best-known titles are checked for your services. */
    private static final int PERSON_MAX = 40;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int accent;
    private Tmdb tmdb;
    private Images images;
    private ImageView backdrop;
    private TextView title, meta, overview;
    private Section movies, series;
    private String region, language;
    private int perRow, minVotes;
    /** "Top rated" on: only titles rated at least topMin, in both rows. Kept between launches. */
    private volatile boolean topRated;
    private double topMin;
    private SharedPreferences prefs;
    private TextView topToggle, sortButton, refreshButton, ageButton;
    /** The age groups the Age button steps through, youngest first: each the region's labels joined with | (config.xml). */
    private List<String> ages;
    /** Their names on the button, in the same order. */
    private String[] ageNames;
    /** The age group shown, or "" for every title. Kept between launches. */
    private volatile String age;
    /** Fetches the rows for the age chosen once the Age button has rested, so stepping past a few asks no server. */
    private final Runnable applyAge = () -> {
        for (Section s : new Section[] {movies, series}) s.choose(s.genres);
    };
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
    private TextView detailTitle, detailMeta, detailNext, detailOverview, backButton, trailerButton;
    /** "Starring" and the actors' buttons. */
    private LinearLayout detailCast, castChips;
    /** An actor's page: their films and series on your services, over the rows and under the detail page. */
    private FrameLayout personPage;
    private TextView personName, personBorn, personBio, personInfo, personStatus, personMoviesLabel, personSeriesLabel;
    private ImageView personPhoto;
    private LinearLayout personMovies, personSeries;
    /** The detail page the actor's page came from, and where that page came from, for Back. */
    private Suggestions.Title personFrom;
    private View personFromReturn;
    private int personId;
    /** Bumped on every actor's page, so a slow answer for one that was left is dropped. */
    private int personGeneration;
    /** The poster and the Trailer button on it. */
    private FrameLayout posterFrame;
    /** The trailer player, full screen over everything while it plays. */
    private Trailer trailer;
    /** The Watch buttons, one per service that has the title, and Back below them. */
    private LinearLayout detailButtons;
    /** The title on the detail page, and the tile the focus goes back to when it closes. */
    private Suggestions.Title detailOf;
    private View returnTo;
    /** "Saved for later", kept in the app's files; its row is below the series, and hidden while it is empty. */
    private Watchlist saved;
    private TextView savedHead, saveButton;
    private LinearLayout savedRow;

    /** The movie or series row: its genre tabs, its tiles, and the rows already fetched, by genre. */
    private final class Section {
        final boolean tv;
        final LinearLayout row;
        final List<TextView> tabs = new ArrayList<>();
        final Map<String, List<Suggestions.Title>> fetched = new HashMap<>();
        /** The genre tabs turned on, in the tabs' order; empty for All. A title must have every one of them. */
        final List<Suggestions.Genre> genres = new ArrayList<>();
        /** Fetches the row once the genre tabs have rested, so turning on two in a row asks the servers once. */
        final Runnable apply = () -> choose(genres);
        /** The key of the row on screen (genre and filter), or null while one is loading. */
        String showing;
        /** Bumped on every choice, so a slow answer for a tab that was left is dropped. */
        int generation;

        Section(LinearLayout col, boolean tv, int label) {
            this.tv = tv;
            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(0, dp(4), 0, dp(1));
            TextView name = text(13, Typeface.BOLD, 0xE6FFFFFF);
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
            LinearLayout block = block(col);
            block.addView(head);
            row = row(block);
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
            t.setOnClickListener(v -> toggle(g));
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
            for (TextView t : tabs) {
                Object g = t.getTag();
                t.setSelected(g == null ? genres.isEmpty() : genres.contains(g));
            }
        }

        /** OK on a tab: All turns every genre off; a genre tab turns on or off. The row follows when the tabs rest. */
        void toggle(Suggestions.Genre g) {
            if (g == null) {
                genres.clear();
            } else if (!genres.remove(g)) {
                List<Suggestions.Genre> next = new ArrayList<>();
                for (Suggestions.Genre o : Suggestions.GENRES) {
                    if (o == g || genres.contains(o)) next.add(o);
                }
                genres.clear();
                genres.addAll(next);
            }
            mark();
            main.removeCallbacks(apply);
            if (g == null) choose(genres);
            else main.postDelayed(apply, GENRE_DELAY_MS);
        }

        void reset() {
            main.removeCallbacks(apply);
            fetched.clear();
            genres.clear();
            showing = null;
            generation++;
            mark();
        }

        void choose(List<Suggestions.Genre> chosen) {
            final List<Suggestions.Genre> g = new ArrayList<>(chosen);
            final boolean top = topRated;
            final String maxAge = age;
            final String key = key(g, top, maxAge);
            if (key.equals(showing)) return;
            if (chosen != genres) {
                genres.clear();
                genres.addAll(g);
            }
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
                    titles = buildRow(tv, g, top, maxAge);
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

    /** What a section keeps a fetched row under: its genre, whether only top rated titles were asked for, and the age. */
    private static String key(List<Suggestions.Genre> gs, boolean top, String maxAge) {
        StringBuilder b = new StringBuilder();
        for (Suggestions.Genre g : gs) b.append(b.length() > 0 ? "+" : "").append(g.key);
        return (gs.isEmpty() ? "all" : b.toString()) + (top ? ":top" : "") + (maxAge.isEmpty() ? "" : ":age" + maxAge);
    }

    /** The next age group on OK: all, then each group from the youngest, then all again. The rows follow when it rests. */
    private void nextAge() {
        int i = ages.indexOf(age);
        age = i + 1 < ages.size() ? ages.get(i + 1) : "";
        prefs.edit().putString("age", age).apply();
        showAge();
        main.removeCallbacks(applyAge);
        main.postDelayed(applyAge, AGE_DELAY_MS);
    }

    /** "Age: All", then the group's name: "Age: Kids" and so on. */
    private void showAge() {
        int i = ages.indexOf(age);
        String value = i < 0 ? getString(R.string.age_all) : i < ageNames.length ? ageNames[i] : age;
        ageButton.setText(getString(R.string.age, value));
        ageButton.setSelected(!age.isEmpty());
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
        for (Section s : new Section[] {movies, series}) s.choose(s.genres);
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
        // The icon turns while the sources are asked.
        refreshButton.animate().rotationBy(360f * 30).setDuration(30_000).setInterpolator(null).start();
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
        ages = Arrays.asList(getResources().getStringArray(R.array.age_groups));
        ageNames = getResources().getStringArray(R.array.age_group_names);
        age = prefs.getString("age", "");
        // A group no longer in config.xml counts as all.
        if (!ages.contains(age)) age = "";

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
        // Up to 60% of the screen, and never under the buttons in the corner (see the controls' layout listener).
        final int titleMax = Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f);
        title.setMaxWidth(titleMax);
        header.addView(title, new LinearLayout.LayoutParams(-2, -2));
        topToggle = text(13, Typeface.NORMAL, 0xFFFFFFFF);
        // Only the star; what it does is said to TalkBack.
        topToggle.setText(R.string.top_rated_icon);
        topToggle.setContentDescription(getString(R.string.top_rated, getString(R.string.top_rated_min)));
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
        ageButton = text(13, Typeface.NORMAL, 0xFFFFFFFF);
        ageButton.setPadding(dp(12), dp(4), dp(12), dp(4));
        ageButton.setBackground(tabBackground());
        ageButton.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}},
                new int[] {0xFF15171C, 0xFFFFFFFF}));
        ageButton.setFocusable(false);
        ageButton.setOnClickListener(v -> nextAge());
        ageButton.setVisibility(ages.isEmpty() ? View.GONE : View.VISIBLE);
        if (!ages.isEmpty()) showAge();
        // Refresh: an icon, faint until it has the focus, left of the other buttons in the corner.
        refreshButton = text(15, Typeface.BOLD, 0xFFFFFFFF);
        refreshButton.setText(R.string.refresh_icon);
        refreshButton.setContentDescription(getString(R.string.refresh));
        refreshButton.setGravity(Gravity.CENTER);
        refreshButton.setPadding(dp(10), dp(3), dp(10), dp(3));
        StateListDrawable faint = new StateListDrawable();
        faint.addState(new int[] {android.R.attr.state_focused}, pill(0x40FFFFFF, 0xFFFFFFFF));
        faint.addState(new int[] {}, pill(0x0FFFFFFF, 0));
        refreshButton.setBackground(faint);
        refreshButton.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {0xFFFFFFFF, 0x59FFFFFF}));
        // Usable from the start, so a first load that failed can be tried again.
        refreshButton.setFocusable(true);
        refreshButton.setOnClickListener(v -> refresh());
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.setMargins(0, 0, dp(8), 0);
        controls.addView(refreshButton, gap);
        controls.addView(sortButton, gap);
        controls.addView(ageButton, gap);
        controls.addView(topToggle);
        top.addView(controls, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        // The title ends a little before the buttons, however wide their labels make them ("Alder: op til 15").
        controls.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int max = Math.max(0, Math.min(titleMax, l - dp(24)));
            if (title.getMaxWidth() != max) title.post(() -> title.setMaxWidth(max));
        });
        meta = oneLine(text(15, Typeface.NORMAL, accent));
        meta.setPadding(0, dp(6), 0, dp(8));
        header.addView(meta);
        overview = text(15, Typeface.NORMAL, 0xCCFFFFFF);
        overview.setMaxLines(2);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(0, 1.15f);
        header.addView(overview, new LinearLayout.LayoutParams(Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f), -2));

        // The rows scroll up and down with the focus under the header, which stays: two fit on the screen, and with
        // "Saved for later" there are three. Out to the screen's sides, so a row still runs on to the right edge.
        final LinearLayout rows = new LinearLayout(this);
        // A whole row at a time, its heading with it: the one the focus is in is scrolled fully into view.
        ScrollView rowsScroll = new ScrollView(this) {
            @Override
            public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                View block = focused;
                while (block != null && block.getParent() != rows) {
                    block = block.getParent() instanceof View ? (View) block.getParent() : null;
                }
                if (block == null) return;
                int y = getScrollY(), to = y;
                if (block.getBottom() > y + getHeight()) to = block.getBottom() - getHeight();
                if (block.getTop() < to) to = block.getTop();
                if (to != y) smoothScrollTo(0, to);
            }
        };
        rowsScroll.setVerticalScrollBarEnabled(false);
        rowsScroll.setClipToPadding(false);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(48), 0, dp(48), dp(4));
        rows.setClipToPadding(false);
        rows.setClipChildren(false);
        rowsScroll.addView(rows);
        // col draws its children unclipped (the rows run past its padding), so the scrolled rows are cut at the
        // edges of the scroll view by this frame instead, clear of the header and of Refresh.
        FrameLayout rowsFrame = new FrameLayout(this);
        rowsFrame.setClipChildren(true);
        rowsFrame.addView(rowsScroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        rlp.setMargins(dp(-48), 0, dp(-48), 0);
        col.addView(rowsFrame, rlp);
        movies = new Section(rows, false, R.string.movies);
        series = new Section(rows, true, R.string.series);
        // Saved for later, at the bottom.
        LinearLayout savedBlock = block(rows);
        savedHead = text(13, Typeface.BOLD, 0xE6FFFFFF);
        savedHead.setText(R.string.saved_row);
        savedHead.setPadding(0, dp(6), 0, dp(3));
        savedBlock.addView(savedHead);
        savedRow = row(savedBlock);

        saved = readSaved();
        showSaved();


        buildPerson(root);
        buildDetail(root);
        trailer = new Trailer(this, root);
        placeholder(movies, R.string.loading);
        placeholder(series, R.string.loading);
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
        // Seen only for a title without a poster, behind the buttons.
        shape.setColor(0x1FFFFFFF);
        detailPoster.setBackground(shape);
        // The poster, with Save and Trailer on it: see-through until they have the focus (Up from the first button).
        posterFrame = new FrameLayout(this);
        posterFrame.addView(detailPoster, new FrameLayout.LayoutParams(-1, -1));
        // Side by side, icons only: Save (＋, ✓ once saved) and Trailer (▶).
        LinearLayout onPoster = new LinearLayout(this);
        onPoster.setOrientation(LinearLayout.HORIZONTAL);
        onPoster.setGravity(Gravity.CENTER_VERTICAL);
        saveButton = posterButton();
        saveButton.setOnClickListener(v -> {
            if (detailOf != null) toggleSaved(detailOf);
        });
        onPoster.addView(saveButton);
        trailerButton = posterButton();
        trailerButton.setText(R.string.trailer_icon);
        trailerButton.setContentDescription(getString(R.string.trailer_play));
        trailerButton.setVisibility(View.GONE);
        trailerButton.setOnClickListener(v -> {
            if (detailOf != null && detailOf.details != null) trailer.play(detailOf.details.trailer);
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMargins(dp(12), 0, 0, 0);
        onPoster.addView(trailerButton, tlp);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        olp.setMargins(0, 0, 0, dp(14));
        posterFrame.addView(onPoster, olp);
        left.addView(posterFrame, new LinearLayout.LayoutParams(-1, dp(250)));
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
        // A series' next episode, or one that came out this week; hidden otherwise.
        detailNext = oneLine(text(16, Typeface.BOLD, 0xFFFFFFFF));
        detailNext.setPadding(0, 0, 0, dp(16));
        words.addView(detailNext);
        // The actors, once TMDb has answered, each a button (Right from the buttons): OK shows their films and
        // series on your services. Hidden until then and for a title TMDb knows none for.
        detailCast = new LinearLayout(this);
        detailCast.setOrientation(LinearLayout.HORIZONTAL);
        detailCast.setGravity(Gravity.CENTER_VERTICAL);
        detailCast.setPadding(0, 0, 0, dp(12));
        TextView starring = text(15, Typeface.NORMAL, 0xB3FFFFFF);
        starring.setText(R.string.starring);
        starring.setPadding(0, 0, dp(10), 0);
        detailCast.addView(starring);
        HorizontalScrollView castScroll = new HorizontalScrollView(this);
        castScroll.setHorizontalScrollBarEnabled(false);
        castScroll.setClipToPadding(false);
        castScroll.setPadding(0, dp(4), dp(24), dp(4));
        castChips = new LinearLayout(this);
        castChips.setOrientation(LinearLayout.HORIZONTAL);
        castScroll.addView(castChips);
        detailCast.addView(castScroll, new LinearLayout.LayoutParams(0, -2, 1f));
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

    /**
     * An actor's page: their name, the focused title's details, and a row of their films and a row of their series
     * that are on your services. Under the detail page, so a title on it opens over it. Hidden at first.
     */
    private void buildPerson(FrameLayout root) {
        personPage = new FrameLayout(this);
        personPage.setBackgroundColor(0xFF000000);
        personPage.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(24), dp(48), dp(14));
        col.setClipToPadding(false);
        col.setClipChildren(false);
        personPage.addView(col, new FrameLayout.LayoutParams(-1, -1));
        // The photo on the left; name, birth, biography and the focused title on the right.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(head, new LinearLayout.LayoutParams(-1, dp(150)));
        personPhoto = new ImageView(this);
        personPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        personPhoto.setClipToOutline(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setColor(0x1FFFFFFF);
        personPhoto.setBackground(shape);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(100), -1);
        plp.setMargins(0, 0, dp(20), 0);
        head.addView(personPhoto, plp);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        head.addView(words, new LinearLayout.LayoutParams(0, -1, 1f));
        personName = oneLine(text(30, Typeface.BOLD, 0xFFFFFFFF));
        words.addView(personName);
        personBorn = oneLine(text(14, Typeface.NORMAL, 0x99FFFFFF));
        personBorn.setPadding(0, dp(6), 0, 0);
        words.addView(personBorn);
        personBio = text(14, Typeface.NORMAL, 0xCCFFFFFF);
        personBio.setEllipsize(TextUtils.TruncateAt.END);
        personBio.setLineSpacing(0, 1.15f);
        personBio.setPadding(0, dp(8), dp(80), 0);
        // The biography gets the room left in the head: as many lines as fit, then an ellipsis.
        personBio.addOnLayoutChangeListener((v, l, top, r, bottom, ol, ot, or, ob) -> {
            int room = bottom - top - personBio.getPaddingTop();
            int lines = Math.max(1, room / personBio.getLineHeight());
            if (personBio.getMaxLines() != lines) personBio.post(() -> personBio.setMaxLines(lines));
        });
        words.addView(personBio, new LinearLayout.LayoutParams(-1, 0, 1f));
        personInfo = oneLine(text(15, Typeface.NORMAL, accent));
        personInfo.setPadding(0, dp(6), 0, 0);
        words.addView(personInfo);
        personStatus = text(15, Typeface.NORMAL, 0x99FFFFFF);
        personStatus.setPadding(0, dp(12), 0, 0);
        col.addView(personStatus);
        personMoviesLabel = text(13, Typeface.BOLD, 0xE6FFFFFF);
        personMoviesLabel.setText(R.string.movies);
        personMoviesLabel.setPadding(0, dp(8), 0, dp(2));
        col.addView(personMoviesLabel);
        personMovies = row(col);
        personSeriesLabel = text(13, Typeface.BOLD, 0xE6FFFFFF);
        personSeriesLabel.setText(R.string.series);
        personSeriesLabel.setPadding(0, dp(8), 0, dp(2));
        col.addView(personSeriesLabel);
        personSeries = row(col);
        root.addView(personPage, new FrameLayout.LayoutParams(-1, -1));
    }

    /**
     * OK on an actor: their page instead of the detail page, filled once TMDb has answered. One request for their
     * photo, biography and credits (one more for an English biography where there is none in the language), and
     * one per title not yet known for where it streams; all cached like the rows' answers.
     */
    private void openPerson(int id, String name) {
        personFrom = detailOf;
        personFromReturn = returnTo;
        personId = id;
        trailer.close();
        detail.setVisibility(View.GONE);
        detailOf = null;
        returnTo = null;
        personName.setText(name);
        personBorn.setText("");
        personBorn.setVisibility(View.GONE);
        personBio.setText("");
        personPhoto.setTag(null);
        personPhoto.setImageDrawable(null);
        personInfo.setText("");
        personStatus.setText(R.string.loading);
        personStatus.setVisibility(View.VISIBLE);
        showPersonRow(personMoviesLabel, personMovies, Collections.emptyList());
        showPersonRow(personSeriesLabel, personSeries, Collections.emptyList());
        personPage.setVisibility(View.VISIBLE);
        personPage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        final int asked = ++personGeneration;
        new Thread(() -> {
            Suggestions.Person p;
            try {
                p = loadPerson(id);
            } catch (Exception e) {
                Log.w(TAG, "person " + id + ": " + e);
                p = null;
            }
            final Suggestions.Person result = p;
            main.post(() -> {
                if (asked != personGeneration || isDestroyed() || personPage.getVisibility() != View.VISIBLE) return;
                showPerson(result, name);
            });
        }).start();
    }

    /**
     * An actor, with only their films and series that are on your services in credits, most popular first: at most
     * PERSON_MAX of their titles are checked for where they stream here. Runs off the main thread.
     */
    private Suggestions.Person loadPerson(int id) throws Exception {
        Suggestions.Person p = Suggestions.parsePerson(tmdb.json(Suggestions.personPath(id, language, true)), minVotes);
        if (p.bio.isEmpty() && !language.startsWith("en")) {
            try {
                p.bio = Suggestions.parsePerson(tmdb.json(Suggestions.personPath(id, "en-US", false)), minVotes).bio;
            } catch (Exception e) {
                Log.w(TAG, "person " + id + " in English: " + e);
            }
        }
        List<Suggestions.Title> some = new ArrayList<>(p.credits.subList(0, Math.min(PERSON_MAX, p.credits.size())));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (Suggestions.Title t : some) pool.execute(() -> streamsOn(t));
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        List<Suggestions.Service> services = found;
        p.credits.clear();
        for (Suggestions.Title t : some) {
            Set<Integer> on = streamsOn(t);
            if (on == null) continue;
            for (Suggestions.Service s : services) {
                if (s.providerId >= 0 && on.contains(s.providerId) && !t.services.contains(s)) t.services.add(s);
            }
            if (!t.services.isEmpty()) p.credits.add(t);
        }
        linkIds(p.credits);
        return p;
    }

    private void showPerson(Suggestions.Person p, String name) {
        if (p == null) {
            personStatus.setText(R.string.offline);
            return;
        }
        if (!p.photo.isEmpty()) images.load(personPhoto, p.photo);
        String born = bornLine(p);
        personBorn.setText(born);
        personBorn.setVisibility(born.isEmpty() ? View.GONE : View.VISIBLE);
        personBio.setText(p.bio);
        List<Suggestions.Title> films = new ArrayList<>(), shows = new ArrayList<>();
        for (Suggestions.Title t : p.credits) (t.tv ? shows : films).add(t);
        if (p.credits.isEmpty()) {
            personStatus.setText(getString(R.string.person_none, name));
            return;
        }
        personStatus.setVisibility(View.GONE);
        showPersonRow(personMoviesLabel, personMovies, films);
        showPersonRow(personSeriesLabel, personSeries, shows);
        View first = firstPersonTile();
        if (first != null) first.requestFocus();
    }

    /** "Born 18 December 1963 in Shawnee, Oklahoma, USA", and when they died; "" when TMDb knows neither. */
    private String bornLine(Suggestions.Person p) {
        String line = "";
        if (!p.born.isEmpty()) {
            line = p.place.isEmpty() ? getString(R.string.born, date(p.born))
                    : getString(R.string.born_in, date(p.born), p.place);
        }
        if (!p.died.isEmpty()) {
            String died = getString(R.string.died, date(p.died));
            line = line.isEmpty() ? died : line + "  ·  " + died;
        }
        return line;
    }

    /** "18 December 1963" in the app's language. */
    private String date(String iso) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso);
            Locale here = getResources().getConfiguration().getLocales().get(0);
            return new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(here, "dMMMMy"), here).format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    private void showPersonRow(TextView label, LinearLayout row, List<Suggestions.Title> titles) {
        row.removeAllViews();
        for (Suggestions.Title t : titles) row.addView(tile(t, this::describePerson));
        int shown = titles.isEmpty() ? View.GONE : View.VISIBLE;
        label.setVisibility(shown);
        ((View) row.getParent()).setVisibility(shown);
        ((HorizontalScrollView) row.getParent()).scrollTo(0, 0);
    }

    /** The focused title on an actor's page: its name, year, rating and services under the actor's name. */
    private void describePerson(Suggestions.Title t) {
        String meta = metaLine(t, true);
        personInfo.setText(meta.isEmpty() ? t.name : t.name + "  ·  " + meta);
    }

    private View firstPersonTile() {
        return personMovies.getChildCount() > 0 ? personMovies.getChildAt(0)
                : personSeries.getChildCount() > 0 ? personSeries.getChildAt(0) : null;
    }

    /** Back on an actor's page: the detail page it came from, with the focus on that actor. */
    private void closePerson() {
        personGeneration++;
        personPage.setVisibility(View.GONE);
        showPersonRow(personMoviesLabel, personMovies, Collections.emptyList());
        showPersonRow(personSeriesLabel, personSeries, Collections.emptyList());
        Suggestions.Title t = personFrom;
        View from = personFromReturn;
        personFrom = null;
        personFromReturn = null;
        if (t == null) {
            content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            View first = firstTile();
            if (first != null) first.requestFocus();
            return;
        }
        showDetail(t, from);
        for (int i = 0; i < castChips.getChildCount(); i++) {
            View c = castChips.getChildAt(i);
            if (Integer.valueOf(personId).equals(c.getTag())) c.requestFocus();
        }
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
        placeTrailer(t);
    }

    /**
     * "▶ Trailer" on the poster, once TMDb's details name a YouTube trailer for t; it plays here, in YouTube's
     * embedded player (Trailer). Hidden without one. On the poster it takes no room from the service buttons. The
     * focus stays where it is.
     */
    private void placeTrailer(Suggestions.Title t) {
        boolean has = t.details != null && !t.details.trailer.isEmpty();
        if (!has && trailerButton.hasFocus()) firstButton().requestFocus();
        trailerButton.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    /** A button on the poster: dark and see-through, filled with the accent while focused. */
    private TextView posterButton() {
        TextView b = oneLine(text(17, Typeface.BOLD, 0xFFFFFFFF));
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(44));
        b.setMinHeight(dp(44));
        b.setPadding(0, 0, 0, 0);
        b.setFocusable(true);
        StateListDrawable on = new StateListDrawable();
        on.addState(new int[] {android.R.attr.state_focused}, round(pill(accent, 0xFFFFFFFF)));
        on.addState(new int[] {}, round(pill(0x99000000, 0x80FFFFFF)));
        b.setBackground(on);
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {0xFF15171C, 0xFFFFFFFF}));
        return b;
    }

    /** "＋ Save" or "✓ Saved" on the detail page's poster, for the title it shows. */
    private void markSave() {
        if (detailOf == null) return;
        boolean has = saved.has(detailOf);
        saveButton.setText(has ? R.string.saved_icon : R.string.save_icon);
        saveButton.setContentDescription(getString(has ? R.string.saved_on : R.string.save));
    }

    /**
     * Saves t for later or removes it (Save on the detail page, or OK held on a tile), says so, and shows the row
     * anew; the focus stays in it if it was there.
     */
    private void toggleSaved(Suggestions.Title t) {
        boolean now = saved.toggle(t, System.currentTimeMillis());
        writeSaved();
        Toast.makeText(this, getString(now ? R.string.saved_toast : R.string.removed_toast, t.name),
                Toast.LENGTH_SHORT).show();
        View focus = getCurrentFocus();
        int at = focus != null && focus.getParent() == savedRow ? savedRow.indexOfChild(focus) : -1;
        showSaved();
        if (at >= 0) {
            View next = savedRow.getChildCount() > 0 ? savedRow.getChildAt(Math.min(at, savedRow.getChildCount() - 1))
                    : firstTile();
            if (next != null) next.requestFocus();
        }
        markSave();
    }

    /** The "Saved for later" row, or none while nothing is saved. */
    private void showSaved() {
        savedRow.removeAllViews();
        List<Suggestions.Title> list = saved.shown();
        for (Suggestions.Title t : list) savedRow.addView(savedTile(t));
        int shown = list.isEmpty() ? View.GONE : View.VISIBLE;
        ((View) savedHead.getParent()).setVisibility(shown);
    }

    /** A tile in the saved row: dimmed when no service of yours has it now, marked when a series has a new episode. */
    private View savedTile(Suggestions.Title t) {
        FrameLayout tile = (FrameLayout) tile(t, this::describeSaved);
        tile.setTag(Watchlist.key(t));
        if (t.services.isEmpty()) {
            for (int i = 0; i < tile.getChildCount(); i++) tile.getChildAt(i).setAlpha(0.35f);
        }
        if (newEpisode(t.details)) {
            TextView badge = oneLine(text(11, Typeface.BOLD, 0xFF15171C));
            badge.setText(R.string.new_episode_badge);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            badge.setBackground(pill(accent, 0));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.setMargins(dp(4), 0, dp(4), dp(6));
            tile.addView(badge, lp);
        }
        return tile;
    }

    /** A focused saved title: as in the other rows, and "Not on your services right now" where that is so. */
    private void describeSaved(Suggestions.Title t) {
        describe(t);
        if (t.services.isEmpty()) {
            String line = metaLine(t, false);
            meta.setText(line.isEmpty() ? getString(R.string.not_available)
                    : line + "  ·  " + getString(R.string.not_available));
        }
    }

    /** Whether a series' latest episode came out in the last week. */
    private static boolean newEpisode(Suggestions.Details d) {
        if (d == null || d.last.date.isEmpty()) return false;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        return d.last.date.compareTo(today) <= 0 && daysAgo(d.last.date) <= 7;
    }

    /** The services in config.xml, as the saved titles name them before the first load has matched them on TMDb. */
    private List<Suggestions.Service> configured() {
        List<Suggestions.Service> services = new ArrayList<>();
        for (String line : getResources().getStringArray(R.array.services)) {
            Suggestions.Service s = Suggestions.parseService(line);
            if (s != null) services.add(s);
        }
        return services;
    }

    private File savedFile() {
        return new File(getFilesDir(), "saved.json");
    }

    private Watchlist readSaved() {
        try {
            return Watchlist.parse(new String(Files.readAllBytes(savedFile().toPath()), StandardCharsets.UTF_8),
                    configured());
        } catch (Exception e) {
            return Watchlist.parse("[]", Collections.emptyList());
        }
    }

    /** Writes the list through a temporary file, so a crash halfway leaves the last one. */
    private void writeSaved() {
        try {
            File tmp = new File(getFilesDir(), "saved.json.tmp");
            Files.write(tmp.toPath(), saved.toJson().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(savedFile())) tmp.delete();
        } catch (Exception e) {
            Log.w(TAG, "save list: " + e);
        }
    }

    /**
     * After a load: which of your services have each saved title now (TMDb for its titles; DRTV and Viaplay keep
     * the ones it was saved with), and a saved series' latest episode, for the badge. Cached like every answer.
     */
    private void checkSaved() {
        final List<Suggestions.Title> list = saved.titles();
        final List<Suggestions.Service> services = found;
        if (list.isEmpty()) return;
        new Thread(() -> {
            Map<Suggestions.Title, List<Suggestions.Service>> on = new HashMap<>();
            Map<Suggestions.Title, Suggestions.Details> details = new HashMap<>();
            for (Suggestions.Title t : list) {
                Set<String> had = new java.util.HashSet<>();
                for (Suggestions.Service s : t.services) had.add(s.names[0]);
                Set<Integer> streaming = t.id > 0 ? streamsOn(t) : null;
                List<Suggestions.Service> now = new ArrayList<>();
                for (Suggestions.Service s : services) {
                    boolean was = had.contains(s.names[0]);
                    if ((s.own() || streaming == null) ? was : streaming.contains(s.providerId)) now.add(s);
                }
                on.put(t, now);
                if (t.tv && t.id > 0 && t.details == null) {
                    try {
                        details.put(t, Suggestions.parseDetails(tmdb.json(Suggestions.detailsPath(t, language)), true,
                                CAST_MAX, language));
                    } catch (Exception e) {
                        Log.w(TAG, "saved details " + t.id + ": " + e);
                    }
                }
            }
            main.post(() -> {
                if (isDestroyed()) return;
                for (Map.Entry<Suggestions.Title, List<Suggestions.Service>> e : on.entrySet()) {
                    e.getKey().services.clear();
                    e.getKey().services.addAll(e.getValue());
                }
                for (Map.Entry<Suggestions.Title, Suggestions.Details> e : details.entrySet()) {
                    if (e.getKey().details == null) e.getKey().details = e.getValue();
                }
                writeSaved();
                View focus = getCurrentFocus();
                Object key = focus != null && focus.getParent() == savedRow ? focus.getTag() : null;
                showSaved();
                for (int i = 0; key != null && i < savedRow.getChildCount(); i++) {
                    if (key.equals(savedRow.getChildAt(i).getTag())) savedRow.getChildAt(i).requestFocus();
                }
            });
        }).start();
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
        markSave();
        if (t.poster.isEmpty()) detailPoster.setImageDrawable(null);
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
        personPage.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
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
        String next = episodeLine(d);
        detailNext.setText(next);
        detailNext.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        detailMeta.setPadding(0, dp(8), 0, next.isEmpty() ? dp(16) : dp(8));
        boolean cast = d != null && !d.cast.isEmpty();
        castChips.removeAllViews();
        for (int i = 0; cast && i < d.cast.size(); i++) {
            int id = i < d.castIds.size() ? d.castIds.get(i) : 0;
            if (id > 0) castChips.addView(castChip(d.cast.get(i), id));
        }
        detailCast.setVisibility(castChips.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /** An actor on the detail page: OK shows their films and series on your services. */
    private TextView castChip(String name, int personId) {
        TextView c = oneLine(text(15, Typeface.NORMAL, 0xFFFFFFFF));
        c.setText(name);
        c.setPadding(dp(12), dp(5), dp(12), dp(5));
        c.setFocusable(true);
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, pill(accent, 0xFFFFFFFF));
        d.addState(new int[] {}, pill(0x26FFFFFF, 0));
        c.setBackground(d);
        c.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {0xFF15171C, 0xFFFFFFFF}));
        c.setTag(personId);
        c.setOnClickListener(v -> openPerson(personId, name));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(6), 0);
        c.setLayoutParams(lp);
        return c;
    }

    /**
     * A series' next episode ("Next episode Friday 14 November (S3 E5)", or "Season 4 starts …" for a season's
     * first), else one that came out in the last week ("New episode …"); "" for a movie, an ended series, or when
     * TMDb doesn't know.
     */
    private String episodeLine(Suggestions.Details d) {
        if (d == null) return "";
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        if (!d.next.date.isEmpty() && d.next.date.compareTo(today) >= 0) {
            String when = day(d.next.date);
            return d.next.number == 1 ? getString(R.string.next_season, d.next.season, when)
                    : getString(R.string.next_episode, when, d.next.season, d.next.number);
        }
        if (!d.last.date.isEmpty() && d.last.date.compareTo(today) <= 0 && daysAgo(d.last.date) <= 7) {
            return getString(R.string.new_episode, day(d.last.date), d.last.season, d.last.number);
        }
        return "";
    }

    /** "Friday 14 November" in the app's language, with the year when it is not this year. */
    private String day(String iso) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso);
            Locale here = getResources().getConfiguration().getLocales().get(0);
            boolean thisYear = iso.startsWith(new SimpleDateFormat("yyyy", Locale.ROOT).format(new Date()));
            String pattern = android.text.format.DateFormat.getBestDateTimePattern(here,
                    thisYear ? "EEEEdMMMM" : "EEEEdMMMMy");
            return new SimpleDateFormat(pattern, here).format(date);
        } catch (Exception e) {
            return iso;
        }
    }

    private static long daysAgo(String iso) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso);
            return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - date.getTime());
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Seasons or runtime, and cast, from TMDb: one request when a detail page opens (none while browsing), cached for cache_hours
     * like every answer. Shown if the page still shows the title when the answer comes.
     */
    private void fetchDetails(Suggestions.Title t) {
        new Thread(() -> {
            Suggestions.Details d;
            try {
                d = Suggestions.parseDetails(tmdb.json(Suggestions.detailsPath(t, language)), t.tv, CAST_MAX,
                        language);
            } catch (Exception e) {
                Log.w(TAG, "details " + t.id + ": " + e);
                return;
            }
            main.post(() -> {
                t.details = d;
                if (t == detailOf && !isDestroyed()) {
                    showDetails(t);
                    placeTrailer(t);
                }
            });
        }).start();
    }

    /** Back to the rows, on the tile the detail page came from (or the first, if the rows were filled meanwhile). */
    private void hideDetail() {
        trailer.close();
        detail.setVisibility(View.GONE);
        // Opened from an actor's page: back to that page, on the tile it came from.
        boolean person = personPage.getVisibility() == View.VISIBLE;
        (person ? personPage : content).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = returnTo != null && returnTo.isAttachedToWindow() ? returnTo
                : person ? firstPersonTile() : savedTileOf(detailOf);
        if (back != null) back.requestFocus();
        detailOf = null;
        returnTo = null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (trailer.showing() && trailer.key(e)) return true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && detail.getVisibility() == View.VISIBLE) {
            if (e.getAction() == KeyEvent.ACTION_UP) hideDetail();
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && personPage.getVisibility() == View.VISIBLE) {
            if (e.getAction() == KeyEvent.ACTION_UP) closePerson();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private View firstTile() {
        if (movies.row.getChildCount() > 0) return movies.row.getChildAt(0);
        return series.row.getChildCount() > 0 ? series.row.getChildAt(0) : savedRow.getChildAt(0);
    }

    /** The saved row's tile for t (shown anew since the detail page opened), else the first tile. */
    private View savedTileOf(Suggestions.Title t) {
        for (int i = 0; t != null && i < savedRow.getChildCount(); i++) {
            if (Watchlist.key(t).equals(savedRow.getChildAt(i).getTag())) return savedRow.getChildAt(i);
        }
        return firstTile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        long stale = getResources().getInteger(R.integer.cache_hours) * 3_600_000L;
        if (loadedAt == 0 || System.currentTimeMillis() - loadedAt > stale) load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Home or another app: the trailer's sound stops with it.
        trailer.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        trailer.close();
        main.removeCallbacksAndMessages(null);
        images.shutdown();
    }

    /** Everything that comes back from one load, built off the main thread. */
    private static final class Result {
        boolean top;
        String age;
        List<Suggestions.Title> movies = new ArrayList<>(), series = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        int error;
    }

    private void load() {
        if (loading) return;
        if (BuildConfig.TMDB_KEY.isEmpty()) {
            placeholder(movies, R.string.no_key);
            placeholder(series, R.string.no_key);
            return;
        }
        loading = true;
        List<Suggestions.Service> services = configured();
        final boolean top = topRated;
        final String maxAge = age;
        new Thread(() -> {
            Result r = new Result();
            r.top = top;
            r.age = maxAge;
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
                r.movies = buildRow(false, Collections.emptyList(), top, maxAge);
                r.series = buildRow(true, Collections.emptyList(), top, maxAge);
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
     * One row: each service's titles (with every genre chosen, or all), mixed in turns, with the TMDb titles checked against
     * what each service really streams, and links looked up. With an age group, only titles with one of its labels.
     * Runs off the main thread.
     */
    private List<Suggestions.Title> buildRow(boolean tv, List<Suggestions.Genre> genres, boolean top, String maxAge)
            throws Exception {
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
                    l = Suggestions.matchingAll(genres, l);
                } else if (s.source.equals(Suggestions.FROM_VIAPLAY)) {
                    l = viaplay(tv, genres);
                } else {
                    l = Suggestions.parseDiscover(tmdb.json(
                            Suggestions.discoverPath(tv, s.providerId, region, language, minVotes, genres, minRating,
                                    maxAge)), tv);
                    // With several genres TMDb can't be asked for all of them in every case (see withGenres).
                    // Keyword genres (romance on series) were asked of TMDb and can't be checked from genre ids.
                    if (genres.size() > 1) l = Suggestions.matchingAll(Suggestions.byIds(tv, genres), l);
                }
            } catch (Tmdb.KeyRefused e) {
                throw e;
            } catch (Exception e) {
                Log.w(TAG, "list " + s.label() + ": " + e);
            }
            // TMDb was asked for these ratings already; DR's and Viaplay's own lists are filtered here.
            if (minRating > 0 && s.own()) l = Suggestions.rated(l, minRating);
            // The same for the age: DR gives no age labels, so DRTV's titles drop out when an age is chosen.
            if (s.own()) l = Suggestions.forAge(l, maxAge);
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
     * has one for a genre chosen, else its "all" pages, filtered by each title's genres so it has every genre chosen.
     * A few pages, about 30 titles.
     */
    private List<Suggestions.Title> viaplay(boolean tv, List<Suggestions.Genre> genres) throws Exception {
        JSONObject all = tmdb.open(Suggestions.viaplayAllUrl(tv));
        // The first genre chosen that Viaplay has a page for; the titles there are checked for the other genres only.
        String url = null;
        List<Suggestions.Genre> rest = new ArrayList<>(genres);
        for (Suggestions.Genre g : genres) {
            url = Suggestions.viaplayGenreUrl(all, g);
            if (url != null) {
                rest.remove(g);
                break;
            }
        }
        JSONObject page = url == null ? all : tmdb.open(url);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String now = iso.format(new Date());
        List<Suggestions.Title> out = new ArrayList<>();
        for (int pages = 1; ; pages++) {
            List<Suggestions.Title> got = Suggestions.parseViaplay(page, tv, now);
            out.addAll(Suggestions.matchingAll(rest, got));
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
                s.fetched.put(key(Collections.emptyList(), r.top, r.age), s.tv ? r.series : r.movies);
                fill(s, s.tv ? r.series : r.movies);
                s.showing = key(Collections.emptyList(), r.top, r.age);
                s.enableTabs();
                topToggle.setFocusable(true);
                sortButton.setFocusable(true);
                ageButton.setFocusable(true);
            }
            // After Refresh the focus stays on the button.
            View first = firstTile();
            if (first != null && !refreshing) first.requestFocus();
            checkSaved();
        }
        refreshing = false;
        refreshButton.animate().cancel();
        refreshButton.setRotation(0);
        if (!r.unmatched.isEmpty()) Log.i(TAG, "not on TMDb: " + TextUtils.join(", ", r.unmatched));
        if (!any && r.error != 0) {
            // Rows from before stay, with a word that they could not be fetched again; else the word in the rows.
            if (loadedAt > 0) {
                Toast.makeText(this, r.error, Toast.LENGTH_LONG).show();
            } else {
                placeholder(movies, r.error);
                placeholder(series, r.error);
            }
        }
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
        s.row.addView(t, new LinearLayout.LayoutParams(-2, dp(TILE_H)));
    }

    private int genreLabel(Suggestions.Genre g) {
        switch (g.key) {
            case "drama": return R.string.genre_drama;
            case "comedy": return R.string.genre_comedy;
            case "romance": return R.string.genre_romance;
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

    /** A pill made a circle, for the icon buttons. */
    private static GradientDrawable round(GradientDrawable g) {
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    private View tile(Suggestions.Title t) {
        return tile(t, this::describe);
    }

    /** A poster tile; focused, it grows and onFocus shows the title's details. OK opens its detail page. */
    private View tile(Suggestions.Title t, java.util.function.Consumer<Suggestions.Title> onFocus) {
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
            if (focused) onFocus.accept(t);
        });
        tile.setOnClickListener(v -> showDetail(t, v));
        // OK held: saved for later, or no longer.
        tile.setOnLongClickListener(v -> {
            toggleSaved(t);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H));
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
            // Disney+ is asked even with Wikidata's id: those are its old /series/<slug>/<id> links, which the app
            // may not open; Movie of the Night has the /browse/entity-… ones. Wikidata's stays the fallback.
            boolean ask = link(t, s) == null || s.pkg.equals(Suggestions.DISNEY);
            if (ask && Suggestions.motnService(s.pkg) != null) {
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

    /** A row with its heading, scrolled into view as one (see the rows' scroll view). */
    private LinearLayout block(LinearLayout rows) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setClipChildren(false);
        rows.addView(b, new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    private LinearLayout row(LinearLayout col) {
        HorizontalScrollView scroll = new HorizontalScrollView(this) {
            /** One tile early: the tile next to the focused one stays in view too, so the row moves on the one before last. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                Rect wider = new Rect(rect);
                wider.inset(-dp(TILE_W + 12), 0);
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
