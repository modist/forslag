package net.steensgaard.forslag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class WatchlistTest {
    private static Suggestions.Title title(int id, boolean tv, String name, Suggestions.Service... on) {
        Suggestions.Title t = new Suggestions.Title();
        t.id = id;
        t.tv = tv;
        t.name = name;
        t.year = "2005";
        t.services.addAll(Arrays.asList(on));
        return t;
    }

    private static Suggestions.Service service(String name, String pkg) {
        return new Suggestions.Service(new String[] {name}, pkg, Suggestions.FROM_TMDB);
    }

    @Test
    public void savesNewestFirstAndRemovesOnTheSecondToggle() {
        Watchlist w = new Watchlist();
        Suggestions.Title a = title(1, true, "A"), b = title(2, false, "B");
        assertTrue(w.toggle(a, 1));
        assertTrue(w.toggle(b, 2));
        assertEquals(Arrays.asList(b, a), w.titles());
        // The same title from another row counts as saved.
        assertTrue(w.has(title(1, true, "A again")));
        assertFalse(w.has(title(1, false, "A movie")));
        assertFalse(w.toggle(title(1, true, "A"), 3));
        assertEquals(Arrays.asList(b), w.titles());
    }

    @Test
    public void keepsTheNewestMax() {
        Watchlist w = new Watchlist();
        for (int i = 1; i <= Watchlist.MAX + 2; i++) w.toggle(title(i, false, "T" + i), i);
        assertEquals(Watchlist.MAX, w.titles().size());
        assertFalse(w.has(title(1, false, "")));
        assertFalse(w.has(title(2, false, "")));
        assertTrue(w.has(title(3, false, "")));
    }

    @Test
    public void showsTitlesOnNoServiceLast() {
        Suggestions.Service netflix = service("Netflix", "com.netflix.ninja");
        Watchlist w = new Watchlist();
        Suggestions.Title gone = title(1, false, "Gone"), here = title(2, false, "Here", netflix);
        w.toggle(here, 1);
        w.toggle(gone, 2);
        assertEquals(Arrays.asList(here, gone), w.shown());
    }

    @Test
    public void readsBackWhatItWrote() {
        Suggestions.Service netflix = service("Netflix", "com.netflix.ninja"), dr = service("DRTV", "dk.dr.tvplayer");
        Watchlist w = new Watchlist();
        Suggestions.Title himym = title(1100, true, "How I Met Your Mother", netflix);
        himym.poster = "https://image.tmdb.org/p.jpg";
        himym.disneyId = "3kpBeRQiKjkq";
        himym.rating = 8.2;
        Suggestions.Title drOnly = title(0, false, "Matador", dr);
        drOnly.drPath = "/serie/matador";
        w.toggle(himym, 1);
        w.toggle(drOnly, 2);

        // Read with services matched by name, as the app does before the first load.
        Suggestions.Service netflix2 = service("Netflix", "com.netflix.ninja"), dr2 = service("DRTV", "dk.dr.tvplayer");
        Watchlist back = Watchlist.parse(w.toJson(), Arrays.asList(netflix2, dr2));
        List<Suggestions.Title> got = back.titles();
        assertEquals(2, got.size());
        assertEquals("Matador", got.get(0).name);
        assertEquals("/serie/matador", got.get(0).drPath);
        assertEquals(Arrays.asList(dr2), got.get(0).services);
        Suggestions.Title h = got.get(1);
        assertEquals(1100, h.id);
        assertTrue(h.tv);
        assertEquals("https://image.tmdb.org/p.jpg", h.poster);
        assertEquals("3kpBeRQiKjkq", h.disneyId);
        assertEquals(8.2, h.rating, 0.001);
        assertEquals(Arrays.asList(netflix2), h.services);
        assertTrue(back.has(title(0, false, "Matador")));

        assertTrue(Watchlist.parse("not json", Arrays.asList(netflix2)).titles().isEmpty());
    }
}
