# LessConfig Java SDK

Java SDK for LessConfig. Access projects, environments and configurations, and receive configuration change notifications.

## Requirements

Java 8 or later.

## Maven

Build and install the SDK into your local Maven repository:

```shell
mvn clean install
```

Add the dependency to your application:

```xml
<dependency>
    <groupId>com.lessconfig</groupId>
    <artifactId>lessconfig-java</artifactId>
    <version>0.0.1</version>
</dependency>
```

## Quick Start

Set `LESSCONFIG_ENDPOINT` to your API root URL, such as `https://example.com/api`, and `LESSCONFIG_TOKEN` to your API token. The SDK adds the API version. Replace the project, environment and configuration key below with your own values.

```java
import com.lessconfig.Config;
import com.lessconfig.LessConfigClient;

import java.io.IOException;

/** Reads a configuration and listens for changes. */
public final class ConfigExample {
    /**
     * Runs the example until Enter is pressed.
     *
     * @param args unused command-line arguments
     * @throws IOException if reading console input fails
     */
    public static void main(String[] args) throws IOException {
        try (LessConfigClient client = LessConfigClient.builder()
                .endpoint(System.getenv("LESSCONFIG_ENDPOINT"))
                .token(System.getenv("LESSCONFIG_TOKEN"))
                .build()) {

            client.addConfigListener("payment", "prod", "application.yml", changes -> {
                Config latest = client.getConfig("payment", "prod", "application.yml");
                System.out.println(latest == null ? "Configuration removed" : latest.getValue());
            });

            Config config = client.getConfig("payment", "prod", "application.yml");
            System.out.println(config == null ? "Configuration not found" : config.getValue());

            System.out.println("Listening for changes. Press Enter to exit.");
            System.in.read();
        }
    }
}
```

## Build and Test

```shell
mvn clean verify
```

JARs are generated in `target/`, with API documentation at `target/reports/apidocs/index.html`.

## License

Licensed under the [Apache License 2.0](LICENSE).
