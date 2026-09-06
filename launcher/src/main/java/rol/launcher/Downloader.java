package rol.launcher;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * File download with resume support and progress reporting.
 *
 * Downloads to &lt;file&gt;.part and renames on success; if a partial file
 * exists, the download continues from its size via the HTTP Range header.
 * total == -1 in progress callbacks means the size is unknown.
 */
public final class Downloader {

    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Downloader() {}

    public static Path download(String url, Path destDir, String fileName, ProgressListener listener)
            throws IOException, InterruptedException {
        return download(url, destDir, fileName, listener, null, null);
    }

    /**
     * Downloads or reuses a cached file. If expectedSize and expectedSha256
     * are both present and the final file matches, no network request is made.
     * A mismatching cached file is removed before downloading.
     */
    public static Path download(String url, Path destDir, String fileName,
                                ProgressListener listener, Long expectedSize,
                                String expectedSha256)
            throws IOException, InterruptedException {
        Files.createDirectories(destDir);
        Path dest = destDir.resolve(fileName);
        if (expectedSize != null && expectedSha256 != null && Files.isRegularFile(dest)) {
            if (Files.size(dest) == expectedSize && sha256Hex(dest).equalsIgnoreCase(expectedSha256)) {
                if (listener != null) listener.onProgress(expectedSize, expectedSize);
                Log.info("Using cached file: " + dest.getFileName());
                return dest;
            }
            Files.deleteIfExists(dest);
            Log.info("Cached file failed verification, redownloading: " + dest.getFileName());
        }
        Path part = destDir.resolve(fileName + ".part");
        long existing = Files.exists(part) ? Files.size(part) : 0L;
        if (expectedSize != null && existing > expectedSize) {
            Files.deleteIfExists(part);
            existing = 0L;
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofHours(2))
                .GET();
        if (existing > 0) {
            request.header("Range", "bytes=" + existing + "-");
        }

        HttpResponse<InputStream> response = HTTP.send(request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int code = response.statusCode();

        long total;
        boolean append;
        if (code == 200) {
            existing = 0; // server ignored the range request — start over
            append = false;
            total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        } else if (code == 206) {
            append = true;
            total = -1L;
            String contentRange = response.headers().firstValue("Content-Range").orElse("");
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0) {
                String totalPart = contentRange.substring(slash + 1).trim();
                if (!totalPart.equals("*")) {
                    total = Long.parseLong(totalPart);
                }
            }
        } else {
            throw new IOException("HTTP " + code + " from " + url);
        }

        long done = existing;
        if (listener != null) {
            listener.onProgress(done, total);
        }
        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
        try (InputStream in = response.body();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(part, options))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (listener != null) {
                    listener.onProgress(done, total);
                }
            }
        }

        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
        if (expectedSize != null && Files.size(dest) != expectedSize) {
            throw new IOException("Downloaded size mismatch for " + fileName);
        }
        if (expectedSha256 != null && !sha256Hex(dest).equalsIgnoreCase(expectedSha256)) {
            Files.deleteIfExists(dest);
            throw new IOException("Downloaded checksum mismatch for " + fileName);
        }
        return dest;
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder out = new StringBuilder(64);
            for (byte b : md.digest()) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }
}
