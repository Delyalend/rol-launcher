package rol.devkit;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds a Windows multi-resolution ICO from a launcher logo PNG. */
final class IconTool {

    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    private IconTool() {}

    static void create(String sourceName, String targetName) throws IOException {
        BufferedImage source = ImageIO.read(Path.of(sourceName).toFile());
        if (source == null) throw new IOException("Cannot read source image: " + sourceName);
        byte[][] pngs = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) pngs[i] = png(source, SIZES[i]);

        Path target = Path.of(targetName).toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(target))) {
            writeShort(out, 0);
            writeShort(out, 1);
            writeShort(out, SIZES.length);
            int offset = 6 + 16 * SIZES.length;
            for (int i = 0; i < SIZES.length; i++) {
                int size = SIZES[i];
                out.writeByte(size == 256 ? 0 : size);
                out.writeByte(size == 256 ? 0 : size);
                out.writeByte(0);
                out.writeByte(0);
                writeShort(out, 1);
                writeShort(out, 32);
                writeInt(out, pngs[i].length);
                writeInt(out, offset);
                offset += pngs[i].length;
            }
            for (byte[] png : pngs) out.write(png);
        }
        System.out.println("Icon created: " + target);
    }

    private static byte[] png(BufferedImage source, int size) throws IOException {
        BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(new Color(4, 6, 22, 255));
        g.fillRect(0, 0, size, size);
        int margin = Math.max(1, size / 16);
        g.setColor(new Color(190, 143, 37, 255));
        g.setStroke(new BasicStroke(Math.max(1, size / 32f)));
        g.drawRoundRect(margin, margin, size - margin * 2 - 1, size - margin * 2 - 1,
                Math.max(2, size / 8), Math.max(2, size / 8));
        double scale = Math.min((size * 0.86) / source.getWidth(), (size * 0.62) / source.getHeight());
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
        g.drawImage(source, (size - w) / 2, (size - h) / 2, w, h, null);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(icon, "png", bytes);
        return bytes.toByteArray();
    }

    private static void writeShort(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 255);
        out.writeByte((value >> 8) & 255);
    }

    private static void writeInt(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 255);
        out.writeByte((value >> 8) & 255);
        out.writeByte((value >> 16) & 255);
        out.writeByte((value >> 24) & 255);
    }
}
