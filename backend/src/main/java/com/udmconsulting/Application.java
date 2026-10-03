package com.udmconsulting;

import com.udmconsulting.platform.runtime.RuntimeRoleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(Application.class, args);
        RuntimeRoleProperties properties = context.getBean(RuntimeRoleProperties.class);
        if (properties.role().isOneShot()) {
            int exitCode = SpringApplication.exit(context);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        }
    }
}
