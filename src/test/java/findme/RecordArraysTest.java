package findme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Records with array components compare, hash and print by the arrays' contents (a photo's bytes print as their count).
class RecordArraysTest {
    @Test
    void face() {
        var a = new Faces.Face(1, 2, 3, 4, new float[] {5, 6}, new float[] {0.25f, 0.75f});
        var b = new Faces.Face(1, 2, 3, 4, new float[] {5, 6}, new float[] {0.25f, 0.75f});
        assertContentEqual(a, b, "[5.0, 6.0]", "[0.25, 0.75]");
        assertNotEquals(new Faces.Face(1, 2, 3, 4, new float[] {5, 6}, new float[] {0.25f, 0.5f}), a);
    }

    @Test
    void detection() {
        var a = new Faces.Detection(0.9f, 1, 2, 3, 4, new float[] {7, 8});
        var b = new Faces.Detection(0.9f, 1, 2, 3, 4, new float[] {7, 8});
        assertContentEqual(a, b, "[7.0, 8.0]");
        assertNotEquals(new Faces.Detection(0.9f, 1, 2, 3, 4, new float[] {7, 9}), a);
    }

    @Test
    void box() {
        var a = new Box(1, 2, 3, 4, new float[] {5, 6}, 0.5f);
        var b = new Box(1, 2, 3, 4, new float[] {5, 6}, 0.5f);
        assertContentEqual(a, b, "[5.0, 6.0]");
        assertNotEquals(new Box(1, 2, 3, 4, new float[] {5, 6}, 0.6f), a);
    }

    @Test
    void scored() {
        var a = new Pacer.Scored(new byte[] {1, 2, 3}, List.of(), 0.5f);
        var b = new Pacer.Scored(new byte[] {1, 2, 3}, List.of(), 0.5f);
        assertContentEqual(a, b, "bytes=3 bytes");
        assertNotEquals(new Pacer.Scored(new byte[] {1, 2, 4}, List.of(), 0.5f), a);
    }

    private static void assertContentEqual(Object a, Object b, String... shown) {
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        for (String s : shown) {
            assertTrue(a.toString().contains(s), a + " should show " + s);
        }
    }
}
