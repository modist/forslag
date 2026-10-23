package dk.tvsuggestions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything about the suggestions that does not need Android: services, TMDb, DRTV and Wikidata requests and
 * answers, and mixing the rows.
 */
final class Suggestions {
    private Suggestions() {}

    static final String NETFLIX = "com.netflix.ninja", DISNEY = "com.disney.disneyplus", DRTV = "dk.dr.tvplayer";

    /** One line of the services list in config.xml, and the TMDb provider it matched. */
    static final class Service {
        final String[] names;
        final String pkg;
        /** Titles come from DR's own catalogue, not from TMDb. */
        final boolean drtv;
        int providerId = -1;
        String providerName;

        Service(String[] names, String pkg, boolean drtv) {
            this.names = names;
            this.pkg = pkg;
            this.drtv = drtv;
        }

        /** The name to show: TMDb's once matched, else the first one configured. */
        String label() {
            return providerName != null ? providerName : names[0];
        }
    }

    /** Whether a title really streams on a service; TMDb's discover filter alone also lets through rentals. */
    interface Check {
        boolean on(Title t, Service s);
    }

    static final class Title {
        /** TMDb's id; 0 for a title known only from DRTV. */
        int id;
        boolean tv;
        /** poster and backdrop are full URLs. */
        String name = "", year = "", overview = "", poster = "", backdrop = "";
        double rating;
        /** What the apps open a title by: Netflix and Disney+ ids from Wikidata, DRTV's own path. */
        String netflixId = "", disneyId = "", drPath = "";
        final List<Service> services = new ArrayList<>();

        /** Fills in what this title lacks from the same title found on another service. */
        void absorb(Title o) {
            if (id == 0) id = o.id;
            if (rating == 0) rating = o.rating;
            if (year.isEmpty()) year = o.year;
            if (overview.isEmpty()) overview = o.overview;
            if (poster.isEmpty()) poster = o.poster;
            if (backdrop.isEmpty()) backdrop = o.backdrop;
            if (drPath.isEmpty()) drPath = o.drPath;
        }

        /** The same title: same kind and folded name, and the same year where both have one. */
        boolean sameAs(Title o) {
            if (id > 0 && o.id > 0) return id == o.id && tv == o.tv;
            return tv == o.tv && fold(name).equals(fold(o.name))
                    && (year.isEmpty() || o.year.isEmpty() || year.equals(o.year));
        }
    }

    /** Lower case, ASCII letters and digits only: "TV 2 Play", "tv2play" and "TV2-Play" are one name. */
    static String fold(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /** "HBO Max,Max|com.wbd.stream", or "drtv:DRTV|dk.dr.tvplayer" for DR's catalogue; null without a name. */
    static Service parseService(String line) {
        line = line.trim();
        boolean drtv = line.startsWith("drtv:");
        if (drtv) line = line.substring(5);
        int bar = line.indexOf('|');
        String names = bar >= 0 ? line.substring(0, bar) : line;
        String pkg = bar >= 0 ? line.substring(bar + 1).trim() : "";
        List<String> list = new ArrayList<>();
        for (String n : names.split(",")) {
            if (!n.trim().isEmpty()) list.add(n.trim());
        }
        return list.isEmpty() ? null : new Service(list.toArray(new String[0]), pkg, drtv);
    }

    /** Sets providerId on each service from TMDb's /watch/providers results, by id or folded name. */
    static void match(List<Service> services, JSONArray providers) {
        Map<String, JSONObject> byName = new HashMap<>();
        Map<Integer, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p == null) continue;
            byId.put(p.optInt("provider_id", -1), p);
            byName.putIfAbsent(fold(p.optString("provider_name", "")), p);
        }
        for (Service s : services) {
            if (s.providerId >= 0) continue;
            for (String n : s.names) {
                JSONObject p = n.matches("\\d+") ? byId.get(Integer.parseInt(n)) : byName.get(fold(n));
                if (p != null) {
                    s.providerId = p.optInt("provider_id", -1);
                    s.providerName = p.optString("provider_name", n);
                    break;
                }
            }
        }
    }

    /** What is included in one subscription in one country, most popular first. */
    static String discoverPath(boolean tv, int providerId, String region, String language, int minVotes) {
        return "/discover/" + (tv ? "tv" : "movie") + "?watch_region=" + region + "&with_watch_providers="
                + providerId + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false"
                + "&vote_count.gte=" + minVotes + "&language=" + language + "&page=1";
    }

    /** Where one title can be watched, in every country. */
    static String watchPath(Title t) {
        return "/" + (t.tv ? "tv" : "movie") + "/" + t.id + "/watch/providers";
    }

    /** The providers that include a title in a subscription in one country, from /{type}/{id}/watch/providers. */
    static Set<Integer> flatrate(JSONObject json, String region) {
        Set<Integer> out = new TreeSet<>();
        JSONObject results = json.optJSONObject("results");
        JSONObject here = results != null ? results.optJSONObject(region) : null;
        JSONArray list = here != null ? here.optJSONArray("flatrate") : null;
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p != null) out.add(p.optInt("provider_id", -1));
        }
        return out;
    }

    static String providersPath(boolean tv, String region, String language) {
        return "/watch/providers/" + (tv ? "tv" : "movie") + "?watch_region=" + region + "&language=" + language;
    }

    static List<Title> parseDiscover(JSONObject json, boolean tv) {
        List<Title> out = new ArrayList<>();
        JSONArray results = json.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || r.optInt("id", 0) <= 0) continue;
            Title t = new Title();
            t.id = r.optInt("id");
            t.tv = tv;
            t.name = str(r, tv ? "name" : "title");
            String date = str(r, tv ? "first_air_date" : "release_date");
            t.year = date.length() >= 4 ? date.substring(0, 4) : "";
            t.overview = str(r, "overview");
            String poster = str(r, "poster_path"), backdrop = str(r, "backdrop_path");
            t.poster = poster.isEmpty() ? "" : TMDB_IMAGES + "w185" + poster;
            t.backdrop = backdrop.isEmpty() ? "" : TMDB_IMAGES + "w780" + backdrop;
            t.rating = r.optDouble("vote_average", 0);
            if (!t.name.isEmpty()) out.add(t);
        }
        return out;
    }

    static final String TMDB_IMAGES = "https://image.tmdb.org/t/p/";
    static final String DRTV_API = "https://production.dr-massive.com/api";

    /** One of DRTV's lists, whole (they hold up to about 100 titles). */
    static String drtvListUrl(String listId) {
        return DRTV_API + "/lists/" + listId + "?page=1&page_size=100&device=web_browser&lang=da&sub=Anonymous";
    }

    /** Films (DR calls them programs) or series (shows and seasons) from a DRTV list. */
    static List<Title> parseDrtv(JSONObject json, boolean tv) {
        List<Title> out = new ArrayList<>();
        JSONArray items = json.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null) continue;
            String type = str(r, "type");
            boolean series = type.equals("show") || type.equals("season");
            boolean film = type.equals("program") || type.equals("movie");
            if (tv ? !series : !film) continue;
            Title t = new Title();
            t.tv = tv;
            t.name = str(r, "title");
            t.year = r.optInt("releaseYear", 0) > 0 ? String.valueOf(r.optInt("releaseYear")) : "";
            t.overview = str(r, "shortDescription");
            t.drPath = str(r, "path");
            JSONObject images = r.optJSONObject("images");
            if (images != null) {
                t.poster = resize(str(images, "poster"), 184, 276);
                t.backdrop = resize(str(images, "wallpaper"), 960, 540);
            }
            if (!t.name.isEmpty() && t.drPath.startsWith("/")) out.add(t);
        }
        return out;
    }

    /** DR's image URLs carry the size to scale to; theirs is 1440x2160, far more than a tile needs. */
    static String resize(String url, int width, int height) {
        return url.replaceFirst("Width=\\d+&Height=\\d+", "Width=" + width + "&Height=" + height);
    }

    /** One SPARQL query for the Netflix and Disney+ ids of all these titles, by their TMDb ids. */
    static String wikidataQuery(List<Title> titles) {
        StringBuilder movies = new StringBuilder(), series = new StringBuilder();
        for (Title t : titles) {
            if (t.id > 0) (t.tv ? series : movies).append(" \"").append(t.id).append('"');
        }
        return "SELECT ?m ?t ?netflix ?disney WHERE { { VALUES ?m {" + movies + " } ?item wdt:P4947 ?m . "
                + "OPTIONAL { ?item wdt:P7595 ?disney } } UNION { VALUES ?t {" + series + " } ?item wdt:P4983 ?t . "
                + "OPTIONAL { ?item wdt:P7596 ?disney } } OPTIONAL { ?item wdt:P1874 ?netflix } }";
    }

    /** Sets netflixId and disneyId from the query's answer; the first id Wikidata gives wins. */
    static void applyWikidata(JSONObject json, List<Title> titles) {
        JSONObject results = json.optJSONObject("results");
        JSONArray rows = results != null ? results.optJSONArray("bindings") : null;
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject b = rows.optJSONObject(i);
            if (b == null) continue;
            boolean tv = b.has("t");
            String id = value(b, tv ? "t" : "m");
            for (Title t : titles) {
                if (t.tv != tv || !String.valueOf(t.id).equals(id)) continue;
                if (t.netflixId.isEmpty()) t.netflixId = value(b, "netflix");
                if (t.disneyId.isEmpty()) t.disneyId = value(b, "disney");
            }
        }
    }

    private static String value(JSONObject binding, String key) {
        JSONObject v = binding.optJSONObject(key);
        return v != null ? v.optString("value", "").trim() : "";
    }

    /** "What We Do in the Shadows" -> "what-we-do-in-the-shadows", for Disney+ links. */
    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "title" : s;
    }

    /**
     * One row from each service's list: taken in turns, so every service shows near the start, and a title on
     * several services (even from different sources) appears once with all of them. A title that fails the check
     * for the service whose list it is on is left out for that service.
     */
    static List<Title> mix(List<List<Title>> lists, List<Service> services, int max, Check check) {
        List<Title> out = new ArrayList<>();
        int longest = 0;
        for (List<Title> l : lists) longest = Math.max(longest, l.size());
        for (int i = 0; i < longest; i++) {
            for (int s = 0; s < lists.size(); s++) {
                if (i >= lists.get(s).size()) continue;
                Title t = lists.get(s).get(i);
                Service service = services.get(s);
                Title known = null;
                for (Title o : out) {
                    if (o.sameAs(t)) {
                        known = o;
                        break;
                    }
                }
                if (known == null && out.size() >= max) continue;
                if (known != null && known.services.contains(service)) continue;
                if (!check.on(t, service)) continue;
                if (known != null) {
                    known.services.add(service);
                    known.absorb(t);
                } else {
                    t.services.add(service);
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** JSON null comes back from optString as "null"; treat it as empty. */
    private static String str(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "").trim();
    }
}
