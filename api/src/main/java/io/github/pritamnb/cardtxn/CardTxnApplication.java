package io.github.pritamnb.cardtxn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CardTxnApplication {
    public static void main(String[] args) {
        SpringApplication.run(CardTxnApplication.class, args);
    }
}
