package core;

import java.net.URI;
import jakarta.ws.rs.core.UriBuilder;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

public class Main {

    public static void main(String[] args) {
        Program.init();
        ResourceConfig rc = new ResourceConfig(RestService.class);

        URI endpoint = UriBuilder
                .fromUri("http://0.0.0.0/")
                .port(Integer.parseInt(System.getenv("PORT")))
                .build();

        GrizzlyHttpServerFactory.createHttpServer(endpoint, rc);
    }

}
