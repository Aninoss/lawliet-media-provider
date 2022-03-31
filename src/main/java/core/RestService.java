package core;

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
                    if (parts[0].equals("media") &&
                            parts[1].equals("rule34") &&
                            subdomainPattern.matcher(subdomain).matches() &&
                            videoDirPattern.matcher(videoDir).matches() &&
                            videoFilePattern.matcher(videoFile).matches() &&
                            fileIsVideo(videoFile) &&
                            isResponsible(videoDir, videoFile)
                    ) {
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
    @Path("/proxy/{url}/{auth}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response cachedProxy(@PathParam("url") String url, @PathParam("auth") String auth) {
        try {
            if (System.getenv("AUTH").equals(auth)) {
                HttpResponse httpResponse = httpClient.request(url);
                if (httpResponse.getCode() / 100 == 2) {
                    return Response.ok(httpResponse.getBody()).build();
                } else {
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

    private boolean isResponsible(String videoDir, String videoFilename) {
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

}
