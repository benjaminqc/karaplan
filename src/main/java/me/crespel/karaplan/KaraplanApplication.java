package me.crespel.karaplan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class KaraplanApplication {

	public static void main(String[] args) {
		SpringApplication.run(KaraplanApplication.class, args);
	}

}
