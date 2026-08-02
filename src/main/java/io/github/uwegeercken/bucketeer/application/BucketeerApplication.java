package io.github.uwegeercken.bucketeer.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;

@SpringBootApplication(scanBasePackages = "io.github.uwegeercken.bucketeer")
@ConfigurationPropertiesScan
public class BucketeerApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--seed")) {
            String[] seedArgs = Arrays.stream(args)
                    .filter(a -> !"--seed".equals(a))
                    .toArray(String[]::new);
            System.exit(SeedRunner.run(seedArgs));
            return;
        }
        SpringApplication.run(BucketeerApplication.class, args);
    }
}
