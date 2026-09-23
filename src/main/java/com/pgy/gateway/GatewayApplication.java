package com.pgy.gateway;

import com.pgy.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * @EnableConfigurationProperties 让 GatewayProperties 生效，对应 application.yml 里的 gateway.* 配置
 * @EnableAsync                   开启异步执行（任务编排用线程池跑，不阻塞 HTTP 请求线程）
 * @EnableScheduling              开启定时任务（用于重新捡起中断的任务）
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
@EnableAsync
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
