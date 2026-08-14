package az.ideanest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the IdeaNest API.
 *
 * <p>The service is a modular monolith. Every module lives in its own package
 * under {@code az.ideanest} and is described in that package's
 * {@code package-info.java}.
 */
@SpringBootApplication
public class IdeaNestApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdeaNestApplication.class, args);
    }
}
