package core;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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

    private final LockManager lockManager = new LockManager();
    private final VideoDownloader videoDownloader = new VideoDownloader(lockManager, jedisPool);

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
                if (parts.length == 4 &&
                        parts[0].equals("media") &&
                        parts[1].equals("rule34") &&
                        StringUtil.stringIsInt(parts[2]) &&
                        fileIsVideo(parts[3]) &&
                        isResponsible(parts[2], parts[3])
                ) {
                    String videoDir = parts[2];
                    String videoFilename = parts[3];
                    String videoUrl = "https://api-cdn-mp4.rule34.xxx/images/" + videoDir + "/" + videoFilename;
                    videoDownloader.downloadVideo(videoUrl, videoDir, videoFilename);
                    saveVideoRequested("rule34/" + videoDir + "/" + videoFilename);
                    return Response.status(200).build();
                }
            }
            return Response.status(403).build();
        } catch (Throwable e) {
            LOGGER.error("Video request error", e);
            return Response.status(500).build();
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
