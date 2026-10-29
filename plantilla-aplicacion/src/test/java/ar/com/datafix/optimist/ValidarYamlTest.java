package ar.com.datafix.optimist;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * V29 - Valida TODOS los YAML antes de generar el WAR (corre en `mvn package`, no en `mvn spring-boot:run`).
 * Usa las mismas reglas del motor. Si algo está mal, el WAR NO se genera y se listan los errores.
 * En un apuro: mvn clean package -Pproduction -DskipTests
 */
class ValidarYamlTest {

    private static final Path RECURSOS = Path.of("src", "main", "resources");

    @Test
    @SuppressWarnings("unchecked")
    void losYamlSonValidos() throws Exception {
        List<String> errores = new ArrayList<>();

        // dominios globales
        Map<String, Object> dominios = Map.of();
        Path dom = RECURSOS.resolve("dominios.yaml");
        if (Files.exists(dom)) {
            try (InputStream in = Files.newInputStream(dom)) {
                Object o = new Yaml().load(in);
                if (o instanceof Map<?, ?> m) dominios = (Map<String, Object>) m;
                else if (o != null) errores.add("dominios.yaml: tiene que ser un bloque 'Nombre: tipo'");
            } catch (Exception e) { errores.add("dominios.yaml: " + e.getMessage()); }
        }

        // cada TRN
        Map<String, String> rutas = new TreeMap<>();   // ruta -> archivo
        Path dir = RECURSOS.resolve("trn");
        List<Path> archivos = new ArrayList<>();
        if (Files.isDirectory(dir)) try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yaml")).sorted().forEach(archivos::add);
        }
        for (Path p : archivos) {
            String nombre = p.getFileName().toString();
            try (InputStream in = Files.newInputStream(p)) {
                Object o = new Yaml().load(in);
                if (!(o instanceof Map<?, ?> m)) { errores.add(nombre + ": archivo vacío o mal armado"); continue; }
                OptimistTrnDefinicion.Trn trn = OptimistTrnDefinicion.desde((Map<String, Object>) m, dominios);
                String previo = rutas.put(trn.ruta(), nombre);
                if (previo != null) errores.add(nombre + ": ruta repetida '" + trn.ruta() + "' (también en " + previo + ")");
            } catch (Exception e) {
                errores.add(nombre + ": " + e.getMessage());
            }
        }

        // V59d: las vistas (vistas/*.yaml, V57) también son rutas trn/<vista> a las que puede apuntar el menú
        Path dirVistas = RECURSOS.resolve("vistas");
        if (Files.isDirectory(dirVistas)) try (Stream<Path> s = Files.list(dirVistas)) {
            for (Path p : s.filter(x -> x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yaml")).sorted().toList()) {
                try (InputStream in = Files.newInputStream(p)) {
                    Object o = new Yaml().load(in);
                    Object v = o instanceof Map<?, ?> m ? m.get("vista") : null;
                    if (v == null) errores.add("vistas/" + p.getFileName() + ": falta 'vista:'");
                    else rutas.put(String.valueOf(v).trim().toLowerCase(Locale.ROOT), "vistas/" + p.getFileName());
                } catch (Exception e) { errores.add("vistas/" + p.getFileName() + ": " + e.getMessage()); }
            }
        }

        // menú
        Path menu = RECURSOS.resolve("menu.yaml");
        if (Files.exists(menu)) {
            try (InputStream in = Files.newInputStream(menu)) {
                Object o = new Yaml().load(in);
                if (!(o instanceof Map<?, ?> m)) errores.add("menu.yaml: tiene que empezar con las categorías (Nombre:)");
                else for (Map.Entry<?, ?> e : m.entrySet()) {
                    // V59d: "Ocultar:" es una lista de títulos / programas que el menú no muestra, no una categoría
                    if (String.valueOf(e.getKey()).trim().equalsIgnoreCase("ocultar")) {
                        if (!(e.getValue() instanceof List<?> l) || l.stream().anyMatch(x -> x instanceof Map<?, ?> || x instanceof List<?>))
                            errores.add("menu.yaml, Ocultar: tiene que ser una lista de títulos o programas (- Favoritos)");
                        continue;
                    }
                    carpeta(String.valueOf(e.getKey()), e.getValue(), rutas.keySet(), errores);
                }
            } catch (Exception e) { errores.add("menu.yaml: " + e.getMessage()); }
        }

        if (!errores.isEmpty()) {
            StringBuilder b = new StringBuilder("\n\nHay " + errores.size() + " error(es) en los YAML. El WAR NO se generó:\n");
            errores.forEach(x -> b.append("  - ").append(x).append('\n'));
            b.append("\nCorregilos y volvé a generar (en un apuro: -DskipTests).\n");
            fail(b.toString());
        }
    }

    private static void carpeta(String donde, Object contenido, Set<String> rutas, List<String> errores) {
        if (contenido == null) return;
        if (contenido instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) carpeta(donde + " / " + e.getKey(), e.getValue(), rutas, errores);
        } else if (contenido instanceof List<?> lista) {
            for (Object o : lista) {
                if (!(o instanceof Map<?, ?> mm)) { errores.add("menu.yaml, " + donde + ": elemento inválido: " + o); continue; }
                if (mm.containsKey("titulo")) {
                    Object titulo = mm.get("titulo"), trn = mm.get("trn"), ruta = mm.get("ruta");
                    if (titulo == null || String.valueOf(titulo).isBlank()) errores.add("menu.yaml, " + donde + ": ítem sin título: " + mm);
                    if (trn != null) {
                        String r = String.valueOf(trn).trim().toLowerCase(Locale.ROOT);
                        if (!rutas.contains(r)) errores.add("menu.yaml, " + donde + ": '" + titulo + "' apunta a la TRN " + trn + ", que no existe (o tiene errores)");
                    } else if (ruta == null && mm.get("pendiente") == null)   // V49v: {titulo, pendiente: prog} = todavía no migrado (se ve en gris)
                        errores.add("menu.yaml, " + donde + ": ítem '" + titulo + "' sin trn, ruta ni pendiente");
                } else if (mm.size() == 1) {
                    Map.Entry<?, ?> e = mm.entrySet().iterator().next();
                    carpeta(donde + " / " + e.getKey(), e.getValue(), rutas, errores);
                } else errores.add("menu.yaml, " + donde + ": elemento sin título: " + mm);
            }
        } else errores.add("menu.yaml, " + donde + ": contenido inválido: " + contenido);
    }
}
