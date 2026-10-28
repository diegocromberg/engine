package ar.com.datafix.optimist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/** V21: arranca igual con java -jar o desplegado como WAR en Tomcat. */
@SpringBootApplication
public class OptimistApplication extends SpringBootServletInitializer {
    public static void main(String[] args) {
        // V49k: de dónde lee los YAML (trn, wbp, proc...): si no es la carpeta que se edita, los cambios no se ven
        System.out.println("[OPTIMIST] YAML de configuración: " + (OptimistRecursos.desarrollo()
                ? OptimistRecursos.fuentes() + " (se ven los cambios al reabrir la pantalla)"
                : "adentro del WAR / classpath (los cambios en src/main/resources NO se ven hasta volver a armar el WAR)"));
        SpringApplication.run(OptimistApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(OptimistApplication.class);
    }
}
