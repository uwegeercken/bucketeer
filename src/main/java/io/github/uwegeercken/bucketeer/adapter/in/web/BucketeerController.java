package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

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

    private void populateCommonModel(Model model) {
        List<String> serverNames = bucketeerUseCase.serverNames();
        if (sessionContext.getSelectedServer() == null && !serverNames.isEmpty()) {
            sessionContext.setSelectedServer(serverNames.getFirst());
        }
        model.addAttribute("serverNames", serverNames);
        model.addAttribute("selectedServer", sessionContext.getSelectedServer());
        model.addAttribute("availableFunctions", bucketeerUseCase.availableFunctions());
    }

    @GetMapping("/")
    public String index(
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) Boolean search,
            Model model) {

        populateCommonModel(model);
        String currentServer = sessionContext.getSelectedServer();

        model.addAttribute("bucket", bucket);
        model.addAttribute("prefix", prefix);
        model.addAttribute("key", key);

        if (Boolean.TRUE.equals(search) && currentServer != null && StringUtils.hasText(bucket)) {
            String resolvedPrefix = bucketeerUseCase.resolveTemplate(prefix, key);
            model.addAttribute("resolvedPrefix", resolvedPrefix);

            // determine the effective S3 prefix and optional key filter
            String s3Prefix = resolvedPrefix;
            String keyFilter = null;

            if (StringUtils.hasText(key)) {
                if (key.endsWith("*")) {
                    // wildcard: use key prefix as additional S3 prefix
                    s3Prefix = resolvedPrefix + key.substring(0, key.length() - 1);
                } else {
                    // exact key: append full key to prefix for precise listing
                    s3Prefix = resolvedPrefix + key;
                    keyFilter = key;
                }
            }

            ObjectListing listing = bucketeerUseCase.listObjects(currentServer, bucket, s3Prefix, token);

            // for exact key: filter client-side to only matching object
            if (keyFilter != null) {
                String finalKeyFilter = resolvedPrefix + keyFilter;
                listing = listing.withObjects(
                        listing.objects().stream()
                                .filter(obj -> obj.key().equals(finalKeyFilter))
                                .toList()
                );
            }

            model.addAttribute("listing", listing);
        }

        return "index";
    }

    @PostMapping("/session/server")
    public String selectServer(@RequestParam String serverName) {
        sessionContext.setSelectedServer(serverName);
        return "redirect:/";
    }

    /**
     * Returns the resolved prefix as plain text.
     * Returns empty string on parse error so the UI can display it gracefully.
     */
    @GetMapping(value = "/api/resolve-prefix", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String resolvePrefix(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key) {
        if (!StringUtils.hasText(prefix)) return "";
        try {
            return bucketeerUseCase.resolveTemplate(prefix, key);
        } catch (Exception e) {
            return "";  // incomplete placeholder – return empty, not an error page
        }
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
