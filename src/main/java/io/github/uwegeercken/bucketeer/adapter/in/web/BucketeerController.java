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

    @GetMapping("/")
    public String index(
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) Boolean search,
            Model model) {

        String currentServer = sessionContext.getSelectedServer();

        model.addAttribute("availableFunctions", bucketeerUseCase.availableFunctions());
        model.addAttribute("bucket", bucket);
        model.addAttribute("prefix", prefix);
        model.addAttribute("key", key);

        // build tooltip text listing all functions with syntax
        String tooltipText = "<strong>Available functions:</strong><br/>" +
                bucketeerUseCase.availableFunctions().stream()
                        .map(f -> "<code>{" + f + "(ref, ...)}</code>")
                        .collect(java.util.stream.Collectors.joining("<br/>"));
        model.addAttribute("tooltipText", tooltipText);

        if (Boolean.TRUE.equals(search) && currentServer != null && StringUtils.hasText(bucket)) {
            String resolvedPrefix = bucketeerUseCase.resolveTemplate(prefix, key);
            model.addAttribute("resolvedPrefix", resolvedPrefix);

            // ensure prefix ends with slash before appending key
            String normalizedPrefix = StringUtils.hasText(resolvedPrefix) && !resolvedPrefix.endsWith("/")
                    ? resolvedPrefix + "/"
                    : resolvedPrefix;

            // determine the effective S3 prefix and optional key filter
            String s3Prefix = normalizedPrefix;
            String keyFilter;

            if (StringUtils.hasText(key)) {
                if (key.endsWith("*")) {
                    keyFilter = null;
                    // wildcard: use key prefix as additional S3 prefix
                    s3Prefix = normalizedPrefix + key.substring(0, key.length() - 1);
                } else {
                    // exact key: append full key to prefix for precise listing
                    s3Prefix = normalizedPrefix + key;
                    keyFilter = key;
                }
            }
            else
            {
                keyFilter = null;
            }

            ObjectListing listing = bucketeerUseCase.listObjects(currentServer, bucket, s3Prefix, token);

            // filter out folder entries (keys ending with '/') and apply exact key filter
            listing = listing.withObjects(
                    listing.objects().stream()
                            .filter(obj -> !obj.key().endsWith("/"))
                            .filter(obj -> keyFilter == null || obj.key().equals(normalizedPrefix + keyFilter))
                            .toList()
            );

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
            return "";
        }
    }

    /**
     * Validates a prefix template and returns JSON: { "valid": true/false, "error": "..." }
     */
    @GetMapping(value = "/api/validate-prefix", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public java.util.Map<String, Object> validatePrefix(
            @RequestParam(required = false) String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return java.util.Map.of("valid", true, "error", "");
        }
        List<String> unknown = bucketeerUseCase.validateTemplate(prefix);
        if (unknown.isEmpty()) {
            return java.util.Map.of("valid", true, "error", "");
        }
        return java.util.Map.of("valid", false, "error",
                "Unknown function(s): " + String.join(", ", unknown));
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