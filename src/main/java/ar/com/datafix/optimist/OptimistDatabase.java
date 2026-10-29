package ar.com.datafix.optimist;

import jakarta.servlet.ServletContext;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * Configuración externa por instalación y acceso JDBC parametrizado.
 *
 * V53: dos modos.
 *  - Un WAR por cliente (como siempre): InPack.war lee /usr/optimist/InPack/optimist.cfg (o optimist.config / OPTIMIST_CONFIG).
 *  - Instalación única (modo central): la base OPTIMIST_ERP tiene la tabla Clientes (Id, razón social, UUID, carpeta). El
 *    login llega con ?CK=<UUID>: se busca el cliente, se lee <OPTIMIST_HOME>/<carpeta>/optimist.cfg y la sesión queda con
 *    ese cliente; cada conexión se abre contra la base del cliente de la sesión. La conexión a la base central está en
 *    optimist-central.cfg. Se usa cuando se indica optimist.central (propiedad, -D o variable OPTIMIST_CENTRAL) o cuando no
 *    hay optimist.cfg para el nombre del WAR y existe <OPTIMIST_HOME>/optimist-central.cfg.
 */
@Component
public class OptimistDatabase {
    /**
     * V21: en Tomcat, DriverManager no descubre solo los drivers que vienen dentro del WAR
     * (WEB-INF/lib): hay que registrarlo explícitamente. Con java -jar no hacía falta.
     */
    static {
        try { Class.forName("com.mysql.cj.jdbc.Driver"); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("Falta mysql-connector-j en el WAR", e); }
    }

    /** V53: un cliente de la instalación única: su fila de Clientes, su carpeta y su optimist.cfg. */
    public record Cliente(Object id, String razonSocial, String uuid, Path carpeta, Properties config) {}

    /** V53: en la sesión de Vaadin, el cliente con el que se entró (modo central). */
    public static final String ATRIBUTO_CLIENTE = "optimist.cliente";
    /** V53: el ?CK= que llegó en la dirección (lo anota OptimistCodigoCliente), para el login. */
    public static final String ATRIBUTO_CK = "optimist.ck";
    /** V53: para trabajos en segundo plano (precalentar un cliente): el cliente del hilo, sin sesión de Vaadin. */
    private static final ThreadLocal<Cliente> CLIENTE_HILO = new ThreadLocal<>();

    /** V29: carpeta del cliente (la del optimist.cfg), o null si no se sabe. Una por aplicación desplegada. */
    static volatile Path carpetaCliente;

    /** V29 / V53: la carpeta del cliente: en modo central, la del cliente de la sesión. */
    public static Path carpetaCliente() {
        Cliente c = clienteActual();
        return c != null ? c.carpeta() : carpetaCliente;
    }

    private final Properties config = new Properties();
    private final String configPath;
    /** V28: dónde se buscó por convención (para el mensaje de error), o null. */
    private final String buscado;
    /** V53: modo central: la configuración de la base OPTIMIST_ERP y la carpeta donde están las de los clientes. */
    private final Properties central = new Properties();
    private final boolean modoCentral;
    private final Path home;

    /** V72: la instancia de Spring, para lo que no la recibe inyectada (la campanita de mensajes de la barra). */
    private static volatile OptimistDatabase INSTANCIA;
    static OptimistDatabase instancia() { return INSTANCIA; }

    /**
     * V83: nombre del sistema que muestra la pantalla (login, barra, títulos). optimist.cfg: sistema.nombre; sin la línea,
     * el de la aplicación (E3, OptimistExtension.nombreSistema: OPTIMIST ERP) o "OPTIMIST".
     */
    public static String nombreSistema() {
        String n = null;
        try {
            OptimistDatabase d = INSTANCIA;
            n = d == null ? null : d.propiedad("sistema.nombre", null);
        } catch (RuntimeException ignorado) { }
        if (n != null && !n.isBlank()) return n.trim();
        for (OptimistExtension e : OptimistExtension.todas()) { String x = e.nombreSistema(); if (x != null && !x.isBlank()) return x.trim(); }
        return "OPTIMIST";
    }

    /** V83 / E4: PostIts (módulo opcional): sin ellos no se muestra el botón ni se lee la tabla PostIts. */
    public static boolean conPostIts() { return conModulo("postits"); }

    /**
     * E4: un módulo opcional del motor ("postits", "mensajes"): el optimist.cfg manda (sistema.postits=true/false); si no
     * dice nada, prendido si alguna extensión de la aplicación lo pide (OptimistExtension.modulos); si no, apagado.
     */
    public static boolean conModulo(String modulo) {
        try {
            OptimistDatabase d = INSTANCIA;
            String v = d == null ? null : d.propiedad("sistema." + modulo, null);
            if (v != null && !v.isBlank()) return "true".equalsIgnoreCase(v.trim());
        } catch (RuntimeException ignorado) { }
        for (OptimistExtension e : OptimistExtension.todas())
            for (String m : e.modulos()) if (modulo.equalsIgnoreCase(m)) return true;
        return false;
    }

    public OptimistDatabase(ServletContext servletContext, Environment environment) {
        INSTANCIA = this;
        String base = System.getProperty("optimist.home");
        if (base == null || base.isBlank()) base = System.getenv("OPTIMIST_HOME");
        if (base == null || base.isBlank()) base = "/usr/optimist";
        // V53: la configuración de la instalación única, si se indicó
        String cen = environment.getProperty("optimist.central");
        if (cen == null || cen.isBlank()) cen = System.getProperty("optimist.central");
        if (cen == null || cen.isBlank()) cen = System.getenv("OPTIMIST_CENTRAL");

        String path = null;
        if (cen == null || cen.isBlank()) {
            path = servletContext.getInitParameter("optimist.config");
            if (path == null || path.isBlank()) path = environment.getProperty("optimist.config");
            if (path == null || path.isBlank()) path = System.getProperty("optimist.config");
            if (path == null || path.isBlank()) path = System.getenv("OPTIMIST_CONFIG");
        }
        // V28: por convención, sin ningún XML: el nombre del WAR es el cliente.
        // InPack.war -> contexto /InPack -> <OPTIMIST_HOME>/InPack/optimist.cfg (por defecto /usr/optimist).
        String porConvencion = null;
        if ((cen == null || cen.isBlank()) && (path == null || path.isBlank())) {
            String ctx = servletContext.getContextPath() == null ? "" : servletContext.getContextPath().replaceAll("^/+", "");
            if (ctx.matches("[A-Za-z0-9_-]+")) {
                Path candidato = Path.of(base, ctx, "optimist.cfg");
                porConvencion = candidato.toString();
                if (Files.isRegularFile(candidato)) path = porConvencion;
            }
            // V53: sin optimist.cfg para este WAR, pero con la configuración central: instalación única
            if ((path == null || path.isBlank()) && Files.isRegularFile(Path.of(base, "optimist-central.cfg")))
                cen = Path.of(base, "optimist-central.cfg").toString();
        }
        if (cen != null && !cen.isBlank()) {
            modoCentral = true;
            try (InputStream input = Files.newInputStream(Path.of(cen))) {
                central.load(input);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo leer la configuración central en " + cen, e);
            }
            // las carpetas de los clientes: clientes.home en optimist-central.cfg, o la carpeta de ese archivo
            String h = central.getProperty("clientes.home", "");
            Path padre = Path.of(cen).toAbsolutePath().getParent();
            home = !h.isBlank() ? Path.of(h) : padre != null ? padre : Path.of(base);
            System.out.println("[OPTIMIST] Instalación única (modo central): " + cen + "; carpetas de los clientes en " + home);
            buscado = null;
            configPath = null;
            return;
        }
        modoCentral = false;
        home = Path.of(base);
        buscado = porConvencion;
        configPath = path;
        // V29: la carpeta del optimist.cfg es la carpeta del cliente (ahí van también los archivos de usuario)
        if (path != null && !path.isBlank()) {
            Path padre = Path.of(path).toAbsolutePath().getParent();
            if (padre != null) carpetaCliente = padre;
        } else if (porConvencion != null) {
            Path padre = Path.of(porConvencion).getParent();
            if (padre != null) carpetaCliente = padre;
        }
        if (path != null && !path.isBlank()) {
            try (InputStream input = Files.newInputStream(Path.of(path))) {
                config.load(input);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo leer optimist.cfg en " + path, e);
            }
        }
    }

    /** V53: true = instalación única (los clientes salen de la base OPTIMIST_ERP y el login necesita ?CK=). */
    public boolean modoCentral() { return modoCentral; }

    /** V53: el cliente de la sesión (o del hilo, en segundo plano), o null. */
    public static Cliente clienteActual() {
        Cliente c = CLIENTE_HILO.get();
        if (c != null) return c;
        com.vaadin.flow.server.VaadinSession s = com.vaadin.flow.server.VaadinSession.getCurrent();
        return s == null ? null : (Cliente) s.getAttribute(ATRIBUTO_CLIENTE);
    }

    /** V53: corre algo en segundo plano con la base de ese cliente (no hay sesión de Vaadin en ese hilo). */
    public static <T> T conCliente(Cliente c, Callable<T> trabajo) throws Exception {
        Cliente antes = CLIENTE_HILO.get();
        CLIENTE_HILO.set(c);
        try { return trabajo.call(); }
        finally { if (antes == null) CLIENTE_HILO.remove(); else CLIENTE_HILO.set(antes); }
    }

    public boolean configured() {
        if (modoCentral) return clienteActual() != null;
        return configPath != null && !configPath.isBlank();
    }

    /** V50l: una clave de optimist.cfg (ej. application.precalentar), o el valor por defecto. V53: la del cliente de la sesión. */
    public String propiedad(String clave, String porDefecto) {
        if (modoCentral) {
            Cliente c = clienteActual();
            return c != null ? c.config().getProperty(clave, porDefecto) : central.getProperty(clave, porDefecto);
        }
        return config.getProperty(clave, porDefecto);
    }

    /**
     * V84i: una clave de optimist.cfg leída del archivo AL DÍA (ej. totales.usuarios): si el archivo cambió desde la última
     * lectura (fecha de modificación), se vuelve a leer, sin reiniciar el servidor. En la instalación única, el del cliente
     * de la sesión.
     */
    public String propiedadAlDia(String clave, String porDefecto) {
        Path archivo = null;
        if (modoCentral) {
            Cliente c = clienteActual();
            if (c != null && c.carpeta() != null) archivo = c.carpeta().resolve("optimist.cfg");
        } else if (configPath != null && !configPath.isBlank()) archivo = Path.of(configPath);
        if (archivo == null) return propiedad(clave, porDefecto);
        try {
            long fecha = Files.getLastModifiedTime(archivo).toMillis();
            Object[] leido = CFG_AL_DIA.get(archivo);
            if (leido == null || (long) leido[0] != fecha) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(archivo)) { p.load(in); }
                leido = new Object[]{fecha, p};
                CFG_AL_DIA.put(archivo, leido);
            }
            return ((Properties) leido[1]).getProperty(clave, porDefecto);
        } catch (IOException | RuntimeException e) {
            return propiedad(clave, porDefecto);
        }
    }
    private static final java.util.concurrent.ConcurrentHashMap<Path, Object[]> CFG_AL_DIA = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * E4: una condición de 'visibleSi' de los YAML (ej. visibleSi: ModuloMutual): la clave application.<Condicion>=true del
     * optimist.cfg del cliente (sin distinguir mayúsculas; ej. application.moduloMutual=true). Sin la clave, no se cumple.
     */
    public boolean condicion(String nombre) {
        if (nombre == null || nombre.isBlank()) return false;
        String buscada = "application." + nombre.trim();
        Properties p;
        if (modoCentral) { Cliente c = clienteActual(); p = c != null ? c.config() : central; }
        else p = config;
        for (String k : p.stringPropertyNames())
            if (k.equalsIgnoreCase(buscada)) return Boolean.parseBoolean(p.getProperty(k).trim());
        return false;
    }

    Connection openConnection() throws SQLException {
        return connection();
    }

    private Connection connection() throws SQLException {
        if (modoCentral) {
            Cliente c = clienteActual();
            if (c == null) throw new SQLException("No se sabe con qué cliente se entró (falta el ?CK= del login)");
            return conectar(c.config(), c.carpeta().resolve("optimist.cfg").toString());
        }
        if (!configured()) throw new SQLException(buscado != null
                ? "No encontré " + buscado + " (crear ese archivo con los datos de la base del cliente y reiniciar la aplicación)"
                : "Falta configurar optimist.cfg (optimist.config / OPTIMIST_CONFIG)");
        return conectar(config, "optimist.cfg");
    }

    private static Connection conectar(Properties cfg, String archivo) throws SQLException {
        String host = cfg.getProperty("database.host", "localhost").trim();
        String port = cfg.getProperty("database.port", "3306").trim();
        String name = cfg.getProperty("database.name", "").trim();
        if (!host.matches("[a-zA-Z0-9._:-]+") || !port.matches("[0-9]{1,5}") || !name.matches("[a-zA-Z0-9_]+"))
            throw new SQLException("Host, puerto o nombre de base de datos inválido en " + archivo);
        String url = "jdbc:mysql://" + host + ":" + port + "/" + name + "?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=LOCAL&zeroDateTimeBehavior=CONVERT_TO_NULL";
        return DriverManager.getConnection(url, cfg.getProperty("database.user", ""), cfg.getProperty("database.password", ""));
    }

    /**
     * V53: el cliente activo con ese UUID (de la tabla Clientes de la base central), con su optimist.cfg leído; null si no
     * existe, está dado de baja o el código no tiene forma de UUID. Si falta su optimist.cfg, error (lo tiene que ver quien
     * instala, no el usuario).
     */
    public Cliente buscarCliente(String uuid) throws SQLException {
        if (!modoCentral || uuid == null) return null;
        String ck = uuid.trim().toLowerCase(Locale.ROOT);
        if (!ck.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return null;
        Object id;
        String razon, carpeta;
        try (Connection c = conectar(central, "optimist-central.cfg");
             PreparedStatement p = c.prepareStatement("SELECT ClienteId, ClienteRazonSocial, ClienteCarpeta FROM Clientes"
                     + " WHERE ClienteUUID=? AND ClienteActivo=1")) {
            p.setString(1, ck);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                id = r.getObject(1);
                razon = r.getString(2) == null ? "" : r.getString(2).trim();
                carpeta = r.getString(3) == null ? "" : r.getString(3).trim();
            }
        }
        if (!carpeta.matches("[A-Za-z0-9_-]{1,60}"))
            throw new SQLException("La carpeta del cliente " + razon + " es inválida en la tabla Clientes: '" + carpeta + "'");
        Path dir = home.resolve(carpeta);
        Path cfg = dir.resolve("optimist.cfg");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(cfg)) {
            p.load(in);
        } catch (IOException e) {
            throw new SQLException("No encontré " + cfg + " (la configuración del cliente " + razon + ")");
        }
        return new Cliente(id, razon, ck, dir, p);
    }

}
