package com.course.vsearch.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(VSearchProperties props) {
        VSearchProperties.Redis cfg = props.getRedis();
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + cfg.getHost() + ":" + cfg.getPort())
                .setDatabase(cfg.getDatabase())
                .setPassword(StringUtils.hasText(cfg.getPassword()) ? cfg.getPassword() : null);
        return Redisson.create(config);
    }
}
