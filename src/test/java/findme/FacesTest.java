package findme;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.Test;

class FacesTest {
    @Test
    void alignedCropAtTemplatePointsIsAScaledCopy() {
        // landmarks exactly at the 112-px template on a 112x112 image -> the 448 crop is that image scaled x4
        Mat img = new Mat(112, 112, CV_8UC3, Scalar.all(0));
        try (UByteIndexer idx = img.createIndexer()) {
            for (int y = 0; y < 112; y++) {
                for (int x = 0; x < 112; x++) {
                    idx.put(y, x, 0, 2 * x);
                }
            }
        }
        Mat crop = Faces.alignedCrop(img, Faces.templateFor(112), 448);
        assertEquals(448, crop.cols());
        assertEquals(448, crop.rows());
        // x=200 in crop -> x=50 in source
        assertEquals(2 * 50, crop.ptr(200, 200).get(0) & 0xFF, 2);
    }

    @Test
    void alignedCropRejectsCoincidentLandmarks() {
        Mat img = new Mat(112, 112, CV_8UC3, Scalar.all(0));
        float[] landmarks = new float[10];

        assertThrows(IllegalArgumentException.class, () -> Faces.alignedCrop(img, landmarks, 112));
    }

    @Test
    void toImageCapsLongSideAndKeepsAspect() {
        BufferedImage b = Faces.toImage(new Mat(3000, 6000, CV_8UC3, Scalar.all(0)), 1080);
        assertEquals(1080, b.getWidth());
        assertEquals(540, b.getHeight());
        assertEquals(BufferedImage.TYPE_3BYTE_BGR, b.getType());
    }

    @Test
    void toImageNeverUpscales() {
        assertEquals(640, Faces.toImage(new Mat(480, 640, CV_8UC3, Scalar.all(0)), 1080).getWidth());
    }

    @Test
    void decodeAppliesExifRotation() throws IOException {
        // a 40x20 landscape JPEG saved with EXIF Orientation=6 (rotate 90 CW)
        try (Mat m = Faces.decode(Files.readAllBytes(Path.of("src/test/resources/exif-rotated.jpg")))) {
            assertEquals(20, m.cols());
            assertEquals(40, m.rows());
        }
    }
}
