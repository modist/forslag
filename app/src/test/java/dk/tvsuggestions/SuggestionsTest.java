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
        assertEquals("/p.jpg", m.get(0).poster);
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
        List<Suggestions.Title> out = Suggestions.mix(Arrays.asList(la, lb), Arrays.asList(a, b), 3);
        assertEquals(3, out.size());
        assertEquals(1, out.get(0).id);
        assertEquals(10, out.get(1).id);
        assertEquals(2, out.get(2).id);
        assertEquals(Arrays.asList(a, b), out.get(2).services);
    }

    @Test
    public void discoverAsksForSubscriptionTitlesInTheRegion() {
        String p = Suggestions.discoverPath(true, 8, "DK", "da-DK");
        assertTrue(p.startsWith("/discover/tv?"));
        assertTrue(p.contains("watch_region=DK"));
        assertTrue(p.contains("with_watch_providers=8"));
        assertTrue(p.contains("with_watch_monetization_types=flatrate"));
        assertTrue(p.contains("language=da-DK"));
    }

    private static Suggestions.Title title(int id) {
        Suggestions.Title t = new Suggestions.Title();
        t.id = id;
        t.name = "T" + id;
        return t;
    }
}
