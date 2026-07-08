package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.infrastructure.config.server.ServerConfig;
import io.github.uwegeercken.bucketeer.infrastructure.config.server.ServerConfigService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/config")
public class ConfigController {

    private final ServerConfigService configService;

    public ConfigController(ServerConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("servers", configService.findAll());
        return "config/list";
    }

    @GetMapping("/server/new")
    public String newForm(Model model) {
        model.addAttribute("server", new ServerConfig("", "", "us-east-1", "", ""));
        model.addAttribute("isNew", true);
        return "config/form";
    }

    @GetMapping("/server/{name}/edit")
    public String editForm(@PathVariable String name, Model model) {
        ServerConfig server = configService.findAll().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Server not found: " + name));
        model.addAttribute("server", server);
        model.addAttribute("isNew", false);
        return "config/form";
    }

    @PostMapping("/server/save")
    public String save(
            @RequestParam String name,
            @RequestParam String endpoint,
            @RequestParam String region,
            @RequestParam String accessKey,
            @RequestParam String secretKey,
            @RequestParam(defaultValue = "false") boolean test,
            RedirectAttributes redirect) {

        ServerConfig server = new ServerConfig(name, endpoint, region, accessKey, secretKey);

        if (test) {
            String error = configService.saveAndTest(server);
            if (error != null) {
                redirect.addFlashAttribute("testError", error);
                redirect.addFlashAttribute("testName", name);
            } else {
                redirect.addFlashAttribute("testSuccess", name);
            }
        } else {
            configService.save(server);
            redirect.addFlashAttribute("saved", name);
        }

        return "redirect:/config";
    }

    @PostMapping("/server/{name}/delete")
    public String delete(@PathVariable String name, RedirectAttributes redirect) {
        configService.delete(name);
        redirect.addFlashAttribute("deleted", name);
        return "redirect:/config";
    }
}
