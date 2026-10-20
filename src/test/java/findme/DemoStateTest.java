package findme;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DemoStateTest {
    @Test void gridFollowsMatchCount() {
        assertEquals(new Grid(1, 1, 1, 0), DemoState.grid(1));
        assertEquals(new Grid(2, 1, 2, 0), DemoState.grid(2));
        assertEquals(new Grid(2, 2, 3, 0), DemoState.grid(3));
        assertEquals(new Grid(2, 2, 4, 0), DemoState.grid(4));
        assertEquals(new Grid(3, 2, 5, 0), DemoState.grid(5));
        assertEquals(new Grid(3, 2, 6, 0), DemoState.grid(6));
        assertEquals(new Grid(3, 2, 5, 4), DemoState.grid(9));
        assertEquals(new Grid(0, 0, 0, 0), DemoState.grid(0));
    }

    private static Shot match() {
        float[] lm = {20, 20, 40, 20, 30, 30, 22, 40, 38, 40};
        return new Shot(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), List.of(new Box(0, 0, 50, 50, lm, .8f)), .8f);
    }

    /// The view animates each new shot and treats an equal one as a republish: no replay.
    @Test void aShotIsNewByItsPhotoAndTheSameWhenRepublished() {
        Shot shot = match();
        Shot other = new Shot(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), shot.boxes(), shot.best());
        assertNotEquals(shot, other); // same faces, another photo
        assertEquals(shot, new Shot(shot.image(), shot.boxes(), shot.best()));
    }

    /// The scan screen is built on its first state and takes that state's photo as already shown; a first photo that
    /// matches must still lock on, so the screen first gets the same state without it.
    @Test void theScanOpensWithoutAPhotoSoTheFirstOneAnimates() {
        List<DemoState> published = new ArrayList<>();
        Demo.Screen screen = new Demo.Screen(published::add, 0);
        Scanning first = new Scanning(List.of(), 1, 13, 1, false, match(), false);
        Scanning second = new Scanning(List.of(), 2, 13, 1, false, match(), false);
        screen.scanning(first);
        screen.scanning(second);
        assertEquals(3, published.size());
        assertNull(((Scanning) published.get(0)).current());
        assertEquals(new Scanning(List.of(), 1, 13, 1, false, null, false), published.get(0));
        assertSame(first, published.get(1));
        assertSame(second, published.get(2));
    }
}
