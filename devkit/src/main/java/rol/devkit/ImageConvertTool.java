package rol.devkit;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Converts the DDS payloads used by the game (often named .tga) to PNG. */
final class ImageConvertTool {

    private ImageConvertTool() {}

    static void convert(String sourceName, String targetName) throws IOException {
        Path source = Path.of(sourceName).toAbsolutePath().normalize();
        Path target = Path.of(targetName).toAbsolutePath().normalize();
        byte[] data = Files.readAllBytes(source);
        BufferedImage image = decodeDds(data, source);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("PNG writer is not available");
        }
        System.out.printf("Converted %s -> %s (%dx%d)%n",
                source, target, image.getWidth(), image.getHeight());
    }

    private static BufferedImage decodeDds(byte[] data, Path source) throws IOException {
        if (data.length < 128 || data[0] != 'D' || data[1] != 'D'
                || data[2] != 'S' || data[3] != ' ') {
            throw new IOException("Not a supported DDS image: " + source);
        }
        int height = intAt(data, 12);
        int width = intAt(data, 16);
        int pitch = intAt(data, 20);
        int pixelFlags = intAt(data, 80);
        int fourCc = intAt(data, 84);
        int bits = intAt(data, 88);
        if (width <= 0 || height <= 0) throw new IOException("Invalid DDS dimensions: " + source);

        if ((pixelFlags & 0x4) != 0 && fourCc == fourCc('D', 'X', 'T', '5')) {
            return decodeDxt5(data, width, height);
        }
        if ((pixelFlags & 0x40) != 0 && bits == 32) {
            return decodeBgra(data, width, height, pitch);
        }
        throw new IOException(String.format("Unsupported DDS format in %s (flags=0x%X, bits=%d)",
                source, pixelFlags, bits));
    }

    private static BufferedImage decodeBgra(byte[] data, int width, int height, int pitch) throws IOException {
        int rowBytes = pitch > 0 ? pitch : width * 4;
        require(data, 128L + (long) rowBytes * height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            int row = 128 + y * rowBytes;
            for (int x = 0; x < width; x++) {
                int p = row + x * 4;
                int b = data[p] & 0xff;
                int g = data[p + 1] & 0xff;
                int r = data[p + 2] & 0xff;
                int a = data[p + 3] & 0xff;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static BufferedImage decodeDxt5(byte[] data, int width, int height) throws IOException {
        int blocksWide = (width + 3) / 4;
        int blocksHigh = (height + 3) / 4;
        long end = 128L + (long) blocksWide * blocksHigh * 16;
        require(data, end);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int offset = 128;
        for (int by = 0; by < blocksHigh; by++) {
            for (int bx = 0; bx < blocksWide; bx++) {
                decodeDxt5Block(data, offset, image, bx * 4, by * 4, width, height);
                offset += 16;
            }
        }
        return image;
    }

    private static void decodeDxt5Block(byte[] data, int offset, BufferedImage image,
                                        int x0, int y0, int width, int height) {
        int a0 = data[offset] & 0xff;
        int a1 = data[offset + 1] & 0xff;
        int[] alpha = new int[8];
        alpha[0] = a0;
        alpha[1] = a1;
        if (a0 > a1) {
            for (int i = 1; i <= 6; i++) alpha[i + 1] = ((7 - i) * a0 + i * a1) / 7;
        } else {
            for (int i = 1; i <= 4; i++) alpha[i + 1] = ((5 - i) * a0 + i * a1) / 5;
            alpha[6] = 0;
            alpha[7] = 255;
        }
        long alphaBits = 0;
        for (int i = 0; i < 6; i++) alphaBits |= (long) (data[offset + 2 + i] & 0xff) << (8 * i);

        int c0 = ushortAt(data, offset + 8);
        int c1 = ushortAt(data, offset + 10);
        int[] colors = new int[4];
        colors[0] = rgb565(c0);
        colors[1] = rgb565(c1);
        colors[2] = interpolate(colors[0], colors[1], 2, 1, 3);
        colors[3] = interpolate(colors[0], colors[1], 1, 2, 3);
        long colorBits = Integer.toUnsignedLong(intAt(data, offset + 12));
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int pixel = y * 4 + x;
                int alphaIndex = (int) ((alphaBits >> (3 * pixel)) & 7);
                int colorIndex = (int) ((colorBits >> (2 * pixel)) & 3);
                int px = x0 + x, py = y0 + y;
                if (px < width && py < height) {
                    image.setRGB(px, py, (alpha[alphaIndex] << 24) | colors[colorIndex]);
                }
            }
        }
    }

    private static int rgb565(int value) {
        int r = ((value >> 11) & 31) * 255 / 31;
        int g = ((value >> 5) & 63) * 255 / 63;
        int b = (value & 31) * 255 / 31;
        return (r << 16) | (g << 8) | b;
    }

    private static int interpolate(int a, int b, int wa, int wb, int divisor) {
        int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / divisor;
        int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / divisor;
        int blue = ((a & 255) * wa + (b & 255) * wb) / divisor;
        return (r << 16) | (g << 8) | blue;
    }

    private static int intAt(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8)
                | ((data[offset + 2] & 255) << 16) | (data[offset + 3] << 24);
    }

    private static int ushortAt(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }

    private static int fourCc(char a, char b, char c, char d) {
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    private static void require(byte[] data, long size) throws IOException {
        if (size > data.length) throw new IOException("Truncated DDS image");
    }
}
