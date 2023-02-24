package core;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import jakarta.inject.Singleton;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import util.StringUtil;

@Path("")
@Singleton
public class RestService {

    private final static Logger LOGGER = LoggerFactory.getLogger(RestService.class);

    private final JedisPool jedisPool = new JedisPool(
            buildPoolConfig(),
            System.getenv("REDIS_HOST"),
            Integer.parseInt(System.getenv("REDIS_PORT"))
    );

    private final Pattern SUBDOMAIN_PATTERN = Pattern.compile("^[a-zA-Z0-9-]*$");
    private final Pattern RULE34_VIDEO_DIR_PATTERN = Pattern.compile("^[0-9]*$");
    private final Pattern DANBOORU_VIDEO_DIR_PATTERN = Pattern.compile("^[0-9a-f]*/[0-9a-f]*$");
    private final Pattern RULE34_VIDEO_FILE_PATTERN = Pattern.compile("^[a-z0-9.]*$");

    private final String DEFAULT_SUBDOMAIN_RULE34 = "api-cdn-mp4";
    private final String DEFAULT_SUBDOMAIN_DANBOORU = "cdn";

    private final LockManager lockManager = new LockManager();
    private final VideoDownloader videoDownloader = new VideoDownloader(lockManager, jedisPool);
    private final HttpClient httpClient = new HttpClient();

    @GET
    @Path("/ping")
    @Produces(MediaType.TEXT_PLAIN)
    public String ping() {
        return "Pong!";
    }

    @GET
    @Path("/request")
    public Response request(@HeaderParam("X-Original-URI") String uri) {
        try {
            if (!uri.contains("/")) {
                return Response.status(403).build();
            }

            String[] parts = uri.substring(1).split("/");
            if (parts.length < 4) {
                return Response.status(403).build();
            }

            if (parts[1].equals("rule34")) {
                if (parts.length != 4) {
                    return Response.status(403).build();
                }
                return requestRule34(parts);
            } else if (parts[1].equals("danbooru")) {
                if (parts.length != 5) {
                    return Response.status(403).build();
                }
                return requestDanbooru(parts);
            } {
                return Response.status(403).build();
            }
        } catch (Throwable e) {
            LOGGER.error("Video request error", e);
            return Response.status(500).build();
        }
    }

    private Response requestRule34(String[] parts) {
        VideoFileAndSubdomain videoFileAndSubdomain = extractVideoFileSubdomain(parts[3], DEFAULT_SUBDOMAIN_RULE34);
        String subdomain = videoFileAndSubdomain.subdomain;
        String videoDir = parts[2];
        String videoFile = videoFileAndSubdomain.videoFile;

        if (!checkRequestUriRule34(subdomain, videoDir, videoFile)) {
            return Response.status(403).build();
        }

        String videoUrl = "https://" + subdomain + ".rule34.xxx/images/" + videoDir + "/" + videoFile;
        videoDownloader.downloadVideo("rule34", videoUrl, videoDir, videoFile);
        saveVideoRequested("rule34/" + videoDir + "/" + videoFile);
        return Response.status(200).build();
    }

    private Response requestDanbooru(String[] parts) {
        VideoFileAndSubdomain videoFileAndSubdomain = extractVideoFileSubdomain(parts[4], DEFAULT_SUBDOMAIN_DANBOORU);
        String subdomain = videoFileAndSubdomain.subdomain;
        String videoDir = parts[2] + "/" + parts[3];
        String videoFile = videoFileAndSubdomain.videoFile;

        if (!checkRequestUriDanbooru(subdomain, videoDir, videoFile)) {
            return Response.status(403).build();
        }

        String videoUrl = "https://" + subdomain + ".donmai.us/original/" + videoDir + "/" + videoFile;
        videoDownloader.downloadVideo("danbooru", videoUrl, videoDir, videoFile);
        saveVideoRequested("danbooru/" + videoDir + "/" + videoFile);
        return Response.status(200).build();
    }

    @GET
    @Path("/media/rule34/{videoDir}/{videoFileAndSubdomainRaw}")
    public Response redirectRule34(@PathParam("videoDir") String videoDir,
                                   @PathParam("videoFileAndSubdomainRaw") String videoFileAndSubdomainRaw) {
        try {
            VideoFileAndSubdomain videoFileAndSubdomain = extractVideoFileSubdomain(videoFileAndSubdomainRaw, DEFAULT_SUBDOMAIN_RULE34);
            String subdomain = videoFileAndSubdomain.subdomain;
            String videoFile = videoFileAndSubdomain.videoFile;

            if (checkRequestUriRule34(subdomain, videoDir, videoFile)) {
                String videoUrl = "https://" + subdomain + ".rule34.xxx/images/" + videoDir + "/" + videoFile;
                return Response.temporaryRedirect(new URI(videoUrl)).build();
            } else {
                return Response.status(403).build();
            }
        } catch (Throwable e) {
            LOGGER.error("Rule34 redirect error", e);
            return Response.status(500).build();
        }
    }

    @GET
    @Path("/media/danbooru/{videoDir1}/{videoDir2}/{videoFileAndSubdomainRaw}")
    public Response redirectDanbooru(@PathParam("videoDir1") String videoDir1,
                                     @PathParam("videoDir2") String videoDir2,
                                   @PathParam("videoFileAndSubdomainRaw") String videoFileAndSubdomainRaw) {
        try {
            VideoFileAndSubdomain videoFileAndSubdomain = extractVideoFileSubdomain(videoFileAndSubdomainRaw, DEFAULT_SUBDOMAIN_DANBOORU);
            String subdomain = videoFileAndSubdomain.subdomain;
            String videoFile = videoFileAndSubdomain.videoFile;
            String videoDir = videoDir1 + "/" + videoDir2;

            if (checkRequestUriDanbooru(subdomain, videoDir, videoFile)) {
                String videoUrl = "https://" + subdomain + ".donmai.us/original/" + videoDir + "/" + videoFile;
                return Response.temporaryRedirect(new URI(videoUrl)).build();
            } else {
                return Response.status(403).build();
            }
        } catch (Throwable e) {
            LOGGER.error("Danbooru redirect error", e);
            return Response.status(500).build();
        }
    }

    @GET
    @Path("/proxy/{url}/{auth}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response proxy(@PathParam("url") String url, @PathParam("auth") String auth) {
        try {
            if (!System.getenv("AUTH").equals(auth)) {
                return Response.status(403).build();
            }

            HttpResponse httpResponse = httpClient.request(url);
            if (httpResponse.getCode() / 100 == 2) {
                return Response.ok(httpResponse.getBody()).build();
            } else {
                LOGGER.warn("Proxy: error response {} for url {}", httpResponse.getCode(), url);
                return Response.status(httpResponse.getCode()).build();
            }
        } catch (Throwable e) {
            LOGGER.error("Error in /proxy", e);
            throw e;
        }
    }

    private VideoFileAndSubdomain extractVideoFileSubdomain(String videoFileAndSubdomain, String defaultSubdomain) {
        if (videoFileAndSubdomain.contains("?s=")) {
            String[] splits = videoFileAndSubdomain.split("\\?s=");
            return new VideoFileAndSubdomain(splits[0], splits[1]);
        } else {
            return new VideoFileAndSubdomain(videoFileAndSubdomain, defaultSubdomain);
        }
    }

    private boolean checkRequestUriRule34(String subdomain, String videoDir, String videoFile) {
        return SUBDOMAIN_PATTERN.matcher(subdomain).matches() &&
                RULE34_VIDEO_DIR_PATTERN.matcher(videoDir).matches() &&
                RULE34_VIDEO_FILE_PATTERN.matcher(videoFile).matches() &&
                fileIsVideo(videoFile) &&
                isResponsible(videoDir, videoFile);
    }

    private boolean checkRequestUriDanbooru(String subdomain, String videoDir, String videoFile) {
        return SUBDOMAIN_PATTERN.matcher(subdomain).matches() &&
                DANBOORU_VIDEO_DIR_PATTERN.matcher(videoDir).matches() &&
                fileIsVideo(videoFile) &&
                isResponsible(videoDir, videoFile);
    }

    private boolean isResponsible(String videoDir, String videoFilename) {
        if (!Boolean.parseBoolean(System.getenv("SHARD_BLOCKING"))) {
            return true;
        }

        int maxShards = Integer.parseInt(System.getenv("MAX_SHARDS"));
        int fileShard = Math.abs(Objects.hash(videoDir, videoFilename)) % maxShards;
        return Arrays.stream(System.getenv("SHARDS").split(","))
                .map(Integer::parseInt)
                .anyMatch(shard -> shard == fileShard);
    }

    private void saveVideoRequested(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(id, Instant.now().toString());
        }
    }

    private boolean fileIsVideo(String file) {
        String[] parts = file.split("\\.");
        return parts.length == 2 &&
                (parts[1].equals("mp4") || parts[1].equals("avi") || parts[1].equals("webm"));
    }

    private JedisPoolConfig buildPoolConfig() {
        final JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(32);
        poolConfig.setMinIdle(0);
        return poolConfig;
    }


    private static class VideoFileAndSubdomain {

        private final String videoFile;
        private final String subdomain;

        public VideoFileAndSubdomain(String videoFile, String subdomain) {
            this.videoFile = videoFile;
            this.subdomain = subdomain;
        }

    }

}
