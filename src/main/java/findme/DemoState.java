package findme;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// What the demo window renders. A background thread publishes a new immutable value on every step; the view only
/// reads it. The variants share this file so the sealed hierarchy stays in one place.
sealed interface DemoState permits Learning, Listing, Scanning, Done, Failed {

    /// Gallery layout for a match count: at most six cells, with a "+N more" tile when there are more.
    static Grid grid(int matches) {
        if (matches <= 0) {
            return new Grid(0, 0, 0, 0);
        }
        // Six cells hold up to six photos; past that the sixth cell becomes the "+N more" tile.
        int photos = matches <= 6 ? matches : 5;
        int more = matches - photos;
        return switch (photos) {
            case 1 -> new Grid(1, 1, photos, more);
            case 2 -> new Grid(2, 1, photos, more);
            case 3, 4 -> new Grid(2, 2, photos, more);
            default -> new Grid(3, 2, photos, more);
        };
    }
}

/// Gallery layout: columns, rows, photos shown, and photos hidden behind a "+N more" tile.
record Grid(int cols, int rows, int photos, int more) {}

/// `album` of `albums` is the album being listed meanwhile, 0 when none is; `listed` counts the photos listed so far.
record Learning(List<BufferedImage> crops, int done, int total, @Nullable Shot current, int listed, int album, int albums)
        implements DemoState {
    Learning {
        crops = List.copyOf(crops);
    }
}

record Listing(List<BufferedImage> crops, int album, int albums, int found, long elapsedMs) implements DemoState {
    Listing {
        crops = List.copyOf(crops);
    }
}

/// The view animates each new `current` (lock-on, stamp); a republished one is equal and doesn't replay.
/// `total` is what is listed so far, and final once `totalFinal`.
record Scanning(List<BufferedImage> crops, int shown, int total, int found, boolean slowedDown, @Nullable Shot current,
        boolean totalFinal) implements DemoState {
    Scanning {
        crops = List.copyOf(crops);
    }
}

record Done(List<BufferedImage> crops, int total, List<Shot> matches, long realScanMs) implements DemoState {
    Done {
        crops = List.copyOf(crops);
        matches = List.copyOf(matches);
    }
}

record Failed(String message) implements DemoState {}

/// Coordinates are already in image pixels. best is the max similarity, 0 when there are no faces.
record Shot(BufferedImage image, List<Box> boxes, float best) {
    Shot {
        boxes = List.copyOf(boxes);
    }
}

record Box(float x1, float y1, float x2, float y2, float[] landmarks, float similarity) {
    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Box(float ox1, float oy1, float ox2, float oy2, float[] olandmarks, float osimilarity)
                && Float.compare(x1, ox1) == 0 && Float.compare(y1, oy1) == 0 && Float.compare(x2, ox2) == 0
                && Float.compare(y2, oy2) == 0 && Arrays.equals(landmarks, olandmarks)
                && Float.compare(similarity, osimilarity) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x1, y1, x2, y2, Arrays.hashCode(landmarks), similarity);
    }

    @Override
    public String toString() {
        return "Box[x1=" + x1 + ", y1=" + y1 + ", x2=" + x2 + ", y2=" + y2 + ", landmarks="
                + Arrays.toString(landmarks) + ", similarity=" + similarity + "]";
    }
}
