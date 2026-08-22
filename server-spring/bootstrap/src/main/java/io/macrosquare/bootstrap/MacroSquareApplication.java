package io.macrosquare.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.macrosquare")
@EnableScheduling
public class MacroSquareApplication {

    public static void main(String[] args) {
        SpringApplication.run(MacroSquareApplication.class, args);
    }
}
