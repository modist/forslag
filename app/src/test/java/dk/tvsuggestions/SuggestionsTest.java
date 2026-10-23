package dk.tvsuggestions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SuggestionsTest {

    @Test
    public void foldIgnoresCaseSpacesAndPunctuation() {
        assertEquals("tv2play", Suggestions.fold("TV 2 Play"));
        assertEquals("tv2play", Suggestions.fold("tv2-PLAY"));
        assertEquals("disneyplus", Suggestions.fold("Disney Plus"));
    }

    @Test
    public void parsesServiceLines() {
        Suggestions.Service s = Suggestions.parseService(" HBO Max , Max |com.wbd.stream");
        assertArrayEquals(new String[] {"HBO Max", "Max"}, s.names);
        assertEquals("com.wbd.stream", s.pkg);
        assertEquals("", Suggestions.parseService("Netflix").pkg);
        assertNull(Suggestions.parseService(" , |com.example"));
    }

    @Test
    public void matchesByFoldedNameOrIdAndSkipsLookalikes() throws Exception {
        JSONArray providers = new JSONArray("[{\"provider_id\":8,\"provider_name\":\"Netflix\"},"
                + "{\"provider_id\":1796,\"provider_name\":\"Netflix basic with Ads\"},"
                + "{\"provider_id\":383,\"provider_name\":\"TV2 Play\"},"
                + "{\"provider_id\":1899,\"provider_name\":\"Max\"},"
                + "{\"provider_id\":620,\"provider_name\":\"DR TV\"}]");
        Suggestions.Service netflix = Suggestions.parseService("Netflix|n");
        Suggestions.Service tv2 = Suggestions.parseService("TV 2 Play|t");
        Suggestions.Service max = Suggestions.parseService("HBO Max,Max|m");
        Suggestions.Service dr = Suggestions.parseService("620|d");
        Suggestions.Service none = Suggestions.parseService("YouSee Play|y");
        Suggestions.match(Arrays.asList(netflix, tv2, max, dr, none), providers);
        assertEquals(8, netflix.providerId);
        assertEquals(383, tv2.providerId);
        assertEquals("TV2 Play", tv2.label());
        assertEquals(1899, max.providerId);
        assertEquals(620, dr.providerId);
        assertEquals(-1, none.providerId);
        assertEquals("YouSee Play", none.label());
    }

    @Test
    public void parsesMoviesAndSeries() throws Exception {
        JSONObject movie = new JSONObject("{\"results\":[{\"id\":1,\"title\":\"Druk\",\"release_date\":\"2020-09-24\","
                + "\"overview\":\"Fire venner\",\"poster_path\":\"/p.jpg\",\"backdrop_path\":null,\"vote_average\":7.7},"
                + "{\"id\":2,\"title\":\"\"},{\"id\":0,\"title\":\"No id\"}]}");
        List<Suggestions.Title> m = Suggestions.parseDiscover(movie, false);
        assertEquals(1, m.size());
        assertEquals("Druk", m.get(0).name);
        assertEquals("2020", m.get(0).year);
        assertEquals("https://image.tmdb.org/t/p/w185/p.jpg", m.get(0).poster);
        assertEquals("", m.get(0).backdrop);
        assertEquals(7.7, m.get(0).rating, 0.001);

        JSONObject tv = new JSONObject("{\"results\":[{\"id\":5,\"name\":\"Borgen\",\"first_air_date\":\"\"}]}");
        List<Suggestions.Title> s = Suggestions.parseDiscover(tv, true);
        assertEquals("Borgen", s.get(0).name);
        assertEquals("", s.get(0).year);
        assertTrue(s.get(0).tv);
        assertTrue(Suggestions.parseDiscover(new JSONObject("{}"), true).isEmpty());
    }

    @Test
    public void mixTakesTurnsMergesDuplicatesAndStopsAtMax() {
        Suggestions.Service a = Suggestions.parseService("A|a");
        Suggestions.Service b = Suggestions.parseService("B|b");
        List<Suggestions.Title> la = Arrays.asList(title(1), title(2), title(3));
        List<Suggestions.Title> lb = Arrays.asList(title(10), title(2));
        List<Suggestions.Title> out = Suggestions.mix(Arrays.asList(la, lb), Arrays.asList(a, b), 3, (t, sv) -> true);
        assertEquals(3, out.size());
        assertEquals(1, out.get(0).id);
        assertEquals(10, out.get(1).id);
        assertEquals(2, out.get(2).id);
        assertEquals(Arrays.asList(a, b), out.get(2).services);
    }

    @Test
    public void discoverAsksForSubscriptionTitlesInTheRegion() {
        String p = Suggestions.discoverPath(true, 8, "DK", "da-DK", 50);
        assertTrue(p.startsWith("/discover/tv?"));
        assertTrue(p.contains("watch_region=DK"));
        assertTrue(p.contains("with_watch_providers=8"));
        assertTrue(p.contains("with_watch_monetization_types=flatrate"));
        assertTrue(p.contains("language=da-DK"));
        assertTrue(p.contains("vote_count.gte=50"));
    }

    @Test
    public void mixLeavesOutTitlesThatAreOnlyRentedOnAService() {
        Suggestions.Service a = Suggestions.parseService("A|a");
        Suggestions.Service b = Suggestions.parseService("B|b");
        List<Suggestions.Title> la = Arrays.asList(title(1), title(2));
        List<Suggestions.Title> lb = Arrays.asList(title(2), title(3));
        // Title 2 streams on A only; B merely rents it.
        List<Suggestions.Title> out = Suggestions.mix(Arrays.asList(la, lb), Arrays.asList(a, b), 10,
                (t, sv) -> !(t.id == 2 && sv == b));
        assertEquals(3, out.size());
        assertEquals(Arrays.asList(a), out.get(1).services);
        // Offered by B first and refused there, it still comes in for A.
        la = Arrays.asList(title(1), title(2));
        lb = Arrays.asList(title(2), title(3));
        out = Suggestions.mix(Arrays.asList(lb, la), Arrays.asList(b, a), 10, (t, sv) -> !(t.id == 2 && sv == b));
        Suggestions.Title two = null;
        for (Suggestions.Title t : out) if (t.id == 2) two = t;
        assertEquals(Arrays.asList(a), two.services);
    }

    @Test
    public void flatrateReadsOnlyTheRegionsSubscriptions() throws Exception {
        JSONObject json = new JSONObject("{\"results\":{\"DK\":{\"buy\":[{\"provider_id\":76}],"
                + "\"flatrate\":[{\"provider_id\":337}]},\"SE\":{\"flatrate\":[{\"provider_id\":76}]}}}");
        assertEquals(new java.util.TreeSet<>(Arrays.asList(337)), Suggestions.flatrate(json, "DK"));
        assertTrue(Suggestions.flatrate(json, "NO").isEmpty());
        assertTrue(Suggestions.flatrate(new JSONObject("{}"), "DK").isEmpty());
    }

    @Test
    public void drtvLinesAreMarked() {
        Suggestions.Service dr = Suggestions.parseService(" drtv:DRTV|dk.dr.tvplayer");
        assertTrue(dr.drtv);
        assertArrayEquals(new String[] {"DRTV"}, dr.names);
        assertEquals("dk.dr.tvplayer", dr.pkg);
        assertTrue(!Suggestions.parseService("Netflix|n").drtv);
    }

    @Test
    public void parsesDrtvFilmsAndSeriesWithSmallImages() throws Exception {
        String img = "https://x/ResizeImage/$value?Format='jpg'&Width=1440&Height=2160&ImageId='1'";
        JSONObject json = new JSONObject("{\"items\":["
                + "{\"id\":\"1\",\"type\":\"program\",\"title\":\"Mass\",\"path\":\"/program/mass_1\","
                + "\"releaseYear\":2021,\"shortDescription\":\"Et drama\",\"images\":{\"poster\":\"" + img + "\"}},"
                + "{\"id\":\"2\",\"type\":\"show\",\"title\":\"Hoest\",\"path\":\"/serie/hoest_2\",\"releaseYear\":null},"
                + "{\"id\":\"3\",\"type\":\"season\",\"title\":\"Kragepigen\",\"path\":\"/saeson/kragepigen_3\"},"
                + "{\"id\":\"4\",\"type\":\"channel\",\"title\":\"DR1\",\"path\":\"/kanal/dr1\"}]}");
        List<Suggestions.Title> films = Suggestions.parseDrtv(json, false);
        assertEquals(1, films.size());
        assertEquals("/program/mass_1", films.get(0).drPath);
        assertEquals("2021", films.get(0).year);
        assertEquals(0, films.get(0).id);
        assertTrue(films.get(0).poster.contains("Width=184&Height=276"));
        List<Suggestions.Title> series = Suggestions.parseDrtv(json, true);
        assertEquals(2, series.size());
        assertEquals("", series.get(0).year);
    }

    @Test
    public void mixMergesTheSameTitleFromDrtvAndTmdbByName() {
        Suggestions.Service dr = Suggestions.parseService("drtv:DRTV|dk.dr.tvplayer");
        Suggestions.Service nf = Suggestions.parseService("Netflix|com.netflix.ninja");
        Suggestions.Title fromDr = title(0);
        fromDr.name = "Midsommar";
        fromDr.drPath = "/program/midsommar_9";
        Suggestions.Title fromTmdb = title(530385);
        fromTmdb.name = "Midsommar";
        fromTmdb.year = "2019";
        fromTmdb.rating = 7.1;
        List<Suggestions.Title> out = Suggestions.mix(Arrays.asList(Arrays.asList(fromDr), Arrays.asList(fromTmdb)),
                Arrays.asList(dr, nf), 10, (t, sv) -> true);
        assertEquals(1, out.size());
        assertEquals(Arrays.asList(dr, nf), out.get(0).services);
        assertEquals(530385, out.get(0).id);
        assertEquals("/program/midsommar_9", out.get(0).drPath);
        assertEquals(7.1, out.get(0).rating, 0.001);
    }

    @Test
    public void wikidataIdsLandOnTheRightTitles() throws Exception {
        Suggestions.Title movie = title(157336);
        Suggestions.Title show = title(1396);
        show.tv = true;
        Suggestions.Title other = title(1396);
        String q = Suggestions.wikidataQuery(Arrays.asList(movie, show, title(0)));
        assertTrue(q.contains("VALUES ?m { \"157336\" }"));
        assertTrue(q.contains("VALUES ?t { \"1396\" }"));
        JSONObject json = new JSONObject("{\"results\":{\"bindings\":["
                + "{\"m\":{\"value\":\"157336\"},\"netflix\":{\"value\":\"70305903\"}},"
                + "{\"t\":{\"value\":\"1396\"},\"netflix\":{\"value\":\"70143836\"},\"disney\":{\"value\":\"abc\"}},"
                + "{\"t\":{\"value\":\"1396\"},\"netflix\":{\"value\":\"99999999\"}}]}}");
        Suggestions.applyWikidata(json, Arrays.asList(movie, show, other));
        assertEquals("70305903", movie.netflixId);
        assertEquals("70143836", show.netflixId);
        assertEquals("abc", show.disneyId);
        assertEquals("", other.netflixId);
        assertEquals("what-we-do-in-the-shadows", Suggestions.slug("What We Do in the Shadows"));
    }

    private static Suggestions.Title title(int id) {
        Suggestions.Title t = new Suggestions.Title();
        t.id = id;
        t.name = "T" + id;
        return t;
    }
}
