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

/** Everything about the suggestions that does not need Android: services, TMDb paths, parsing, mixing. */
final class Suggestions {
    private Suggestions() {}

    /** One line of the services list in config.xml, and the TMDb provider it matched. */
    static final class Service {
        final String[] names;
        final String pkg;
        int providerId = -1;
        String providerName;

        Service(String[] names, String pkg) {
            this.names = names;
            this.pkg = pkg;
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
        int id;
        boolean tv;
        String name = "", year = "", overview = "", poster = "", backdrop = "";
        double rating;
        final List<Service> services = new ArrayList<>();
    }

    /** Lower case, ASCII letters and digits only: "TV 2 Play", "tv2play" and "TV2-Play" are one name. */
    static String fold(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /** "HBO Max,Max|com.wbd.stream"; null for a line without a name. */
    static Service parseService(String line) {
        int bar = line.indexOf('|');
        String names = bar >= 0 ? line.substring(0, bar) : line;
        String pkg = bar >= 0 ? line.substring(bar + 1).trim() : "";
        List<String> list = new ArrayList<>();
        for (String n : names.split(",")) {
            if (!n.trim().isEmpty()) list.add(n.trim());
        }
        return list.isEmpty() ? null : new Service(list.toArray(new String[0]), pkg);
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
            t.poster = str(r, "poster_path");
            t.backdrop = str(r, "backdrop_path");
            t.rating = r.optDouble("vote_average", 0);
            if (!t.name.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * One row from each service's list: taken in turns, so every service shows near the start, and a title on
     * several services appears once with all of them. A title that fails the check for a service is left out for it.
     */
    static List<Title> mix(List<List<Title>> lists, List<Service> services, int max, Check check) {
        List<Title> out = new ArrayList<>();
        Map<Integer, Title> seen = new HashMap<>();
        int longest = 0;
        for (List<Title> l : lists) longest = Math.max(longest, l.size());
        for (int i = 0; i < longest; i++) {
            for (int s = 0; s < lists.size(); s++) {
                if (i >= lists.get(s).size()) continue;
                Title t = lists.get(s).get(i);
                Title known = seen.get(t.id);
                if (known != null) {
                    if (!known.services.contains(services.get(s)) && check.on(known, services.get(s))) {
                        known.services.add(services.get(s));
                    }
                    continue;
                }
                if (out.size() >= max || !check.on(t, services.get(s))) continue;
                t.services.add(services.get(s));
                seen.put(t.id, t);
                out.add(t);
            }
        }
        return out;
    }

    /** JSON null comes back from optString as "null"; treat it as empty. */
    private static String str(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "").trim();
    }
}
