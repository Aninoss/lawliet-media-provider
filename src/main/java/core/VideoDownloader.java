package core;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VideoDownloader {

    private final static Logger LOGGER = LoggerFactory.getLogger(VideoDownloader.class);

    private final LockManager lockManager;

    public VideoDownloader(LockManager lockManager) {
        this.lockManager = lockManager;
    }

    public File downloadVideo(String videoUrl, String videoFileDir, String videoFilename) {
        File videoFile = new File(videoFileDir + "/" + videoFilename);
        synchronized (lockManager.get(videoUrl)) {
            if (!videoFile.exists()) {
                new File(videoFileDir).mkdir();
                LOGGER.info("Downloading video: {}", videoUrl);
                try {
                    FileUtils.copyURLToFile(
                            new URL(videoUrl),
                            videoFile,
                            3_000,
                            20_000
                    );
                } catch (IOException e) {
                    LOGGER.error("Exception on video download", e);
                }
                LOGGER.info("Video download complete: {}", videoUrl);
            }
        }
        return videoFile;
    }

}
