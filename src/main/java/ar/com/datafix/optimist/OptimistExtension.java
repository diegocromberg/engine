package ar.com.datafix.optimist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * E3 - Los "enchufes" de OPTIMIST Engine: lo propio de cada aplicación (OPTIMIST ERP, Factory...) que el motor no conoce.
 *
 * Una aplicación con código Java propio lo declara en una clase que implementa esta interfaz y la anota en
 *   src/main/resources/META-INF/services/ar.com.datafix.optimist.OptimistExtension
 * (un renglón con el nombre completo de la clase; es el mecanismo estándar de Java, ServiceLoader). El motor las carga al
 * arrancar. Todo tiene un valor por defecto: una aplicación sin Java propio (ej. Factory) no declara ninguna y el motor
 * funciona solo con sus YAML.
 *
 * Regla: el motor no nombra tablas, procedimientos, clientes ni comprobantes de ninguna aplicación; lo que necesita de una
 * aplicación lo pide por acá.
 */
public interface OptimistExtension {

    /** El nombre del sistema si el optimist.cfg no dice sistema.nombre (OPTIMIST ERP: "OPTIMIST ERP"). */
    default String nombreSistema() { return null; }

    /** Procedimientos en Java (OptimistProcedimiento.registrarNativo / OptimistProcedimientos.reemplazar). Se llama una vez al arrancar. */
    default void registrarProcedimientos() {}

    /**
     * Al iniciar sesión, después de que el motor leyó el usuario de SecUser (SecUserId y SecUserName en el contexto): lo que
     * la aplicación agrega al contexto (el WWPContext de GeneXus: empresa, división...). Un SQLException con mensaje corta el
     * ingreso con ese mensaje (ej. "Usuario no habilitado").
     */
    default void alIniciarSesion(Connection cn, String usuario, OptimistContexto ctx) throws SQLException {}

    /** Después de armar el contexto: un mensaje si el usuario no puede entrar (ej. sin empresa asignada), o null. */
    default String validarIngreso(OptimistContexto ctx, String usuario) { return null; }

    /** Las variables de contexto que agrega (se usan en los YAML como "&Empresa"), además de &SecUserId y &SecUserName. */
    default List<String> variablesContexto() { return List.of(); }

    /**
     * Las variables de contexto que, junto con el usuario, forman la clave de la configuración que se recuerda por usuario
     * (GetUsrProc, VERCampo...). Ej. el ERP: empresa y división, porque cada usuario la recuerda por empresa.
     */
    default List<String> claveUsuario() { return List.of(); }

    /** Las consultas con 'calculo: nombre' (ej. el stock del ERP): nombre en minúscula -> quien arma la pantalla. */
    default Map<String, OptimistConsultaView.Calculo> calculosConsulta() { return Map.of(); }

    /** Columnas que toda consulta trae aunque no se vean, si la TRN las tiene (ej. el código y la descripción del artículo). */
    default List<String> consultaOcultasSiempre() { return List.of(); }

    /** Los objetos de los mensajes internos que no se llaman como su TRN (código -> TRN). */
    default Map<String, String> objetosMensajes() { return Map.of(); }

    /** Las TRN que se precalientan al arrancar, si el optimist.cfg no dice otra cosa (application.precalentarTrn). */
    default List<String> precalentarTrn() { return List.of(); }

    /**
     * Consultas: los procesos (GetUsrProc) que la aplicación lee en todas sus consultas, además de los de 'procesos:' del YAML,
     * y cómo los ajusta (ej. OPTIMIST ERP: sin el 156 ni el 157, la cantidad).
     */
    default Set<Integer> procesosConsulta() { return Set.of(); }
    default void ajustarProcesosConsulta(Map<Integer, Boolean> procesos) {}

    /**
     * El valor de un parámetro global (variables 'paramGlb: N' de las consultas; tipo "alfa", "numero" o "boolean"), o null
     * si la aplicación no tiene parámetros globales. OPTIMIST ERP: GetParamGlbValorAlfa / Numerico / Boolean.
     */
    default Object parametroGlobal(Connection cn, int numero, String tipo) throws SQLException { return null; }

    /** Cálculo comercial ('calculo:' de las TRN): de dónde salen los decimales de los precios si el YAML no dice 'decimales'. */
    default Buscar decimalesPrecios() { return null; }
    /** Una lectura simple: la columna de la fila de la tabla con esa clave. */
    record Buscar(String tabla, String columna, Map<String, Object> clave) {}

    /** Las monedas para el filtro de la columna de moneda de los totales (V77): {código, "código - descripción"}. */
    default List<OptimistTrnDefinicion.Valor> monedas(Connection cn) throws SQLException { return List.of(); }

    /** El texto de la columna 'segun' de la ventana de códigos repetidos de una referencia (null: el valor tal cual). */
    default String textoSegun(String columna, Object valor) { return null; }

    /**
     * Las &variables "de instalación" del inicio de las acciones (V65o: las acciones se recuerdan por instalación):
     * las fijas (en minúscula, con el &) y patrones cuyo grupo 1 es la &variable que reciben (ej. GetParamGlbValorAlfa.Call(1, &X)).
     */
    default Set<String> variablesInstalacion() { return Set.of(); }
    default List<java.util.regex.Pattern> patronesInstalacion() { return List.of(); }

    // ------------------------------------------------------------------ las extensiones cargadas

    /** Todas las extensiones de la aplicación (se cargan una vez; sin efectos al cargarlas). */
    static List<OptimistExtension> todas() { return Cargadas.TODAS; }

    final class Cargadas {
        private Cargadas() {}
        private static final Logger LOG = LoggerFactory.getLogger(OptimistExtension.class);
        static final List<OptimistExtension> TODAS;
        static {
            List<OptimistExtension> l = new ArrayList<>();
            // una extensión que no se puede cargar no impide las demás
            Iterator<OptimistExtension> it = ServiceLoader.load(OptimistExtension.class, OptimistExtension.class.getClassLoader()).iterator();
            for (int vueltas = 0; vueltas < 1000; vueltas++) {
                try {
                    if (!it.hasNext()) break;
                    l.add(it.next());
                } catch (ServiceConfigurationError e) {
                    LOG.error("No se pudo cargar una extensión de la aplicación: {}", e.getMessage());
                }
            }
            TODAS = List.copyOf(l);
            LOG.info("[OPTIMIST] Extensiones de la aplicación: {}", TODAS.isEmpty() ? "ninguna" : TODAS.stream().map(x -> x.getClass().getSimpleName()).toList());
        }
    }
}
