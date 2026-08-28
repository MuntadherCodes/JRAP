package dev.hmcodes.jrap.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/** JRAP modular-monolith assembly (SRS §4). */
@SpringBootApplication(scanBasePackages = "dev.hmcodes.jrap")
@EntityScan(basePackages = "dev.hmcodes.jrap")
@EnableJpaRepositories(basePackages = "dev.hmcodes.jrap")
@EnableScheduling
public class JrapApplication {

    public static void main(String[] args) {
        SpringApplication.run(JrapApplication.class, args);
    }
}
