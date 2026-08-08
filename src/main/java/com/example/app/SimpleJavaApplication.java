package com.example.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class SimpleJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimpleJavaApplication.class, args);
    }

    @GetMapping("/")
    public String home() {
        return "Hey this is new version of the app";
    }

    @GetMapping("/health")
    public String health() {
        return "UP";
    }
}