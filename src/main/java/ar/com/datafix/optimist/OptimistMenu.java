package ar.com.datafix.optimist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * V21 - Menú lateral definido en resources/menu.yaml, con carpetas anidadas a cualquier nivel
 * (Stock y Producción > Mantenimiento de Tablas > Artículos > Artículos).
 * Un ítem apunta a una TRN del motor (trn: Articulos) o a una pantalla programada (ruta: paises).
 * Si el archivo falta o tiene errores, se muestran las categorías básicas y el error va al log.
 */
public final class OptimistMenu {
    private OptimistMenu() {}
    private static final Logger LOG = LoggerFactory.getLogger(OptimistMenu.class);

    /** ruta null (V49n): opción del menú de GeneXus que todavía no está en OPTIMIST ERP (se muestra en gris). */
    public record Item(String titulo, String ruta) {
        public boolean pendiente() { return ruta == null; }
    }
    public static final String AVISO_PENDIENTE = "todavía no está en OPTIMIST ERP";
    /** Carpeta del menú. hijos: Item o Carpeta, en el mismo orden que en el YAML. */
    public record Carpeta(String nombre, List<Object> hijos) {}

    private static final List<String> BASICAS = List.of("Menú General", "Stock y Producción", "Costos", "Ventas",
            "Logística", "Compras", "CRM", "Cuentas Corrientes", "Impuestos", "Caja y Bancos", "Contabilidad",
            "Estadísticas", "Servicios");

    /**
     * V48e: el menú del usuario. Si la base tiene las tablas Menu y MenuSecUser (de GeneXus) y el usuario tiene
     * opciones asignadas, se arma con la jerarquía de Menu (MenuParentId) y SOLO las opciones del usuario.
     * Se arma una vez por sesión (al iniciar sesión se leen las tablas; acá se guarda el resultado).
     * Si no hay tablas, o el usuario no tiene ninguna fila en MenuSecUser, queda el menu.yaml completo.
     */
    @SuppressWarnings("unchecked")
    public static List<Carpeta> cargar() {
        OptimistContexto ctx = OptimistContexto.actual();
        if (ctx == null || ctx.menuNodos == null || ctx.menuNodos.isEmpty()) return cargarYaml();
        if (ctx.menuPermitidos == null || ctx.menuPermitidos.isEmpty()) {
            if (ctx.menuArmado == null) {
                LOG.warn("El usuario {} no tiene opciones en MenuSecUser: se muestra el menú completo (menu.yaml)", ctx.valor("SecUserId"));
                ctx.menuArmado = Boolean.FALSE;
            }
            return cargarYaml();
        }
        synchronized (ctx) {
            if (ctx.menuArmado instanceof List<?> l) return (List<Carpeta>) l;
            List<Carpeta> m;
            try { m = deLaBase(ctx); }
            catch (RuntimeException e) {
                LOG.error("No se pudo armar el menú desde la tabla Menu: {}", e.toString());
                ctx.menuRutas = null; ctx.menuRutasPermitidas = null;
                m = cargarYaml();
            }
            ctx.menuArmado = m;
            return m;
        }
    }

    /**
     * V48e: ¿el usuario puede entrar a esta ruta ("trn/ped", "wbp/prsaltawbp")? Solo se controla lo que está en el
     * menú de la base: lo que no figura en el menú (o si el menú sale del menu.yaml) se deja pasar.
     */
    public static boolean permitida(String ruta) {
        OptimistContexto ctx = OptimistContexto.actual();
        if (ctx == null || ruta == null) return true;
        if (ctx.menuArmado == null) cargar();
        Set<String> todas = ctx.menuRutas, mias = ctx.menuRutasPermitidas;
        if (todas == null || mias == null) return true;
        String r = ruta.replaceAll("^/+", "").toLowerCase(Locale.ROOT);
        return !todas.contains(r) || mias.contains(r);
    }

    /** Un nodo de la tabla Menu. */
    private record Nodo(String id, String titulo, String url, String padre) {}

    private static List<Carpeta> deLaBase(OptimistContexto ctx) {
        // rutas del menu.yaml por título (para las opciones cuyo MenuURL no se puede traducir a una TRN o panel)
        Map<String, List<String[]>> porTitulo = new HashMap<>();   // título normalizado -> {camino normalizado, ruta}
        for (Carpeta c : cargarYaml()) indexar(c, normal(c.nombre()), porTitulo);
        Set<String> trns = new HashSet<>(OptimistTrnCatalogo.cargar().trns().keySet());
        Set<String> wbps = new HashSet<>();
        try {
            for (org.springframework.core.io.Resource r : OptimistRecursos.listar("wbp", ".yaml")) {
                String f = r.getFilename();
                if (f != null && f.toLowerCase(Locale.ROOT).endsWith(".yaml")) wbps.add(f.substring(0, f.length() - 5).toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) { LOG.warn("menú: no se pudieron listar los wbp: {}", e.getMessage()); }
        Set<String> cnss = OptimistConsultaView.nombres();   // V52: consultas/<Nombre>.yaml (cns/<nombre>)

        Map<String, Nodo> nodos = new LinkedHashMap<>();
        for (String[] n : ctx.menuNodos) nodos.put(n[0], new Nodo(n[0], n[1], n[2], n[3]));
        Map<String, List<Nodo>> hijos = new LinkedHashMap<>();
        List<Nodo> raices = new ArrayList<>();
        for (Nodo n : nodos.values()) {
            if (n.padre().isEmpty() || n.padre().equals(n.id()) || !nodos.containsKey(n.padre())) raices.add(n);
            else hijos.computeIfAbsent(n.padre(), k -> new ArrayList<>()).add(n);
        }
        Set<String> rutas = new HashSet<>(), permitidas = new HashSet<>(), sinRuta = new TreeSet<>();
        Armado a = new Armado(hijos, ctx.menuPermitidos, trns, wbps, cnss, porTitulo, rutas, permitidas, sinRuta, new HashSet<>(), ocultas());
        List<Object> arriba = new ArrayList<>();
        for (Nodo r : raices) { Object o = a.armar(r, ""); if (o != null) arriba.add(o); }
        // una sola raíz ("OPTIMIST ERP"): sus hijos son las categorías de la barra
        while (arriba.size() == 1 && arriba.get(0) instanceof Carpeta unica && unica.hijos().stream().anyMatch(h -> h instanceof Carpeta))
            arriba = new ArrayList<>(unica.hijos());
        List<Carpeta> cats = new ArrayList<>();
        List<Object> sueltos = new ArrayList<>();
        for (Object o : arriba) {
            if (o instanceof Carpeta c) cats.add(c); else sueltos.add(o);
        }
        if (!sueltos.isEmpty()) {
            // V51b: los ítems sueltos van al "Menú General" que ya exista, aunque no sea la primera carpeta (en IEP la primera
            // era Favoritos y aparecían dos "Menú General" en la barra)
            Carpeta general = cats.stream().filter(c -> normal(c.nombre()).equals("menu general")).findFirst().orElse(null);
            if (general != null) general.hijos().addAll(0, sueltos);
            else cats.add(0, new Carpeta("Menú General", sueltos));
        }
        ctx.menuRutas = rutas;
        ctx.menuRutasPermitidas = permitidas;
        if (!sinRuta.isEmpty())
            LOG.info("Menú de la base: {} opciones del usuario todavía no existen en el motor: {}", sinRuta.size(), sinRuta);
        return cats;
    }

    private record Armado(Map<String, List<Nodo>> hijos, Set<String> permitidos, Set<String> trns, Set<String> wbps, Set<String> cnss,
                          Map<String, List<String[]>> porTitulo, Set<String> rutas, Set<String> permitidas,
                          Set<String> sinRuta, Set<String> vistos, Set<String> ocultas) {
        Object armar(Nodo n, String camino) {
            if (!vistos.add(n.id())) return null;   // ciclo en MenuParentId
            List<Nodo> hs = hijos.get(n.id());
            String titulo = n.titulo().isEmpty() ? n.id() : n.titulo();
            // V59d: lo que menu.yaml manda ocultar (por título o por programa de GeneXus) no aparece, con todo lo que tenga adentro
            if (ocultas.contains(normal(titulo)) || (!programa(n.url()).isEmpty() && ocultas.contains(programa(n.url())))) return null;
            if (hs == null || hs.isEmpty()) {
                String ruta = ruta(n, camino);
                boolean mia = permitidos.contains(n.id());
                // V49n: una opción del usuario que todavía no tiene pantalla en OPTIMIST se muestra en gris (antes no aparecía,
                // y un menú con todas sus opciones pendientes, como Estadísticas o Servicios, desaparecía entero)
                if (ruta == null) {
                    if (!mia) return null;
                    if (!n.url().isEmpty()) sinRuta.add(titulo + " (" + n.url() + ")");
                    return new Item(titulo, null);
                }
                rutas.add(ruta);
                if (!mia) return null;
                permitidas.add(ruta);
                return new Item(titulo, ruta);
            }
            String sub = camino.isEmpty() ? normal(titulo) : camino + "/" + normal(titulo);
            List<Object> lista = new ArrayList<>();
            for (Nodo h : hs) { Object o = armar(h, sub); if (o != null) lista.add(o); }
            return lista.isEmpty() ? null : new Carpeta(titulo, lista);
        }

        /** MenuURL -> ruta del motor: "wwpais.aspx" -> trn/pais, "prsaltawbp" -> wbp/prsaltawbp. Si no, por título en menu.yaml. */
        String ruta(Nodo n, String camino) {
            String p = programa(n.url());
            if (!p.isEmpty()) {
                if (p.startsWith("ww") && trns.contains(p.substring(2))) return "trn/" + p.substring(2);
                if (trns.contains(p)) return "trn/" + p;
                if (wbps.contains(p)) return "wbp/" + p;
                if (cnss.contains(p)) return "cns/" + p;   // V52
            }
            List<String[]> cands = porTitulo.get(normal(n.titulo()));
            if (cands == null || cands.isEmpty()) return null;
            for (String[] c : cands) if (c[0].equals(camino)) return c[1];
            for (String[] c : cands) if (c[0].endsWith(camino.substring(camino.lastIndexOf('/') + 1))) return c[1];
            return cands.get(0)[1];
        }
    }

    /** Nombre del programa de un MenuURL: sin parámetros, carpetas, extensión ni paquete, en minúscula. */
    static String programa(String url) {
        if (url == null) return "";
        String u = url.trim();
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        q = u.indexOf('#'); if (q >= 0) u = u.substring(0, q);
        u = u.substring(u.lastIndexOf('/') + 1);
        u = u.replaceAll("(?i)\\.(aspx|jsp|html?|do|php)$", "");
        u = u.substring(u.lastIndexOf('.') + 1);
        u = u.toLowerCase(Locale.ROOT);
        return u.matches("[a-z0-9_]+") ? u : "";
    }

    private static void indexar(Carpeta c, String camino, Map<String, List<String[]>> porTitulo) {
        for (Object h : c.hijos()) {
            if (h instanceof Item it && !it.pendiente()) porTitulo.computeIfAbsent(normal(it.titulo()), k -> new ArrayList<>()).add(new String[]{camino, it.ruta()});
            else if (h instanceof Carpeta s) indexar(s, camino + "/" + normal(s.nombre()), porTitulo);
        }
    }

    /** Título para comparar: minúscula, sin acentos ni espacios de más. */
    private static String normal(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * V59d: la lista "Ocultar:" de menu.yaml: títulos de opciones o carpetas, o programas de GeneXus (el MenuURL sin
     * extensión), que el menú nunca muestra, aunque estén en las tablas Menu / MenuSecUser del usuario. Comparación sin
     * mayúsculas ni acentos.
     */
    @SuppressWarnings("unchecked")
    static Set<String> ocultas() {
        try (InputStream in = OptimistRecursos.abrir("menu.yaml")) {
            if (in == null) return Set.of();
            Map<String, Object> y = new Yaml().load(in);
            Set<String> r = new HashSet<>();
            if (y != null)
                for (Map.Entry<String, Object> e : y.entrySet())
                    if (normal(e.getKey()).equals("ocultar") && e.getValue() instanceof List<?> l)
                        for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) r.add(normal(String.valueOf(o)));
            return r;
        } catch (Exception e) {
            LOG.warn("menu.yaml, Ocultar: {}", e.getMessage());
            return Set.of();
        }
    }

    /** El menú de resources/menu.yaml (el de siempre). */
    public static List<Carpeta> cargarYaml() {
        try (InputStream in = OptimistRecursos.abrir("menu.yaml")) {
            if (in == null) throw new IllegalStateException("no se encontró menu.yaml");
            Map<String, Object> y = new Yaml().load(in);
            if (y == null) throw new IllegalStateException("menu.yaml vacío");
            List<Carpeta> cats = new ArrayList<>();
            for (Map.Entry<String, Object> e : y.entrySet()) {
                if (normal(e.getKey()).equals("ocultar")) continue;   // V59d: no es una categoría (ver ocultas())
                cats.add(carpeta(e.getKey(), e.getValue(), e.getKey()));
            }
            return cats;
        } catch (Exception e) {
            LOG.error("No se pudo leer menu.yaml: {}", e.getMessage());
            return BASICAS.stream().map(n -> new Carpeta(n, List.of())).toList();
        }
    }

    /**
     * El contenido de una carpeta puede ser:
     *  - una lista: cada elemento es un ítem {titulo, trn|ruta} o una subcarpeta {Nombre: contenido}
     *  - un bloque: cada clave es una subcarpeta
     */
    @SuppressWarnings("unchecked")
    private static Carpeta carpeta(String nombre, Object contenido, String donde) {
        List<Object> hijos = new ArrayList<>();
        if (contenido instanceof Map<?, ?> m) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet())
                hijos.add(carpeta(e.getKey(), e.getValue(), donde + " / " + e.getKey()));
        } else if (contenido instanceof List<?> lista) {
            for (Object o : lista) {
                try {
                    if (!(o instanceof Map<?, ?> om)) throw new IllegalArgumentException("elemento inválido: " + o);
                    Map<String, Object> mm = (Map<String, Object>) om;
                    if (mm.containsKey("titulo")) hijos.add(item(mm));
                    else if (mm.size() == 1) {
                        Map.Entry<String, Object> e = mm.entrySet().iterator().next();
                        hijos.add(carpeta(e.getKey(), e.getValue(), donde + " / " + e.getKey()));
                    } else throw new IllegalArgumentException("elemento sin titulo: " + mm);
                } catch (Exception ex) {
                    LOG.error("menu.yaml, {}: {}", donde, ex.getMessage());
                }
            }
        }
        return new Carpeta(nombre, hijos);
    }

    private static Item item(Map<String, Object> m) {
        Object titulo = m.get("titulo");
        if (titulo == null || String.valueOf(titulo).isBlank()) throw new IllegalArgumentException("ítem sin titulo: " + m);
        Object trn = m.get("trn"), ruta = m.get("ruta");
        // V49n: {titulo: Balance, pendiente: balance} = opción de GeneXus que todavía no está (se ve en gris)
        if (trn == null && ruta == null && m.containsKey("pendiente")) return new Item(String.valueOf(titulo).trim(), null);
        String destino;
        if (trn != null) destino = "trn/" + String.valueOf(trn).trim().toLowerCase(Locale.ROOT);
        else if (ruta != null) destino = String.valueOf(ruta).trim();
        else throw new IllegalArgumentException("ítem '" + titulo + "' sin trn, ruta ni pendiente");
        if (!destino.matches("[a-z0-9_/-]*")) throw new IllegalArgumentException("ruta inválida: " + destino);
        return new Item(String.valueOf(titulo).trim(), destino);
    }
}
