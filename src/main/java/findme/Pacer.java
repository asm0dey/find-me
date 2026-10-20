package findme;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.opencv_core.Mat;
import org.jspecify.annotations.Nullable;

/// Takes scanned photos off the pipeline's queue at display pace and publishes a [Scanning] for each. The pipeline
/// never waits for the display: the queue is unbounded, and holds bytes plus detections rather than decoded images.
/// The pace fits the rest of the photos into the time left before a deadline ([#pace]), and the display never runs
/// ahead of the pipeline: with nothing queued it waits for the next photo.
final class Pacer {
    /// What the pipeline hands over per photo, in completion order.
    sealed interface Result permits Scored, Failed {
    }

    /// A fetched, decoded photo: its original bytes, every detected face, and the best similarity (0 with no faces).
    record Scored(byte[] bytes, List<Faces.Face> faces, float best) implements Result {
        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Scored(byte[] obytes, List<Faces.Face> ofaces, float obest)
                    && Arrays.equals(bytes, obytes)
                    && faces.equals(ofaces)
                    && Float.compare(best, obest) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(bytes), faces, best);
        }

        @Override
        public String toString() {
            return "Scored[bytes=" + bytes.length + " bytes, faces=" + faces + ", best=" + best + "]";
        }
    }

    /// A photo that couldn't be fetched or decoded: counted, never shown.
    record Failed() implements Result {}

    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /// How long a miss and a match stay on screen, in ms.
    record Pace(long missMs, long matchMs) {}

    private static final int DISPLAY_SIDE = 1080;
    private static final long POLL_MS = 50;
    private final BlockingQueue<Result> queue;
    private final List<BufferedImage> crops;
    private final float[] me;
    private final Consumer<DemoState> publish;
    private final Sleeper sleeper;
    private final long deadlineNanos;
    private final AtomicInteger total = new AtomicInteger();
    private volatile boolean listingFinished;
    private volatile boolean pipelineFinished;

    /// `deadlineNanos` is the System.nanoTime() by which the last photo should have been shown.
    Pacer(
            BlockingQueue<Result> queue,
            List<BufferedImage> crops,
            float[] me,
            Consumer<DemoState> publish,
            Sleeper sleeper,
            long deadlineNanos
    ) {
        this.queue = queue;
        this.crops = List.copyOf(crops);
        this.me = me;
        this.publish = publish;
        this.sleeper = sleeper;
        this.deadlineNanos = deadlineNanos;
    }

    /// `photos` more are listed, and will each put one result on the queue. Call before queuing them.
    void add(int photos) {
        total.addAndGet(photos);
    }

    /// Every photo is added: the total is final. Call after the last [#add].
    void listingFinished() {
        listingFinished = true;
    }

    /// From now on whatever is still queued is shown slower than it was scanned.
    void pipelineFinished() {
        pipelineFinished = true;
    }

    /// Matches hold `MATCH_MS` while they need at most half the time left, else share that half (1000 ms at least);
    /// misses share what the matches leave, between 30 and 150 ms. If even 30 ms misses don't fit, matches shrink to
    /// what does, down to 1000 ms. Past the deadline: as fast as allowed.
    static Pace pace(long remainingMs, int missesLeft, int matchesLeft) {
        if (remainingMs <= 0) {
            return new Pace(Theme.MIN_MISS_MS, Theme.MIN_MATCH_MS);
        }
        long matchMs = matchesLeft * Theme.MATCH_MS > remainingMs / 2
                ? Math.max(Theme.MIN_MATCH_MS, remainingMs / 2 / matchesLeft)
                : Theme.MATCH_MS;
        long missMs = missesLeft <= 0
                ? Theme.MISS_MS
                : Math.clamp((remainingMs - matchesLeft * matchMs) / missesLeft, Theme.MIN_MISS_MS, Theme.MISS_MS);
        long fit = matchesLeft == 0 ? matchMs : (remainingMs - Math.max(0, missesLeft) * missMs) / matchesLeft;
        if (missMs == Theme.MIN_MISS_MS && fit < matchMs) {
            // misses can't go faster: matches give up the rest
            matchMs = Math.max(Theme.MIN_MATCH_MS, fit);
        }
        return new Pace(missMs, matchMs);
    }

    static long holdMs(Result r, Pace pace) {
        return switch (r) {
            case Scored s -> isMatch(s) ? pace.matchMs() : pace.missMs();
            case Failed _ -> 0;
        };
    }

    private static boolean isMatch(Result r) {
        return r instanceof Scored s && s.best() >= FindMe.THRESHOLD;
    }

    /// Shows every result until the listing is finished and all its photos are shown, then returns the matches, best
    /// first.
    List<Shot> run() throws InterruptedException {
        List<Shot> matches = new ArrayList<>();
        Shot current = null;
        int shown = 0;
        while (!listingFinished || shown < total.get()) {
            // listingFinished first: once it's set, total is final
            Result r = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
            if (r == null) {
                // nothing scanned yet: wait rather than run ahead of the pipeline
                continue;
            }
            long start = System.nanoTime();
            shown++;
            boolean slowedDown = !queue.isEmpty() || pipelineFinished;
            boolean totalFinal = listingFinished;
            int known = total.get();
            if (r instanceof Scored s) {
                current = shot(s);
                if (isMatch(s)) {
                    matches.add(current);
                }
            }
            // a failure republishes the previous shot itself, so the view doesn't replay it
            publish.accept(new Scanning(crops, shown, known, matches.size(), slowedDown, current, totalFinal));
            long hold = holdMs(r, pace(r, shown, known, totalFinal, matches.size()));
            // decoding counts too
            long left = hold - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (left > 0) {
                sleeper.sleep(left);
            }
        }
        matches.sort(Comparator.comparingDouble(Shot::best).reversed());
        return matches;
    }

    /// The pace for `r`, the `shown`-th photo of `known` so far: matches still to come are the queued ones plus, for
    /// photos not scanned yet, the match rate so far. While the total still grows the misses left are unknown, so
    /// they go as fast as allowed, and the pipeline sets the pace.
    private Pace pace(Result r, int shown, int known, boolean totalFinal, int matchesShown) {
        int queued = 0;
        int queuedMatches = 0;
        for (Result q : queue) {
            queued++;
            queuedMatches += isMatch(q) ? 1 : 0;
        }
        int scanned = shown + queued;
        long unscanned = Math.max(0, known - scanned);
        int matchesLeft = (isMatch(r) ? 1 : 0)
                + queuedMatches
                + (int) Math.round((double) (matchesShown + queuedMatches) / scanned * unscanned);
        Pace p = pace(
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()),
                known - shown + 1 - matchesLeft,
                matchesLeft
        );
        return totalFinal ? p : new Pace(Theme.MIN_MISS_MS, p.matchMs());
    }

    private Shot shot(Scored s) {
        try (var _ = new PointerScope()) {
            return shot(Faces.decode(s.bytes()), s.faces(), me, s.best());
        } catch (IOException e) {
            // same bytes, same decoder
            throw new IllegalStateException("decoded in the pipeline, but not for display", e);
        }
    }

    /// The photo at display size, with boxes and landmarks scaled to it and each face's own similarity to `me`.
    /// Call inside a PointerScope.
    static Shot shot(Mat mat, List<Faces.Face> faces, float[] me, float best) {
        BufferedImage image = Faces.toImage(mat, DISPLAY_SIDE);
        float k = (float) image.getWidth() / mat.cols();
        List<Box> boxes = new ArrayList<>(faces.size());
        for (Faces.Face f : faces) {
            float[] lm = f.landmarks().clone();
            for (int i = 0; i < lm.length; i++) {
                lm[i] *= k;
            }
            boxes.add(new Box(f.x1() * k, f.y1() * k, f.x2() * k, f.y2() * k, lm, Faces.similarity(f.embedding(), me)));
        }
        return new Shot(image, boxes, best);
    }
}
