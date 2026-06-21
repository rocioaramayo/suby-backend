package com.tpo.suby;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SubyApplication {

	public static void main(String[] args) {
		SpringApplication.run(SubyApplication.class, args);
	}

	
}
