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

    private final Pattern subdomainPattern = Pattern.compile("^[a-zA-Z0-9-]*$");
    private final Pattern videoDirPattern = Pattern.compile("^[0-9]*$");
    private final Pattern videoFilePattern = Pattern.compile("^[a-z0-9.]*$");

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
            if (uri.contains("/")) {
                String[] parts = uri.substring(1).split("/");
                if (parts.length == 4) {
                    String subdomain;
                    String videoDir;
                    String videoFile;
                    if (parts[3].contains("?s=")) {
                        subdomain = parts[3].split("\\?s=")[1];
                        videoDir = parts[2];
                        videoFile = parts[3].split("\\?s=")[0];
                    } else {
                        subdomain = "api-cdn-mp4";
                        videoDir = parts[2];
                        videoFile = parts[3];
                    }
                    if (checkRequestUri(parts[0], parts[1], subdomain, videoDir, videoFile)) {
                        String videoUrl = "https://" + subdomain + ".rule34.xxx/images/" + videoDir + "/" + videoFile;
                        videoDownloader.downloadVideo(videoUrl, videoDir, videoFile);
                        saveVideoRequested("rule34/" + videoDir + "/" + videoFile);
                        return Response.status(200).build();
                    }
                }
            }
            return Response.status(403).build();
        } catch (Throwable e) {
            LOGGER.error("Video request error", e);
            return Response.status(500).build();
        }
    }

    @GET
    @Path("/media/{domain}/{videoDir}/{videoFileAndSubdomainRaw}")
    public Response redirect(@PathParam("domain") String domain,
                             @PathParam("videoDir") String videoDir,
                             @PathParam("videoFileAndSubdomainRaw") String videoFileAndSubdomainRaw) {
        try {
            VideoFileAndSubdomain videoFileAndSubdomain = extractVideoFileSubdomain(videoFileAndSubdomainRaw);
            String subdomain = videoFileAndSubdomain.subdomain;
            String videoFile = videoFileAndSubdomain.videoFile;

            if (checkRequestUri("media", domain, subdomain, videoDir, videoFile)) {
                String videoUrl = "https://" + subdomain + ".rule34.xxx/images/" + videoDir + "/" + videoFile;
                return Response.temporaryRedirect(new URI(videoUrl)).build();
            } else {
                return Response.status(403).build();
            }
        } catch (Throwable e) {
            LOGGER.error("Redirect error", e);
            return Response.status(500).build();
        }
    }

    @GET
    @Path("/proxy/{url}/{auth}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response proxy(@PathParam("url") String url, @PathParam("auth") String auth) {
        try {
            if (System.getenv("AUTH").equals(auth)) {
                HttpResponse httpResponse = httpClient.request(url);
                if (httpResponse.getCode() / 100 == 2) {
                    return Response.ok(httpResponse.getBody()).build();
                } else {
                    LOGGER.warn("Proxy: error response {} for url {}", httpResponse.getCode(), url);
                    return Response.status(httpResponse.getCode()).build();
                }
            } else {
                return Response.status(403).build();
            }
        } catch (Throwable e) {
            LOGGER.error("Error in /proxy", e);
            throw e;
        }
    }

    private VideoFileAndSubdomain extractVideoFileSubdomain(String videoFileAndSubdomain) {
        if (videoFileAndSubdomain.contains("?s=")) {
            String[] splits = videoFileAndSubdomain.split("\\?s=");
            return new VideoFileAndSubdomain(splits[0], splits[1]);
        } else {
            return new VideoFileAndSubdomain(videoFileAndSubdomain, "api-cdn-us-mp4");
        }
    }

    private boolean checkRequestUri(String root, String domain, String subdomain, String videoDir, String videoFile) {
        return root.equals("media") &&
                domain.equals("rule34") &&
                subdomainPattern.matcher(subdomain).matches() &&
                videoDirPattern.matcher(videoDir).matches() &&
                videoFilePattern.matcher(videoFile).matches() &&
                fileIsVideo(videoFile) &&
                isResponsible(videoDir, videoFile);
    }

    private boolean isResponsible(String videoDir, String videoFilename) {
        if (Boolean.parseBoolean(System.getenv("SHARD_BLOCKING"))) {
            int maxShards = Integer.parseInt(System.getenv("MAX_SHARDS"));
            int fileShard = Math.abs(Objects.hash(videoDir, videoFilename)) % maxShards;
            return Arrays.stream(System.getenv("SHARDS").split(","))
                    .map(Integer::parseInt)
                    .anyMatch(shard -> shard == fileShard);
        } else {
            return true;
        }
    }

    private void saveVideoRequested(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(id, Instant.now().toString());
        }
    }

    private boolean fileIsVideo(String file) {
        String[] parts = file.split("\\.");
        return parts.length == 2 &&
                StringUtil.stringIsAlphanumeric(parts[0]) &&
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
