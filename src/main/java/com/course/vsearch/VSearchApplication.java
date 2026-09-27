package com.course.vsearch;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.course.vsearch.config")
@MapperScan("com.course.vsearch.mapper")
public class VSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(VSearchApplication.class, args);
    }
}
