package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.template.function.TemplateFunction;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Comparator;
import java.util.List;

@Controller
public class HelpController {

    private final List<TemplateFunction> functions;

    public HelpController(List<TemplateFunction> functions) {
        this.functions = functions.stream()
                .sorted(Comparator.comparing(TemplateFunction::name))
                .toList();
    }

    @GetMapping("/help")
    public String help(Model model) {
        model.addAttribute("functions", functions);
        return "help";
    }
}