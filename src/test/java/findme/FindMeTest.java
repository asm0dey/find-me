package findme;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FindMeTest {

    @Test void averageIsUnitLengthMean() {
        float[] a = FindMe.average(List.of(new float[]{1, 0}, new float[]{0, 1}));
        assertArrayEquals(new float[]{0.70710677f, 0.70710677f}, a, 1e-6f);
    }
}
