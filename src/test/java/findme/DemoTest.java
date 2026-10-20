package findme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DemoTest {
    private final List<DemoState> published = new CopyOnWriteArrayList<>();
    private final Demo.Screen screen = new Demo.Screen(published::add, System.nanoTime());
    private final LinkedBlockingQueue<List<FindMe.Photo>> listed = new LinkedBlockingQueue<>();

    private static FindMe.Photo photo(int i) {
        return new FindMe.Photo("p" + i, "file:/p" + i, "p" + i);
    }

    /// Lists `albums` on its own thread, then fails with `failure` if given, ending the queue either way.
    private FutureTask<@Nullable Void> lister(List<List<FindMe.Photo>> albums, @Nullable IOException failure) {
        var listing = new FutureTask<@Nullable Void>(() -> {
            try {
                listed.addAll(albums);
                if (failure != null) {
                    throw failure;
                }
            } finally {
                listed.add(Demo.END);
            }
            return null;
        });
        Thread.ofPlatform().start(listing);
        return listing;
    }

    @Test
    void scansEveryListedAlbumThenIsDone() throws Exception {
        var checked = new CopyOnWriteArrayList<FindMe.Photo>();
        var listing = lister(List.of(List.of(photo(1), photo(2)), List.of(photo(3))), null);
        // failures hold for 0 ms, and a deadline long past keeps the pace at its floor
        Demo.scan(p -> {
            checked.add(p);
            return new Pacer.Failed();
        }, new float[] {1, 0}, listed, listing, List.of(), screen, System.nanoTime());
        assertEquals(3, checked.size());
        Done done = assertInstanceOf(Done.class, published.getLast());
        assertEquals(3, done.total());
        assertTrue(done.matches().isEmpty());
        Scanning last = (Scanning) published.get(published.size() - 2);
        assertEquals(List.of(3, 3, true), List.of(last.shown(), last.total(), last.totalFinal()));
    }

    @Test
    void theListersFailureIsTheDirectors() {
        var listing = lister(List.of(List.of(photo(1))), new IOException("album folder not found: /nope"));
        var e = assertThrows(ExecutionException.class, () -> Demo.scan(
                _ -> new Pacer.Failed(),
                new float[] {1, 0},
                listed,
                listing,
                List.of(),
                screen,
                System.nanoTime()
        ));
        assertEquals("album folder not found: /nope", assertInstanceOf(IOException.class, e.getCause()).getMessage());
        assertTrue(published.stream().noneMatch(Done.class::isInstance));
    }
}
