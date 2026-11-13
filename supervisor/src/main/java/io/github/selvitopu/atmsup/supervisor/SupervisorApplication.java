package io.github.selvitopu.atmsup.supervisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The ATM Supervisor: a Spring Modulith modular monolith. */
@SpringBootApplication
public class SupervisorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupervisorApplication.class, args);
    }
}
