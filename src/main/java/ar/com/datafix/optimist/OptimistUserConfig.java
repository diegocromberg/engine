package ar.com.datafix.optimist;

import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.Properties;

/**
 * Configuración persistente por usuario (Apariencia, WorkWith, ancho del menú...).
 * V29: vive en la carpeta del CLIENTE: <carpeta del optimist.cfg>/usuarios/optimist.<usuario>.xml
 * (antes podía quedar en la carpeta de trabajo de Tomcat, compartida entre clientes).
 * Si el archivo nuevo no existe y hay uno en la ubicación vieja, se copia la primera vez.
 * Las escrituras son por CLAVES: se relee el archivo, se cambian solo esas claves y se graba,
 * así Apariencia y el WorkWith no se pisan entre sí.
 */
@Component
public class OptimistUserConfig {
    private final Path baseDir;
    private final Path baseVieja;
    /** Un solo candado para TODAS las instancias (cada pantalla crea la suya). */
    private static final Object CANDADO = new Object();

    public OptimistUserConfig() {
        String cfg = System.getProperty("optimist.config");
        if (cfg == null || cfg.isBlank()) cfg = System.getenv("OPTIMIST_CONFIG");
        Path vieja = (cfg == null || cfg.isBlank()) ? Path.of(".") : Path.of(cfg).toAbsolutePath().getParent();
        baseVieja = vieja == null ? Path.of(".") : vieja;
        Path cliente = OptimistDatabase.carpetaCliente();
        baseDir = cliente != null ? cliente.resolve("usuarios") : baseVieja;
    }

    private static String seguro(String usuario) {
        return (usuario == null ? "anonimo" : usuario).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private Path archivo(String usuario) { return baseDir.resolve("optimist." + seguro(usuario) + ".xml"); }

    public Properties cargar(String usuario) {
        Properties p = defaults();
        Path a = archivo(usuario);
        if (!Files.exists(a)) migrar(usuario, a);
        if (Files.exists(a)) try (InputStream in = Files.newInputStream(a)) { p.loadFromXML(in); } catch (Exception ignored) {}
        return p;
    }

    /** Copia el archivo de la ubicación vieja (si existe) a la carpeta del cliente. */
    private void migrar(String usuario, Path nuevo) {
        if (baseDir.equals(baseVieja)) return;
        Path viejo = baseVieja.resolve("optimist." + seguro(usuario) + ".xml");
        try {
            if (Files.exists(viejo)) {
                Files.createDirectories(nuevo.getParent());
                Files.copy(viejo, nuevo);
            }
        } catch (IOException ignored) {}
    }

    /** Graba el archivo completo: primero a un temporal y después lo reemplaza, así nunca queda a medio escribir. */
    private void grabar(String usuario, Properties p) throws IOException {
        Files.createDirectories(baseDir);
        Path destino = archivo(usuario);
        Path tmp = destino.resolveSibling(destino.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.storeToXML(out, OptimistDatabase.nombreSistema() + " - Configuración de " + usuario, "UTF-8");
        }
        try { Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** Compatibilidad: graba todas las propiedades dadas encima de lo que ya tiene el archivo. */
    public void guardar(String usuario, Properties p) throws IOException { guardarClaves(usuario, p); }

    /** V29: cambia solo estas claves (el resto del archivo queda como está). Valor null = borrar la clave. */
    public void guardarClaves(String usuario, Map<?, ?> cambios) throws IOException {
        synchronized (CANDADO) {
            // V64j: se parte de lo que el archivo tiene grabado, SIN los valores iniciales: así guardar el WorkWith (columnas,
            // renglones) no deja grabada una Apariencia que el usuario nunca eligió, y si cambian los valores iniciales le llegan.
            Properties actual = new Properties();
            Path a = archivo(usuario);
            if (!Files.exists(a)) migrar(usuario, a);
            if (Files.exists(a)) try (InputStream in = Files.newInputStream(a)) { actual.loadFromXML(in); } catch (Exception ignored) {}
            for (Map.Entry<?, ?> e : cambios.entrySet()) {
                if (e.getValue() == null) actual.remove(String.valueOf(e.getKey()));
                else actual.setProperty(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            grabar(usuario, actual);
        }
    }

    /** V29: una sola clave (null = borrarla). Errores al grabar se ignoran: es una preferencia. */
    public void guardarClave(String usuario, String clave, String valor) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put(clave, valor);
        try { guardarClaves(usuario, m); } catch (IOException ignored) {}
    }

    public Path ruta(String usuario) { return archivo(usuario); }

    /** Claves de Apariencia (Restablecer vuelve solo estas a su valor inicial). */
    public static final java.util.List<String> CLAVES_APARIENCIA = java.util.List.of(
            "modo", "acento", "fondo", "texto", "fuente", "letra", "densidad", "densidadFormulario", "pantallaCompleta", "filasPagina", "menu", "acciones");

    public static Properties defaults() {
        Properties p = new Properties();
        // V64j: lo que ve un usuario que todavía no tocó su Apariencia (y lo que deja "Restablecer"): modo Claro, Arial 15,
        // grilla y formularios supercompactos, pantalla completa, menú horizontal y acciones en el menú (⋮).
        p.setProperty("modo", "Claro"); p.setProperty("acento", "#0ea88f"); p.setProperty("fondo", "#eef2f5"); p.setProperty("texto", "#203344");
        p.setProperty("fuente", "Arial"); p.setProperty("letra", "15"); p.setProperty("densidad", "Supercompacta"); p.setProperty("densidadFormulario", "Supercompacta"); p.setProperty("pantallaCompleta", "true"); p.setProperty("filasPagina", "10");
        p.setProperty("menu", "Horizontal");   // V46: Vertical (al costado) u Horizontal (barra arriba)
        p.setProperty("acciones", "Menú");   // V56: acciones del renglón en el menú (⋮) o en el renglón
        return p;
    }
}
