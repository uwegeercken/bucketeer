package io.github.uwegeercken.bucketeer.application;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.domain.template.PrefixTemplateEngine;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Service
public class BucketeerService implements BucketeerUseCase {

    private final S3StoragePort s3StoragePort;
    private final PrefixTemplateEngine templateEngine;

    public BucketeerService(S3StoragePort s3StoragePort, PrefixTemplateEngine templateEngine) {
        this.s3StoragePort = s3StoragePort;
        this.templateEngine = templateEngine;
    }

    @Override
    public List<String> serverNames() {
        return s3StoragePort.serverNames();
    }

    @Override
    public List<String> availableFunctions() {
        return templateEngine.availableFunctions();
    }

    @Override
    public String resolveTemplate(String template, String key) {
        return templateEngine.resolve(template, key);
    }

    @Override
    public List<String> validateTemplate(String template) {
        return templateEngine.validate(template);
    }

    @Override
    public ObjectListing listObjects(String serverName, String bucket, String resolvedPrefix, String continuationToken) {
        return s3StoragePort.listObjects(serverName, bucket, resolvedPrefix, continuationToken);
    }

    @Override
    public void fetchAllObjects(String serverName, String bucket, String resolvedPrefix,
                                Consumer<ObjectListing> pageCallback) {
        String token = null;
        do {
            ObjectListing page = s3StoragePort.listObjects(serverName, bucket, resolvedPrefix, token);
            pageCallback.accept(page);
            token = page.truncated() ? page.nextContinuationToken() : null;
        } while (token != null);
    }
}