package io.github.uwegeercken.bucketeer.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3ClientConfig {

    @Bean
    public Map<String, S3Client> s3Clients(S3Properties props) {
        Map<String, S3Client> clients = new LinkedHashMap<>();
        for (S3Properties.ServerEntry server : props.servers()) {
            S3Client client = S3Client.builder()
                    .endpointOverride(URI.create(server.endpoint()))
                    .region(Region.of(server.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(server.accessKey(), server.secretKey())
                    ))
                    .forcePathStyle(true)
                    .build();
            clients.put(server.name(), client);
        }
        return clients;
    }
}
