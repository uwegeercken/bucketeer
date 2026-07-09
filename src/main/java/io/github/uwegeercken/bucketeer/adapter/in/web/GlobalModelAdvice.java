package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.infrastructure.config.S3Properties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

@ControllerAdvice
public class GlobalModelAdvice {

    private final BucketeerUseCase bucketeerUseCase;
    private final SessionContext sessionContext;
    private final S3Properties s3Properties;

    public GlobalModelAdvice(BucketeerUseCase bucketeerUseCase,
                             SessionContext sessionContext,
                             S3Properties s3Properties) {
        this.bucketeerUseCase = bucketeerUseCase;
        this.sessionContext   = sessionContext;
        this.s3Properties     = s3Properties;
    }

    @ModelAttribute("serverNames")
    public List<String> serverNames() {
        return bucketeerUseCase.serverNames();
    }

    @ModelAttribute("selectedServer")
    public String selectedServer() {
        List<String> names = bucketeerUseCase.serverNames();
        if (sessionContext.getSelectedServer() == null && !names.isEmpty()) {
            sessionContext.setSelectedServer(names.getFirst());
        }
        return sessionContext.getSelectedServer();
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return s3Properties.version();
    }

    @ModelAttribute("appReleaseDate")
    public String appReleaseDate() {
        return s3Properties.releaseDate();
    }
}