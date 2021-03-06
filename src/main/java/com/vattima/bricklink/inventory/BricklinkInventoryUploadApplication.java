package com.vattima.bricklink.inventory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;


@Slf4j
@SpringBootApplication(scanBasePackages = {"net.bricklink", "com.bricklink", "com.vattima"})
@EnableConfigurationProperties
public class BricklinkInventoryUploadApplication {

    public static void main(String[] args) {
        SpringApplication.run(BricklinkInventoryUploadApplication.class, args);
    }

}
