package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Trn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * V21 - Catálogo de TRN: lee todos los archivos trn/*.yaml del classpath y dominios.yaml.
 * No se cachea: se relee en cada uso (son archivos chicos) para que un cambio de YAML
 * se vea apenas se recargan los recursos, sin tocar Java.
 */
public final class OptimistTrnCatalogo {
    private OptimistTrnCatalogo() {}
    private static final Logger LOG = LoggerFactory.getLogger(OptimistTrnCatalogo.class);

    /** trns: las que cargaron bien (por ruta). errores: archivo -> motivo. */
    public record Resultado(Map<String, Trn> trns, Map<String, String> errores) {}

    /** V28: última lectura y su "firma" (nombre + fecha de cada archivo). Si nada cambió, no se relee. */
    private record Cache(String firma, Resultado resultado, long revisado) {}
    private static volatile Cache cache;

    public static Resultado cargar() {
        Cache previo = cache;   // V36: en el WAR los YAML no cambian; en desarrollo se miran como mucho cada 2 segundos
        if (previo != null && (!OptimistRecursos.desarrollo() || System.currentTimeMillis() - previo.revisado() < OptimistProcedimientos.REVISAR_CADA_MS))
            return previo.resultado();
        Resource[] archivos;
        try {
            archivos = OptimistRecursos.trns();   // V27: en desarrollo, directo de src/main/resources
            // V57: las vistas (vistas/*.yaml) van al final, después de todas las TRN
            Resource[] vistas = OptimistRecursos.listar("vistas", ".yaml");
            if (vistas.length > 0) {
                Resource[] todos = java.util.Arrays.copyOf(archivos, archivos.length + vistas.length);
                System.arraycopy(vistas, 0, todos, archivos.length, vistas.length);
                archivos = todos;
            }
        }
        catch (Exception e) {
            LOG.error("No se pudieron listar las TRN", e);
            return new Resultado(new TreeMap<>(), new TreeMap<>(Map.of("trn/*.yaml", String.valueOf(e.getMessage()))));
        }
        String firma = firma(archivos);
        Cache c = cache;   // firma y resultado viajan juntos: nunca queda uno viejo con la firma nueva
        if (c != null && firma.equals(c.firma())) { cache = new Cache(firma, c.resultado(), System.currentTimeMillis()); return c.resultado(); }
        Resultado nuevo = leerTodo(archivos);
        cache = new Cache(firma, nuevo, System.currentTimeMillis());
        return nuevo;
    }

    /** Nombre + fecha de modificación de cada YAML y de dominios.yaml: cambia apenas se graba uno. */
    private static String firma(Resource[] archivos) {
        StringBuilder b = new StringBuilder();
        for (Resource r : archivos) b.append(r.getFilename()).append('@').append(fecha(r)).append(';');
        try {
            java.net.URL u = OptimistRecursos.desarrollo()
                    ? OptimistRecursos.fuentes().resolve("dominios.yaml").toUri().toURL()
                    : OptimistTrnCatalogo.class.getResource("/dominios.yaml");
            if (u != null) b.append("dominios@").append(u.openConnection().getLastModified());
        } catch (Exception ignorado) { b.append("dominios@?"); }
        return b.toString();
    }

    private static long fecha(Resource r) {
        try { return r.lastModified(); } catch (Exception e) { return -1; }
    }

    private static Resultado leerTodo(Resource[] archivos) {
        Map<String, Trn> trns = new TreeMap<>();
        Map<String, String> errores = new TreeMap<>();
        Map<String, Object> dominios = leer("dominios.yaml");
        Map<String, Map<String, Object>> crudas = new HashMap<>();   // V57: el YAML de cada TRN (por nombre), para las vistas
        List<Map<String, Object>> vistas = new ArrayList<>();
        List<String> nombresVistas = new ArrayList<>();
        try {
            for (Resource r : archivos) {
                String nombre = r.getFilename();
                try (InputStream in = r.getInputStream()) {
                    Map<String, Object> y = new Yaml().load(in);
                    if (y == null) throw new IllegalArgumentException("archivo vacío");
                    if (y.containsKey("vista")) { vistas.add(y); nombresVistas.add(nombre); continue; }
                    Trn trn = OptimistTrnDefinicion.desde(y, dominios);
                    if (trns.containsKey(trn.ruta())) throw new IllegalArgumentException("ruta repetida: " + trn.ruta());
                    trns.put(trn.ruta(), trn);
                    crudas.put(trn.nombre().toLowerCase(Locale.ROOT), y);
                } catch (Exception e) {
                    errores.put(nombre, e.getMessage());
                    LOG.error("TRN {}: {}", nombre, e.getMessage());
                }
            }
        } catch (Exception e) {
            errores.put("trn/*.yaml", e.getMessage());
            LOG.error("No se pudieron listar las TRN", e);
        }
        // V57: las vistas van después de las TRN (al buscar una TRN por su nombre o su tabla, se encuentra la TRN, no la vista)
        Map<String, Trn> todas = new LinkedHashMap<>(trns);
        for (int i = 0; i < vistas.size(); i++) {
            String nombre = nombresVistas.get(i);
            try {
                Trn v = vista(vistas.get(i), crudas, dominios);
                if (todas.containsKey(v.ruta())) throw new IllegalArgumentException("ruta repetida: " + v.ruta());
                todas.put(v.ruta(), v);
            } catch (Exception e) {
                errores.put(nombre, e.getMessage());
                LOG.error("Vista {}: {}", nombre, e.getMessage());
            }
        }
        return new Resultado(todas, errores);
    }

    /**
     * V57 - Vista: la lista (WorkWith) de una TRN con otra configuración, como los ExtraWW de GeneXus (ej.
     * ExtraWWPEDAutorizar: los pedidos pendientes que el usuario puede autorizar). Es la TRN de 'trn:' con lo de
     * 'workwith:' de la vista encima (cada clave reemplaza a la de la TRN: columnas, filtro, condicion, acciones,
     * botones, nuevo...), su título y su ruta (el nombre de la vista en minúscula, como la opción del menú de GeneXus).
     * El formulario es el de la TRN.
     */
    @SuppressWarnings("unchecked")
    private static Trn vista(Map<String, Object> v, Map<String, Map<String, Object>> crudas, Map<String, Object> dominios) {
        String nombre = String.valueOf(v.get("vista")).trim();
        if (!nombre.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("vista: nombre inválido '" + nombre + "'");
        for (String k : v.keySet())
            if (!List.of("vista", "trn", "titulo", "workwith").contains(k))
                throw new IllegalArgumentException("clave desconocida '" + k + "' (vista, trn, titulo, workwith)");
        Object t = v.get("trn");
        if (t == null) throw new IllegalArgumentException("falta 'trn:' (la transacción de la lista)");
        Map<String, Object> base = crudas.get(String.valueOf(t).trim().toLowerCase(Locale.ROOT));
        if (base == null) throw new IllegalArgumentException("la TRN " + t + " no existe o tiene errores");
        Map<String, Object> y = new LinkedHashMap<>(base);
        y.put("ruta", nombre.toLowerCase(Locale.ROOT));
        if (v.get("titulo") != null) y.put("titulo", String.valueOf(v.get("titulo")));
        Map<String, Object> ww = base.get("workwith") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        if (v.get("workwith") instanceof Map<?, ?> mv) ww.putAll((Map<String, Object>) mv);
        else if (v.get("workwith") != null) throw new IllegalArgumentException("workwith: debe ser un bloque de claves");
        y.put("workwith", ww);
        return OptimistTrnDefinicion.desde(y, dominios);
    }

    public static List<Trn> todas() { return new ArrayList<>(cargar().trns().values()); }

    private static Map<String, Object> leer(String recurso) {
        try (InputStream in = OptimistRecursos.abrir(recurso)) {
            if (in == null) return Map.of();
            Map<String, Object> m = new Yaml().load(in);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            LOG.error("No se pudo leer {}", recurso, e);
            return Map.of();
        }
    }
}
