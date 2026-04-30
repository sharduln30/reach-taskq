package com.merakilabs.taskq.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = "com.merakilabs.taskq")
@ConfigurationPropertiesScan(basePackages = "com.merakilabs.taskq")
@EnableScheduling
public class TaskqApplication {

    public static void main(final String[] args) {
        SpringApplication.run(TaskqApplication.class, args);
    }
}
