package core;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpClient {

    private final static Logger LOGGER = LoggerFactory.getLogger(HttpClient.class);
    public static final String USER_AGENT = "Lawliet Discord Bot by Aninoss#7220";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public HttpResponse request(String url) {
        String domain = url.split("/")[2];
        try (AsyncTimer timer = new AsyncTimer(Duration.ofSeconds(10))) {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                return new HttpResponse()
                        .setCode(response.code())
                        .setBody(response.body().string());
            }
        } catch (InterruptedIOException e) {
            LOGGER.error("Web time out ({})", domain);
            return new HttpResponse()
                    .setCode(500);
        } catch (Throwable e) {
            LOGGER.error("Web error ({})", domain, e);
            return new HttpResponse()
                    .setCode(500);
        }
    }

}
