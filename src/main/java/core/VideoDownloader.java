package core;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class VideoDownloader {

    private final static Logger LOGGER = LoggerFactory.getLogger(VideoDownloader.class);
    private static final String VIDEO_ROOT_DIR = "/cdn/media";
    private static final Duration VIDEO_CACHE_MAX_AGE = Duration.ofDays(30);

    private final LockManager lockManager;
    private final JedisPool jedisPool;

    public VideoDownloader(LockManager lockManager, JedisPool jedisPool) {
        this.lockManager = lockManager;
        this.jedisPool = jedisPool;
        if (Boolean.parseBoolean(System.getenv("CACHE_CLEANER"))) {
            startCacheCleaner();
        }
    }

    public void downloadVideo(String domain, String videoUrl, String videoDir, String videoFilename) {
        String videoFullDir = VIDEO_ROOT_DIR + "/" + domain + "/" + videoDir;
        File videoFile = new File(videoFullDir + "/" + videoFilename);
        synchronized (lockManager.get(videoUrl)) {
            if (!videoFile.exists()) {
                new File(videoFullDir).mkdir();
                LOGGER.info("Downloading video: {}", videoUrl);
                try {
                    FileUtils.copyURLToFile(
                            new URL(videoUrl),
                            videoFile,
                            3_000,
                            30_000
                    );
                } catch (IOException e) {
                    LOGGER.error("Exception on video download", e);
                }
                LOGGER.info("Video download complete: {}", videoUrl);
            }
        }
    }

    private void startCacheCleaner() {
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleAtFixedRate(() -> {
            LOGGER.info("Starting cache cleaner...");
            cleanCacheOneLevel("rule34");
            cleanCacheTwoLevels("danbooru");
            cleanCacheTwoLevels("realbooru");
        }, 0, 1, TimeUnit.DAYS);
    }

    private void cleanCacheOneLevel(String domain) {
        int fileCount = 0;
        int fileDeleteCount = 0;
        int fileDeleteErrorCount = 0;

        try (Jedis jedis = jedisPool.getResource()) {
            File rootDirFile = new File(VIDEO_ROOT_DIR + "/" + domain);
            for (File dir : rootDirFile.listFiles()) {
                for (File videoFile : dir.listFiles()) {
                    String[] parts = videoFile.getAbsolutePath().split("/");
                    String redisKey = parts[parts.length - 3] + "/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
                    String redisValue = jedis.get(redisKey);
                    Instant videoInstant = redisValue != null ? Instant.parse(redisValue).plus(VIDEO_CACHE_MAX_AGE) : Instant.MIN;
                    if (Instant.now().isAfter(videoInstant)) {
                        if (videoFile.delete()) {
                            fileDeleteCount++;
                        } else {
                            fileDeleteErrorCount++;
                        }
                    }
                    fileCount++;
                }
            }

            LOGGER.info("Cache cleaner completed! ({}; {} / {} deleted; {} errors)", domain, fileDeleteCount, fileCount, fileDeleteErrorCount);
        } catch (Throwable e) {
            LOGGER.error("Error in cache cleaner", e);
        }
    }

    private void cleanCacheTwoLevels(String domain) {
        int fileCount = 0;
        int fileDeleteCount = 0;
        int fileDeleteErrorCount = 0;

        try (Jedis jedis = jedisPool.getResource()) {
            File rootDirFile = new File(VIDEO_ROOT_DIR + "/" + domain);
            for (File dir1 : rootDirFile.listFiles()) {
                for (File dir2 : dir1.listFiles()) {
                    for (File videoFile : dir2.listFiles()) {
                        String[] parts = videoFile.getAbsolutePath().split("/");
                        String redisKey = parts[parts.length - 4] + "/" + parts[parts.length - 3] + "/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
                        String redisValue = jedis.get(redisKey);
                        Instant videoInstant = redisValue != null ? Instant.parse(redisValue).plus(VIDEO_CACHE_MAX_AGE) : Instant.MIN;
                        if (Instant.now().isAfter(videoInstant)) {
                            if (videoFile.delete()) {
                                fileDeleteCount++;
                            } else {
                                fileDeleteErrorCount++;
                            }
                        }
                        fileCount++;
                    }
                }
            }

            LOGGER.info("Cache cleaner completed! ({}; {} / {} deleted; {} errors)", domain, fileDeleteCount, fileCount, fileDeleteErrorCount);
        } catch (Throwable e) {
            LOGGER.error("Error in cache cleaner", e);
        }
    }

}
