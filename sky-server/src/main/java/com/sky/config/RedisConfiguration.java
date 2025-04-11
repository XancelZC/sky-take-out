package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
public class RedisConfiguration {

    @Bean
    public RedisTemplate redisTemplate(RedisConnectionFactory redisConnectionFactory){
        log.info("开始设置redis模版对象");

        //创建基础模版对象
        RedisTemplate redisTemplate = new RedisTemplate();

        //添加连接工厂 其中包含密码，端口等基础信息
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        //将redis序列化 序列化后Java才能使用 默认的会乱码
        redisTemplate.setKeySerializer(new StringRedisSerializer());

        return redisTemplate;
    }

}
