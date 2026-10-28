package ar.com.datafix.optimist;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * V27 - De dónde se leen los YAML de configuración (TRN, menú, dominios).
 * En DESARROLLO (la aplicación corre dentro de la carpeta del proyecto) se leen directo de
 * src/main/resources: se graba el YAML y se reabre la pantalla, sin compilar.
 * En PRODUCCIÓN (WAR en Tomcat) esa carpeta no existe y se leen de adentro del WAR.
 * No toca la configuración de Maven (addResources rompía los estilos de Vaadin).
 */
public final class OptimistRecursos {
    private OptimistRecursos() {}

    private static final Path FUENTES = buscarFuentes();

    /**
     * V49k: la carpeta src/main/resources del proyecto. Antes se buscaba solo en la carpeta desde donde se arrancó (user.dir):
     * si VS Code arrancaba desde la carpeta de arriba del proyecto, no la encontraba, leía los YAML compilados (target/classes)
     * y los cambios no se veían hasta reiniciar. Ahora también se busca a partir de donde están las clases (target/classes)
     * y en optimist-erp/ adentro de user.dir. Se puede fijar con -Doptimist.recursos=C:/ruta/src/main/resources.
     */
    private static Path buscarFuentes() {
        List<Path> candidatas = new ArrayList<>();
        String fija = System.getProperty("optimist.recursos");
        if (fija != null && !fija.isBlank()) candidatas.add(Path.of(fija.trim()));
        Path ud = Path.of(System.getProperty("user.dir"));
        candidatas.add(ud.resolve("src").resolve("main").resolve("resources"));
        try {
            Path clases = Path.of(OptimistRecursos.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // .../proyecto/target/classes -> .../proyecto/src/main/resources
            if (clases.getFileName() != null && clases.getFileName().toString().equals("classes") && clases.getParent() != null
                    && clases.getParent().getParent() != null)
                candidatas.add(clases.getParent().getParent().resolve("src").resolve("main").resolve("resources"));
        } catch (Exception ignorado) { }
        candidatas.add(ud.resolve("optimist-erp").resolve("src").resolve("main").resolve("resources"));
        for (Path c : candidatas) if (Files.isDirectory(c.resolve("trn"))) return c.toAbsolutePath();
        return candidatas.get(fija != null && !fija.isBlank() ? 1 : 0);
    }

    /** V49k: la carpeta de los YAML en desarrollo (para mostrarla al arrancar). */
    public static Path fuentes() { return FUENTES; }

    /** true: se leen de src/main/resources (desarrollo). */
    public static boolean desarrollo() { return Files.isDirectory(FUENTES); }

    /** Abre un recurso ("menu.yaml", "trn/Articulos.yaml"). null si no existe. */
    public static InputStream abrir(String recurso) throws IOException {
        if (desarrollo()) {
            Path p = FUENTES.resolve(recurso);
            if (Files.isRegularFile(p)) return Files.newInputStream(p);
        }
        return OptimistRecursos.class.getResourceAsStream("/" + recurso);
    }

    /** Los trn/*.yaml. */
    public static Resource[] trns() throws IOException { return listar("trn", ".yaml"); }

    /** V34: los archivos de una carpeta de recursos con esa extensión ("proc", ".proc"). */
    public static Resource[] listar(String carpeta, String extension) throws IOException {
        if (desarrollo()) {
            Path dir = FUENTES.resolve(carpeta);
            if (Files.isDirectory(dir)) {
                List<Resource> r = new ArrayList<>();
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(extension)).sorted()
                     .forEach(p -> r.add(new FileSystemResource(p)));
                }
                return r.toArray(new Resource[0]);
            }
        }
        return new PathMatchingResourcePatternResolver().getResources("classpath*:" + carpeta + "/*" + extension);
    }
}
