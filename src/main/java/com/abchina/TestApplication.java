package com.abchina;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;


@RestController
@SpringBootApplication
public class TestApplication {

    @RequestMapping("/")
    public Object hallo(){
        return "测试";
    }


    /**
     * Start
     * @param args vm参数
     * @throws IOException io异常
     */
    public static void main(String[] args) throws IOException {
        ConfigurableApplicationContext context = SpringApplication.run(TestApplication.class, args);

        System.out.println("启动结束..");
    }



}
