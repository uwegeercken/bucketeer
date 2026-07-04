package io.github.uwegeercken.bucketeer.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "io.github.uwegeercken.bucketeer")
@ConfigurationPropertiesScan
public class BucketeerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BucketeerApplication.class, args);
    }
}
