package core;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;

public class VideoDownloader {

    private final static Logger LOGGER = LoggerFactory.getLogger(VideoDownloader.class);
    private static final String VIDEO_ROOT_DIR = "/cdn/media";
    private static final Duration VIDEO_CACHE_MAX_AGE = Duration.ofDays(30);

    private final LockManager lockManager;
    private final OkHttpClient httpClient;

    public VideoDownloader(LockManager lockManager, OkHttpClient httpClient) {
        this.lockManager = lockManager;
        this.httpClient = httpClient;
    }

    public long downloadVideo(String domain, String videoUrl, String videoDir, String videoFilename) {
        return downloadVideo(domain, videoUrl, videoDir, videoFilename, null);
    }

    public long downloadVideo(String domain, String videoUrl, String videoDir, String videoFilename, String referer) {
        String videoFullDir = VIDEO_ROOT_DIR + "/" + domain + "/" + videoDir;
        File videoFile = new File(videoFullDir + "/" + videoFilename);
        synchronized (lockManager.get(videoUrl)) {
            if (!videoFile.exists()) {
                new File(videoFullDir).mkdirs();
                LOGGER.info("Downloading video: {}", videoUrl);

                Request.Builder request = new Request.Builder()
                        .header("User-Agent", RestService.USER_AGENT)
                        .header("Accept-Encoding", "gzip")
                        .url(videoUrl)
                        .get();

                if (referer != null) {
                    request = request.header("Referer", referer);
                }

                try (okhttp3.Response response = httpClient.newCall(request.build()).execute();
                     ResponseBody body = response.body();
                     BufferedSink sink = Okio.buffer(Okio.sink(videoFile));
                ) {
                    sink.writeAll(body.source());
                } catch (IOException e) {
                    LOGGER.error("Exception on video download", e);
                }
                LOGGER.info("Video download complete: {}", videoUrl);
            }
        }

        try {
            return Files.size(videoFile.toPath());
        } catch (IOException e) {
            LOGGER.error("Exception when reading file size for {}", videoFile.getPath(), e);
            return -1;
        }
    }

}
