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
        Files.createDirectories(destDir);
        Path part = destDir.resolve(fileName + ".part");
        long existing = Files.exists(part) ? Files.size(part) : 0L;

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

        Path dest = destDir.resolve(fileName);
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }
}
