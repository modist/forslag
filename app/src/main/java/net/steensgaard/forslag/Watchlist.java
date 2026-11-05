package net.steensgaard.forslag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Saved for later": the titles saved on the detail page (or by holding OK on a tile), newest first, at most MAX.
 * Kept as JSON in the app's own files, with what a tile and the detail page need, so the row shows at once, offline
 * too. Nothing here needs Android.
 */
final class Watchlist {
    static final int MAX = 50;

    /** Newest first. */
    private final List<Suggestions.Title> titles = new ArrayList<>();
    private final Map<String, Long> savedAt = new HashMap<>();

    /** One title, whichever row it came from: TMDb's id, else (DRTV, Viaplay) its folded name and year. */
    static String key(Suggestions.Title t) {
        return (t.tv ? "t" : "m") + (t.id > 0 ? String.valueOf(t.id) : ":" + Suggestions.fold(t.name) + ":" + t.year);
    }

    boolean has(Suggestions.Title t) {
        return savedAt.containsKey(key(t));
    }

    /** Saves t first in the list, or removes it if it is saved; true if it is saved now. The oldest goes past MAX. */
    boolean toggle(Suggestions.Title t, long now) {
        String k = key(t);
        if (savedAt.remove(k) != null) {
            for (int i = 0; i < titles.size(); i++) {
                if (key(titles.get(i)).equals(k)) titles.remove(i--);
            }
            return false;
        }
        titles.add(0, t);
        savedAt.put(k, now);
        while (titles.size() > MAX) savedAt.remove(key(titles.remove(titles.size() - 1)));
        return true;
    }

    /** Every title saved, newest first. */
    List<Suggestions.Title> titles() {
        return new ArrayList<>(titles);
    }

    /** The row: those on one of your services first, newest first, then those that are not on any right now. */
    List<Suggestions.Title> shown() {
        List<Suggestions.Title> on = new ArrayList<>(), off = new ArrayList<>();
        for (Suggestions.Title t : titles) (t.services.isEmpty() ? off : on).add(t);
        on.addAll(off);
        return on;
    }

    String toJson() {
        JSONArray a = new JSONArray();
        try {
            for (Suggestions.Title t : titles) {
                JSONArray services = new JSONArray();
                for (Suggestions.Service s : t.services) services.put(s.names[0]);
                a.put(new JSONObject().put("id", t.id).put("tv", t.tv).put("name", t.name).put("year", t.year)
                        .put("date", t.date).put("overview", t.overview).put("poster", t.poster)
                        .put("backdrop", t.backdrop).put("rating", t.rating).put("age", t.age)
                        .put("netflix", t.netflixId).put("disney", t.disneyId).put("dr", t.drPath)
                        .put("viaplay", t.viaplayPath).put("services", services).put("at", savedAt.get(key(t))));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return a.toString();
    }

    /** The list as toJson wrote it, each title on the services of these that it was saved with; empty if unreadable. */
    static Watchlist parse(String json, List<Suggestions.Service> services) {
        Watchlist w = new Watchlist();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length() && w.titles.size() < MAX; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Suggestions.Title t = new Suggestions.Title();
                t.id = o.optInt("id");
                t.tv = o.optBoolean("tv");
                t.name = o.optString("name", "");
                t.year = o.optString("year", "");
                t.date = o.optString("date", "");
                t.overview = o.optString("overview", "");
                t.poster = o.optString("poster", "");
                t.backdrop = o.optString("backdrop", "");
                t.rating = o.optDouble("rating", 0);
                t.age = o.optString("age", "");
                t.netflixId = o.optString("netflix", "");
                t.disneyId = o.optString("disney", "");
                t.drPath = o.optString("dr", "");
                t.viaplayPath = o.optString("viaplay", "");
                JSONArray on = o.optJSONArray("services");
                for (Suggestions.Service s : services) {
                    for (int j = 0; on != null && j < on.length(); j++) {
                        if (s.names[0].equals(on.optString(j))) t.services.add(s);
                    }
                }
                if (t.name.isEmpty() || w.has(t)) continue;
                w.titles.add(t);
                w.savedAt.put(key(t), o.optLong("at"));
            }
        } catch (Exception e) {
            return new Watchlist();
        }
        return w;
    }
}
