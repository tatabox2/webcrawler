package org.smileyface.webcrawler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
class HelloController {

    @GetMapping("/")
    public String index() {
        return "Hello World";
    }
}
