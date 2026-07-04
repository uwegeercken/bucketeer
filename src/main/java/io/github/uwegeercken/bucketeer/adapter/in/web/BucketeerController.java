package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Controller
public class BucketeerController {

    private final BucketeerUseCase bucketeerUseCase;
    private final S3StoragePort s3StoragePort;
    private final SessionContext sessionContext;

    public BucketeerController(BucketeerUseCase bucketeerUseCase,
                               S3StoragePort s3StoragePort,
                               SessionContext sessionContext) {
        this.bucketeerUseCase = bucketeerUseCase;
        this.s3StoragePort = s3StoragePort;
        this.sessionContext = sessionContext;
    }

    @GetMapping("/")
    public String index(
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) Boolean search,
            Model model) {

        List<String> serverNames = bucketeerUseCase.serverNames();
        if (sessionContext.getSelectedServer() == null && !serverNames.isEmpty()) {
            sessionContext.setSelectedServer(serverNames.getFirst());
        }

        String currentServer = sessionContext.getSelectedServer();
        model.addAttribute("serverNames", serverNames);
        model.addAttribute("selectedServer", currentServer);
        model.addAttribute("availablePlaceholders", bucketeerUseCase.availableFunctions());
        model.addAttribute("bucket", bucket);
        model.addAttribute("prefix", prefix);
        model.addAttribute("key", key);

        if (Boolean.TRUE.equals(search) && currentServer != null && StringUtils.hasText(bucket)) {
            String resolvedPrefix = bucketeerUseCase.resolveTemplate(prefix, key);
            model.addAttribute("resolvedPrefix", resolvedPrefix);
            ObjectListing listing = bucketeerUseCase.listObjects(currentServer, bucket, resolvedPrefix, token);
            model.addAttribute("listing", listing);
        }

        return "index";
    }

    @PostMapping("/session/server")
    public String selectServer(@RequestParam String serverName) {
        sessionContext.setSelectedServer(serverName);
        return "redirect:/";
    }

    @GetMapping("/api/resolve-prefix")
    @org.springframework.web.bind.annotation.ResponseBody
    public String resolvePrefix(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key) {
        return bucketeerUseCase.resolveTemplate(prefix, key);
    }

    @GetMapping("/download")
    public void download(
            @RequestParam String bucket,
            @RequestParam String key,
            HttpServletResponse response) throws IOException {

        String currentServer = sessionContext.getSelectedServer();
        if (currentServer == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No server selected");
            return;
        }
        String filename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try (InputStream in = s3StoragePort.downloadObject(currentServer, bucket, key)) {
            in.transferTo(response.getOutputStream());
        }
    }
}
