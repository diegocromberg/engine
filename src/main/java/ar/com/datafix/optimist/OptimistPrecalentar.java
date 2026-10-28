package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Trn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * V50l: PRECALENTADO. Apenas arranca la instalación (Tomcat levantó el WAR), en segundo plano y sin que nadie espere,
 * deja armado todo lo que antes pagaba el primer usuario del día:
 *  1. lee todas las TRN (YAML), los dominios y el menú;
 *  2. lee y compila todos los procedimientos (.proc);
 *  3. compila las reglas y el inicio (evento Start) de cada TRN;
 *  4. lee la estructura de la base (tablas y columnas);
 *  5. simula abrir el último registro de las TRN de gestión (Artículos, Fórmulas, Costos, PRS, PED, CPBC, SC, CMP, CPBP,
 *     COBR, PAGO, Caja, Banco, Stock y Asientos): lo carga y calcula sus fórmulas
 *     (stock, totales, impuestos...), así quedan armados los planes de los For Each y la JVM "en caliente";
 *  6. (V82o) arma la lista de su WorkWith como la pide la pantalla: la primera página y la cantidad con los totales.
 * Todo con una conexión que al final hace rollback: no graba nada.
 * En optimist.cfg: application.precalentar=false lo apaga; application.precalentarTrn=PRS,PED,... cambia la lista del paso 5.
 * El resultado queda en el log de Tomcat ("[OPTIMIST] Precalentado ...").
 */
@Component
public class OptimistPrecalentar {
    private static final Logger LOG = LoggerFactory.getLogger(OptimistPrecalentar.class);
    private final OptimistDatabase db;
    /** E3: las TRN que se precalientan si el optimist.cfg no dice otra cosa: las que diga la aplicación (en OPTIMIST ERP, la parte de gestión). */
    static String trnPorDefecto() {
        List<String> r = new ArrayList<>();
        for (OptimistExtension e : OptimistExtension.todas()) r.addAll(e.precalentarTrn());
        return String.join(",", r);
    }

    public OptimistPrecalentar(OptimistDatabase db) { this.db = db; }

    @org.springframework.context.event.EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        // V53: instalación única: al arrancar no hay cliente; cada uno se precalienta con su primer ingreso (cliente())
        if (db.modoCentral()) {
            // V82s: lo que es igual para todos los clientes (TRN, procedimientos, reglas e inicios) se arma ya, al levantar el
            // WAR; la parte de la base de cada cliente, la primera vez que entra alguien suyo (cliente())
            if (!Boolean.parseBoolean(db.propiedad("application.precalentar", "true").trim())) return;
            Thread t = new Thread(() -> {
                long t0 = System.currentTimeMillis();
                List<String> hecho = new ArrayList<>();
                try { definiciones(hecho); }
                catch (Throwable e) { hecho.add("se cortó: " + e); LOG.warn("Precalentado común", e); }
                System.out.println("[OPTIMIST] Instalación única: precalentado lo común en " + (System.currentTimeMillis() - t0) + " ms: "
                        + String.join("; ", hecho) + ". Cada cliente, su base, la primera vez que entra alguien suyo");
            }, "optimist-precalentar-comun");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            return;
        }
        if (!Boolean.parseBoolean(db.propiedad("application.precalentar", "true").trim())) {
            System.out.println("[OPTIMIST] Precalentado apagado (application.precalentar=false)");
            return;
        }
        Thread t = new Thread(this::precalentar, "optimist-precalentar");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** V53: los clientes ya precalentados (por UUID) desde que arrancó la aplicación. */
    private static final java.util.Set<String> CLIENTES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** V53: precalienta la base de ese cliente en segundo plano, la primera vez (con application.precalentar de su optimist.cfg). */
    public void cliente(OptimistDatabase.Cliente c) {
        if (c == null || !CLIENTES.add(c.uuid())) return;
        if (!Boolean.parseBoolean(c.config().getProperty("application.precalentar", "true").trim())) return;
        Thread t = new Thread(() -> {
            try { OptimistDatabase.conCliente(c, () -> { precalentar(); return null; }); }
            catch (Exception e) { LOG.warn("Precalentado de {}: {}", c.razonSocial(), e.getMessage()); }
        }, "optimist-precalentar-" + c.id());
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    void precalentar() {
        long t0 = System.currentTimeMillis();
        List<String> hecho = new ArrayList<>();
        try {
            Collection<Trn> trns = definiciones(hecho);
            long t;
            if (!db.configured()) { hecho.add("sin base configurada: no se simula abrir registros"); return; }
            try (Connection c = db.openConnection()) {
                c.setAutoCommit(false);
                try {
                    t = System.currentTimeMillis();
                    int tablas = OptimistProcedimiento.esquema(c).size();
                    hecho.add("estructura de " + tablas + " tablas (" + (System.currentTimeMillis() - t) + " ms)");
                    for (String n : db.propiedad("application.precalentarTrn", trnPorDefecto()).split(",")) {
                        if (n.isBlank()) continue;
                        hecho.add(abrirUltimo(n.trim(), trns, c));
                    }
                } finally {
                    try { c.rollback(); } catch (Exception ignored) {}
                }
            }
        } catch (Throwable e) {
            hecho.add("se cortó: " + e);
            LOG.warn("Precalentado", e);
        } finally {
            OptimistDatabase.Cliente cl = OptimistDatabase.clienteActual();
            System.out.println("[OPTIMIST] Precalentado" + (cl == null ? "" : " de " + cl.razonSocial()) + " en " + (System.currentTimeMillis() - t0) + " ms: " + String.join("; ", hecho));
        }
    }

    private static String sinAcentos(String s) {
        return s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Pasos 1 a 3 (sin base): TRN, procedimientos, reglas e inicios. Devuelve las TRN. */
    static Collection<Trn> definiciones(List<String> hecho) {
        long t = System.currentTimeMillis();
        Collection<Trn> trns = OptimistTrnCatalogo.cargar().trns().values();
        hecho.add(trns.size() + " TRN (" + (System.currentTimeMillis() - t) + " ms)");

        t = System.currentTimeMillis();
        int procs = OptimistProcedimientos.cargar().procs().size();
        hecho.add(procs + " procedimientos (" + (System.currentTimeMillis() - t) + " ms)");

        t = System.currentTimeMillis();
        int reglas = 0;
        for (Trn trn : trns) {
            try { OptimistReglas.de(trn); reglas++; }
            catch (RuntimeException e) { LOG.warn("Precalentado: reglas de {}: {}", trn.nombre(), e.getMessage()); }
        }
        hecho.add(reglas + " reglas e inicios (" + (System.currentTimeMillis() - t) + " ms)");
        return trns;
    }

    /** Carga el último registro de la TRN (por su clave) y calcula sus fórmulas, como al abrirlo. */
    private String abrirUltimo(String nombre, Collection<Trn> trns, Connection c) {
        long t = System.currentTimeMillis();
        Trn trn = trns.stream().filter(x -> x.nombre().equalsIgnoreCase(nombre)).findFirst()
                .or(() -> trns.stream().filter(x -> sinAcentos(x.titulo()).equalsIgnoreCase(sinAcentos(nombre))).findFirst()).orElse(null);
        if (trn == null) return nombre + ": no existe";
        var claves = trn.cabecera().claves();
        if (claves.isEmpty()) return nombre + ": sin clave";
        try {
            // el último registro por su clave (también con clave compuesta, ej. Fórmulas: artículo + id)
            List<String> ks = claves.stream().map(OptimistTrnDefinicion.Campo::nombre).toList();
            Map<String, Object> clave = new LinkedHashMap<>();
            try (PreparedStatement p = c.prepareStatement("SELECT " + String.join(",", ks) + " FROM " + trn.cabecera().tabla()
                    + " ORDER BY " + String.join(" DESC,", ks) + " DESC LIMIT 1");
                 ResultSet rs = p.executeQuery()) {
                if (rs.next()) for (int i = 0; i < ks.size(); i++) clave.put(ks.get(i), rs.getObject(i + 1));
            }
            if (clave.isEmpty()) return nombre + ": sin registros";
            Object ultimo = clave.size() == 1 ? clave.values().iterator().next() : clave.values();
            OptimistTrnDatos datos = new OptimistTrnDatos(c);
            OptimistTrnDatos.Registro reg = datos.cargarBc(trn, clave);
            Map<String, List<Map<String, Object>>> lineas = new LinkedHashMap<>();
            reg.detalles().forEach((det, ls) -> {
                List<Map<String, Object>> l = new ArrayList<>();
                for (OptimistTrnDatos.Linea x : ls) l.add(new HashMap<>(x.valores()));
                lineas.put(det, l);
            });
            OptimistCalculo.calcular(trn, reg.cabecera(), lineas, datos.abridor());
            List<String> mensajes = new ArrayList<>();
            try {   // el inicio (Start): sin usuario conectado puede cortarse antes del final; igual deja armado lo que corrió
                OptimistReglas r = OptimistReglas.de(trn);
                r.iniciar(r.nuevoEstado("DSP"), reg.cabecera(), datos.abridor(), mensajes);
            } catch (RuntimeException e) { /* sin sesión: no importa */ }
            // V82o: la lista del WorkWith como la pide la pantalla: la primera página y la cantidad con los totales
            long tl = System.currentTimeMillis();
            String lista = "";
            try {
                Set<String> sumar = new LinkedHashSet<>();
                for (String col : trn.workwith().columnas()) {
                    OptimistTrnDefinicion.Campo cc = trn.cabecera().campo(col);
                    if (cc != null && trn.workwith().totaliza(cc)) sumar.add(cc.nombre());
                }
                datos.listarPagina(trn, Map.of(), null, false, 0, 20);
                datos.contarYSumar(trn, Map.of(), null, sumar, trn.workwith().monedaTotales(trn.cabecera()));
                lista = ", lista " + (System.currentTimeMillis() - tl) + " ms";
            } catch (Exception e) { lista = ", lista: " + e.getMessage(); }
            return nombre + " " + ultimo + " (" + (tl - t) + " ms" + lista + ")";
        } catch (Exception e) {
            return nombre + ": " + e.getMessage();
        }
    }
}
