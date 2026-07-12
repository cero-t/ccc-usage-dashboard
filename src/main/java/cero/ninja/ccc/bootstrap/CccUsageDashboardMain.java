package cero.ninja.ccc.bootstrap;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

import java.nio.file.Path;

@QuarkusMain
public final class CccUsageDashboardMain {

    CccUsageDashboardMain() {
    }

    public static void main(String... args) {
        ApplicationBootstrap.prepare(
                System.getenv(),
                System.getProperties(),
                Path.of(System.getProperty("user.dir")),
                Path.of(System.getProperty("user.home")),
                message -> System.out.println("[ccc-usage-dashboard] " + message));
        Quarkus.run(args);
    }
}
