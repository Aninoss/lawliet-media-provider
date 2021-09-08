package core;

import java.io.File;
import java.time.Instant;
import jakarta.inject.Singleton;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import util.StringUtil;

@Path("")
@Singleton
public class RestService {

    private final JedisPool jedisPool = new JedisPool(
            buildPoolConfig(),
            System.getenv("REDIS_HOST"),
            Integer.parseInt(System.getenv("REDIS_PORT"))
    );

    private final LockManager lockManager = new LockManager();
    private final VideoDownloader videoDownloader = new VideoDownloader(lockManager);

    @GET
    @Path("/ping")
    @Produces(MediaType.TEXT_PLAIN)
    public String ping() {
        return "Pong!";
    }

    @GET
    @Path("/media/rule34/{arg0}/{arg1}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response ping(@PathParam("arg0") String arg0, @PathParam("arg1") String arg1) {
        if (StringUtil.stringIsInt(arg0) && fileIsVideo(arg1)) {
            String videoUrl = "https://api-cdn-mp4.rule34.xxx/images/" + arg0 + "/" + arg1;
            String videoFileDir = "/cdn/media/rule34/" + arg0;
            File videoFile = videoDownloader.downloadVideo(videoUrl, videoFileDir, arg1);
            saveVideoRequested("rule34/" + arg0 + "/" + arg1);
            return Response.ok(videoFile, MediaType.APPLICATION_OCTET_STREAM)
                    .build();
        }
        return Response.status(403).build();
    }

    private void saveVideoRequested(String id) {
        try(Jedis jedis = jedisPool.getResource()) {
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
