package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDatos.Linea;
import ar.com.datafix.optimist.OptimistTrnDatos.Registro;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Campo;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Nivel;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Referencia;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Celda;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Solapa;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Tabla;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Trn;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Bloque;
import ar.com.datafix.optimist.OptimistTrnDefinicion.BloqueTabla;
import ar.com.datafix.optimist.OptimistTrnDefinicion.BloqueDetalle;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Panel;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Renglon;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.SQLException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * V21 - Pantalla GENÉRICA de TRN. Todo sale de trn/{nombre}.yaml:
 * WorkWith (plantilla maestra), formulario con solapas, N detalles, referencias con buscador,
 * validaciones y SQL. No hay que escribir Java para una TRN nueva: solo el YAML.
 */
@Route("trn/:nombre")
public class OptimistTrnView extends VerticalLayout implements BeforeEnterObserver {
    private final OptimistTrnDatos datos;
    private final OptimistDatabase db;
    private Trn trn;
    private OptimistWorkWith.Pantalla<Map<String, Object>> pantalla;

    /** Condiciones de visibleSi. Para agregar una: una línea acá. Desconocida = oculto. */
    private final Map<String, BooleanSupplier> condiciones = new HashMap<>();

    public OptimistTrnView(@Autowired OptimistTrnDatos datos, @Autowired OptimistDatabase db) {
        this.datos = datos;
        this.db = db;
        condiciones.put("modulomutual", db::moduloMutual);
    }

    // V48d: el formulario abierto es un paso del historial del navegador (atrás lo cierra, adelante lo vuelve a abrir)
    private final List<Dialog> formulariosAbiertos = new ArrayList<>();
    private boolean cerrandoPorNavegacion;
    private String ruta;
    private boolean formularioEnUrl;   // la dirección actual ya tiene ?abrir= / ?ver= / ?nuevo=
    private String rutaArmada;         // V49z: la TRN de la lista que está armada (para no rearmarla al volver de un formulario)
    private boolean llegoDesdeApp;     // V50f: la dirección actual vino de otra pantalla de OPTIMIST (no de escribirla o F5)
    /** V50f: las listas ya armadas por TRN (las últimas 6), para volver a ellas como estaban. */
    private final LinkedHashMap<String, Object[]> armadas = new LinkedHashMap<>();
    private boolean encima;            // V50h: el formulario de esta TRN está encima de la lista de otra (la que lo llamó)

    @Override
    public void beforeEnter(BeforeEnterEvent e) {
        if (OptimistShell.usuario() == null) { OptimistShell.alLogin(e); return; }
        if (OptimistVisorPdf.volviendoDePdf() && pantalla != null) return;   // V84h: el Atrás cerró una ventana: todo queda como estaba
        // V48d: atrás / adelante del navegador: lo que estaba abierto se cierra (sin volver a tocar el historial)
        if (!formulariosAbiertos.isEmpty()) {
            cerrandoPorNavegacion = true;
            try { for (Dialog d : new ArrayList<>(formulariosAbiertos)) d.close(); }
            finally { cerrandoPorNavegacion = false; }
            formulariosAbiertos.clear();
        }
        String ruta = e.getRouteParameters().get("nombre").orElse("").toLowerCase(Locale.ROOT);
        this.ruta = ruta;
        Map<String, List<String>> qp = e.getLocation().getQueryParameters().getParameters();
        formularioEnUrl = qp.containsKey("abrir") || qp.containsKey("ver") || qp.containsKey("nuevo");
        // V50f: se llegó desde otra pantalla de OPTIMIST (un vínculo, un web panel): al cerrar el formulario se vuelve ahí
        llegoDesdeApp = e.getTrigger() != com.vaadin.flow.router.NavigationTrigger.PAGE_LOAD;
        OptimistShell.rutaActual("trn/" + ruta);
        if (OptimistShell.sinPermiso(this, e, "trn/" + ruta)) return;   // V48e
        OptimistTrnCatalogo.Resultado cat = OptimistTrnCatalogo.cargar();
        Trn anterior = trn;
        trn = cat.trns().get(ruta);
        // V84f: la misma pantalla sirve para todas las TRN (Vaadin la reusa al ir de una lista a otra): lo calculado con el inicio
        // de las acciones de la anterior no vale para esta. Antes, de Comprobantes a Pedidos quedaban las &variables de
        // Comprobantes y las acciones de Pedidos salían en gris ("&FacturarEligiendoCPBC = (vacío)") hasta apretar F5.
        if (trn != anterior) {
            variablesAcciones = null;
            errorInicioAcciones = "";
            posibles.clear();
        }
        if (trn == null) {
            String motivo = cat.errores().entrySet().stream()
                    .filter(x -> x.getKey().toLowerCase(Locale.ROOT).startsWith(ruta + "."))
                    .map(x -> x.getKey() + ": " + x.getValue()).findFirst()
                    .orElse("No existe trn/" + ruta + ".yaml");
            Span aviso = new Span("No se pudo abrir la transacción. " + motivo);
            aviso.getStyle().set("padding", "28px").set("color", "#ff8585");
            OptimistShell.montar(this, aviso, "Transacción");
            return;
        }
        // V49z: al cerrar un formulario el navegador vuelve a trn/x (atrás) y se pasa otra vez por acá: si es la misma TRN (y su
        // YAML no cambió), la lista queda como estaba (página, filtros, orden, desplazamiento). Antes se rearmaba y volvía a la página 1.
        // V50h: un registro de otra TRN abierto desde OPTIMIST (vínculo, web panel) se muestra ENCIMA de la pantalla que lo llamó:
        // la lista de esa otra TRN no se arma ni se muestra, así al cerrar se vuelve directo a donde estaba (sin pasar por su lista).
        boolean veniaEncima = encima;
        encima = false;
        if (formularioEnUrl && llegoDesdeApp && pantalla != null && rutaArmada != null && !ruta.equals(rutaArmada)) {
            encima = true;   // abajo sigue la lista que estaba (rutaArmada); 'pantalla' es la de ella y no se recarga con esta TRN
        } else if (formularioEnUrl && llegoDesdeApp && (pantalla == null || rutaArmada == null)) {
            armarLista();   // hace falta para el formulario, pero no se muestra: abajo queda vacío y al cerrar se vuelve
            OptimistShell.montar(this, new Div(), trn.titulo());
            rutaArmada = null;
        } else if (veniaEncima && ruta.equals(rutaArmada) && armadas.get(ruta) != null
                && armadas.get(ruta)[0] == trn && armadas.get(ruta)[1] == pantalla) {
            cargar();        // la lista nunca se fue de la pantalla: solo los datos al día
        } else if (pantalla == null || trn != anterior || !ruta.equals(rutaArmada)) {
            armarLista();
            rutaArmada = ruta;
        }
        // V48: ?abrir=<clave> abre ese registro en Modificar (lo usa un web panel que creó el registro, ej. el alta de PRS)
        List<String> abrir = e.getLocation().getQueryParameters().getParameters().get("abrir");
        if (abrir != null && !abrir.isEmpty() && trn.cabecera().claves().size() == 1) abrirPorClave(abrir.get(0), false);
        // V48d: ?ver=<clave> (Visualizar) y ?nuevo (alta), para ir adelante con el navegador
        List<String> ver = qp.get("ver");
        if (ver != null && !ver.isEmpty() && trn.cabecera().claves().size() == 1) abrirPorClave(ver.get(0), true);
        if (qp.containsKey("nuevo") && trn.workwith().alta() == null) formulario(null, false);
    }

    /** V50f: la lista de esta TRN: la ya armada (queda como estaba: página, filtros, orden) o una nueva. La monta. */
    private void armarLista() {
        Object[] guardada = armadas.get(ruta);
        if (guardada != null && guardada[0] == trn) {
            @SuppressWarnings("unchecked") OptimistWorkWith.Pantalla<Map<String, Object>> p = (OptimistWorkWith.Pantalla<Map<String, Object>>) guardada[1];
            pantalla = p;
            OptimistShell.montar(this, pantalla.contenido, trn.titulo());
            cargar();   // los datos, al día (la página se mantiene)
        } else {
            long t0 = System.nanoTime();   // V84i
            construir();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms >= 500) System.out.println("[" + trn.nombre() + "] armar la lista (con la primera página): " + ms + " ms");
            if (armadas.size() >= 6) armadas.remove(armadas.keySet().iterator().next());
            armadas.put(ruta, new Object[]{trn, pantalla});
        }
    }

    /** V48: abre el registro de esta clave (texto, como llega de la URL o del web panel), en Modificar o Visualizar. */
    private void abrirPorClave(Object clave, boolean soloVer) {
        Campo k = trn.cabecera().claves().get(0);
        Object v = clave;
        if (clave != null && k.dominio().esNumerico()) {
            try { v = k.dominio().esEntero() ? (Object) new java.math.BigDecimal(String.valueOf(clave).trim()).longValueExact()
                    : new java.math.BigDecimal(String.valueOf(clave).trim()); }
            catch (RuntimeException ex) { Notification.show("Clave inválida: " + clave, 6000, Notification.Position.MIDDLE); return; }
        }
        Map<String, Object> fila = new HashMap<>();
        fila.put(k.nombre(), v);
        abrir(fila, soloVer);
    }

    /** "+ Nuevo": el formulario vacío o, con 'workwith.alta', el web panel de alta (ej. PRSAltaWbp) y después el registro creado. */
    private void nuevo() {
        String alta = trn.workwith().alta();
        if (alta == null) { formulario(null, false); return; }
        OptimistPanelView.abrirDialogo(alta, db, ab -> {
            cargar();   // el registro nuevo ya aparece en la lista
            abrirTrn(ab.trn(), ab.modo(), ab.clave());
        });
    }

    /** Abre un registro de esta TRN o de otra (otra: navega a su pantalla con ?abrir=). */
    private void abrirTrn(String nombre, String modo, Object clave) {
        if ("Lista".equalsIgnoreCase(modo)) {   // V66: WWxxx.Call() en el panel: la lista de esa TRN
            if (nombre.equalsIgnoreCase(trn.nombre())) { cargar(); return; }
            Trn otra = OptimistPanelView.trn(nombre);
            if (otra == null) { Notification.show("No existe la TRN " + nombre, 6000, Notification.Position.MIDDLE); return; }
            UI.getCurrent().navigate("trn/" + otra.ruta());
            return;
        }
        if (nombre.equalsIgnoreCase(trn.nombre()) && trn.cabecera().claves().size() == 1)
            abrirPorClave(clave, modo != null && modo.equalsIgnoreCase("Display"));
        else {
            Trn otra = OptimistPanelView.trn(nombre);
            if (otra == null) { Notification.show("No existe la TRN " + nombre, 6000, Notification.Position.MIDDLE); return; }
            boolean ver = modo != null && modo.equalsIgnoreCase("Display");   // V50e: Visualizar -> ?ver=
            UI.getCurrent().navigate("trn/" + otra.ruta(), com.vaadin.flow.router.QueryParameters.of(ver ? "ver" : "abrir", String.valueOf(clave)));
        }
    }

    // ------------------------------------------------------------------ V48: acciones del renglón

    private final Map<String, OptimistFormula> condicionesAcciones = new HashMap<>();

    /**
     * Las acciones del renglón, todas y siempre en el mismo orden. V56e: la que no cumple su condición va igual, sin qué
     * hacer (hacer = null): el menú no la muestra y en el renglón deja su lugar vacío, así los íconos quedan alineados.
     * V56g: la que no puede corresponder a ningún renglón (su condición pide una &variable que da falso, ej. &Baires4 en
     * los demás clientes) no va: no deja lugar.
     */
    private List<OptimistWorkWith.Accion> accionesDe(Map<String, Object> f, OptimistTrnDefinicion.Acciones ac) {
        List<OptimistWorkWith.Accion> r = new ArrayList<>();
        // V65o: la que no corresponde a este renglón va en gris con el motivo (antes no se veía y había que buscar por qué)
        if (posible(ac.modificar())) r.add(accionOGris(VaadinIcon.PENCIL, "Modificar", ac.modificar(), f,
                () -> siSigue(ac.modificar(), f, "Modificar", fresca -> abrir(fresca, false))));
        if (posible(ac.visualizar())) r.add(accionOGris(VaadinIcon.SEARCH, "Visualizar", ac.visualizar(), f, () -> abrir(f, true)));
        if (posible(ac.borrar())) r.add(accionOGris(VaadinIcon.CLOSE, "Eliminar", ac.borrar(), f,
                () -> siSigue(ac.borrar(), f, "Eliminar", this::confirmarBorrado)));
        for (OptimistTrnDefinicion.AccionRenglon a : ac.renglon())
            if (posible(a.si()))
                r.add(accionOGris(icono(a.icono()), a.titulo(), a.si(), f, () -> accion(a, f)));
        // V56g: las acciones con el mismo título y el mismo ícono son UNA acción con variantes según el renglón (ej. en
        // BAIRES4, Modificar abre la TRN o la cabecera según el estado; Autorizar, con o sin motivo): comparten un solo
        // lugar, el de la primera, y en cada renglón va la variante que corresponde.
        Map<String, Integer> lugar = new HashMap<>();
        List<OptimistWorkWith.Accion> unidas = new ArrayList<>();
        for (OptimistWorkWith.Accion a : r) {
            String k = a.texto().trim().toLowerCase(Locale.ROOT) + "|" + a.icono();
            Integer i = lugar.get(k);
            if (i == null) { lugar.put(k, unidas.size()); unidas.add(a); }
            else if (unidas.get(i).hacer() == null && a.hacer() != null) unidas.set(i, a);
            // V65o: dos variantes que no corresponden (ej. Autorizar con o sin motivo): el motivo de la que pide algo del renglón
            else if (unidas.get(i).hacer() == null && a.hacer() == null && a.porQueNo() != null
                    && (unidas.get(i).porQueNo() == null || unidas.get(i).porQueNo().contains("&"))) unidas.set(i, a);
        }
        return unidas;
    }

    private final Map<String, Boolean> posibles = new HashMap<>();

    /**
     * V56g: si la acción puede corresponder a algún renglón. Es falso solo si la condición, partida por los "and" de
     * afuera de los paréntesis, tiene una parte que usa solo &variables (sin campos del renglón ni procedimientos) y
     * esa parte da falso. Con un "or" afuera de los paréntesis no se analiza (puede corresponder).
     */
    private boolean posible(String condicion) {
        if (condicion == null) return true;
        if (condicion.equals("false")) return false;
        return posibles.computeIfAbsent(condicion, c -> {
            List<String> partes = partesAnd(c);
            if (partes == null) return true;   // un "or" afuera de los paréntesis: puede corresponder
            Set<String> deInstalacion = variablesDeInstalacion();
            for (String x : partes) {
                if (x.isEmpty() || !x.contains("&")) continue;
                try {
                    OptimistFormula fm = OptimistFormula.de(x);
                    boolean soloVariables = fm.procedimientos().isEmpty() && fm.atributos().stream().allMatch(a -> a.startsWith("&"));
                    // V65o: solo se saca del todo la que es de OTRA instalación (ej. &Baires4, &Instalacion = 'STONCOR'); la que
                    // depende de un parámetro (ej. &FacturarEligiendoCPBC, parámetro 61) se muestra en gris con el motivo
                    boolean instalacion = fm.atributos().stream().anyMatch(a -> deInstalacion.contains(a.toLowerCase(Locale.ROOT)));
                    if (soloVariables && instalacion && !cumple(x, Map.of())) return false;
                } catch (RuntimeException e) { /* no se entiende la parte: puede corresponder */ }
            }
            return true;
        });
    }

    /** V65o: la condición partida por los "and" de afuera de los paréntesis (sin espacios a los lados); null si tiene un "or" afuera. */
    private static List<String> partesAnd(String c) {
        List<String> partes = new ArrayList<>();
        int nivel = 0, desde = 0;
        String b = c.toLowerCase(Locale.ROOT);
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch == '\'' || ch == '"') { int j = c.indexOf(ch, i + 1); if (j < 0) break; i = j; continue; }
            if (ch == '(') nivel++;
            else if (ch == ')') nivel--;
            else if (nivel == 0 && Character.isWhitespace(ch)) {
                if (b.startsWith("or", i + 1) && i + 3 < c.length() && Character.isWhitespace(c.charAt(i + 3))) return null;
                if (b.startsWith("and", i + 1) && i + 4 < c.length() && Character.isWhitespace(c.charAt(i + 4))) {
                    partes.add(c.substring(desde, i).trim()); desde = i + 5; i += 4;
                }
            }
        }
        partes.add(c.substring(Math.min(desde, c.length())).trim());
        return partes;
    }

    private Set<String> variablesDeInstalacion;

    /**
     * V65o: las &variables que dependen de la instalación (parámetro 1): &Instalacion y las que se calculan con ella en el
     * inicio de las acciones (ej. &Baires4 = &Instalacion = "BAIRES4" or ...). En minúscula, con el &.
     */
    private Set<String> variablesDeInstalacion() {
        if (variablesDeInstalacion != null) return variablesDeInstalacion;
        // E3: las fijas y los patrones los da la aplicación (OPTIMIST ERP: &Instalacion y GetParamGlbValorAlfa.Call(1, &X))
        Set<String> r = new HashSet<>();
        for (OptimistExtension e : OptimistExtension.todas()) r.addAll(e.variablesInstalacion());
        String inicio = trn.workwith().acciones() == null ? null : trn.workwith().acciones().inicio();
        if (inicio != null) {
            for (OptimistExtension e : OptimistExtension.todas())
                for (java.util.regex.Pattern pat : e.patronesInstalacion()) {
                    java.util.regex.Matcher p1 = pat.matcher(inicio);
                    while (p1.find()) r.add(p1.group(1).toLowerCase(Locale.ROOT));
                }
            boolean otra = true;
            while (otra) {   // &X = ... &Instalacion ... (y en cadena)
                otra = false;
                for (String linea : inicio.split("\n")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(&\\w+)\\s*=(.*)$").matcher(linea);
                    if (!m.find()) continue;
                    String v = m.group(1).toLowerCase(Locale.ROOT), resto = m.group(2).toLowerCase(Locale.ROOT);
                    if (r.contains(v)) continue;
                    for (String x : r) if (resto.matches("(?s).*" + java.util.regex.Pattern.quote(x) + "\\b.*")) { r.add(v); otra = true; break; }
                }
            }
        }
        return variablesDeInstalacion = r;
    }

    /** V65o: la acción, o en gris con el motivo si no corresponde a este renglón. */
    private OptimistWorkWith.Accion accionOGris(VaadinIcon icono, String titulo, String condicion, Map<String, Object> f, Runnable hacer) {
        if (cumple(condicion, f)) return new OptimistWorkWith.Accion(icono, titulo, hacer);
        return new OptimistWorkWith.Accion(icono, titulo, null, porQueNo(condicion, f));
    }

    /**
     * V65o: por qué una condición no se cumple en el renglón: la primera parte (de las unidas por "and") que da falso, con
     * los valores que tiene hoy. Ej. "No se cumple: PEDFacturado = false (hoy PEDFacturado = Sí)".
     */
    private String porQueNo(String condicion, Map<String, Object> f) {
        if (condicion == null) return null;
        List<String> partes = partesAnd(condicion);
        String falla = condicion.trim();
        if (partes != null)
            for (String p : partes) if (!p.isEmpty() && !cumple(p, f)) { falla = p; break; }
        StringBuilder hoy = new StringBuilder();
        try {
            OptimistFormula fm = OptimistFormula.de(falla);
            Map<String, Object> vars = variablesAcciones();
            for (String a : new java.util.TreeSet<>(fm.atributos())) {
                if (a.contains(".")) continue;
                Object v;
                if (a.startsWith("&")) v = vars.get(a.substring(1));
                else v = f.entrySet().stream().filter(x -> x.getKey() != null && x.getKey().equalsIgnoreCase(a)).map(Map.Entry::getValue).findFirst().orElse(null);
                if (hoy.length() > 0) hoy.append(", ");
                hoy.append(a).append(" = ").append(v == null ? "(vacío)" : v instanceof Boolean bo ? (bo ? "Sí" : "No") : v);
            }
            if (!fm.procedimientos().isEmpty()) hoy.append(hoy.length() > 0 ? "; " : "").append("el procedimiento dio falso");
        } catch (RuntimeException ignorado) { }
        // V66b: si el inicio de las acciones falló, las &variables quedan vacías: se dice por qué
        String err = falla.contains("&") && errorInicioAcciones != null && !errorInicioAcciones.isBlank()
                ? ". El inicio de las acciones dio error: " + errorInicioAcciones : "";
        return "No se cumple: " + falla + (hoy.length() == 0 ? "" : " (hoy " + hoy + ")") + err;
    }

    /** V50l: variables del inicio del WorkWith (workwith.acciones.inicio, el Start del WWP de GeneXus): &Instalacion, &FacturarEligiendoCPBC... */
    private Map<String, Object> variablesAcciones;
    /** V66b: los errores del inicio de las acciones (se muestran en el motivo de la acción en gris). */
    private String errorInicioAcciones = "";

    private Map<String, Object> variablesAcciones() {
        if (variablesAcciones != null) return variablesAcciones;
        Map<String, Object> vars = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String inicio = trn.workwith().acciones() == null ? null : trn.workwith().acciones().inicio();
        if (inicio != null && !inicio.isBlank()) {
            long t0 = System.nanoTime();   // V84i
            List<String> mensajes = new ArrayList<>();
            try (java.sql.Connection c = datos.abridor().abrir()) {
                OptimistProcedimientos.asegurarNativos();
                OptimistProcedimiento ctx = OptimistProcedimiento.nativo("LoadWWPContext");
                Map<String, Object> v = new HashMap<>();
                if (ctx != null) v.put("wwpcontext", ctx.ejecutar(List.of(), null, n -> null, mensajes, 0).get("context"));
                OptimistProcedimiento pr = OptimistProcedimiento.leer(trn.nombre() + ".acciones.inicio", inicio);
                pr.ejecutarCon(v, Map.of(), c, OptimistProcedimientos::buscar, mensajes);
                v.forEach((k, x) -> { if (k != null) vars.put(k, x); });
                // V84d: las variables declaradas que no tomaron valor (un For Each que no encontró nada, una sentencia que dio
                // error) quedan con su valor vacío (No, 0, ""), como en GeneXus; antes faltaban y la lista no se podía leer
                // ("workwith.condicion: &FiltrarVendedor no está en el inicio de las acciones")
                for (var d : pr.variables.entrySet()) {
                    if (d.getKey() == null || vars.containsKey(d.getKey())) continue;
                    try { vars.put(d.getKey(), OptimistProcedimiento.tipar(d.getValue(), null)); }
                    catch (RuntimeException ignorado) { vars.put(d.getKey(), null); }
                }
            } catch (SQLException | RuntimeException ex) {
                mensajes.add(ex.getMessage());
            }
            if (!mensajes.isEmpty()) System.out.println("[" + trn.nombre() + "] inicio de las acciones: " + String.join(" | ", mensajes));
            errorInicioAcciones = String.join(" | ", mensajes);
            // V84d: si el inicio dio error, se avisa (para saber la causa sin mirar la consola del servidor)
            if (!mensajes.isEmpty() && UI.getCurrent() != null)
                Notification.show(trn.titulo() + ": el inicio de la lista dio error: " + errorInicioAcciones, 10000, Notification.Position.MIDDLE);
            // V66b: lo que dejó el inicio, siempre en la consola (para saber por qué una acción sale en gris)
            StringBuilder dejo = new StringBuilder();
            vars.forEach((k, x) -> { if (!k.equalsIgnoreCase("wwpcontext")) dejo.append(dejo.length() > 0 ? ", " : "").append(k).append(" = ").append(x == null ? "(vacío)" : x); });
            System.out.println("[" + trn.nombre() + "] inicio de las acciones (" + (System.nanoTime() - t0) / 1_000_000 + " ms), variables: " + (dejo.length() == 0 ? "(ninguna)" : dejo));
        }
        return variablesAcciones = vars;
    }

    /**
     * null = siempre; "false" = nunca; si no, la condición (fórmula) con los valores del renglón. Si falla, no se muestra.
     * V50l: la condición también puede usar las &Variables del inicio del WorkWith, llamar procedimientos
     * (PEDVerificarBajaCambio.Udp(PEDId, TrnMode.Update), GetParamGlbValorBoolean.Udp(112)...) y TrnMode.Update / Insert / Delete / Display.
     */
    private boolean cumple(String condicion, Map<String, Object> fila) {
        if (condicion == null) return true;
        if (condicion.equals("false")) return false;
        try {
            OptimistFormula fm = condicionesAcciones.computeIfAbsent(condicion, OptimistFormula::de);
            Map<String, Object> v = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            fila.forEach((k, x) -> { if (k != null) v.put(k, x); });
            boolean usaMas = !fm.procedimientos().isEmpty() || condicion.contains("&") || condicion.toLowerCase(Locale.ROOT).contains("trnmode.");
            if (!usaMas) return Boolean.TRUE.equals(fm.evaluar(v::get));
            Map<String, Object> vars = variablesAcciones();
            return Boolean.TRUE.equals(fm.evaluar(new OptimistFormula.Contexto() {
                @Override public Object valor(String n) {
                    if (n.startsWith("&")) return vars.get(n.substring(1));
                    String b = n.toLowerCase(Locale.ROOT);
                    if (b.startsWith("trnmode.")) return switch (b.substring(8)) {
                        case "update" -> "UPD"; case "insert" -> "INS"; case "delete" -> "DLT"; case "display" -> "DSP"; default -> n.substring(8);
                    };
                    return v.get(n);
                }
                @Override public Object procedimiento(String nombre, List<Object> args) {
                    Object r = OptimistProcedimiento.Funciones.llamar(nombre, args);
                    if (r != OptimistProcedimiento.Funciones.NO_ES) return r;
                    if (!OptimistProcedimientos.existe(nombre)) throw new IllegalStateException("falta el procedimiento " + nombre);
                    try { return OptimistProcedimientos.udp(nombre, args, conexionAcciones()); }   // V50n: una conexión por pasada
                    catch (SQLException e) { throw new IllegalStateException(nombre + ": " + e.getMessage(), e); }
                }
            }));
        } catch (RuntimeException ex) {
            System.out.println("[" + trn.nombre() + "] condición '" + condicion + "': " + ex.getMessage());
            return false;
        }
    }

    /**
     * V50n: una sola conexión para todas las condiciones con procedimientos de una pasada de la grilla (todos los renglones
     * de la página, y otra vez al actualizarla después de una acción). Antes cada llamada (ej. PEDVerificarBajaCambio, dos por
     * renglón) abría su propia conexión a MySQL: unas 125 conexiones y ~5 segundos para mostrar 21 pedidos.
     * Se abre la primera vez que hace falta y se cierra justo antes de mandar la respuesta al navegador.
     */
    private java.sql.Connection conexionAcciones;

    private java.sql.Connection conexionAcciones() throws SQLException {
        if (conexionAcciones != null && !conexionAcciones.isClosed()) return conexionAcciones;
        conexionAcciones = datos.abridor().abrir();
        UI ui = UI.getCurrent();
        if (ui != null) ui.beforeClientResponse(this, ctx -> cerrarConexionAcciones());
        return conexionAcciones;
    }

    private void cerrarConexionAcciones() {
        if (conexionAcciones == null) return;
        try { conexionAcciones.close(); } catch (SQLException ignorado) { }
        conexionAcciones = null;
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent e) {
        cerrarConexionAcciones();   // V50n: por si se sale de la pantalla antes de responder
        super.onDetach(e);
    }

    private static VaadinIcon icono(String nombre) {
        if (nombre == null || nombre.isBlank()) return VaadinIcon.CARET_RIGHT;
        try { return VaadinIcon.valueOf(nombre.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException ex) { return VaadinIcon.CARET_RIGHT; }
    }

    /** El valor de un parámetro: un campo del renglón, una constante (número, 'texto', true/false) o &Variable (de salida: null). */
    private static Object parametro(String p, Map<String, Object> fila) {
        String t = p.trim();
        if (t.startsWith("&")) return null;
        if ((t.startsWith("'") && t.endsWith("'") || t.startsWith("\"") && t.endsWith("\"")) && t.length() >= 2) return t.substring(1, t.length() - 1);
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) return Boolean.parseBoolean(t);
        if (t.matches("-?\\d+(\\.\\d+)?")) return new java.math.BigDecimal(t);
        for (Map.Entry<String, Object> e : fila.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(t)) return e.getValue();
        return t;   // texto sin comillas (ej. C)
    }

    /**
     * V48c: la lista puede estar vieja (otro usuario u otra pantalla cambió el registro después de mostrarla): antes de
     * hacer la acción se vuelve a leer el registro de la base y se controla de nuevo su condición. Si ya no se cumple
     * (ej. el presupuesto pasó de En Trámite a Aprobado en otra pestaña) no se hace y se actualiza la lista.
     */
    private void siSigue(String condicion, Map<String, Object> fila, String titulo, java.util.function.Consumer<Map<String, Object>> hacer) {
        Map<String, Object> fresca = new HashMap<>(fila);
        try {
            fresca.putAll(datos.cargarCabecera(trn, claveDe(fila)).cabecera());
        } catch (SQLException ex) {
            Notification.show(capital(trn.singular()) + " " + clave(fila) + " ya no está (" + ex.getMessage() + "). Se actualizó la lista.",
                    8000, Notification.Position.MIDDLE);
            cargar();
            return;
        }
        if (condicion != null && !cumple(condicion, fresca)) {
            Notification.show(capital(trn.singular()) + " " + clave(fila) + " cambió mientras tenías la lista abierta (otro usuario u otra pantalla): "
                    + "ya no corresponde \"" + titulo + "\". Se actualizó la lista.", 10000, Notification.Position.MIDDLE);
            cargar();
            return;
        }
        hacer.accept(fresca);
    }

    /**
     * V48c: control de concurrencia al confirmar (como GeneXus): si la cabecera en la base ya no es la que se abrió,
     * otro usuario u otra pantalla la grabó en el medio. Devuelve el aviso, o null si nadie la tocó.
     */
    private String cambioAjeno(Form f, Registro abierto) {
        if (f.enBase == null) return null;
        Map<String, Object> enBase;
        try { enBase = datos.cargarCabecera(trn, claveDe(abierto.cabecera())).cabecera(); }
        catch (SQLException ex) { return "No se pudo guardar: " + trn.singular() + " " + clave(abierto.cabecera()) + " ya no está en la base (" + ex.getMessage() + ")."; }
        List<String> cambiados = new ArrayList<>();
        for (Campo c : trn.cabecera().persistidos()) {
            if (!f.enBase.containsKey(c.nombre())) continue;
            if (!OptimistTrnDatos.igual(f.enBase.get(c.nombre()), enBase.get(c.nombre()))) cambiados.add(c.etiqueta());
        }
        if (cambiados.isEmpty()) return null;
        return "No se pudo guardar: otro usuario u otra pantalla modificó " + trn.singular() + " " + clave(abierto.cabecera())
                + " mientras lo tenías abierto (cambió: " + String.join(", ", cambiados.subList(0, Math.min(5, cambiados.size())))
                + (cambiados.size() > 5 ? "..." : "") + "). Cancelá y volvé a abrirlo para ver los cambios.";
    }

    /**
     * V66k: abre la consulta de una lupa 'ver:' con los valores del registro (o del renglón). Cierra el formulario como un
     * vínculo (queda en el historial: Volver / Atrás lo vuelve a abrir). En Modificar, pregunta antes (se pierde lo no guardado).
     */
    private void verConsulta(Form f, Dialog dialogo, OptimistTrnDefinicion.Ver v, Map<String, Object> valores) {
        Map<String, Object> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        valores.forEach((k, x) -> { if (k != null) ci.put(k, x); });
        List<Object> args = new ArrayList<>();
        for (String p : v.parametros()) args.add(ci.get(p));
        Runnable ir = () -> {
            Dialog d = dialogo != null ? dialogo : f.dialogo;
            if (d != null) {
                cerrandoPorNavegacion = true;
                try { d.close(); } finally { cerrandoPorNavegacion = false; }
            }
            try { OptimistConsultaView.navegar(v.consulta(), args); }
            catch (RuntimeException ex) { Notification.show(v.titulo() + ": " + ex.getMessage(), 8000, Notification.Position.MIDDLE); }
        };
        if (f.soloVer) { ir.run(); return; }
        new ConfirmDialog(v.titulo(), "Para verla se cierra el formulario y lo que no guardaste se pierde. ¿Seguís?",
                "Sí", e -> ir.run(), "No", e -> { }).open();
    }

    /** Ejecuta una acción del renglón (pregunta antes si tiene 'confirmar'). */
    private void accion(OptimistTrnDefinicion.AccionRenglon a, Map<String, Object> filaLista) {
        // V48c: después del "Sí", se controla con el registro como está ahora en la base (ver siSigue)
        Runnable correr = () -> siSigue(a.si(), filaLista, a.titulo(), fila -> ejecutarAccion(a, fila));
        if (a.confirmar() == null || a.confirmar().isBlank()) { correr.run(); return; }
        ConfirmDialog d = new ConfirmDialog(a.titulo(), a.confirmar(), "Sí", e -> correr.run(), "No", e -> { });
        d.open();
    }

    private void ejecutarAccion(OptimistTrnDefinicion.AccionRenglon a, Map<String, Object> fila) {
        List<Object> args = new ArrayList<>();
        for (String p : a.parametros()) args.add(parametro(p, fila));
        switch (a.tipo()) {
            case "proc" -> correrProcedimiento(a, args);
            case "panel" -> OptimistPanelView.abrirDialogo(a.objeto(), db, args, ab -> { cargar(); abrirTrn(ab.trn(), ab.modo(), ab.clave()); }, this::cargar);
            case "cns" -> {   // V66k: una consulta con lo que recibe (ej. Ver Imputaciones de un asiento)
                try { OptimistConsultaView.navegar(a.objeto(), args); }
                catch (RuntimeException ex) { Notification.show(a.titulo() + ": " + ex.getMessage(), 8000, Notification.Position.MIDDLE); }
            }
            default -> abrirTrn(a.objeto(), a.modo(), args.isEmpty() ? null : args.get(0));
        }
    }

    /** Procedimiento de una acción: muestra sus Msg() y los textos que devuelve (ej. &Mensaje), y actualiza la lista. */
    private void correrProcedimiento(OptimistTrnDefinicion.AccionRenglon a, List<Object> args) {
        List<String> mensajes = new ArrayList<>();
        OptimistContexto ctx = OptimistContexto.actual();
        if (ctx != null) ctx.poner("MensajeError", "");
        OptimistProcedimiento.tomarTrnAAbrir();   // V48: nada pendiente de antes
        OptimistProcedimiento.tomarPdfs();        // V49: ídem los PDF
        Object[] abrirDespues = null;
        List<Object[]> pdfs = List.of();
        try (java.sql.Connection c = db.openConnection()) {
            Map<String, Object> salida = OptimistProcedimientos.ejecutar(a.objeto(), args, c, mensajes);
            abrirDespues = OptimistProcedimiento.tomarTrnAAbrir();   // V48: ej. Generar Pedido abre el pedido creado
            pdfs = OptimistProcedimiento.tomarPdfs();                 // V49: ej. Imprimir Presupuesto
            // como el WorkWithPlus: el procedimiento deja su aviso en &WWPContext.MensajeError ("El Presupuesto 69 fue Cancelado")
            Object me = ctx == null ? null : ctx.valores().entrySet().stream().filter(x -> "MensajeError".equalsIgnoreCase(x.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (me instanceof String sm && !sm.isBlank()) { mensajes.add(sm.strip()); ctx.poner("MensajeError", ""); }
            OptimistProcedimiento p = OptimistProcedimientos.buscar(a.objeto());
            for (int i = 0; p != null && i < a.parametros().size() && i < p.parametros.size(); i++) {
                if (!a.parametros().get(i).trim().startsWith("&")) continue;
                Object v = salida.get(p.parametros.get(i).nombre().toLowerCase(Locale.ROOT));
                if (v instanceof String sv && !sv.isBlank()) mensajes.add(sv.strip());
            }
        } catch (SQLException | RuntimeException ex) {
            mensajes.add(a.titulo() + ": " + ex.getMessage());
            OptimistProcedimiento.tomarPdfs();
        }
        if (!pdfs.isEmpty()) {   // V49: la impresión se muestra (el último PDF que se generó); los avisos, si hay, igual
            Object[] ultimo = pdfs.get(pdfs.size() - 1);
            OptimistVisorPdf.abrir(a.titulo(), String.valueOf(ultimo[0]), (byte[]) ultimo[1]);
            if (!mensajes.isEmpty()) Notification.show(String.join("\n", mensajes), 8000, Notification.Position.BOTTOM_START);
            return;
        }
        cargar();
        if (abrirDespues != null && mensajes.isEmpty()) {   // el procedimiento pidió abrir una TRN (y no dejó un error)
            boolean ver = abrirDespues.length > 2 && abrirDespues[2] != null
                    && List.of("dsp", "display").contains(String.valueOf(abrirDespues[2]).trim().toLowerCase(Locale.ROOT));
            abrirTrn(String.valueOf(abrirDespues[0]), ver ? "Display" : "Update", abrirDespues[1]);   // V58: el modo que pidió
            return;
        }
        OptimistProcedimiento.tomarTrnAAbrir();
        Notification.show(mensajes.isEmpty() ? a.titulo() + ": listo." : String.join("\n", mensajes), mensajes.isEmpty() ? 3000 : 10000,
                Notification.Position.MIDDLE);
    }

    private void construir() {
        Nivel cab = trn.cabecera();
        pantalla = new OptimistWorkWith.Pantalla<>(this, new Grid<>(), trn.plural(), "+ Nuevo",
                this::nuevo, f -> trn.singular() + " " + clave(f),
                f -> abrir(f, false), f -> abrir(f, true), this::confirmarBorrado,
                new H1(trn.titulo()), new Span(OptimistDatabase.nombreSistema()));
        estiloRenglonWorkWith(cab);   // V61h
        // V77: la columna de la moneda de los totales filtra con un combo de las monedas (igual, no "contiene": "$" no trae "U$S")
        String monedaTot = trn.workwith().monedaTotales(cab);
        List<OptimistTrnDefinicion.Valor> monedasFiltro = monedaTot == null ? List.of() : datos.monedas();
        for (String n : trn.workwith().columnas()) {
            // V84: workwith.columnasSi: la columna va solo si su condición da verdadero (ej. el Saldo según VERCampos)
            String si = trn.workwith().condicionColumna(n);
            if (si != null && !cumple(si, Map.of())) continue;
            Campo c = cab.campo(n);
            // V47: clave interna de una tabla con código (ArticulosPK, EntidadesPK): se ve el código (lo trae listar())
            String kCod = OptimistTrnDatos.referenciaConCodigo(cab, c.nombre()) == null ? null : OptimistTrnDatos.claveCodigo(c.nombre());
            if (kCod != null) {
                pantalla.columna(c.nombre(), trn.workwith().titulo(c), f -> String.valueOf(f.getOrDefault(kCod, "")),
                        f -> f.getOrDefault(kCod, ""), null, null, trn.workwith().estilos().get(c.nombre()));
                continue;
            }
            // Excel: combos con su texto; números, fechas y el resto con su valor real (números sumables)
            java.util.function.Function<Map<String, Object>, Object> valorExcel = c.dominio().esCombo()
                    ? f -> OptimistTrnCampos.mostrar(c, f.get(c.nombre()))
                    : f -> f.get(c.nombre());
            String formato = c.dominio().esNumerico() && !c.dominio().esCombo()
                    ? (c.dominio().decimales() > 0 ? "#,##0." + "0".repeat(c.dominio().decimales()) : "0") : null;
            // V50e: columna con enlace: el valor es un vínculo que abre la otra TRN en Visualizar
            OptimistTrnDefinicion.Enlace en = OptimistTrnDefinicion.enlace(cab.tabla(), c.nombre());
            if (en != null) pantalla.enlaceColumna(c.nombre(), f -> hrefEnlace(en, c.nombre(), f));
            // V50: filtro por tipo: números (=, <, entre...) y fechas (desde - hasta)
            // V60: dominio enumerado (ClaseCbte, CPI, Status...): combo de selección múltiple con sus valores
            if (c.dominio().esCombo()) pantalla.filtroValores(c.nombre(), c.dominio().valores());
            else if (c.dominio().esNumerico()) pantalla.tipoFiltro(c.nombre(), "numero");
            else if (c.dominio().tipo() == OptimistTrnDefinicion.Tipo.DATE || c.dominio().tipo() == OptimistTrnDefinicion.Tipo.DATETIME)
                pantalla.tipoFiltro(c.nombre(), "fecha");
            else if (c.nombre().equalsIgnoreCase(monedaTot)) pantalla.filtroValores(c.nombre(), monedasFiltro);   // V77
            Grid.Column<Map<String, Object>> col = pantalla.columna(c.nombre(), trn.workwith().titulo(c),
                    f -> OptimistTrnCampos.mostrar(c, f.get(c.nombre())), valorExcel, formato,
                    c.insignia() == null ? null : f -> OptimistTrnCampos.colorInsignia(c, cab, f),   // V40: pastilla de color
                    trn.workwith().estilos().get(c.nombre()));   // V48e: clase del WorkWith
            dimensionarColumna(col, c, trn.workwith().titulo(c), trn.workwith().anchos().get(c.nombre()));
            if (trn.workwith().totaliza(c)) pantalla.totalizar(c.nombre(), formato);   // V75: total al pie
            // V50: el filtro por tipo necesita lugar: operador + valor (números) o desde + hasta (fechas)
            if (trn.workwith().anchos().get(c.nombre()) == null) {
                int min = c.dominio().esCombo() ? 140   // V60: el combo de selección múltiple
                        : c.dominio().esNumerico() ? 90
                        : c.dominio().tipo() == OptimistTrnDefinicion.Tipo.DATE || c.dominio().tipo() == OptimistTrnDefinicion.Tipo.DATETIME ? 130 : 0;
                String w = col.getWidth();
                int actual = w != null && w.endsWith("px") ? parseIntSeguro(w.substring(0, w.length() - 2), 0) : 0;
                if (min > 0 && actual > 0 && actual < min) col.setWidth(min + "px");
            }
        }
        pantalla.archivoExcel(trn.nombre());
        // V77: los totales por moneda (la columna ...MonedasId de la lista o la de workwith.totalesMoneda)
        if (monedaTot != null) pantalla.totalesMoneda(monedaTot, f -> f.get(monedaTot));
        // V50o: workwith.paginarEnServidor: la base devuelve solo la página visible (filtros y orden en el SQL)
        // V57: workwith.condicion (las vistas): una condición más en el WHERE, con las &Variables del inicio de las acciones
        if (trn.workwith().condicion() != null)
            pantalla.fuente(new OptimistWorkWith.Pantalla.Fuente<>() {
                @Override public int contar(Map<String, String> filtros) throws Exception { return datos.contar(trn, filtros, condicionSql()); }
                @Override public OptimistTrnDatos.Conteo contarYSumar(Map<String, String> filtros, java.util.Set<String> sumar, String moneda) throws Exception {
                    return datos.contarYSumar(trn, filtros, condicionSql(), sumar, moneda);
                }
                @Override public List<Map<String, Object>> pagina(Map<String, String> filtros, String orden, boolean desc, int desde, int cantidad)
                        throws Exception { return datos.listarPagina(trn, filtros, orden, desc, desde, cantidad, condicionSql()); }
            });
        else if (trn.workwith().paginarEnServidor())
            pantalla.fuente(new OptimistWorkWith.Pantalla.Fuente<>() {
                @Override public int contar(Map<String, String> filtros) throws Exception { return datos.contar(trn, filtros); }
                @Override public OptimistTrnDatos.Conteo contarYSumar(Map<String, String> filtros, java.util.Set<String> sumar, String moneda) throws Exception {
                    return datos.contarYSumar(trn, filtros, null, sumar, moneda);
                }
                @Override public List<Map<String, Object>> pagina(Map<String, String> filtros, String orden, boolean desc, int desde, int cantidad)
                        throws Exception { return datos.listarPagina(trn, filtros, orden, desc, desde, cantidad); }
            });
        // V57: sin "+ Nuevo" (workwith.nuevo: false) y los botones de arriba de la lista (workwith.botones)
        if (!trn.workwith().nuevo()) pantalla.nuevo.setVisible(false);
        int lugar = 1;
        // V59: un botón porRenglon corre para cada renglón tildado: la lista lleva un tilde por renglón
        if (trn.workwith().botones().stream().anyMatch(OptimistTrnDefinicion.AccionRenglon::porRenglon)) pantalla.seleccionMultiple();
        for (OptimistTrnDefinicion.AccionRenglon b : trn.workwith().botones()) {
            if (b.porRenglon() ? !posible(b.si()) : !cumple(b.si(), Map.of())) continue;   // V59: porRenglon: su si es por renglón
            Button bt = new Button(b.titulo(), OptimistWorkWith.iconoAccion(icono(b.icono())).create(), e -> boton(b));
            bt.getElement().setAttribute("title", b.titulo());
            pantalla.barraAcciones.addComponentAtIndex(Math.min(lugar++, pantalla.barraAcciones.getComponentCount()), bt);
        }
        pantalla.identificador(f -> claveDe(f));   // V32: el renglón trabajado sigue marcado al volver del formulario
        // V69: el puntito de los registros con PostIts (una consulta por página)
        if (!"postits".equalsIgnoreCase(trn.cabecera().tabla()) && OptimistDatabase.conPostIts()) {   // V83: sistema.postits=false
            Map<String, List<OptimistPostItsDatos.PostIt>> PostIts = new HashMap<>();
            pantalla.marcas(visibles -> {
                PostIts.clear();
                List<String> ids = new ArrayList<>();
                for (Map<String, Object> v : visibles) ids.add(OptimistPostItsDatos.idRegistro(trn.nombre(), clave(v)));
                try (java.sql.Connection c = db.openConnection()) { PostIts.putAll(OptimistPostItsDatos.leerVarios(c, ids)); }
                catch (SQLException | RuntimeException ex) { /* sin la tabla PostIts: sin marcas */ }
            }, v -> OptimistPostIts.marca(PostIts.get(OptimistPostItsDatos.idRegistro(trn.nombre(), clave(v)))));
        }
        // V48: acciones del renglón según condiciones (workwith.acciones, como el WorkWithPlus de GeneXus)
        OptimistTrnDefinicion.Acciones ac = trn.workwith().acciones();
        if (ac != null && !ac.equals(OptimistTrnDefinicion.Acciones.TODAS)) pantalla.acciones(f -> accionesDe(f, ac));
        // filasPorPagina del YAML no se aplica: manda la preferencia del usuario (igual que en Zonas y Países).
        cargar();
        OptimistShell.montar(this, pantalla.contenido, trn.titulo());
    }

    /** V57: un botón de arriba de la lista (workwith.botones): pregunta si tiene 'confirmar' y lo hace (no es de un renglón). */
    private void boton(OptimistTrnDefinicion.AccionRenglon b) {
        if (b.porRenglon()) { botonPorRenglon(b); return; }
        Runnable correr = () -> {
            if (!cumple(b.si(), Map.of())) { Notification.show("Ya no corresponde \"" + b.titulo() + "\".", 6000, Notification.Position.MIDDLE); return; }
            ejecutarAccion(b, Map.of());
        };
        if (b.confirmar() == null || b.confirmar().isBlank()) { correr.run(); return; }
        new ConfirmDialog(b.titulo(), b.confirmar(), "Sí", e -> correr.run(), "No", e -> { }).open();
    }

    /**
     * V59: botón porRenglon (ej. "Autorizar seleccionados", el For Each Line de GeneXus): corre el procedimiento para cada
     * renglón tildado. Antes de cada uno se vuelve a leer el registro y se controla la condición del botón (si cambió
     * mientras la lista estaba abierta, se saltea y se avisa). Al final, un solo aviso con lo que pasó y la lista al día.
     */
    private void botonPorRenglon(OptimistTrnDefinicion.AccionRenglon b) {
        List<Map<String, Object>> filas = pantalla.seleccionados();
        if (filas.isEmpty()) { Notification.show("Tildá los renglones para \"" + b.titulo() + "\".", 5000, Notification.Position.MIDDLE); return; }
        Runnable correr = () -> {
            List<String> mensajes = new ArrayList<>();
            int hechos = 0;
            OptimistContexto ctx = OptimistContexto.actual();
            for (Map<String, Object> fila : filas) {
                Map<String, Object> fresca = new HashMap<>(fila);
                try { fresca.putAll(datos.cargarCabecera(trn, claveDe(fila)).cabecera()); }
                catch (SQLException ex) { mensajes.add(capital(trn.singular()) + " " + clave(fila) + ": ya no está."); continue; }
                if (b.si() != null && !cumple(b.si(), fresca)) {
                    mensajes.add(capital(trn.singular()) + " " + clave(fila) + ": ya no corresponde (cambió mientras la lista estaba abierta).");
                    continue;
                }
                List<Object> args = new ArrayList<>();
                for (String p : b.parametros()) args.add(parametro(p, fresca));
                if (ctx != null) ctx.poner("MensajeError", "");
                OptimistProcedimiento.tomarTrnAAbrir();
                OptimistProcedimiento.tomarPdfs();
                try (java.sql.Connection c = db.openConnection()) {
                    List<String> m = new ArrayList<>();
                    OptimistProcedimientos.ejecutar(b.objeto(), args, c, m);
                    hechos++;
                    Object me = ctx == null ? null : ctx.valores().entrySet().stream().filter(x -> "MensajeError".equalsIgnoreCase(x.getKey()))
                            .map(Map.Entry::getValue).findFirst().orElse(null);
                    if (me instanceof String sm && !sm.isBlank()) m.add(sm.strip());
                    for (String x : m) mensajes.add(clave(fila) + ": " + x);
                } catch (SQLException | RuntimeException ex) {
                    mensajes.add(clave(fila) + ": " + ex.getMessage());
                } finally {
                    OptimistProcedimiento.tomarTrnAAbrir();
                    OptimistProcedimiento.tomarPdfs();
                    if (ctx != null) ctx.poner("MensajeError", "");
                }
            }
            cargar();
            String resumen = b.titulo() + ": " + hechos + " de " + filas.size() + " listo" + (filas.size() == 1 ? "" : "s") + ".";
            Notification.show(mensajes.isEmpty() ? resumen : resumen + "\n" + String.join("\n", mensajes),
                    mensajes.isEmpty() ? 4000 : 12000, Notification.Position.MIDDLE);
        };
        String pregunta = (b.confirmar() == null || b.confirmar().isBlank() ? "¿" + b.titulo() + "?" : b.confirmar())
                + " (" + filas.size() + " " + (filas.size() == 1 ? trn.singular() : trn.plural()) + ")";
        new ConfirmDialog(b.titulo(), pregunta, "Sí", e -> correr.run(), "No", e -> { }).open();
    }

    /**
     * V57: workwith.condicion como SQL: los campos guardados de la cabecera van con su columna (t0.), las &Variables del
     * inicio de las acciones como parámetros, true / false como 1 / 0; el resto (and, or, not, exists, subconsultas,
     * nombres después de un punto) queda igual. Lo que está entre comillas simples no se toca.
     */
    private OptimistTrnDatos.Extra condicionSql() {
        String c = trn.workwith().condicion();
        if (c == null) return null;
        Map<String, Object> vars = variablesAcciones();
        List<Object> ps = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int i = 0, n = c.length();
        while (i < n) {
            char ch = c.charAt(i);
            if (ch == '\'') {
                int j = c.indexOf('\'', i + 1);
                if (j < 0) j = n - 1;
                sb.append(c, i, j + 1);
                i = j + 1;
            } else if (ch == '&' && i + 1 < n && Character.isLetter(c.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(c.charAt(j)) || c.charAt(j) == '_')) j++;
                String var = c.substring(i + 1, j);
                if (!vars.containsKey(var)) throw new IllegalStateException("workwith.condicion: &" + var + " no está en el inicio de las acciones");
                sb.append('?');
                ps.add(vars.get(var));
                i = j;
            } else if (Character.isLetter(ch) || ch == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(c.charAt(j)) || c.charAt(j) == '_')) j++;
                String id = c.substring(i, j);
                int k = sb.length() - 1;
                while (k >= 0 && Character.isWhitespace(sb.charAt(k))) k--;
                boolean trasPunto = k >= 0 && sb.charAt(k) == '.';
                Campo campo = trasPunto ? null : trn.cabecera().campo(id);
                if (!trasPunto && id.equalsIgnoreCase("true")) sb.append('1');
                else if (!trasPunto && id.equalsIgnoreCase("false")) sb.append('0');
                else if (campo != null && campo.persistido()) sb.append("t0.").append(OptimistTrnDatos.col(campo.nombre()));
                else sb.append(id);
                i = j;
            } else { sb.append(ch); i++; }
        }
        return new OptimistTrnDatos.Extra(null, List.of(), Map.of(), sb.toString(), ps);
    }

    private void cargar() {
        if (encima) return;   // V50h: abajo está la lista de otra TRN
        if (pantalla.conFuente()) { pantalla.cargar(List.of()); return; }   // V50o: pide la página a la base
        try { pantalla.cargar(datos.listar(trn)); }
        catch (SQLException ex) { pantalla.cargar(List.of()); error(ex); }
    }

    private Map<String, Object> claveDe(Map<String, Object> fila) {
        return OptimistTrnDatos.subMapa(fila, trn.cabecera().claves());
    }
    private String clave(Map<String, Object> fila) {
        return OptimistTrnDatos.textoClave(trn.cabecera().claves(), fila);
    }
    /** Se muestra: no oculto y con su condición visibleSi cumplida. V34: las fórmulas también (solo lectura, en vivo). */
    private boolean visible(Campo c) {
        if (c.oculto()) return false;
        if (c.visibleSi() == null) return true;
        BooleanSupplier s = condiciones.get(c.visibleSi().toLowerCase(Locale.ROOT));
        return s != null && s.getAsBoolean();
    }
    /**
     * V49o: columna de un detalle. Una oculta (oculto: true) que tiene una regla "X.Visible = ..." igual se arma, escondida:
     * la regla la muestra según la instalación o el usuario (la configuración de columnas VERCampo, la rentabilidad).
     */
    private boolean visibleDet(Nivel det, Campo c) {
        if (visible(c)) return true;
        if (!c.oculto()) return false;
        try { return OptimistReglas.de(trn).controlaVisible(c.nombre()); } catch (RuntimeException e) { return false; }
    }
    private void dimensionar(Dialog dialogo) {
        OptimistVisuals.dimensionarFormulario(dialogo, pantalla.configuracion.pantallaCompleta(),
                trn.dosNiveles() || !trn.solapas().isEmpty() ? "min(1100px,96vw)" : "min(640px,96vw)");
    }

    /**
     * V33: arma un bloque de la pantalla. Panel = recuadro con título que se abre y se cierra con su ícono
     * (arranca como diga 'abierto'); renglón = sus bloques uno al lado del otro (en el celular bajan).
     */
    private Component armarBloque(Bloque b, Map<String, Component> piezas, Form f, Set<String> usados,
                                  Set<String> detUsados, Map<String, Component> detalles) {
        return switch (b) {
            case BloqueTabla bt -> tabla(bt.tabla(), piezas, f, usados);
            case BloqueDetalle bd -> {
                Nivel det = trn.detalle(bd.detalle());
                if (det == null || det.oculto() || !detUsados.add(det.nombre()) || detalles.get(det.nombre()) == null) yield new Div();
                Component c = detalles.get(det.nombre());
                f.bloquesDetalle.put(det.nombre().toLowerCase(Locale.ROOT), c);   // V65d: para la regla <Detalle>.Visible
                if (bd.sinTitulo()) { Component tt = f.titulosDetalle.get(det.nombre().toLowerCase(Locale.ROOT)); if (tt != null) tt.setVisible(false); }   // V65f
                if (bd.completo()) f.completos.add(det.nombre().toLowerCase(Locale.ROOT));   // las filas que se agreguen después, también
                if (bd.completo()) estirarDetalle(f, det);
                if (bd.alinear() != null) alinear(c, bd.alinear());
                yield c;
            }
            case Panel p -> {
                VerticalLayout contenido = new VerticalLayout();
                contenido.setPadding(false); contenido.setWidthFull();
                for (Bloque h : p.bloques()) contenido.add(armarBloque(h, piezas, f, usados, detUsados, detalles));
                com.vaadin.flow.component.details.Details panel =
                        new com.vaadin.flow.component.details.Details(p.titulo() == null ? "" : p.titulo(), contenido);
                panel.setOpened(p.abierto());
                panel.setWidthFull();
                panel.addClassName("optimist-panel");
                // V65d: un panel que solo tiene un detalle se oculta con él (regla <Detalle>.Visible = ...)
                if (p.bloques().size() == 1 && p.bloques().get(0) instanceof BloqueDetalle solo)
                    f.panelDeDetalle.put(solo.detalle().toLowerCase(Locale.ROOT), panel);
                if (p.ancho() != null) panel.getStyle().set("flex", "0 1 " + p.ancho()).set("width", p.ancho());
                if (p.alinear() != null) alinear(panel, p.alinear());   // V38
                if (p.ocultarVacio()) {   // V49t: el panel no se ve mientras sus detalles no tengan renglones
                    List<String> dets = new ArrayList<>();
                    detallesDe(p.bloques(), dets);
                    f.panelesVacios.put(panel, dets);
                    actualizarPanelesVacios(f);
                }
                yield panel;
            }
            case Renglon r -> {
                Div fila = new Div();
                fila.addClassName("optimist-renglon");
                fila.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "var(--optimist-form-gap, 12px)")
                        .set("align-items", "flex-start").set("width", "100%");
                for (Bloque h : r.bloques()) {
                    Component c = armarBloque(h, piezas, f, usados, detUsados, detalles);
                    if (!(h instanceof Panel p && p.ancho() != null)) c.getElement().getStyle().set("flex", "1 1 280px");
                    c.getElement().getStyle().set("min-width", "0");
                    fila.add(c);
                }
                yield fila;
            }
        };
    }

    private static void detallesDe(List<Bloque> bs, List<String> dets) {
        for (Bloque b : bs) {
            if (b instanceof BloqueDetalle bd) dets.add(bd.detalle());
            else if (b instanceof Panel p) detallesDe(p.bloques(), dets);
            else if (b instanceof Renglon r) detallesDe(r.bloques(), dets);
        }
    }

    /** V49t: los paneles 'ocultarVacio' se ven solo si alguno de sus detalles tiene un renglón con datos. */
    private void actualizarPanelesVacios(Form f) {
        f.panelesVacios.forEach((panel, dets) -> {
            boolean hay = false;
            for (String d : dets) {
                Nivel det = trn.detalle(d);
                if (det == null) continue;
                for (FilaDetalle fd : f.filas.getOrDefault(det.nombre(), List.of()))
                    if (fd.original() != null || !sinTocar(fd)) { hay = true; break; }
                if (hay) break;
            }
            panel.setVisible(hay);
        });
    }

    /** V38: el bloque toma el ancho de su contenido y va a la izquierda, al centro o a la derecha. */
    private static void alinear(Component c, String alinear) {
        String margen = switch (alinear) { case "centro" -> "0 auto"; case "derecha" -> "0 0 0 auto"; default -> "0 auto 0 0"; };
        c.getElement().getStyle().set("width", "fit-content").set("max-width", "100%").set("margin", margen)
                .set("align-self", switch (alinear) { case "centro" -> "center"; case "derecha" -> "flex-end"; default -> "flex-start"; });
    }

    /**
     * V38: <detalle X completo>: la grilla ocupa todo el ancho; cada columna crece en proporción a su ancho
     * (el título y las celdas con la misma regla, así siguen alineados). Las filas que se agreguen después, igual.
     */
    private void estirarDetalle(Form f, Nivel det) {
        Component h = f.encabezados.get(det.nombre());
        if (h instanceof HorizontalLayout enc) estirarRenglon(enc);
        for (FilaDetalle fd : f.filas.getOrDefault(det.nombre(), List.of())) {
            Component r = f.renglonDe.get(fd);
            if (r instanceof HorizontalLayout hr) estirarRenglon(hr);
        }
        Component g = f.grillas.get(det.nombre());
        if (g instanceof VerticalLayout vg) {
            vg.setWidthFull();
            if (vg.getParent().orElse(null) instanceof Div d) d.getStyle().set("overflow-x", "auto");
            vg.getChildren().forEach(x -> { if (x instanceof VerticalLayout v) v.setWidthFull(); });
        }
    }

    /** Cada pieza con ancho en px crece en proporción a ese ancho (flex-grow = px); la papelera queda fija. */
    private static void estirarRenglon(HorizontalLayout h) {
        h.setWidthFull();
        h.getChildren().forEach(c -> {
            String w = c.getElement().getStyle().get("min-width");
            if (w == null) w = c.getElement().getStyle().get("width");
            if (w == null || !w.endsWith("px")) return;
            if (c instanceof Button) return;   // la papelera
            int px;
            try { px = (int) Double.parseDouble(w.substring(0, w.length() - 2)); } catch (NumberFormatException e) { return; }
            c.getElement().getStyle().set("flex", px + " 1 " + w).set("min-width", w).set("max-width", "none");
        });
    }

    /**
     * V33: estilo del campo, con prioridad sobre Apariencia. Al control se le pasan las propiedades de Vaadin para
     * el valor y la etiqueta de arriba (--vaadin-input-field-*); a la etiqueta de la izquierda, el estilo directo.
     */
    static void aplicarEstilo(Campo c, com.vaadin.flow.component.Component control, com.vaadin.flow.component.html.Span etiqueta) {
        aplicarEstilo(c.estilo(), control, etiqueta);
    }

    static void aplicarEstilo(Map<String, String> estilo, com.vaadin.flow.component.Component control, com.vaadin.flow.component.html.Span etiqueta) {
        if (estilo.isEmpty()) return;
        if (etiqueta != null) {
            estilo.forEach((k, v) -> etiqueta.getStyle().set(k, v));
            if (estilo.containsKey("color")) etiqueta.getStyle().set("opacity", "1");
        }
        if (control != null) {
            var st = control.getElement().getStyle();
            estilo.forEach((k, v) -> {
                st.set(k, v);
                st.set("--vaadin-input-field-value-" + k, v);   // font-weight, font-style, font-size, font-family, color
                st.set("--vaadin-input-field-label-" + k, v);
                // V45: la letra del campo manda sobre la que eligió el usuario en Apariencia (--lumo-font-family)
                if (k.equals("font-family")) st.set("--lumo-font-family", v);
                // V47: en los campos de solo lectura (inferidos, Visualizar, Enabled = false de las reglas) Lumo pinta el
                // valor con --lumo-secondary-text-color y no con --vaadin-input-field-value-color: el color quedaba solo en la etiqueta
                if (k.equals("color")) st.set("--lumo-body-text-color", v).set("--lumo-secondary-text-color", v);
            });
        }
    }

    /**
     * V32: ancho de cada columna del WorkWith según su dominio (antes todas iguales, con espacio desperdiciado).
     * Con 'anchos:' en el YAML manda ese valor. Los textos largos absorben el espacio que sobra y, si no entran,
     * siguen en otro renglón. Los números van alineados a la derecha.
     */
    static void dimensionarColumna(Grid.Column<?> col, Campo c, String titulo, String anchoYaml) {
        OptimistTrnDefinicion.Dominio d = c.dominio();
        int caracteres;
        if (d.esCombo()) caracteres = d.valores().stream().mapToInt(v -> v.texto() == null ? 0 : v.texto().length()).max().orElse(8);
        else caracteres = switch (d.tipo()) {
            case DATE -> 10;
            case DATETIME -> 16;
            case BOOLEAN -> 3;
            case NUMERIC -> d.longitud() + (d.longitud() > 3 ? (d.longitud() - 1) / 3 : 0) + (d.decimales() > 0 ? 1 : 0);
            default -> Math.min(d.longitud(), 45);
        };
        int px = Math.max(caracteres * 8, Math.min(titulo == null ? 0 : titulo.length() * 8, 160)) + 32;
        px = Math.max(70, Math.min(px, 380));
        boolean textoLargo = d.esTexto() && !d.esCombo() && d.longitud() >= 30;
        col.setAutoWidth(false);
        col.setWidth(anchoYaml != null ? anchoYaml : px + "px");
        // V66l: lo que sobra de la pantalla se reparte entre las columnas en proporción a su ancho (antes solo los textos largos)
        col.setFlexGrow(OptimistWorkWith.crecimiento(anchoYaml != null ? anchoYaml : px + "px"));
        if (d.esNumerico() && !d.esCombo()) col.setTextAlign(com.vaadin.flow.component.grid.ColumnTextAlign.END);
    }

    // ------------------------------------------------------------------ alta / modificación / visualización

    private void abrir(Map<String, Object> fila, boolean soloVer) {
        try { formulario(datos.cargar(trn, claveDe(fila)), soloVer); }
        catch (SQLException ex) { error(ex); }
        catch (RuntimeException ex) {
            // V32: antes un error que no era de MySQL no mostraba nada (el formulario simplemente no abría)
            ex.printStackTrace();
            Notification.show("No se pudo abrir " + trn.singular() + ": " + ex, 15000, Notification.Position.MIDDLE);
        }
    }

    /** Estado del formulario abierto: editores de la cabecera y filas de cada detalle. */
    /** V83: un archivo elegido en el formulario (nombre sin extensión, extensión y contenido; contenido null = quitarlo). */
    private record ArchivoNuevo(String nombre, String tipo, byte[] contenido) {}

    private static final class Form {
        final boolean nuevo, soloVer;
        Map<String, Object> enBase;   // V48c: la cabecera como estaba en la base al terminar de abrir (control de concurrencia)
        final List<String> avisosAlGrabar = new ArrayList<>();   // V82e: Msg() de las AfterLevel que corren con lo grabado
        Map<String, Object> grabada;  // V65k: la cabecera recién grabada (con su clave), para las reglas de AfterComplete
        /** V83: archivos (Blob) elegidos o quitados en este formulario: se graban al Confirmar. Contenido null = quitar. */
        final Map<String, ArchivoNuevo> archivos = new LinkedHashMap<>();
        final Registro actual;
        final Map<String, OptimistTrnCampos.Editor> cab = new LinkedHashMap<>();
        final Map<String, List<FilaDetalle>> filas = new LinkedHashMap<>();
        Dialog dialogo;
        /** Últimos valores inferidos traídos (sirven al título aunque no estén en pantalla). */
        final Map<String, Object> inferidos = new HashMap<>();
        /** V34: se están recalculando las fórmulas (evita recalcular desde adentro del recálculo). */
        boolean calculando;
        /** V35: reglas de la TRN (null si no tiene), su estado (variables, modo) y los valores al abrir (GetOldValue). */
        OptimistReglas reglas;
        OptimistReglas.Estado estado;
        Map<String, Object> viejos = Map.of();
        /** V35: si el editor era de solo lectura antes de las reglas (Enabled = true no habilita lo que no se edita). */
        final Map<OptimistTrnCampos.Editor, Boolean> soloLecturaBase = new IdentityHashMap<>();
        /** V35: visibilidad de las columnas de los detalles que decidieron las reglas (detalle.campo -> visible). */
        final Map<String, Boolean> columnas = new HashMap<>();
        final Map<String, Component> titulosColumna = new HashMap<>();
        final Set<String> mensajesVistos = new HashSet<>();
        boolean aplicandoReglas;
        /** V48: líneas nuevas en las que ya corrieron todas las reglas del nivel (la primera vez que se toca la línea). */
        final Set<Object> lineasIniciadas = Collections.newSetFromMap(new IdentityHashMap<>());
        /** V36: cálculo comercial de la TRN (sección 'calculo:'; null si no tiene) y decimales de los precios. */
        OptimistComercial comercial;
        Integer decimales;
        /** V36: lo leído de la base mientras se edita (parámetros, artículo, tasa...): cada cambio es inmediato. */
        final OptimistLecturas lecturas = new OptimistLecturas();
        /** V38: piezas de cada detalle, para estirarlo a todo el ancho (<detalle X completo>). */
        final Set<String> completos = new HashSet<>();
        final Map<String, Component> encabezados = new HashMap<>(), grillas = new HashMap<>();
        final Map<FilaDetalle, Component> renglonDe = new IdentityHashMap<>();
        /** V44: la etiqueta de la izquierda de cada campo de la cabecera (una regla .Visible la oculta junto con el campo). */
        final Map<OptimistTrnCampos.Editor, Component> etiquetas = new IdentityHashMap<>();
        /** V49t: la clase que puso una regla X.Class en cada campo, y las propiedades de estilo que le agregó. */
        final Map<OptimistTrnCampos.Editor, String> claseActual = new IdentityHashMap<>();
        /** V49t: paneles 'ocultarVacio' y los detalles que tienen adentro. */
        final Map<Component, List<String>> panelesVacios = new IdentityHashMap<>();
        /** V65d: el bloque de cada detalle y el panel que solo lo tiene a él (regla <Detalle>.Visible). */
        final Map<String, Component> bloquesDetalle = new HashMap<>(), panelDeDetalle = new HashMap<>();
        /** V65f: el título (H3) de cada detalle, para <detalle X sinTitulo>. */
        final Map<String, Component> titulosDetalle = new HashMap<>();
        final Map<OptimistTrnCampos.Editor, Set<String>> estiloDeRegla = new IdentityHashMap<>();
        Form(Registro actual, boolean soloVer) { this.actual = actual; this.nuevo = actual == null; this.soloVer = soloVer; }
        String modo() { return nuevo ? "INS" : soloVer ? "DSP" : "UPD"; }

        /** Valores actuales de la cabecera: lo cargado + lo editado. */
        Map<String, Object> valoresCabecera() {
            Map<String, Object> v = new LinkedHashMap<String, Object>(nuevo ? Map.<String, Object>of() : actual.cabecera());
            v.putAll(inferidos);
            for (OptimistTrnCampos.Editor ed : cab.values()) v.put(ed.campo().nombre(), ed.leer().get());
            return v;
        }
    }

    /** inferidos: lo que trajeron las referencias de la fila, aunque no se vea (lo usan las fórmulas). */
    private record FilaDetalle(Linea original, Map<String, OptimistTrnCampos.Editor> editores, Map<String, Object> inferidos) {
        /** Valores actuales de la fila: lo cargado + lo traído por las referencias + lo editado. */
        Map<String, Object> valores() {
            Map<String, Object> v = new LinkedHashMap<String, Object>(original == null ? Map.<String, Object>of() : original.valores());
            v.putAll(inferidos);
            for (OptimistTrnCampos.Editor ed : editores.values()) v.put(ed.campo().nombre(), ed.leer().get());
            return v;
        }
    }

    /** Fila nueva que el usuario no tocó: cada campo editable está vacío o sigue con su valor por defecto. */
    /**
     * V50z: renglón nuevo, o ya grabado con algún campo editable distinto de lo grabado. Al Confirmar, GeneXus pasa por las
     * reglas del renglón (y las on BeforeValidate / BeforeUpdate) solo esos: un renglón que no se tocó no se vuelve a validar
     * (ej. el control de cambio de precio, parámetro 187, en el pedido recién generado desde un presupuesto).
     */
    private static boolean modificada(FilaDetalle fd) {
        if (fd.original() == null) return true;
        Map<String, Object> o = fd.original().valores();
        for (OptimistTrnCampos.Editor ed : fd.editores().values())
            if (ed.campo().editable()) {
                Object antes = o.get(ed.campo().nombre()), ahora = ed.leer().get();
                boolean vacios = OptimistProcedimiento.Funciones.vacioGx(antes) && OptimistProcedimiento.Funciones.vacioGx(ahora);
                if (!vacios && !OptimistTrnDatos.igual(antes, ahora)) return true;
            }
        return false;
    }

    /**
     * V65: renglón nuevo de un detalle con 'siguiente: true' en su clave: al empezar a cargarlo (un cambio en otro campo)
     * la clave toma el mayor de la grilla + 1, si está vacía. Se puede cambiar.
     */
    private void numerarFila(Form f, Nivel det, FilaDetalle fila) {
        if (det == null || fila == null || fila.original() != null) return;
        for (Campo k : det.claves()) {
            if (!OptimistTrnDefinicion.siguiente(det.tabla(), k.nombre())) continue;
            OptimistTrnCampos.Editor ed = fila.editores().get(k.nombre());
            if (ed == null || !ed.vacio()) continue;
            if (fila.editores().values().stream().filter(x -> x != ed && x.campo().editable()).allMatch(OptimistTrnCampos.Editor::vacio)) continue;
            long max = 0;
            for (FilaDetalle otra : f.filas.getOrDefault(det.nombre(), List.of())) {
                Object v = otra.original() != null ? otra.original().valores().get(k.nombre()) : null;
                OptimistTrnCampos.Editor oe = otra.editores().get(k.nombre());
                if (oe != null && !oe.vacio()) v = oe.leer().get();
                if (v instanceof Number n) max = Math.max(max, n.longValue());
                else if (v != null) try { max = Math.max(max, new java.math.BigDecimal(String.valueOf(v).trim()).longValue()); } catch (NumberFormatException ignorado) { }
            }
            boolean antes = f.aplicandoReglas;
            f.aplicandoReglas = true;   // que escribir la clave no vuelva a disparar el cambio del renglón
            try { ed.escribir().accept(OptimistTrnDefinicion.normalizar(k.dominio(), max + 1)); }
            finally { f.aplicandoReglas = antes; }
        }
    }

    private static boolean sinTocar(FilaDetalle fd) {
        // V65: la clave que numera el motor ('siguiente: true') no cuenta: un renglón con solo su número sigue vacío
        return fd.editores().values().stream().filter(x -> x.campo().editable())
                .filter(x -> !(x.campo().clave() && fd.original() == null && OptimistTrnDefinicion.siguienteCampo(x.campo())))
                .allMatch(x -> x.vacio()
                || (x.campo().defectoActual() != null && OptimistTrnDatos.igual(x.campo().defectoActual(), x.leer().get()))
                // V65: la fecha que arrancó en hoy tampoco cuenta (el renglón vacío sigue vacío)
                || (fd.original() == null && fechaDeHoy(x.campo()) && java.time.LocalDate.now().equals(x.leer().get())));
    }

    /** V65: fecha editable sin 'default': en el alta (cabecera o renglón nuevo) arranca en la fecha de hoy. */
    private static boolean fechaDeHoy(Campo c) {
        return c.editable() && c.defectoActual() == null && c.dominio().tipo() == OptimistTrnDefinicion.Tipo.DATE;
    }

    /** Título del formulario: el 'encabezado' del YAML con los valores actuales, o singular + clave. */
    private String titulo(Form f) {
        String base = f.soloVer ? "Visualizar " + trn.singular() : capital(trn.singular());
        Map<String, Object> v = f.valoresCabecera();
        if (trn.encabezado() != null) {
            boolean alguno = false;
            StringBuilder sb = new StringBuilder();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^}]*)}").matcher(trn.encabezado());
            while (m.find()) {
                Campo c = trn.cabecera().campo(m.group(1).trim());
                String txt = OptimistTrnCampos.mostrarEncabezado(c, v.get(c.nombre()));
                if (!txt.isBlank()) alguno = true;
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(txt));
            }
            m.appendTail(sb);
            if (alguno) return (f.soloVer ? "Visualizar: " : "") + sb.toString().trim();
        }
        if (f.nuevo) return "Nuev" + trn.genero() + " " + trn.singular();
        return base + " " + clave(f.actual.cabecera());
    }

    private void formulario(Registro actual, boolean soloVer) {
        Form f = new Form(actual, soloVer);
        Nivel cab = trn.cabecera();
        Dialog dialogo = new Dialog();
        OptimistVisuals.estilizarDialogo(dialogo);
        dimensionar(dialogo);
        f.dialogo = dialogo;

        // --- editores de la cabecera (todos los visibles; después se reparten en solapas)
        Map<String, Component> piezas = new LinkedHashMap<>();
        // los campos de tablas con etiqueta a la izquierda se crean sin etiqueta propia (la pone la tabla)
        Set<String> etiquetaAparte = new HashSet<>();
        List<Tabla> todasLasTablas = new ArrayList<>(trn.tablas());
        trn.solapas().forEach(s -> todasLasTablas.addAll(s.tablas()));
        for (Tabla t : todasLasTablas) if (t.etiquetaIzquierda()) etiquetaAparte.addAll(t.campos());
        for (Campo c : cab.campos()) {
            if (!visible(c)) continue;
            OptimistTrnCampos.Editor ed = conCodigo(crearEditor(cab, c, etiquetaAparte.contains(c.nombre()), false),
                    cab, f::valoresCabecera, f.nuevo ? Map.of() : actual.cabecera());   // V47: se ve el código
            if (f.nuevo) {
                Object inicial = c.defectoActual();
                if (inicial == null && !soloVer && OptimistTrnDefinicion.siguiente(cab.tabla(), c.nombre())) inicial = datos.siguiente(cab, c);   // V65
                // V65: fecha editable sin 'default': en el alta arranca en la fecha de hoy
                if (inicial == null && !soloVer && c.editable() && c.dominio().tipo() == OptimistTrnDefinicion.Tipo.DATE) inicial = java.time.LocalDate.now();
                if (inicial != null) ed.escribir().accept(inicial);
            }
            else ed.escribir().accept(actual.cabecera().get(c.nombre()));
            boolean ro = soloVer || !c.editable() || (!f.nuevo && (c.clave() || !c.editableEnModificacion()));
            ed.soloLectura().accept(ro);
            if (c.autonumerico() && f.nuevo) ed.componente().getElement().setProperty("placeholder", "automático");
            aplicarEstilo(c, ed.componente(), null);   // V33: estilo del campo (negrita, itálica, tamaño, color)
            f.cab.put(c.nombre(), ed);
            f.soloLecturaBase.put(ed, ro);
            // V35: cada cambio: referencias, reglas y fórmulas (en ese orden)
            if (!ro) ed.alCambiar().accept(() -> cambioCabecera(f, c.nombre()));
            OptimistTrnDefinicion.LupaProc lpc = OptimistTrnDefinicion.lupaProc(cab.tabla(), c.nombre());   // V65: también en la cabecera
            piezas.put(c.nombre(), lpc != null ? (ro ? ed.componente() : conLupaProcCabecera(f, ed, lpc))
                    : conBuscador(ed, cab, null, !ro, () -> cambioCabecera(f, c.nombre()), f::valoresCabecera, f.cab::get));
            OptimistTrnDefinicion.Enlace destino = OptimistTrnDefinicion.enlace(cab.tabla(), c.nombre());
            if (destino != null) enlazar(f, ed, destino);   // V50c: el pedido generado -> abre el PED
        }

        // --- título (encabezado): valores iniciales y actualización en vivo
        for (String n : trn.camposEncabezado()) {
            OptimistTrnCampos.Editor ed = f.cab.get(trn.cabecera().campo(n).nombre());
            if (ed != null && ed.campo().editable()) ed.alCambiar().accept(() -> dialogo.setHeaderTitle(titulo(f)));
        }
        dialogo.setHeaderTitle(titulo(f));
        // V48: ayuda del formulario (ayuda/<TRN>.md), si la hay (la ventana es modal: la de la barra de arriba no se puede tocar)
        Button ayudaTrn = OptimistAyuda.boton(trn.nombre());
        if (ayudaTrn != null) dialogo.getHeader().add(ayudaTrn);
        // V66k: las lupas 'ver:' de la cabecera (ej. Asientos: "Ver imputaciones del asiento"), arriba del formulario
        if (!f.nuevo)
            for (OptimistTrnDefinicion.Ver v : OptimistTrnDefinicion.ver(trn.nombre(), trn.cabecera().nombre())) {
                if (!cumple(v.si(), f.valoresCabecera())) continue;
                Button bv = new Button(v.titulo(), VaadinIcon.SEARCH.create(), e -> verConsulta(f, dialogo, v, f.valoresCabecera()));
                bv.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
                bv.getElement().setAttribute("title", v.titulo());
                dialogo.getHeader().add(bv);
            }

        // V65: en el alta, los campos de la cabecera sin editor (ocultos) con 'default' ya tienen su valor mientras se carga:
        // las reglas y las lupas los usan (MovStockEmpresa = &EmpStk -> STAlmacenesEmpresa = MovStockEmpresa). Antes recién al grabar.
        if (f.nuevo)
            for (Campo c : cab.campos())
                if (!f.cab.containsKey(c.nombre()) && c.persistido() && c.defectoActual() != null) f.inferidos.putIfAbsent(c.nombre(), c.defectoActual());

        // --- bloques de detalle
        Map<String, Component> bloques = new LinkedHashMap<>();
        for (Nivel det : trn.detallesVisibles()) bloques.put(det.nombre(), bloqueDetalle(f, det));

        // --- armado: con solapas o todo junto
        Component cuerpo;
        if (trn.solapas().isEmpty()) {
            VerticalLayout v = new VerticalLayout();
            Set<String> detUsadosRaiz = new HashSet<>();
            if (!trn.bloques().isEmpty()) {
                // V33: la pantalla en orden, con paneles y renglones
                Set<String> usados = new HashSet<>();
                for (Bloque b : trn.bloques()) v.add(armarBloque(b, piezas, f, usados, detUsadosRaiz, bloques));
                List<Component> resto = new ArrayList<>();
                piezas.forEach((n, p) -> { if (!usados.contains(n)) resto.add(p); });
                if (!resto.isEmpty()) v.add(grupoCampos(resto));
            } else if (trn.filas().isEmpty() && trn.tablas().isEmpty()) v.add(grupoCampos(piezas.values()));
            else {
                Set<String> usados = new HashSet<>();
                for (Tabla t : trn.tablas()) v.add(tabla(t, piezas, f, usados));
                v.add(renglones(trn.filas(), piezas, usados));
                List<Component> resto = new ArrayList<>();
                piezas.forEach((n, p) -> { if (!usados.contains(n)) resto.add(p); });
                if (!resto.isEmpty()) v.add(grupoCampos(resto));
            }
            v.setPadding(false);
            v.setWidthFull();
            bloques.forEach((n, b) -> { if (!detUsadosRaiz.contains(n)) v.add(b); });
            cuerpo = v;
        } else {
            TabSheet solapas = new TabSheet();
            solapas.setWidthFull();
            Set<String> usados = new HashSet<>();
            Set<String> detUsados = new HashSet<>();
            for (Solapa s : trn.solapas()) {
                VerticalLayout v = new VerticalLayout();
                v.setPadding(false);
                v.setWidthFull();
                if (!s.bloques().isEmpty()) {
                    // V33: en orden (tablas, detalles, paneles, renglones)
                    for (Bloque b : s.bloques()) v.add(armarBloque(b, piezas, f, usados, detUsados, bloques));
                    solapas.add(s.titulo(), v);
                    continue;
                }
                for (Tabla t : s.tablas()) v.add(tabla(t, piezas, f, usados));
                if (!s.filas().isEmpty()) v.add(renglones(s.filas(), piezas, usados));
                else {
                    List<Component> cs = new ArrayList<>();
                    for (String n : s.campos()) {
                        String nombre = cab.campo(n).nombre();
                        Component p = piezas.get(nombre);
                        if (p != null && usados.add(nombre)) cs.add(p);
                    }
                    if (!cs.isEmpty()) v.add(grupoCampos(cs));
                }
                for (String d : s.detalles()) {
                    Nivel det = trn.detalle(d);
                    if (det.oculto()) continue;   // V27: detalle oculto
                    if (detUsados.add(det.nombre())) v.add(bloques.get(det.nombre()));
                }
                solapas.add(s.titulo(), v);
            }
            // lo visible que no quedó en ninguna solapa no se pierde: va a "Otros datos"
            List<Component> resto = new ArrayList<>();
            piezas.forEach((n, p) -> { if (!usados.contains(n)) resto.add(p); });
            VerticalLayout otros = new VerticalLayout();
            otros.setPadding(false);
            if (!resto.isEmpty()) otros.add(grupoCampos(resto));
            bloques.forEach((n, b) -> { if (!detUsados.contains(n)) otros.add(b); });
            if (otros.getComponentCount() > 0) solapas.add("Otros datos", otros);
            cuerpo = solapas;
        }
        dialogo.add(cuerpo);
        if (!trn.archivos().isEmpty()) dialogo.add(panelArchivos(f, soloVer));   // V83: archivos (Blob)

        if (soloVer) {
            OptimistVisuals.pieIzquierda(dialogo, new Button("Cerrar", e -> dialogo.close()));
        } else {
            Button guardar = new Button("Confirmar", e -> {
                // V48c: si otro usuario u otra pantalla lo modificó mientras estaba abierto, no se pisa lo que grabó
                if (!f.nuevo) {
                    String cambio = cambioAjeno(f, actual);
                    if (cambio != null) { Notification.show(cambio, 15000, Notification.Position.MIDDLE); return; }
                }
                // V35: reglas antes de grabar (Error frena el grabado)
                // V36: al grabar se lee todo de nuevo (caché vacío), con una sola conexión
                // V65k: las reglas de antes, el grabado y las de AfterInsert / BeforeComplete son UNA transacción: si algo
                // falla (un Error de una regla, un procedimiento, una referencia), no queda nada grabado a medias
                try {
                    Map<String, Object> clave = OptimistLecturas.enTransaccion(() -> {
                        List<String> errRegla;
                        try { errRegla = reglasAlGrabar(f); }
                        catch (RuntimeException ex) {   // V48: antes un error inesperado acá no mostraba nada (Confirmar "no hacía nada")
                            ex.printStackTrace();
                            throw new IllegalArgumentException("(reglas) " + ex, ex);
                        }
                        if (!errRegla.isEmpty()) throw new IllegalArgumentException(String.join(" · ", errRegla));
                        Map<String, Object> valores = new LinkedHashMap<String, Object>(f.nuevo ? Map.<String, Object>of() : actual.cabecera());
                        valores.putAll(f.inferidos);   // V34: para las fórmulas que se graban (no se graban ellos)
                        for (OptimistTrnCampos.Editor ed : f.cab.values())
                            if (ed.campo().persistido()) valores.put(ed.campo().nombre(), ed.leer().get());
                        Map<String, List<Linea>> detalles = new LinkedHashMap<>();
                        for (Nivel det : trn.detallesVisibles()) {
                            List<Linea> lineas = new ArrayList<>();
                            for (FilaDetalle fd : f.filas.get(det.nombre())) {
                                if (fd.original() == null && sinTocar(fd)) continue;
                                Map<String, Object> v = new LinkedHashMap<String, Object>(fd.original() == null ? Map.<String, Object>of() : fd.original().valores());
                                v.putAll(fd.inferidos());   // V34: para las fórmulas que se graban
                                for (OptimistTrnCampos.Editor ed : fd.editores().values())
                                    if (ed.campo().persistido()) v.put(ed.campo().nombre(), ed.leer().get());
                                lineas.add(new Linea(v, fd.original() == null ? null : fd.original().claveOriginal()));
                            }
                            detalles.put(det.nombre(), lineas);
                        }
                        Map<String, Object> k = datos.guardar(trn, new Registro(valores, detalles), f.nuevo, OptimistShell.usuario(), enganche(f));
                        Map<String, Object> cabGrabada = new LinkedHashMap<>(valores);
                        if (k != null) cabGrabada.putAll(k);
                        f.grabada = cabGrabada;
                        return k;
                    });
                    f.avisosAlGrabar.forEach(x -> Notification.show(x, 8000, Notification.Position.MIDDLE));   // V82e
                    f.avisosAlGrabar.clear();
                    if (f.reglas != null) {   // V35: on AfterComplete (ya grabado y confirmado, como en GeneXus)
                        Map<String, Object> cabGrabada = f.grabada;
                        OptimistReglas.Efecto fin = f.reglas.aplicar(OptimistReglas.Momento.GRABAR, Set.of("aftercomplete"), null, cabGrabada, null,
                                f.viejos, new HashSet<>(), f.estado, datos.abridor(), null);
                        fin.errores().forEach(x -> Notification.show(x, 8000, Notification.Position.MIDDLE));
                    }
                    // V65l: un alta confirmada: su paso del historial pasa de ?nuevo a ?abrir=<clave> (Adelante abre lo grabado, no un alta vacía)
                    if (f.nuevo && ruta != null && clave != null && trn.cabecera().claves().size() == 1) {
                        String kn = trn.cabecera().claves().get(0).nombre();
                        Object k = clave.entrySet().stream().filter(x -> x.getKey().equalsIgnoreCase(kn)).map(Map.Entry::getValue).findFirst().orElse(null);
                        if (k != null) UI.getCurrent().getPage().getHistory().replaceState(null,
                                new com.vaadin.flow.router.Location("trn/" + ruta, com.vaadin.flow.router.QueryParameters.of("abrir", String.valueOf(k))));
                    }
                    dialogo.close();
                    cargar();
                    Notification.show(capital(trn.singular()) + " guardad" + trn.genero());
                } catch (SQLException | IllegalArgumentException ex) {
                    Notification.show("No se pudo guardar: " + ex.getMessage(), 9000, Notification.Position.MIDDLE);
                } catch (RuntimeException ex) {   // V48: idem, cualquier otro error se muestra
                    ex.printStackTrace();
                    Notification.show("No se pudo guardar: " + ex, 15000, Notification.Position.MIDDLE);
                }
            });
            guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            OptimistVisuals.pieIzquierda(dialogo, new Button("Cancelar", e -> dialogo.close()), guardar);
        }
        f.lecturas.hacer(() -> {
            long a0 = System.nanoTime();
            iniciarReglas(f);   // V35: evento Start (variables) y reglas de visibilidad / habilitación
            long a1 = System.nanoTime();
            recalcular(f);   // V34: totales y demás fórmulas desde que se abre
            long a2 = System.nanoTime();
            // V49w: si abrir tarda, en la consola queda en qué parte (y las fórmulas lentas, en el aviso de OptimistCalculo)
            if ((a2 - a0) / 1_000_000 >= 1500)
                System.out.println("[" + trn.nombre() + "] abrir: inicio y reglas " + (a1 - a0) / 1_000_000 + " ms, fórmulas "
                        + (a2 - a1) / 1_000_000 + " ms (" + f.lecturas.resumen() + "). Las consultas que más tardaron:" + f.lecturas.detalle(12));
            // V49t: con los totales ya calculados, otra vez lo que solo pinta (Visible / Enabled / Class): PRSRentabilidad.Class usa la rentabilidad
            if (f.reglas != null) {
                f.aplicandoReglas = true;
                try {
                    aplicarEfecto(f, null, null, f.reglas.aplicar(OptimistReglas.Momento.PANTALLA, null, null, f.valoresCabecera(), null, f.viejos,
                            new HashSet<>(), f.estado, datos.abridor(), null));
                } catch (RuntimeException ex) { System.out.println("[" + trn.nombre() + "] " + ex.getMessage()); }
                finally { f.aplicandoReglas = false; }
            }
        });
        // V48c: después del inicio (que puede grabar algo), cómo quedó en la base: al confirmar se compara con esto
        if (!f.nuevo && !soloVer && actual != null)
            try { f.enBase = datos.cargarCabecera(trn, claveDe(actual.cabecera())).cabecera(); } catch (SQLException ignorado) { }
        pintarInsignias(f);   // V40
        historial(dialogo, actual, soloVer);   // V48d
        // V69: los PostIts del registro (y el botón para pegar uno), si ya está grabado
        if (!f.nuevo && actual != null && !"postits".equalsIgnoreCase(trn.cabecera().tabla()))
            OptimistPostIts.enFormulario(db, dialogo, trn.nombre(), clave(actual.cabecera()));
        dialogo.open();
    }

    /**
     * V48d: el formulario como un paso del historial. Al abrirlo se agrega la dirección trn/x?abrir=clave (o ?ver=, ?nuevo);
     * al cerrarlo con sus botones se vuelve atrás; con el atrás del navegador, beforeEnter lo cierra.
     */
    private void historial(Dialog dialogo, Registro actual, boolean soloVer) {
        if (ruta == null) return;
        OptimistVisuals.sinPasoAtras(dialogo);   // V65: el formulario tiene su paso con dirección
        formulariosAbiertos.add(dialogo);
        com.vaadin.flow.component.page.History h = UI.getCurrent().getPage().getHistory();
        boolean agregado = false;
        if (!formularioEnUrl) {
            com.vaadin.flow.router.QueryParameters q = null;
            if (actual == null) q = com.vaadin.flow.router.QueryParameters.of("nuevo", "1");
            else if (trn.cabecera().claves().size() == 1) {
                Object k = actual.cabecera().get(trn.cabecera().claves().get(0).nombre());
                if (k != null) q = com.vaadin.flow.router.QueryParameters.of(soloVer ? "ver" : "abrir", String.valueOf(k));
            }
            if (q != null) {
                com.vaadin.flow.router.Location lf = new com.vaadin.flow.router.Location("trn/" + ruta, q);
                h.pushState(null, lf); agregado = true;
                OptimistVisorPdf.ubicacion(lf);   // V84h: el Atrás con una lupa abierta sobre el registro vuelve acá
            }
        }
        boolean agregadoFinal = agregado;
        dialogo.addOpenedChangeListener(ev -> {
            if (ev.isOpened()) return;
            formulariosAbiertos.remove(dialogo);
            if (cerrandoPorNavegacion || OptimistVisuals.navegando()) return;   // V65l: el navegador ya está en otra dirección
            if (agregadoFinal) h.back();   // saca el paso del formulario: el navegador queda en la lista
            else if (formularioEnUrl && llegoDesdeApp) { formularioEnUrl = false; h.back(); }   // V50f: vuelve a la pantalla que lo abrió
            else if (formularioEnUrl) {
                formularioEnUrl = false;
                // V50g: si la página se cargó entera desde otra de OPTIMIST (un vínculo que recargó), igual se vuelve ahí;
                // si se escribió la dirección o es F5, queda la lista de esta TRN
                String rutaLista = "trn/" + ruta;
                UI ui = UI.getCurrent();
                ui.getPage().executeJs("if (document.referrer && document.referrer.startsWith(location.origin) && history.length > 1)"
                        + " { history.back(); return true; } return false;")
                        .then(Boolean.class, volvio -> { if (!Boolean.TRUE.equals(volvio)) h.replaceState(null, new com.vaadin.flow.router.Location(rutaLista)); });
            }
        });
    }

    /**
     * Tabla estilo GeneXus: grilla CSS con columnas de ancho fijo; con etiquetas a la izquierda cada
     * columna lógica son dos columnas reales (etiqueta | campo). Los campos no visibles dejan la celda vacía.
     */
    private Component tabla(Tabla t, Map<String, Component> piezas, Form f, Set<String> usados) {
        int n = t.cantidadColumnas();
        // V29: mínimo de cada columna de campos según los campos que tiene (64..100 px): un campo nunca se aplasta;
        // si no entra todo, aparece la barra horizontal.
        int[] minimo = new int[n];
        java.util.Arrays.fill(minimo, 64);
        for (List<Celda> fila : t.filas()) {
            int col = 0;
            for (Celda celda : fila) {
                if (celda.span() == 1 && col < n && celda.campo() != null && f.cab.containsKey(celda.campo())) {
                    String an = OptimistTrnCampos.ancho(f.cab.get(celda.campo()).campo());
                    int px = an.endsWith("px") ? parseIntSeguro(an.substring(0, an.length() - 2), 100) : 100;
                    minimo[col] = Math.max(minimo[col], Math.max(64, Math.min(100, px)));
                }
                col += celda.span();
            }
        }
        // V29: la columna de etiquetas se ajusta al texto más largo (hasta anchoEtiquetas); con el valor por defecto, 150px como tope
        String anchoEt = "150px".equals(t.anchoEtiquetas()) ? "fit-content(150px)" : t.anchoEtiquetas();
        List<String> tpl = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String a = i < t.columnas().size() ? t.columnas().get(i) : "1fr";
            if (t.etiquetaIzquierda()) tpl.add(anchoEt);
            tpl.add(a.endsWith("fr") ? "minmax(" + minimo[i] + "px," + a + ")" : a);
        }
        Div grilla = new Div();
        grilla.addClassName("optimist-tabla");   // V28: en el celular pasa a una columna (optimist-global.css)
        grilla.getStyle().set("display", "grid").set("grid-template-columns", String.join(" ", tpl))
                .set("column-gap", "var(--optimist-form-col-gap, 14px)").set("row-gap", "0").set("align-items", "center").set("width", "100%");
        // V44: el espacio entre renglones va como margen de cada celda: un renglón que una regla oculta entero no deja hueco
        String medioRenglon = "calc(var(--optimist-form-row-gap, 10px) / 2) 0";
        int factor = t.etiquetaIzquierda() ? 2 : 1;
        int renglon = 0;
        for (List<Celda> fila : t.filas()) {
            // V24: un renglón que tiene campos pero ninguno visible no ocupa lugar.
            // (Los campos NO se corren de columna: si falta uno, queda su hueco.)
            boolean tieneCampos = fila.stream().anyMatch(c -> c.campo() != null);
            boolean algunoVisible = fila.stream().anyMatch(c -> c.campo() != null && piezas.containsKey(c.campo()));
            if (tieneCampos && !algunoVisible) continue;
            renglon++;
            int col = 1;
            for (Celda celda : fila) {
                int ancho = celda.span() * factor;
                Component pieza = celda.campo() == null ? null : piezas.get(celda.campo());
                if (pieza != null && usados.add(celda.campo())) {
                    OptimistTrnCampos.Editor ed = f.cab.get(celda.campo());
                    int colCampo = col;
                    if (t.etiquetaIzquierda()) {
                        Campo c = ed.campo();
                        Span et = new Span(c.etiqueta() + (c.obligatorio() && c.editable() && !f.soloVer ? " *" : ""));
                        et.getElement().setAttribute("title", c.etiqueta());
                        et.getStyle().set("grid-row", String.valueOf(renglon)).set("grid-column", col + " / span 1")
                                .set("font-size", "var(--lumo-font-size-s)").set("color", "var(--optimist-texto)")
                                .set("opacity", "0.85").set("overflow", "hidden").set("text-overflow", "ellipsis").set("white-space", "nowrap")
                                .set("margin", medioRenglon);
                        aplicarEstilo(c, null, et);
                        // campo multirenglón: la etiqueta va arriba, como en GeneXus
                        if (c.renglones() > 1) et.getStyle().set("align-self", "start").set("padding-top", "10px");
                        grilla.add(et);
                        f.etiquetas.put(ed, et);
                        colCampo = col + 1;
                    }
                    int spanCampo = t.etiquetaIzquierda() ? ancho - 1 : ancho;
                    pieza.getElement().getStyle().set("grid-row", String.valueOf(renglon))
                            .set("grid-column", colCampo + " / span " + spanCampo).set("width", "100%").set("min-width", "0")
                            .set("margin", medioRenglon);
                    if (pieza != ed.componente())
                        ed.componente().getElement().getStyle().set("flex", "1 1 auto").set("width", "auto").set("min-width", "0");
                    else
                        ed.componente().getElement().getStyle().set("width", "100%");
                    // V48: el campo no se estira más que su tamaño natural (un N(6) no ocupa todo el renglón);
                    // los de varios renglones sí usan todo el ancho
                    if (ed.campo().renglones() <= 1) {
                        String nat = OptimistTrnCampos.ancho(ed.campo());
                        if (pieza != ed.componente() && nat.endsWith("px"))
                            nat = (parseIntSeguro(nat.substring(0, nat.length() - 2), 200) + 40) + "px";
                        // V49t: en una tabla alineada a la derecha (los totales), los números van contra el borde derecho
                        boolean derecha = "derecha".equals(t.alinear()) && ed.campo().dominio().esNumerico();
                        pieza.getElement().getStyle().set("max-width", nat).set("justify-self", derecha ? "end" : "start");
                    }
                    grilla.add(pieza);
                }
                col += ancho;
            }
        }
        VerticalLayout caja = new VerticalLayout();
        caja.setPadding(false);
        caja.setSpacing(false);
        caja.setWidthFull();
        if (t.titulo() != null) {
            Span tit = new Span(t.titulo());
            tit.getStyle().set("font-weight", "600").set("margin", "6px 0 4px").set("color", "var(--optimist-texto)");
            caja.add(tit);
        }
        Div desplazable = new Div(grilla);
        desplazable.setWidthFull();
        desplazable.getStyle().set("overflow-x", "auto");
        // V49q: la grilla del detalle tampoco se corre sola a la izquierda al tocar o cambiar un campo
        desplazable.addAttachListener(ev -> desplazable.getElement().executeJs(OptimistWorkWith.Pantalla.FIJAR_DESPLAZAMIENTO));
        if (t.alinear() != null) {
            // V33: la tabla toma el ancho de su contenido y se alinea a la izquierda, al centro o a la derecha
            grilla.getStyle().set("width", "fit-content").set("max-width", "100%");
            String margen = switch (t.alinear()) { case "centro" -> "0 auto"; case "derecha" -> "0 0 0 auto"; default -> "0 auto 0 0"; };
            grilla.getStyle().set("margin", margen);
            if (t.titulo() != null) caja.setAlignItems(switch (t.alinear()) {
                case "centro" -> com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER;
                case "derecha" -> com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END;
                default -> com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.START; });
        }
        caja.add(desplazable);
        return caja;
    }

    private static int parseIntSeguro(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** Un renglón de pantalla por cada lista del YAML (los campos ocultos o no visibles se saltean). */
    private Component[] renglones(List<List<String>> filas, Map<String, Component> piezas, Set<String> usados) {
        List<Component> r = new ArrayList<>();
        Nivel cab = trn.cabecera();
        for (List<String> fila : filas) {
            List<Component> cs = new ArrayList<>();
            for (String n : fila) {
                String nombre = cab.campo(n).nombre();
                Component p = piezas.get(nombre);
                if (p != null && usados.add(nombre)) cs.add(p);
            }
            if (!cs.isEmpty()) r.add(grupoCampos(cs));
        }
        return r.toArray(new Component[0]);
    }

    private HorizontalLayout grupoCampos(Collection<Component> cs) {
        HorizontalLayout h = new HorizontalLayout();
        h.setWidthFull();
        h.getStyle().set("flex-wrap", "wrap").set("align-items", "flex-end");
        cs.forEach(h::add);
        return h;
    }

    /** Trae de nuevo las descripciones de la cabecera (cuando cambia una clave foránea). */
    private void refrescarCabecera(Form f) {
        try {
            Map<String, Object> v = f.valoresCabecera();
            List<Referencia> faltan = datos.completarReferenciasYFaltantes(trn.cabecera(), null, v, Map.of());
            marcarFaltantes(trn.cabecera(), null, f.cab, faltan, v, Map.of());   // V65
            for (Campo c : trn.cabecera().campos()) if (c.inferido()) f.inferidos.put(c.nombre(), v.get(c.nombre()));
            for (OptimistTrnCampos.Editor ed : f.cab.values())
                if (ed.campo().inferido()) ed.escribir().accept(v.get(ed.campo().nombre()));
            if (f.dialogo != null) f.dialogo.setHeaderTitle(titulo(f));
        } catch (SQLException ex) { error(ex); }
    }

    private void refrescarFila(Form f, Nivel det, FilaDetalle fila) {
        try {
            Map<String, Object> v = fila.valores();
            Map<String, Object> vc = f.valoresCabecera();
            List<Referencia> faltan = datos.completarReferenciasYFaltantes(det, trn.cabecera(), v, vc);
            marcarFaltantes(det, trn.cabecera(), fila.editores(), faltan, v, vc);   // V65
            for (Campo c : det.campos()) if (c.inferido()) fila.inferidos().put(c.nombre(), v.get(c.nombre()));
            for (OptimistTrnCampos.Editor ed : fila.editores().values())
                if (ed.campo().inferido()) ed.escribir().accept(v.get(ed.campo().nombre()));
        } catch (SQLException ex) { error(ex); }
    }

    /**
     * V65: el campo con lupa (el último de la referencia) queda en rojo con "No existe ..." si lo cargado no está en su tabla
     * (escrito a mano); cuando se corrige, vuelve a la normalidad. Al Confirmar se valida igual que siempre.
     */
    private void marcarFaltantes(Nivel nivel, Nivel cabecera, Map<String, OptimistTrnCampos.Editor> eds, List<Referencia> faltan,
                                 Map<String, Object> valores, Map<String, Object> valoresCabecera) {
        for (Referencia ref : nivel.referencias()) {
            if (ref.conCodigo()) continue;   // los que se cargan por código ya avisan solos
            OptimistTrnCampos.Editor ed = eds.get(ultimo(ref.campos().keySet()));
            if (ed == null || !(ed.componente() instanceof com.vaadin.flow.component.HasValidation hv)) continue;
            var el = ed.componente().getElement();
            if (faltan.contains(ref)) {
                List<String> partes = new ArrayList<>();
                for (String l : ref.campos().keySet()) {
                    Campo c = nivel.campo(l) != null ? nivel.campo(l) : cabecera == null ? null : cabecera.campo(l);
                    Object v = nivel.campo(l) != null ? valores.get(l) : valoresCabecera.get(l);
                    partes.add((c == null ? l : c.etiqueta()) + " " + (v == null ? "" : c == null ? String.valueOf(v) : OptimistTrnCampos.mostrar(c, v)));
                }
                Map<String, Object> fk = new LinkedHashMap<>();
                for (String l : ref.campos().keySet()) fk.put(l, nivel.campo(l) != null ? valores.get(l) : valoresCabecera.get(l));
                String motivo = datos.motivoNoValido(nivel, ref, fk);   // V65: existe pero no cumple el 'donde' (no está activo)
                hv.setErrorMessage(motivo != null ? String.join(" / ", partes) + " " + motivo : "No existe " + String.join(" / ", partes));
                hv.setInvalid(true);
                el.setAttribute("data-no-existe", "1");
            } else if (el.hasAttribute("data-no-existe")) {
                hv.setInvalid(false);
                el.removeAttribute("data-no-existe");
            }
        }
    }

    /**
     * V34: recalcula todas las fórmulas (cabecera y líneas) con lo que hay en pantalla y las muestra.
     * Si una no se puede calcular (ej. falta su procedimiento), queda vacía y el motivo se ve al pasar el mouse.
     */
    private void recalcular(Form f) {
        if (f.calculando || !OptimistCalculo.tieneFormulas(trn)) return;
        f.calculando = true;
        try {
            Map<String, List<Map<String, Object>>> lineas = new LinkedHashMap<>();
            Map<String, List<FilaDetalle>> usadas = new LinkedHashMap<>();
            for (Nivel det : trn.detallesVisibles()) {
                List<Map<String, Object>> vs = new ArrayList<>();
                List<FilaDetalle> us = new ArrayList<>();
                for (FilaDetalle fd : f.filas.getOrDefault(det.nombre(), List.of())) {
                    if (fd.original() == null && sinTocar(fd)) continue;   // las filas vacías no suman
                    vs.add(fd.valores());
                    us.add(fd);
                }
                lineas.put(det.nombre(), vs);
                usadas.put(det.nombre(), us);
            }
            OptimistCalculo.Resultado r = datos.calcular(trn, f.valoresCabecera(), lineas);
            f.inferidos.putAll(r.cabecera());   // V35: las reglas también usan las fórmulas ocultas
            r.lineas().forEach((det, calc) -> {
                List<FilaDetalle> us = usadas.getOrDefault(det, List.of());
                for (int i = 0; i < Math.min(calc.size(), us.size()); i++) us.get(i).inferidos().putAll(calc.get(i));
            });
            r.cabecera().forEach((n, v) -> {
                OptimistTrnCampos.Editor ed = f.cab.get(n);
                if (ed != null) mostrarCalculado(ed, v, r.errores().get(n));
            });
            r.lineas().forEach((det, calc) -> {
                List<FilaDetalle> us = usadas.getOrDefault(det, List.of());
                for (int i = 0; i < Math.min(calc.size(), us.size()); i++) {
                    FilaDetalle fd = us.get(i);
                    calc.get(i).forEach((n, v) -> {
                        OptimistTrnCampos.Editor ed = fd.editores().get(n);
                        if (ed != null) mostrarCalculado(ed, v, r.errores().get(n));
                    });
                }
            });
            if (f.dialogo != null && trn.encabezado() != null) f.dialogo.setHeaderTitle(titulo(f));
        } catch (RuntimeException ex) {
            ex.printStackTrace();   // nunca se corta el formulario por una fórmula
        } finally {
            f.calculando = false;
            if (!f.panelesVacios.isEmpty()) actualizarPanelesVacios(f);   // V49t
        }
    }

    // ------------------------------------------------------------------ V35: reglas

    /** ¿El campo es parte de la clave de alguna referencia del nivel? (entonces hay que traer sus descripciones) */
    private static boolean claveDeReferencia(Nivel nivel, String campo) {
        if (campo == null) return false;
        for (Referencia r : nivel.referencias()) for (String k : r.campos().keySet()) if (k.equalsIgnoreCase(campo)) return true;
        return false;
    }

    /** Evento Start y reglas de carga (variables, Enabled/Visible) al abrir el formulario. */
    private void iniciarReglas(Form f) {
        try { f.comercial = OptimistComercial.de(trn); }
        catch (RuntimeException ex) { Notification.show("Cálculo de " + trn.singular() + ": " + ex.getMessage(), 12000, Notification.Position.MIDDLE); }
        if (trn.inicio() == null && trn.reglas() == null) return;
        try { f.reglas = OptimistReglas.de(trn); }
        catch (RuntimeException ex) {
            Notification.show("Reglas de " + trn.singular() + ": " + ex.getMessage(), 12000, Notification.Position.MIDDLE);
            return;
        }
        if (f.reglas.vacio()) { f.reglas = null; return; }
        f.estado = f.reglas.nuevoEstado(f.modo());
        f.viejos = f.nuevo ? Map.of() : new LinkedHashMap<>(f.actual.cabecera());
        List<String> msgs = new ArrayList<>();
        f.reglas.iniciar(f.estado, f.valoresCabecera(), datos.abridor(), msgs);
        msgs.forEach(m -> System.out.println("[" + trn.nombre() + "] inicio: " + m));
        f.aplicandoReglas = true;
        try {
            aplicarEfecto(f, null, null, f.reglas.aplicar(OptimistReglas.Momento.CARGA, null, null, f.valoresCabecera(), null, f.viejos,
                    new HashSet<>(), f.estado, datos.abridor(), null));
            for (Nivel det : trn.detallesVisibles())
                for (FilaDetalle fd : f.filas.getOrDefault(det.nombre(), List.of()))
                    // V48: en las líneas nuevas vacías no corren procedimientos (GetEntiPorBonPorRec...): solo lo que habilita / oculta
                    aplicarEfecto(f, det, fd, f.reglas.aplicar(fd.original() == null && sinTocar(fd) ? OptimistReglas.Momento.PANTALLA : OptimistReglas.Momento.CARGA,
                            null, det, fd.valores(), f.valoresCabecera(),
                            fd.original() == null ? null : fd.original().valores(), new HashSet<>(), f.estado, datos.abridor(), null));
        } finally { f.aplicandoReglas = false; }
    }

    /** Cambió un campo de la cabecera: referencias, fórmulas, reglas de la cabecera, fórmulas. */
    private void cambioCabecera(Form f, String campo) {
        if (f.aplicandoReglas || f.calculando) return;
        long t0 = System.nanoTime();
        f.lecturas.hacer(() -> cambioCabeceraAhora(f, campo));
        pintarInsignias(f);
        registrarDemora("cabecera." + campo, t0, f);
    }

    private void cambioCabeceraAhora(Form f, String campo) {
        Map<String, Object> antes = f.valoresCabecera();
        if (claveDeReferencia(trn.cabecera(), campo)) refrescarCabecera(f);
        recalcular(f);
        if (f.reglas != null) {
            // V37: solo las reglas que dependen del campo, de lo que trajo su referencia (ej. el cliente) y de lo recalculado
            Set<String> cambiados = diferencias(antes, f.valoresCabecera());
            if (campo != null) cambiados.add(campo);
            reglasCabecera(f, cambiados, OptimistReglas.Momento.CAMBIO, null, true);
            recalcular(f);
        }
        if (f.comercial != null) {   // V36: la cabecera cambia el cálculo de todas las líneas (ej. factura A o B)
            comercialLineas(f);
            comercialPie(f);
        }
        lineasPorTotales(f, antes, null);
    }

    /**
     * V49c: cambiaron totales de la cabecera (PRSSubtotal, PRSTotal...): se recalculan las líneas de los detalles cuyas reglas
     * los usan (la base imponible de cada impuesto: PRSImpuestosBaseImponible = PRSSubtotal If &ImpuestosDetBaseCalculo = 1 ...).
     * Solo los totales (campos fórmula), no lo que carga el usuario. 'excepto': el detalle donde se originó el cambio.
     * Dos vueltas como máximo: el importe de los impuestos vuelve a cambiar el total.
     */
    private void lineasPorTotales(Form f, Map<String, Object> cabAntes, Nivel excepto) {
        if (f.reglas == null || f.soloVer) return;
        Map<String, Object> antes = cabAntes;
        for (int vuelta = 0; vuelta < 2; vuelta++) {
            Map<String, Object> ahora = f.valoresCabecera();
            Set<String> totales = diferencias(antes, ahora);
            totales.removeIf(n -> { Campo c = trn.cabecera().campo(n); return c == null || c.formula() == null; });
            if (totales.isEmpty()) return;
            antes = ahora;
            boolean corrio = false;
            for (Nivel det : trn.detallesVisibles()) {
                if (det == excepto) continue;
                Set<String> cambios = f.reglas.cambiosParaLineas(det, totales);
                if (cambios.isEmpty()) continue;
                for (FilaDetalle fd : f.filas.getOrDefault(det.nombre(), List.of())) {
                    if (fd.original() == null && sinTocar(fd)) continue;
                    Set<String> c = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    c.addAll(cambios);
                    reglasLinea(f, det, fd, OptimistReglas.Momento.CAMBIO, null, c, true);
                    corrio = true;
                }
            }
            if (!corrio) return;
            recalcular(f);
            comercialPie(f);
        }
    }

    /** Cambió un campo de una línea (o se quitó una línea: fila null): referencias, reglas de la línea, fórmulas, reglas de la cabecera. */
    private void cambioFila(Form f, Nivel det, FilaDetalle fila, String campo) {
        if (f.aplicandoReglas || f.calculando) return;
        numerarFila(f, det, fila);   // V65
        long t0 = System.nanoTime();
        f.lecturas.hacer(() -> cambioFilaAhora(f, det, fila, campo));
        pintarInsignias(f);
        registrarDemora((det == null ? "" : det.nombre() + ".") + campo, t0, f);
    }

    /** V40: los campos con insignia (status) se ven como pastilla del color que corresponde a su valor. */
    private void pintarInsignias(Form f) {
        Map<String, Object> cab = null;
        for (OptimistTrnCampos.Editor ed : f.cab.values()) {
            if (ed.campo().insignia() == null) continue;
            if (cab == null) cab = f.valoresCabecera();
            OptimistTrnCampos.pintarInsignia(ed, OptimistTrnCampos.colorInsignia(ed.campo(), trn.cabecera(), cab));
        }
        for (Nivel det : trn.detallesVisibles()) {
            if (det.campos().stream().noneMatch(c -> c.insignia() != null)) continue;
            for (FilaDetalle fd : f.filas.getOrDefault(det.nombre(), List.of())) {
                Map<String, Object> v = fd.valores();
                for (OptimistTrnCampos.Editor ed : fd.editores().values())
                    if (ed.campo().insignia() != null) OptimistTrnCampos.pintarInsignia(ed, OptimistTrnCampos.colorInsignia(ed.campo(), det, v));
            }
        }
    }

    /** V36: si un cambio tarda, queda en la consola del servidor (para saber qué regla o fórmula lo demora). */
    private void registrarDemora(String que, long t0, Form f) {
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms >= 500) System.out.println("[" + trn.nombre() + "] cambio de " + que + ": " + ms + " ms (" + f.lecturas.resumen() + ")");
    }

    private void cambioFilaAhora(Form f, Nivel det, FilaDetalle fila, String campo) {
        Map<String, Object> cabAntes = f.valoresCabecera();
        Map<String, Object> filaAntes = fila == null ? Map.of() : fila.valores();
        boolean dep = "1".equals(System.getenv("OPTIMIST_DEPURAR")) || Boolean.getBoolean("optimist.reglas.depurar");
        long t0 = System.nanoTime(), t1, t2 = 0;
        if (fila != null && claveDeReferencia(det, campo)) refrescarFila(f, det, fila);
        t1 = System.nanoTime();
        // V36: cuentas del renglón antes de las reglas (las reglas ven los importes al día) y después (si cambiaron precio, tasa o descuento)
        if (fila != null) comercialLinea(f, det, fila);
        if (f.reglas != null && fila != null) {
            // V37: como Excel: solo las reglas que dependen de lo que cambió (el campo, lo que trajo su referencia y las cuentas)
            Set<String> cambiados = diferencias(filaAntes, fila.valores());
            if (campo != null) cambiados.add(campo);
            // V48: como GeneXus, en una línea nueva la primera vez corren TODAS las reglas del nivel: las que solo usan datos de la
            // cabecera también (PRSListasPreciosId = GetEntiListaPreciosCondPago.Udp(PRSEntidadesPK, PRSCPagoId) if ...IsEmpty()),
            // que si no nunca se disparan porque nada de lo que leen cambia en la línea.
            boolean primera = fila.original() == null && f.lineasIniciadas.add(fila);
            if (dep) System.out.println("[" + trn.nombre() + "] " + det.nombre() + "." + campo + (primera ? ": línea nueva, corren todas las reglas" : ": cambió " + cambiados));
            reglasLinea(f, det, fila, OptimistReglas.Momento.CAMBIO, null, cambiados, !primera);
            t2 = System.nanoTime();
            comercialLinea(f, det, fila);
        }
        comercialItemizado(f);   // V50w
        if (fila != null) estiloRenglon(f, det, fila);   // V50x
        long t3 = System.nanoTime();
        recalcular(f);
        comercialPie(f);
        if (dep) System.out.println("[" + trn.nombre() + "] " + (det == null ? "" : det.nombre()) + "." + campo + ": referencias " + (t1 - t0) / 1_000_000 + " ms, reglas "
                + (t2 == 0 ? 0 : (t2 - t1) / 1_000_000) + " ms, fórmulas " + (System.nanoTime() - t3) / 1_000_000 + " ms");
        if (f.reglas != null) {
            // la cabecera: solo las reglas que usan los totales (u otros datos de la cabecera) que cambiaron
            Set<String> cambiadosCab = diferencias(cabAntes, f.valoresCabecera());
            if (!cambiadosCab.isEmpty()) {
                reglasCabecera(f, cambiadosCab, OptimistReglas.Momento.CAMBIO, null, true);
                recalcular(f);
                comercialPie(f);
            }
            lineasPorTotales(f, cabAntes, det);
        }
    }

    /** V37: los campos cuyo valor cambió (números por su valor: 10 = 10.00). */
    private static Set<String> diferencias(Map<String, Object> antes, Map<String, Object> despues) {
        Set<String> r = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> claves = new HashSet<>(antes.keySet());
        claves.addAll(despues.keySet());
        for (String k : claves) {
            if (k == null) continue;
            Object a = antes.get(k), b = despues.get(k);
            if (Objects.equals(a, b)) continue;
            if (a instanceof Number || b instanceof Number) {
                try { if (a != null && b != null && OptimistFormula.num(a).compareTo(OptimistFormula.num(b)) == 0) continue; }
                catch (RuntimeException ignorado) { }
            }
            r.add(k);
        }
        return r;
    }

    // ------------------------------------------------------------------ V36: cálculo comercial

    private int decimalesDe(Form f) {
        if (f.decimales == null)
            f.decimales = f.comercial.decimales(f.valoresCabecera(), f.estado == null ? null : f.estado::variable, datos.abridor());
        return f.decimales;
    }

    /** Precio neto, descuento, subtotal e IVA de un renglón (si la TRN tiene 'calculo:' y es su detalle). */
    private void comercialLinea(Form f, Nivel det, FilaDetalle fila) {
        if (f.comercial == null || f.soloVer || det == null || !det.nombre().equalsIgnoreCase(f.comercial.detalle().nombre())) return;
        if (fila.original() == null && sinTocar(fila)) return;
        try {
            Map<String, Object> r = f.comercial.linea(fila.valores(), f.valoresCabecera(), decimalesDe(f),
                    f.estado == null ? null : f.estado::variable);
            escribirCalculados(f, fila.editores(), fila.inferidos(), r);
        } catch (RuntimeException ex) {
            ex.printStackTrace();   // nunca se corta el formulario por una cuenta
        }
    }

    private void comercialLineas(Form f) {
        if (f.comercial == null) return;
        Nivel det = f.comercial.detalle();
        for (FilaDetalle fd : f.filas.getOrDefault(det.nombre(), List.of())) comercialLinea(f, det, fd);
        comercialItemizado(f);
        recalcular(f);
    }

    /**
     * V50x: estiloRenglon del detalle: si la línea cumple la condición, todos sus campos con ese estilo (ej. los totalizadores del
     * itemizado en negrita); si deja de cumplirla, vuelven a su estilo propio. Vale en Modificar y en Visualizar.
     */
    /**
     * V61h: workwith.estiloRenglon: los renglones del WorkWith que cumplen la condición van con ese estilo (todo el renglón,
     * con enlaces e insignias incluidos). El estilo va como una parte CSS de la grilla con su regla en la página.
     */
    private void estiloRenglonWorkWith(Nivel cab) {
        OptimistTrnDefinicion.EstiloRenglon er = OptimistTrnDefinicion.estiloRenglonWorkWith(cab.tabla());
        if (er == null) return;
        String css = OptimistWorkWith.estiloCss(er.estilo());
        if (css == null) return;
        String parte = "optimist-er-" + cab.tabla().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        OptimistFormula fo = OptimistFormula.de(er.si());
        pantalla.parteRenglon(f -> {
            try {
                Map<String, Object> v = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                f.forEach((k, x) -> { if (k != null) v.put(k, x); });
                return OptimistFormula.verdad(fo.evaluar(v::get)) ? parte : null;
            } catch (RuntimeException ex) { return null; }
        });
        String regla = "vaadin-grid::part(" + parte + "){" + css.replace(";", " !important;") + "}";
        getElement().executeJs("let s=document.getElementById($0);if(!s){s=document.createElement('style');s.id=$0;document.head.appendChild(s);}s.textContent=$1;",
                "estilo-" + parte, regla);
    }

    private void estiloRenglon(Form f, Nivel det, FilaDetalle fila) {
        if (det == null || fila == null) return;
        List<OptimistTrnDefinicion.EstiloRenglon> estilos = OptimistTrnDefinicion.estilosRenglon(det.tabla());
        if (estilos.isEmpty()) return;
        Map<String, Object> v = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        f.valoresCabecera().forEach((k, x) -> { if (k != null) v.put(k, x); });
        fila.valores().forEach((k, x) -> { if (k != null) v.put(k, x); });
        List<OptimistTrnDefinicion.EstiloRenglon> cumplen = new ArrayList<>();
        Set<String> claves = new HashSet<>();
        for (OptimistTrnDefinicion.EstiloRenglon er : estilos) {
            claves.addAll(er.estilo().keySet());
            boolean si;
            try { si = OptimistFormula.verdad(OptimistFormula.de(er.si()).evaluar(v::get)); } catch (RuntimeException ex) { si = false; }
            if (si) cumplen.add(er);
        }
        // V65c: varias condiciones, cada una sobre toda la línea o solo sobre sus 'campos'; se limpia lo que pudo poner
        // cualquiera de ellas, vuelve el estilo propio del campo y se aplican las que se cumplen (la de más abajo manda)
        for (OptimistTrnCampos.Editor ed : fila.editores().values()) {
            var st = ed.componente().getElement().getStyle();
            for (String k : claves) {
                st.remove(k); st.remove("--vaadin-input-field-value-" + k); st.remove("--vaadin-input-field-label-" + k);
                if (k.equals("color")) { st.remove("--lumo-body-text-color"); st.remove("--lumo-secondary-text-color"); }   // los pone aplicarEstilo
                if (k.equals("font-family")) st.remove("--lumo-font-family");
            }
            aplicarEstilo(ed.campo().estilo(), ed.componente(), null);
            for (OptimistTrnDefinicion.EstiloRenglon er : cumplen)
                if (er.aplica(ed.campo().nombre())) aplicarEstilo(er.estilo(), ed.componente(), null);
        }
    }

    /** V50w: itemizado: los renglones totalizadores con la suma de sus componentes, en pantalla (al grabar, TotalizarIva igual). */
    private void comercialItemizado(Form f) {
        if (f.comercial == null || f.soloVer) return;
        try {
            List<FilaDetalle> filas = new ArrayList<>();
            List<Map<String, Object>> vals = new ArrayList<>();
            for (FilaDetalle fd : f.filas.getOrDefault(f.comercial.detalle().nombre(), List.of())) {
                if (fd.original() == null && sinTocar(fd)) continue;
                filas.add(fd);
                vals.add(fd.valores());
            }
            f.comercial.itemizar(vals, f.valoresCabecera(), f.estado == null ? null : f.estado::variable)
                    .forEach((i, v) -> escribirCalculados(f, filas.get(i).editores(), filas.get(i).inferidos(), v));
        } catch (RuntimeException ex) {
            ex.printStackTrace();   // nunca se corta el formulario por una cuenta
        }
    }

    /** Bonificación y recargo de pie, sobre los totales ya recalculados; después se recalculan los totales. */
    private void comercialPie(Form f) {
        if (f.comercial == null || f.soloVer) return;
        try {
            Map<String, Object> r = f.comercial.pie(f.valoresCabecera(), decimalesDe(f), f.estado == null ? null : f.estado::variable);
            if (r.isEmpty()) return;
            escribirCalculados(f, f.cab, f.inferidos, r);
            recalcular(f);
        } catch (RuntimeException ex) {
            ex.printStackTrace();
        }
    }

    private void escribirCalculados(Form f, Map<String, OptimistTrnCampos.Editor> eds, Map<String, Object> ocultos, Map<String, Object> valores) {
        boolean antes = f.aplicandoReglas;
        f.aplicandoReglas = true;   // escribir un campo no dispara de nuevo el cambio
        try {
            valores.forEach((n, v) -> {
                OptimistTrnCampos.Editor ed = editorDe(eds, n);
                if (ed != null) {
                    try { ed.escribir().accept(v); } catch (RuntimeException ex) { ocultos.put(n, v); }
                } else ocultos.put(n, v);
            });
        } finally { f.aplicandoReglas = antes; }
    }

    /** Como GeneXus web, una línea se procesa entera: after() vale para todos sus campos que se cargan. */
    private static Set<String> editablesDe(Nivel det) {
        Set<String> r = new HashSet<>();
        for (Campo c : det.campos()) if (c.editable()) r.add(c.nombre());
        return r;
    }

    private OptimistReglas.Efecto reglasLinea(Form f, Nivel det, FilaDetalle fila, OptimistReglas.Momento m, Set<String> eventos) {
        return reglasLinea(f, det, fila, m, eventos, editablesDe(det), false);
    }

    private OptimistReglas.Efecto reglasLinea(Form f, Nivel det, FilaDetalle fila, OptimistReglas.Momento m, Set<String> eventos,
                                              Set<String> cambiados, boolean soloAfectadas) {
        f.aplicandoReglas = true;
        try {
            OptimistReglas.Efecto e = f.reglas.aplicar(m, eventos, det, fila.valores(), f.valoresCabecera(),
                    fila.original() == null ? null : fila.original().valores(), cambiados, f.estado, datos.abridor(), null, soloAfectadas);
            aplicarEfecto(f, det, fila, e);
            // si una regla cambió una clave foránea (ej. la lista de precios), se traen sus descripciones
            if (e.asignados().keySet().stream().anyMatch(k -> claveDeReferencia(det, k))) refrescarFila(f, det, fila);
            return e;
        } finally { f.aplicandoReglas = false; }
    }

    private OptimistReglas.Efecto reglasCabecera(Form f, Set<String> cambiados, OptimistReglas.Momento m) {
        return reglasCabecera(f, cambiados, m, null);
    }

    private OptimistReglas.Efecto reglasCabecera(Form f, Set<String> cambiados, OptimistReglas.Momento m, Set<String> eventos) {
        return reglasCabecera(f, cambiados, m, eventos, false);
    }

    private OptimistReglas.Efecto reglasCabecera(Form f, Set<String> cambiados, OptimistReglas.Momento m, Set<String> eventos, boolean soloAfectadas) {
        f.aplicandoReglas = true;
        try {
            OptimistReglas.Efecto e = f.reglas.aplicar(m, eventos, null, f.valoresCabecera(), null, f.viejos, cambiados, f.estado, datos.abridor(), null, soloAfectadas);
            aplicarEfecto(f, null, null, e);
            if (e.asignados().keySet().stream().anyMatch(k -> claveDeReferencia(trn.cabecera(), k))) refrescarCabecera(f);
            return e;
        } finally { f.aplicandoReglas = false; }
    }

    /** Lo que hicieron las reglas: valores a los campos (o a los ocultos), habilitar/ocultar, mensajes. */
    private void aplicarEfecto(Form f, Nivel det, FilaDetalle fila, OptimistReglas.Efecto e) {
        Map<String, OptimistTrnCampos.Editor> eds = fila == null ? f.cab : fila.editores();
        Map<String, Object> ocultos = fila == null ? f.inferidos : fila.inferidos();
        e.asignados().forEach((n, v) -> {
            OptimistTrnCampos.Editor ed = eds.get(n);
            if (ed == null && fila != null && trn.cabecera().campo(n) != null && (det == null || det.campo(n) == null)) {
                ed = f.cab.get(trn.cabecera().campo(n).nombre());   // una regla de línea que asigna un campo de la cabecera
                if (ed == null) { f.inferidos.put(n, v); return; }
            }
            if (ed != null) {
                try { ed.escribir().accept(v); } catch (RuntimeException ex) { ocultos.put(n, v); }
            } else ocultos.put(n, v);
        });
        e.habilitados().forEach((n, h) -> {
            OptimistTrnCampos.Editor ed = editorDe(eds, n);
            if (ed != null && !f.soloVer) ed.soloLectura().accept(Boolean.TRUE.equals(f.soloLecturaBase.get(ed)) || !h);
        });
        e.visibles().forEach((n, vis) -> {
            if (fila == null && det == null) {
                OptimistTrnCampos.Editor ed = editorDe(eds, n);
                if (ed != null) {
                    piezaDe(ed).setVisible(vis);
                    Component et = f.etiquetas.get(ed);   // V44: y su etiqueta
                    if (et != null) et.setVisible(vis);
                    return;
                }
                // V65d: <Detalle>.Visible = ...: el detalle entero (y su panel, si no tiene otra cosa). Ej. COBR: las Formas
                // de Pago no se ven en una cobranza imputadora (Detalle_de_Pago.Visible = false en GeneXus)
                String dn = n.toLowerCase(Locale.ROOT);
                Component bloque = f.bloquesDetalle.get(dn), pnl = f.panelDeDetalle.get(dn);
                if (bloque != null) bloque.setVisible(vis);
                if (pnl != null) pnl.setVisible(vis);
                return;
            }
            // en un detalle, la columna entera (título y todas las filas)
            Nivel d = det;
            String clave = d.nombre() + "." + n.toLowerCase(Locale.ROOT);
            if (Objects.equals(f.columnas.put(clave, vis), vis)) return;
            Component t = f.titulosColumna.get(clave);
            if (t != null) t.setVisible(vis);
            for (FilaDetalle fd : f.filas.getOrDefault(d.nombre(), List.of())) {
                OptimistTrnCampos.Editor ed = editorDe(fd.editores(), n);
                if (ed != null) piezaDe(ed).setVisible(vis);
            }
        });
        // V49t: X.Class = "Clase" (por ahora, campos de la cabecera)
        if (fila == null && det == null) e.clases().forEach((n, cl) -> {
            OptimistTrnCampos.Editor ed = editorDe(eds, n);
            if (ed == null || Objects.equals(f.claseActual.get(ed), cl)) return;
            Map<String, String> est = cl.isEmpty() ? Map.of() : OptimistTrnDefinicion.estiloDeClase(trn.nombre(), cl);
            if (est == null) {
                if (f.mensajesVistos.add("clase:" + cl)) System.out.println("[" + trn.nombre() + "] " + n + ".Class: la clase '" + cl + "' no está en 'clases:' (ni en dominios.yaml)");
                return;
            }
            f.claseActual.put(ed, cl);
            var st = ed.componente().getElement().getStyle();
            for (String k : f.estiloDeRegla.getOrDefault(ed, Set.of())) {
                st.remove(k); st.remove("--vaadin-input-field-value-" + k); st.remove("--vaadin-input-field-label-" + k);
                if (k.equals("font-family")) st.remove("--lumo-font-family");
                if (k.equals("color")) { st.remove("--lumo-body-text-color"); st.remove("--lumo-secondary-text-color"); }
            }
            aplicarEstilo(ed.campo().estilo(), ed.componente(), null);   // el estilo propio del campo
            aplicarEstilo(est, ed.componente(), null);
            f.estiloDeRegla.put(ed, new HashSet<>(est.keySet()));
        });
        for (String m : e.mensajes())
            if (f.mensajesVistos.add(m)) Notification.show(m, 6000, Notification.Position.MIDDLE);
        for (String x : e.fallas()) if (f.mensajesVistos.add("falla:" + x)) System.out.println("[" + trn.nombre() + "] " + x);
    }

    private static OptimistTrnCampos.Editor editorDe(Map<String, OptimistTrnCampos.Editor> eds, String nombre) {
        OptimistTrnCampos.Editor ed = eds.get(nombre);
        if (ed != null) return ed;
        for (var x : eds.entrySet()) if (x.getKey().equalsIgnoreCase(nombre)) return x.getValue();
        return null;
    }

    /** El componente a mostrar/ocultar: el campo, o el campo + su lupa. */
    private static Component piezaDe(OptimistTrnCampos.Editor ed) {
        Component c = ed.componente();
        return c.getParent().filter(p -> p instanceof HorizontalLayout h && h.getComponentCount() == 2).orElse(c);
    }

    /**
     * Antes de grabar (como GeneXus): la pasada completa de reglas de cada línea y de la cabecera, después
     * BeforeValidate, AfterValidate, BeforeInsert/Update y AfterLevel. Devuelve los errores (vacío = se puede grabar).
     */
    private List<String> reglasAlGrabar(Form f) {
        List<String> errores = new ArrayList<>();
        if (f.reglas == null) {   // V36: sin reglas, igual se rehacen las cuentas antes de grabar
            comercialLineas(f);
            comercialPie(f);
            return errores;
        }
        OptimistReglas.Momento G = OptimistReglas.Momento.GRABAR;
        for (Nivel det : trn.detallesVisibles()) {
            int n = 0;
            for (FilaDetalle fd : f.filas.getOrDefault(det.nombre(), List.of())) {
                if (fd.original() == null && sinTocar(fd)) continue;
                n++;
                comercialLinea(f, det, fd);
                if (!modificada(fd)) continue;   // V50z: como GeneXus, las reglas corren solo en los renglones nuevos o modificados
                for (String x : reglasLinea(f, det, fd, G, null).errores()) errores.add(det.titulo() + ", renglón " + n + ": " + x);
                comercialLinea(f, det, fd);
            }
        }
        recalcular(f);
        comercialPie(f);
        errores.addAll(reglasCabecera(f, new HashSet<>(), G).errores());
        recalcular(f);
        comercialPie(f);
        String antesDeGrabar = f.nuevo ? "beforeinsert" : "beforeupdate";
        // V65: como GeneXus, primero la cabecera pasa por sus eventos y después CADA renglón pasa por los suyos
        // (BeforeValidate -> AfterValidate -> BeforeInsert/Update) antes de seguir con el próximo. Antes cada evento corría
        // para todos los renglones juntos y una variable de renglón (ej. &bHayAlmacen de MovStock) quedaba con el valor del
        // último: "No está definida la relación artículo/almacén" salía en todos los renglones por culpa de uno.
        for (String ev : List.of("beforevalidate", "aftervalidate", antesDeGrabar))
            errores.addAll(reglasCabecera(f, new HashSet<>(), G, Set.of(ev)).errores());
        for (Nivel det : trn.detallesVisibles()) {
            int n = 0;
            for (FilaDetalle fd : f.filas.getOrDefault(det.nombre(), List.of())) {
                if (fd.original() == null && sinTocar(fd)) continue;
                n++;
                if (!modificada(fd)) continue;   // V50z
                for (String ev : List.of("beforevalidate", "aftervalidate", antesDeGrabar)) {
                    String e2 = ev.startsWith("before") && !ev.equals("beforevalidate") ? (fd.original() == null ? "beforeinsert" : "beforeupdate") : ev;
                    for (String x : reglasLinea(f, det, fd, G, Set.of(e2)).errores()) errores.add(det.titulo() + ", renglón " + n + ": " + x);
                }
            }
        }
        errores.addAll(reglasCabecera(f, new HashSet<>(), G, Set.of("afterlevel")).errores());
        recalcular(f);
        return errores;
    }

    /** Reglas on AfterInsert/AfterUpdate y BeforeComplete: dentro de la transacción (un Error deshace todo). */
    private OptimistTrnDatos.Enganche enganche(Form f) {
        OptimistTrnDatos.Enganche reglas = enganche0(f);
        if (f.archivos.isEmpty()) return reglas;
        // V83: los archivos elegidos se graban en la misma transacción, ANTES de las reglas de después de grabar (AfterInsert,
        // BeforeComplete): como en GeneXus, son parte del registro (ej. ProcCheckDiferenciaArchivos lee los archivos nuevos)
        return (c, clave, cabecera) -> {
            Map<String, Object> k = clave != null ? clave : claveDe(cabecera);
            for (Map.Entry<String, ArchivoNuevo> e : f.archivos.entrySet()) {
                OptimistTrnDefinicion.Archivo a = trn.archivos().stream().filter(x -> x.nombre().equals(e.getKey())).findFirst().orElse(null);
                if (a == null) continue;
                ArchivoNuevo n = e.getValue();
                OptimistTrnDatos.grabarArchivo(c, trn, k, a, n.contenido(), n.nombre(), n.tipo());
            }
            if (reglas != null) reglas.antesDeConfirmar(c, clave, cabecera);
        };
    }

    /**
     * V83: panel "Archivos" del formulario: por cada archivo (Blob), el nombre del que está grabado, Bajar, Elegir otro y Quitar.
     * Lo elegido se graba al Confirmar (en el alta también). Bajar trae el contenido de la base en el momento.
     */
    @SuppressWarnings("deprecation")
    private Component panelArchivos(Form f, boolean soloVer) {
        VerticalLayout v = new VerticalLayout();
        v.setPadding(false);
        v.setSpacing(false);
        v.getStyle().set("margin-top", "12px");
        H3 titulo = new H3("Archivos");
        titulo.getStyle().set("margin", "6px 0");
        v.add(titulo);
        Map<String, Object> cab = f.actual == null ? Map.of() : f.actual.cabecera();
        for (OptimistTrnDefinicion.Archivo a : trn.archivos()) {
            String nom = a.nombreArchivo() == null || cab.get(a.nombreArchivo()) == null ? "" : String.valueOf(cab.get(a.nombreArchivo())).trim();
            String tip = a.tipoArchivo() == null || cab.get(a.tipoArchivo()) == null ? "" : String.valueOf(cab.get(a.tipoArchivo())).trim();
            String grabado = nom.isEmpty() ? "" : tip.isEmpty() ? nom : nom + "." + tip;
            Span etiqueta = new Span(a.etiqueta());
            etiqueta.getStyle().set("min-width", "180px").set("font-weight", "600");
            Span actual = new Span(f.nuevo ? "(sin archivo)" : grabado.isEmpty() ? "(sin nombre)" : grabado);
            actual.getStyle().set("min-width", "220px");
            Button bajar = new Button("Bajar", VaadinIcon.DOWNLOAD.create(), e -> bajarArchivo(f, a, grabado));
            bajar.setEnabled(!f.nuevo);
            HorizontalLayout renglon = new HorizontalLayout(etiqueta, actual, bajar);
            renglon.setAlignItems(FlexComponent.Alignment.CENTER);
            if (!soloVer) {
                com.vaadin.flow.component.upload.receivers.MemoryBuffer buffer = new com.vaadin.flow.component.upload.receivers.MemoryBuffer();
                com.vaadin.flow.component.upload.Upload up = new com.vaadin.flow.component.upload.Upload(buffer);
                up.setMaxFiles(1);
                up.setMaxFileSize(50 * 1024 * 1024);
                up.setDropAllowed(false);
                up.setUploadButton(new Button("Elegir otro…", VaadinIcon.UPLOAD.create()));
                up.getStyle().set("padding", "0").set("border", "none");
                up.addSucceededListener(e -> {
                    try (java.io.InputStream in = buffer.getInputStream()) {
                        String completo = e.getFileName() == null ? "" : e.getFileName();
                        int p = completo.lastIndexOf('.');
                        String n = p > 0 ? completo.substring(0, p) : completo, t = p > 0 ? completo.substring(p + 1) : "";
                        f.archivos.put(a.nombre(), new ArchivoNuevo(n, t, in.readAllBytes()));
                        actual.setText(completo + " (se graba al Confirmar)");
                    } catch (java.io.IOException ex) {
                        Notification.show("No se pudo leer el archivo: " + ex.getMessage(), 8000, Notification.Position.MIDDLE);
                    }
                    up.clearFileList();
                });
                up.addFileRejectedListener(e -> Notification.show(e.getErrorMessage(), 8000, Notification.Position.MIDDLE));
                Button quitar = new Button("Quitar", VaadinIcon.TRASH.create(), e -> {
                    f.archivos.put(a.nombre(), new ArchivoNuevo("", "", null));
                    actual.setText("(se quita al Confirmar)");
                });
                quitar.setEnabled(!f.nuevo);
                renglon.add(up, quitar);
            }
            v.add(renglon);
        }
        return v;
    }

    /** V83: baja el archivo (Blob) grabado del registro abierto. */
    private void bajarArchivo(Form f, OptimistTrnDefinicion.Archivo a, String nombre) {
        byte[] bytes;
        try { bytes = datos.leerArchivo(trn, claveDe(f.actual.cabecera()), a.nombre()); }
        catch (SQLException ex) { Notification.show("No se pudo leer el archivo: " + ex.getMessage(), 8000, Notification.Position.MIDDLE); return; }
        if (bytes == null || bytes.length == 0) { Notification.show(a.etiqueta() + ": no tiene archivo grabado", 5000, Notification.Position.MIDDLE); return; }
        String archivo = nombre == null || nombre.isBlank() ? a.nombre() : nombre;
        com.vaadin.flow.server.StreamResource recurso =
                new com.vaadin.flow.server.StreamResource(archivo, () -> new java.io.ByteArrayInputStream(bytes));
        recurso.setContentType("application/octet-stream");
        // se guarda solo la última descarga, para no acumular archivos en la sesión
        if (ultimoArchivo != null) ultimoArchivo.unregister();
        ultimoArchivo = com.vaadin.flow.server.VaadinSession.getCurrent().getResourceRegistry().registerResource(recurso);
        UI.getCurrent().getPage().executeJs(
                "const a=document.createElement('a');a.href=$0;a.download=$1;document.body.appendChild(a);a.click();a.remove();",
                ultimoArchivo.getResourceUri().toString(), archivo);
    }
    private com.vaadin.flow.server.StreamRegistration ultimoArchivo;

    private OptimistTrnDatos.Enganche enganche0(Form f) {
        if (f.reglas == null) return null;
        return (c, clave, cabecera) -> {
            List<String> errores = new ArrayList<>();
            f.avisosAlGrabar.clear();
            // V82e: las AfterLevel que leen el registro (ej. ValidarPartidas) con los renglones ya grabados, antes que el resto
            Map<String, Object> conTodo = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            conTodo.putAll(f.valoresCabecera());
            cabecera.forEach((k, v) -> { if (v != null || !conTodo.containsKey(k)) conTodo.put(k, v); });
            OptimistReglas.Efecto nivel = f.reglas.aplicar(OptimistReglas.Momento.GRABAR, Set.of(OptimistReglas.AFTERLEVEL_BASE), null, conTodo,
                    null, f.viejos, new HashSet<>(), f.estado, null, c);
            nivel.fallas().forEach(x -> System.out.println("[" + trn.nombre() + "] " + x));
            if (!nivel.errores().isEmpty()) throw new IllegalArgumentException(String.join(" · ", nivel.errores()));
            f.avisosAlGrabar.addAll(nivel.mensajes());
            Set<String> eventos = Set.of(f.nuevo ? "afterinsert" : "afterupdate", "beforecomplete");
            OptimistReglas.Efecto e = f.reglas.aplicar(OptimistReglas.Momento.GRABAR, eventos, null, cabecera, null, f.viejos,
                    new HashSet<>(), f.estado, null, c);
            errores.addAll(e.errores());
            e.fallas().forEach(x -> System.out.println("[" + trn.nombre() + "] " + x));
            if (!errores.isEmpty()) throw new IllegalArgumentException(String.join(" · ", errores));
        };
    }

    private static void mostrarCalculado(OptimistTrnCampos.Editor ed, Object v, String error) {
        try { ed.escribir().accept(v); } catch (RuntimeException ex) { ed.escribir().accept(null); }
        if (error != null) ed.componente().getElement().setAttribute("title", "No se pudo calcular: " + error);
        else ed.componente().getElement().removeAttribute("title");
    }

    /**
     * Si el campo es el ÚLTIMO campo de la clave de una referencia, le agrega el botón de búsqueda.
     * Referencia simple (Tipo): busca en toda la tabla. V24: referencia compuesta (Tipo + Subtipo,
     * Marca + Modelo): la lupa va en Subtipo / Modelo y busca solo dentro del padre ya cargado.
     * 'valores' da los valores actuales del formulario (o de la fila) para saber cuál es el padre.
     */
    private Component conBuscador(OptimistTrnCampos.Editor ed, Nivel nivel, Nivel cabecera, boolean habilitado,
                                  Runnable alElegir, Supplier<Map<String, Object>> valores) {
        return conBuscador(ed, nivel, cabecera, habilitado, alElegir, valores, n -> null);
    }

    /** V80: editorDe: el editor de otro campo del mismo formulario / renglón (para completar un padre libre al elegir). */
    private Component conBuscador(OptimistTrnCampos.Editor ed, Nivel nivel, Nivel cabecera, boolean habilitado,
                                  Runnable alElegir, Supplier<Map<String, Object>> valores,
                                  java.util.function.Function<String, OptimistTrnCampos.Editor> editorDe) {
        String nombre = ed.campo().nombre();
        Referencia ref = nivel.referencias().stream()
                .filter(r -> ultimo(r.campos().keySet()).equalsIgnoreCase(nombre)).findFirst().orElse(null);
        if (ref == null || !habilitado) return ed.componente();
        String local = ref.campos().keySet().stream().filter(k -> k.equalsIgnoreCase(nombre)).findFirst().orElse(nombre);
        Button lupa = new Button(VaadinIcon.SEARCH.create(), e -> {
            Map<String, Object> actuales = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            actuales.putAll(valores.get());
            // el padre tiene que estar cargado antes de buscar el hijo
            for (String k : ref.campos().keySet()) {
                if (k.equalsIgnoreCase(local) || !OptimistTrnDatos.vacio(actuales.get(k))) continue;
                // V65: el padre oculto que todavía no se completó (renglón sin tocar): su valor por defecto o el de la cabecera
                // que le copia una regla (STAlmacenesEmpresa = MovStockEmpresa): la empresa / división con que se trabaja
                Object auto = valorPadreAuto(k, nivel, cabecera, actuales);
                if (!OptimistTrnDatos.vacio(auto)) { actuales.put(k, auto); continue; }
                Campo padre = nivel.campo(k) != null ? nivel.campo(k) : cabecera == null ? null : cabecera.campo(k);
                // V80: un padre de solo lectura (no se puede elegir antes): la lupa no filtra por él y lo completa al elegir
                // (ej. la Clase en Modelos de Comprobantes, que en GeneXus completaba el Prompt del Tipo)
                if (padre != null && !padre.editable() && editorDe.apply(padre.nombre()) != null) {
                    actuales.put(OptimistTrnDatos.LIBRE + padre.nombre(), true);
                    continue;
                }
                Notification.show("Elegí primero " + (padre == null ? k : padre.etiqueta()), 4000, Notification.Position.MIDDLE);
                return;
            }
            buscador(ref, local, actuales, nivel, cabecera, fila -> {
                // V80: primero los padres libres (con lo de la fila elegida), después el campo de la lupa
                for (Map.Entry<String, Object> x : fila.entrySet()) {
                    if (!x.getKey().startsWith(OptimistTrnDatos.LIBRE)) continue;
                    OptimistTrnCampos.Editor ep = editorDe.apply(x.getKey().substring(OptimistTrnDatos.LIBRE.length()));
                    if (ep != null) { try { ep.escribir().accept(x.getValue()); } catch (RuntimeException ignorado) { } }
                }
                ed.escribir().accept(fila.get("clave"));
                alElegir.run();
            });
        });
        lupa.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        lupa.getElement().setAttribute("title", "Buscar " + ed.campo().etiqueta());
        HorizontalLayout h = new HorizontalLayout(ed.componente(), lupa);
        h.setSpacing(false);
        h.setAlignItems(FlexComponent.Alignment.END);
        return h;
    }

    /**
     * V65: el valor de un campo padre de una lupa que todavía está vacío: el 'default' del campo (ej. "&EmpStk") o, si una
     * regla sin condición lo copia de otro campo (STAlmacenesEmpresa = MovStockEmpresa;), el valor de ese campo. null si no hay.
     */
    private Object valorPadreAuto(String k, Nivel nivel, Nivel cabecera, Map<String, Object> actuales) {
        Campo c = nivel.campo(k) != null ? nivel.campo(k) : cabecera == null ? null : cabecera.campo(k);
        if (c != null && c.defectoActual() != null) return c.defectoActual();
        try {
            OptimistReglas r = OptimistReglas.de(trn);
            if (r == null) return null;
            for (OptimistReglas.Regla x : r.reglas) {
                if (x.tipo != OptimistReglas.Tipo.ASIGNAR || x.destino == null || !x.destino.equalsIgnoreCase(k)) continue;
                if (x.cond != null || !x.eventos.isEmpty() || x.expr == null || x.expr.atributos().size() != 1) continue;
                String origen = x.expr.atributos().iterator().next();
                Object v = actuales.get(origen);
                if (v == null) {
                    Campo co = nivel.campo(origen) != null ? nivel.campo(origen) : cabecera == null ? null : cabecera.campo(origen);
                    if (co != null) v = co.defectoActual();
                }
                if (!OptimistTrnDatos.vacio(v)) return v;
            }
        } catch (RuntimeException ignorado) { }
        return null;
    }

    /**
     * V50c: campo con 'enlace: TRN': se ve como un vínculo (color del acento, manito) y al tocarlo se abre esa TRN en ese registro,
     * como la navegación de pantalla a pantalla de GeneXus. El formulario actual queda en el historial: Atrás vuelve a él.
     */
    private void enlazar(Form f, OptimistTrnCampos.Editor ed, OptimistTrnDefinicion.Enlace destino) {
        var el = ed.componente().getElement();
        el.getStyle().set("cursor", "pointer").set("--vaadin-input-field-value-color", "var(--lumo-primary-text-color)")
                .set("--lumo-secondary-text-color", "var(--lumo-primary-text-color)").set("text-decoration", "underline");
        el.setAttribute("title", "Ver " + destino.trn());
        el.addEventListener("click", e -> {
            Object v = destino.clave() == null ? ed.leer().get() : f.valoresCabecera().get(destino.clave());
            if (OptimistTrnDatos.vacio(v) || (v instanceof Number nn && new java.math.BigDecimal(nn.toString()).signum() == 0)) return;
            if (f.dialogo != null) {
                cerrandoPorNavegacion = true;   // sin volver atrás en el historial: el presupuesto queda como paso anterior
                try { f.dialogo.close(); } finally { cerrandoPorNavegacion = false; }
            }
            abrirTrn(destino.trn(), "Display", v);   // V50e: siempre en Visualizar
        });
    }

    /** V50e: la dirección que abre en Visualizar la TRN del enlace de esta fila del WorkWith, o null si no hay clave. */
    private static String hrefEnlace(OptimistTrnDefinicion.Enlace en, String campo, Map<String, Object> fila) {
        Object k = fila.get(en.clave() == null ? campo : en.clave());
        if (OptimistTrnDatos.vacio(k) || (k instanceof Number nn && new java.math.BigDecimal(nn.toString()).signum() == 0)) return null;
        Trn otra = OptimistPanelView.trn(en.trn());
        return otra == null ? null : "trn/" + otra.ruta() + "?ver=" + java.net.URLEncoder.encode(String.valueOf(k).strip(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** V49u: el control del campo; con 'combo: {tabla, valor, texto}', un combo con los registros de esa tabla. */
    private OptimistTrnCampos.Editor crearEditor(Nivel nivel, Campo c, boolean enLinea, boolean enGrilla) {
        OptimistTrnDefinicion.ComboTabla ct = OptimistTrnDefinicion.comboTabla(nivel.tabla(), c.nombre());
        if (ct == null) return OptimistTrnCampos.crear(c, enLinea, enGrilla);
        List<Object[]> items = combosTabla.computeIfAbsent(ct, k -> {
            List<Object[]> r = new ArrayList<>();
            String sql = k.sql();   // V66t: con su donde
            try (java.sql.Connection cn = db.openConnection(); java.sql.Statement st = cn.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) r.add(new Object[]{rs.getObject(1), rs.getString(2) == null ? "" : rs.getString(2).strip()});
            } catch (SQLException ex) { System.out.println("[" + trn.nombre() + "] combo de " + k.tabla() + ": " + ex.getMessage()); }
            return r;
        });
        com.vaadin.flow.component.combobox.ComboBox<Object[]> cb = new com.vaadin.flow.component.combobox.ComboBox<>(enLinea ? null : c.etiqueta());
        cb.setItems(items);
        cb.setItemLabelGenerator(x -> String.valueOf(x[1]));
        cb.setClearButtonVisible(ct.vacio());
        if (ct.vacioTexto() != null) cb.setPlaceholder(ct.vacioTexto());   // V66t
        cb.setWidth(enGrilla ? OptimistTrnCampos.anchoGrilla(c) : "min(320px,100%)");
        cb.setRequiredIndicatorVisible(c.obligatorio());
        if (enLinea) cb.setAriaLabel(c.etiqueta());
        return new OptimistTrnCampos.Editor(c, cb, () -> cb.getValue() == null ? null : cb.getValue()[0],
                v -> cb.setValue(v == null ? null : items.stream().filter(x -> OptimistTrnDatos.igual(x[0], v)).findFirst().orElse(null)),
                cb::setReadOnly, r -> cb.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }));
    }

    /** V49u: los registros de cada combo de tabla (se leen una vez por pantalla). */
    private final Map<OptimistTrnDefinicion.ComboTabla, List<Object[]>> combosTabla = new HashMap<>();

    /** V49u: el campo con la lupa propia que arma un procedimiento (ej. el LP del presupuesto: precios del artículo por lista). */
    private Component conLupaProc(Form f, Nivel det, FilaDetalle fila, OptimistTrnCampos.Editor ed, OptimistTrnDefinicion.LupaProc lp) {
        return conLupaProc(ed, lp, () -> {
            Map<String, Object> v = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            v.putAll(f.valoresCabecera());
            v.putAll(fila.inferidos());
            v.putAll(fila.valores());
            abrirLupaProc(f, det, lp, v, fila.editores(), fila.inferidos(), campo -> cambioFila(f, det, fila, campo));
        });
    }

    /** V65: la lupa de procedimiento también en la cabecera (ej. el almacén del artículo en Marbetes, DEVO, OTR). */
    private Component conLupaProcCabecera(Form f, OptimistTrnCampos.Editor ed, OptimistTrnDefinicion.LupaProc lp) {
        return conLupaProc(ed, lp, () -> {
            Map<String, Object> v = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            v.putAll(f.valoresCabecera());
            abrirLupaProc(f, trn.cabecera(), lp, v, f.cab, f.inferidos, campo -> cambioCabecera(f, campo));
        });
    }

    private Component conLupaProc(OptimistTrnCampos.Editor ed, OptimistTrnDefinicion.LupaProc lp, Runnable abrir) {
        Button lupa = new Button(VaadinIcon.SEARCH.create(), e -> abrir.run());
        lupa.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        lupa.getElement().setAttribute("title", lp.titulo() != null ? lp.titulo() : "Buscar " + ed.campo().etiqueta());
        HorizontalLayout h = new HorizontalLayout(ed.componente(), lupa);
        h.setSpacing(false);
        h.setAlignItems(FlexComponent.Alignment.END);
        return h;
    }

    @SuppressWarnings("unchecked")
    private void abrirLupaProc(Form f, Nivel det, OptimistTrnDefinicion.LupaProc lp, Map<String, Object> v,
                               Map<String, OptimistTrnCampos.Editor> editores, Map<String, Object> inferidos, Consumer<String> cambio) {
        List<Object> args = new ArrayList<>();
        for (String p : lp.parametros()) {
            Object a = p.startsWith("&") ? (f.estado == null ? null : f.estado.variable(p)) : v.get(p);
            if (!p.startsWith("&") && OptimistTrnDatos.vacio(a)) {   // V65: ej. el almacén sin el artículo cargado
                Campo pc = det.campo(p) != null ? det.campo(p) : trn.cabecera().campo(p);
                Notification.show("Elegí primero " + (pc == null ? p : pc.etiqueta()), 4000, Notification.Position.MIDDLE);
                return;
            }
            args.add(a);
        }
        List<Map<String, Object>> filas = new ArrayList<>();
        java.util.Set<String> sinProceso = new java.util.HashSet<>();   // V55: columnas cuyo proceso no está habilitado
        try (java.sql.Connection c = db.openConnection()) {
            for (Map.Entry<String, Integer> pr : lp.procesos().entrySet())
                if (!OptimistProcedimientos.procesoHabilitado(c, pr.getValue())) sinProceso.add(pr.getKey().toLowerCase(Locale.ROOT));
            Map<String, Object> salida = OptimistProcedimientos.ejecutar(lp.proc(), args, c, new ArrayList<>());
            for (Object x : salida.values())
                if (x instanceof List<?> l) { for (Object y : l) if (y instanceof Map<?, ?> m) filas.add((Map<String, Object>) m); break; }
        } catch (SQLException | RuntimeException ex) {
            Notification.show(lp.proc() + ": " + ex.getMessage(), 8000, Notification.Position.MIDDLE);
            return;
        }
        if (filas.isEmpty()) { Notification.show("No hay datos para elegir", 4000, Notification.Position.MIDDLE); return; }
        Dialog d = new Dialog();
        OptimistVisuals.estilizarDialogo(d);
        d.setHeaderTitle(lp.titulo() != null ? lp.titulo() : "Elegir");
        OptimistVisuals.botonCerrar(d);   // V65
        d.setWidth("min(900px,96vw)");
        Grid<Map<String, Object>> g = new Grid<>();
        g.addThemeName("optimist-grid");
        java.util.function.Consumer<Map<String, Object>> elegir = m -> {
            d.close();
            // como el Prompt de GeneXus: cada campo que devuelve, y sus reglas, en orden (el precio elegido queda último)
            for (Map.Entry<String, String> e : lp.devuelve().entrySet()) {
                Object val = m.get(e.getValue());
                OptimistTrnCampos.Editor x = editorDe(editores, e.getKey());
                if (x != null) {
                    try { x.escribir().accept(val); } catch (RuntimeException ex) { inferidos.put(e.getKey(), val); }
                } else inferidos.put(e.getKey(), val);
                cambio.accept(x != null ? x.campo().nombre() : e.getKey());
            }
        };
        g.addComponentColumn(m -> {
            Button tilde = new Button(VaadinIcon.CHECK.create(), e -> elegir.accept(m));
            tilde.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            tilde.getStyle().set("color", "var(--lumo-primary-text-color)").set("cursor", "pointer");
            tilde.getElement().setAttribute("title", "Elegir");
            return tilde;
        }).setWidth("56px").setFlexGrow(0);
        java.text.DecimalFormat num = new java.text.DecimalFormat("#,##0.00##", java.text.DecimalFormatSymbols.getInstance(new Locale("es", "AR")));
        java.time.format.DateTimeFormatter fec = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        lp.columnas().forEach((campo, titulo) -> {
            if (sinProceso.contains(campo.toLowerCase(Locale.ROOT))) return;
            boolean numerica = filas.stream().map(m -> m.get(campo)).filter(Objects::nonNull).findFirst().orElse(null) instanceof Number;
            Grid.Column<Map<String, Object>> col = g.addColumn(m -> {
                Object x = m.get(campo);
                if (x == null) return "";
                if (x instanceof java.math.BigDecimal b && b.scale() > 0) return num.format(b);
                if (x instanceof java.time.LocalDate ld) return ld.getYear() <= 1 ? "" : fec.format(ld);
                if (x instanceof java.time.LocalDateTime lt) return lt.getYear() <= 1 ? "" : fec.format(lt);
                return String.valueOf(x).strip();
            }).setHeader(titulo).setAutoWidth(true).setResizable(true);
            if (numerica) col.setTextAlign(com.vaadin.flow.component.grid.ColumnTextAlign.END);
        });
        g.setItems(filas);
        g.addItemDoubleClickListener(e -> elegir.accept(e.getItem()));
        g.setHeight(Math.min(480, 60 + filas.size() * 40) + "px");
        d.add(g);
        OptimistVisuals.pieIzquierda(d, new Button("Cancelar", e -> d.close()));
        d.open();
    }

    /**
     * V47: si el campo es la clave interna de una tabla con código (codigos: en dominios.yaml), el control muestra y
     * acepta el CÓDIGO (ArticulosId "TTESP0500002", EntidadesId 2222); por detrás se sigue leyendo y escribiendo la
     * clave interna (ArticulosPK 75), que es lo que se guarda, lo que usan las reglas y lo que trae la descripción.
     * Al escribir un código se busca su clave respetando el 'donde' de la referencia (ej. solo clientes según el CPI);
     * si no existe, el campo queda marcado; si hay más de uno (cliente y proveedor con el mismo código), se elige.
     * inicial: los valores con que se abrió el registro (si ya traen el código, no se vuelve a buscar).
     */
    private OptimistTrnCampos.Editor conCodigo(OptimistTrnCampos.Editor ed, Nivel nivel, Supplier<Map<String, Object>> valores,
                                               Map<String, Object> inicial) {
        Referencia ref = OptimistTrnDatos.referenciaConCodigo(nivel, ed.campo().nombre());
        if (ref == null) return ed;
        Campo c = ed.campo();
        TextField t = new TextField();
        String etiqueta = ed.componente().getElement().getProperty("label");
        if (etiqueta != null && !etiqueta.isEmpty()) t.setLabel(etiqueta);
        else t.setAriaLabel(c.etiqueta());
        String ancho = ed.componente().getElement().getStyle().get("width");
        if (ancho != null) t.setWidth(ancho);
        t.setRequiredIndicatorVisible(c.obligatorio());
        t.getElement().setAttribute("title", c.etiqueta() + ": " + ref.codigo().columna());
        // el campo local que la referencia ya trae con el código (PRS trae ArticulosId): al abrir, no hace falta buscarlo
        String localCodigo = ref.trae().entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(ref.codigo().columna()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        Object[] clave = {null};
        boolean[] primera = {true};
        List<Runnable> oyentes = new ArrayList<>();
        Consumer<Object> escribir = v -> {
            clave[0] = v;
            t.setInvalid(false);
            if (OptimistTrnDatos.vacio(v)) { t.setValue(""); primera[0] = false; return; }
            String cod = null;
            if (primera[0] && localCodigo != null && OptimistTrnDatos.igual(inicial.get(c.nombre()), v)
                    && !OptimistTrnDatos.vacio(inicial.get(localCodigo)))
                cod = String.valueOf(inicial.get(localCodigo)).stripTrailing();
            primera[0] = false;
            if (cod == null) {
                try { cod = datos.codigoDe(ref, v); } catch (SQLException ex) { error(ex); }
            }
            t.setValue(cod != null ? cod : String.valueOf(v));
        };
        t.addValueChangeListener(e -> {
            if (!e.isFromClient()) return;
            String txt = e.getValue() == null ? "" : e.getValue().trim();
            if (txt.isEmpty()) { clave[0] = null; t.setInvalid(false); oyentes.forEach(Runnable::run); return; }
            List<Map<String, Object>> hallados;
            try {
                Map<String, Object> act = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                act.putAll(valores.get());
                hallados = datos.porCodigo(ref, txt, act::get, c);
            } catch (SQLException ex) { error(ex); return; }
            Consumer<Map<String, Object>> elegir = h -> {
                clave[0] = h.get("clave");
                t.setInvalid(false);
                t.setValue(String.valueOf(h.get("codigo")));
                oyentes.forEach(Runnable::run);
            };
            if (hallados.size() == 1) elegir.accept(hallados.get(0));
            else if (hallados.isEmpty()) {
                clave[0] = null;
                // V65: existe pero no cumple el 'donde' (ej. un artículo que no está activo): se dice eso, no "no existe"
                String motivo = null;
                if (!ref.donde().isEmpty()) {
                    try {
                        Referencia sinDonde = new Referencia(ref.tabla(), ref.campos(), ref.trae(), ref.descripcion(), new LinkedHashMap<>(), ref.codigo());
                        Map<String, Object> act2 = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                        act2.putAll(valores.get());
                        if (!datos.porCodigo(sinDonde, txt, act2::get, c).isEmpty()) {
                            String m = OptimistTrnDefinicion.mensajeReferencia(nivel.tabla(), ref);
                            motivo = txt + " " + (m != null ? m : "no está habilitado");
                        }
                    } catch (SQLException ignorado) { }
                }
                t.setErrorMessage(motivo != null ? motivo : "No existe " + txt + " en " + ref.tabla());
                t.setInvalid(true);
                oyentes.forEach(Runnable::run);
            } else elegirEntre(ref, c, txt, hallados, elegir);
        });
        return new OptimistTrnCampos.Editor(c, t, () -> clave[0], escribir, t::setReadOnly, oyentes::add);
    }

    /** V47: el mismo código en más de un registro (un cliente y un proveedor con el mismo número): el usuario elige. */
    private void elegirEntre(Referencia ref, Campo c, String codigo, List<Map<String, Object>> hallados, Consumer<Map<String, Object>> elegir) {
        Dialog d = new Dialog();
        OptimistVisuals.estilizarDialogo(d);
        d.setHeaderTitle(codigo + ": hay " + hallados.size() + " en " + ref.tabla());
        OptimistVisuals.botonCerrar(d);   // V65
        d.setWidth("min(560px,96vw)");
        Grid<Map<String, Object>> g = new Grid<>();
        g.addThemeName("optimist-grid");
        g.addComponentColumn(m -> {
            Button tilde = new Button(VaadinIcon.CHECK.create(), e -> { d.close(); elegir.accept(m); });
            tilde.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            tilde.getElement().setAttribute("title", "Elegir");
            return tilde;
        }).setWidth("56px").setFlexGrow(0);
        g.addColumn(m -> String.valueOf(m.get("codigo"))).setHeader("Código").setAutoWidth(true).setFlexGrow(0);
        g.addColumn(m -> String.valueOf(m.get("descripcion"))).setHeader("Descripción");
        if (ref.codigo().segun() != null)
            g.addColumn(m -> tipoEntidad(ref.codigo().segun(), m.get("segun"))).setHeader("Tipo").setAutoWidth(true).setFlexGrow(0);
        g.setItems(hallados);
        g.setAllRowsVisible(true);
        g.addItemDoubleClickListener(e -> { d.close(); elegir.accept(e.getItem()); });
        d.add(g);
        OptimistVisuals.pieIzquierda(d, new Button("Cancelar", e -> d.close()));
        d.open();
    }

    /** Texto del 'segun' (E3: lo da la aplicación; en OPTIMIST ERP, EntidadesTipoEntidad = Cliente / Proveedor); si no, el valor tal cual. */
    private static String tipoEntidad(String columna, Object v) {
        if (v == null) return "";
        for (OptimistExtension e : OptimistExtension.todas()) {
            String t = e.textoSegun(columna, v);
            if (t != null) return t;
        }
        return String.valueOf(v);
    }

    /** V48: el campo lleva lupa (es el último de la clave de una referencia del nivel y se puede editar). */
    private static boolean tieneLupa(Nivel nivel, Campo c) {
        if (c.editable() && OptimistTrnDefinicion.lupaProc(nivel.tabla(), c.nombre()) != null) return true;   // V49u
        return c.editable() && nivel.referencias().stream().anyMatch(r -> ultimo(r.campos().keySet()).equalsIgnoreCase(c.nombre()));
    }

    private static String ultimo(Collection<String> claves) {
        String u = "";
        for (String k : claves) u = k;
        return u;
    }

    /** V48: la TRN cuya tabla es la de la referencia, si tiene 'workwith.prompt' (columnas de la lupa con filtro); si no, null. */
    private static Trn destinoConPrompt(Referencia ref) {
        try {
            for (Trn t : OptimistTrnCatalogo.cargar().trns().values())
                if (t.cabecera().tabla().equalsIgnoreCase(ref.tabla()) && !t.workwith().prompt().isEmpty()) return t;
        } catch (RuntimeException ex) { System.out.println("prompt de " + ref.tabla() + ": " + ex.getMessage()); }
        return null;
    }

    /** V48: lupa con las columnas de 'workwith.prompt' de la TRN destino, un filtro por columna (como el prompt de GeneXus).
     *  V50k: con OptimistLupa (la misma de todo OPTIMIST): filtros por tipo como el WorkWith y carga por páginas. */
    private void buscadorPrompt(Trn destino, Referencia ref, String local, Map<String, Object> padres, Nivel nivel, Nivel cabecera,
                                Consumer<Map<String, Object>> elegir) {
        List<OptimistLupa.Columna> cols = new ArrayList<>(columnasLibres(padres, nivel, cabecera));   // V80
        for (String n : destino.workwith().prompt()) {
            Campo c = destino.cabecera().campo(n);
            cols.add(new OptimistLupa.Columna(n, destino.workwith().tituloPrompt(c), c.dominio(), v -> OptimistTrnCampos.mostrar(c, v)));
        }
        OptimistLupa.abrir("Buscar " + destino.singular(), cols,
                (filtros, limite, desde) -> datos.buscarPrompt(destino, ref, local, padres, nivel, cabecera, filtros, limite, desde),
                elegir);
    }

    /** V80: las columnas de la lupa para los padres libres (ver OptimistTrnDatos.LIBRE): su etiqueta y su valor como se ve. */
    private static List<OptimistLupa.Columna> columnasLibres(Map<String, Object> padres, Nivel nivel, Nivel cabecera) {
        List<OptimistLupa.Columna> r = new ArrayList<>();
        for (String k : padres.keySet()) {
            if (!k.startsWith(OptimistTrnDatos.LIBRE)) continue;
            String n = k.substring(OptimistTrnDatos.LIBRE.length());
            Campo c = nivel.campo(n) != null ? nivel.campo(n) : cabecera == null ? null : cabecera.campo(n);
            if (c == null) continue;
            r.add(new OptimistLupa.Columna(k, c.etiqueta(), c.dominio().esCombo() ? null : c.dominio(), v -> OptimistTrnCampos.mostrar(c, v)));
        }
        return r;
    }

    private void buscador(Referencia ref, String local, Map<String, Object> padres, Nivel nivel, Nivel cabecera, Consumer<Map<String, Object>> elegir) {
        Trn destino = destinoConPrompt(ref);   // V48
        if (destino != null) { buscadorPrompt(destino, ref, local, padres, nivel, cabecera, elegir); return; }
        // V50k: la lupa simple también con OptimistLupa: Código y Descripción, cada una con su filtro, y carga por páginas
        Campo fc = nivel.campo(local) != null ? nivel.campo(local) : cabecera.campo(local);
        List<OptimistLupa.Columna> cols = new ArrayList<>(columnasLibres(padres, nivel, cabecera));   // V80: primero los padres libres
        cols.add(new OptimistLupa.Columna("codigo", "Código", ref.conCodigo() || fc == null ? null : fc.dominio(),
                        v -> OptimistLupa.texto(v)));
        cols.add(new OptimistLupa.Columna("descripcion", "Descripción", null, OptimistLupa::texto));
        OptimistLupa.abrir("Buscar en " + ref.tabla(), cols,
                (filtros, limite, desde) -> {
                    List<Map<String, Object>> r = datos.buscar(ref, local, padres, nivel, cabecera, null, filtros, limite, desde);
                    if (limite >= 0) for (Map<String, Object> m : r) m.putIfAbsent("codigo", m.get("clave"));   // V68: < 0 = el total
                    return r;
                },
                elegir);
    }

    // ------------------------------------------------------------------ detalles

    private Component bloqueDetalle(Form f, Nivel det) {
        List<FilaDetalle> filas = new ArrayList<>();
        f.filas.put(det.nombre(), filas);
        VerticalLayout renglones = new VerticalLayout();
        renglones.setPadding(false);
        renglones.setSpacing(false);
        renglones.setWidth(null);
        if (!f.nuevo) f.actual.lineas(det.nombre()).forEach(l -> agregarFila(f, det, filas, renglones, l));
        if (!f.soloVer) for (int i = 0; i < 5; i++) agregarFila(f, det, filas, renglones, null);
        HorizontalLayout encabezado = encabezadoDetalle(f, det);
        f.encabezados.put(det.nombre(), encabezado);
        VerticalLayout grilla = new VerticalLayout(encabezado, renglones);
        grilla.setPadding(false);
        grilla.setSpacing(false);
        grilla.setWidth(null);
        f.grillas.put(det.nombre(), grilla);
        Div desplazable = new Div(grilla);
        desplazable.setWidthFull();
        desplazable.getStyle().set("overflow-x", "auto");
        // V49q: la grilla del detalle tampoco se corre sola a la izquierda al tocar o cambiar un campo
        desplazable.addAttachListener(ev -> desplazable.getElement().executeJs(OptimistWorkWith.Pantalla.FIJAR_DESPLAZAMIENTO));
        H3 tituloDet = new H3(det.titulo());
        f.titulosDetalle.put(det.nombre().toLowerCase(Locale.ROOT), tituloDet);   // V65f: <detalle X sinTitulo> lo oculta
        VerticalLayout bloque = new VerticalLayout(tituloDet, desplazable);
        bloque.setPadding(false);
        bloque.setWidthFull();
        if (!f.soloVer) {
            Button agregar = new Button("Agregar Filas", e -> { for (int i = 0; i < 5; i++) agregarFila(f, det, filas, renglones, null); });
            Span aviso = new Span("Las filas vacías se ignoran" + (det.numerador() != null ? "; la numeración se asigna al guardar." : "."));
            aviso.getStyle().set("color", "#9fb8c5");
            bloque.add(agregar, aviso);
        }
        return bloque;
    }

    private HorizontalLayout encabezadoDetalle(Form f, Nivel det) {
        Span espacio = new Span();
        // V66k: la papelera y las lupas 'ver:' del renglón
        String anchoEsp = (44 + 40 * OptimistTrnDefinicion.ver(trn.nombre(), det.nombre()).size()) + "px";
        espacio.setWidth(anchoEsp);
        espacio.setMinWidth(anchoEsp);
        HorizontalLayout h = new HorizontalLayout(espacio);
        for (Campo c : det.campos()) {
            if (!visibleDet(det, c) || (det.numerador() != null && c.clave())) continue;
            Span s = new Span(c.etiqueta() + (c.obligatorio() && c.editable() ? " *" : ""));
            if (c.oculto()) s.setVisible(Boolean.TRUE.equals(f.columnas.get(det.nombre() + "." + c.nombre().toLowerCase(Locale.ROOT))));
            String ancho = OptimistTrnCampos.anchoGrilla(c, tieneLupa(det, c) && !f.soloVer);
            s.setWidth(ancho);
            s.setMinWidth(ancho);
            s.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--optimist-texto)").set("opacity", "0.85");
            f.titulosColumna.put(det.nombre() + "." + c.nombre().toLowerCase(Locale.ROOT), s);
            h.add(s);
        }
        return h;
    }

    private void agregarFila(Form f, Nivel det, List<FilaDetalle> filas, VerticalLayout renglones, Linea linea) {
        Map<String, OptimistTrnCampos.Editor> eds = new LinkedHashMap<>();
        Button quitar = new Button(VaadinIcon.TRASH.create());
        quitar.getElement().setAttribute("title", "Quitar línea");
        quitar.setEnabled(!f.soloVer);
        quitar.setWidth("44px");
        quitar.setMinWidth("44px");
        HorizontalLayout renglon = new HorizontalLayout(quitar);
        renglon.setAlignItems(FlexComponent.Alignment.CENTER);
        // V66k: las lupas 'ver:' del detalle, en cada renglón grabado (ej. Asientos: las imputaciones del renglón). En los
        // renglones nuevos (o donde no se cumple su condición) no se ven, pero ocupan su lugar: las columnas quedan alineadas.
        for (OptimistTrnDefinicion.Ver v : OptimistTrnDefinicion.ver(trn.nombre(), det.nombre())) {
            Button bv = new Button(VaadinIcon.SEARCH.create());
            bv.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            bv.getElement().setAttribute("title", v.titulo());
            bv.setWidth("40px");
            bv.setMinWidth("40px");
            boolean ver = false;
            if (linea != null) {
                try {
                    Map<String, Object> vals = new LinkedHashMap<>(f.valoresCabecera());
                    vals.putAll(linea.valores());
                    ver = cumple(v.si(), vals);
                } catch (RuntimeException ignorado) { }
            }
            if (!ver) bv.getStyle().set("visibility", "hidden");
            bv.addClickListener(e -> {
                if (linea == null) return;
                Map<String, Object> vals = new LinkedHashMap<>(f.valoresCabecera());
                vals.putAll(linea.valores());
                verConsulta(f, f.dialogo, v, vals);
            });
            renglon.add(bv);
        }
        FilaDetalle fila = new FilaDetalle(linea, eds, new HashMap<>());
        // V65: renglón nuevo: los campos ocultos con 'default' (sin editor) ya tienen su valor (las lupas y las reglas los usan)
        if (linea == null)
            for (Campo c : det.campos())
                if (!visibleDet(det, c) && c.persistido() && c.defectoActual() != null) fila.inferidos().put(c.nombre(), c.defectoActual());
        for (Campo c : det.campos()) {
            if (!visibleDet(det, c)) continue;
            if (det.numerador() != null && c.clave()) continue; // la asigna el numerador
            OptimistTrnCampos.Editor ed = conCodigo(crearEditor(det, c, true, true), det, () -> {   // V47: se ve el código
                Map<String, Object> v = new LinkedHashMap<>(f.valoresCabecera());
                if (fila.original() != null) v.putAll(fila.original().valores());
                for (OptimistTrnCampos.Editor x : fila.editores().values()) v.put(x.campo().nombre(), x.leer().get());
                return v;
            }, linea == null ? Map.of() : linea.valores());
            if (linea != null) ed.escribir().accept(linea.valores().get(c.nombre()));
            else if (c.defectoActual() != null) ed.escribir().accept(c.defectoActual());
            else if (fechaDeHoy(c) && !f.soloVer) ed.escribir().accept(java.time.LocalDate.now());   // V65: la fecha arranca en hoy
            boolean ro = f.soloVer || !c.editable() || (linea != null && (c.clave() || !c.editableEnModificacion()));
            ed.soloLectura().accept(ro);
            aplicarEstilo(c, ed.componente(), null);   // V45: el estilo (y la clase) también en las líneas del detalle
            f.soloLecturaBase.put(ed, ro);
            if (!ro) ed.alCambiar().accept(() -> cambioFila(f, det, fila, c.nombre()));   // V35: referencias, reglas y fórmulas
            eds.put(c.nombre(), ed);
            OptimistTrnDefinicion.LupaProc lp = OptimistTrnDefinicion.lupaProc(det.tabla(), c.nombre());   // V49u
            Component pieza = lp != null ? (ro ? ed.componente() : conLupaProc(f, det, fila, ed, lp))
                    : conBuscador(ed, det, trn.cabecera(), !ro, () -> cambioFila(f, det, fila, c.nombre()), () -> {
                Map<String, Object> v = new LinkedHashMap<>(f.valoresCabecera());
                if (fila.original() != null) v.putAll(fila.original().valores());
                for (OptimistTrnCampos.Editor x : fila.editores().values()) v.put(x.campo().nombre(), x.leer().get());
                return v;
            }, n -> eds.get(n));   // V80: para completar un padre libre del renglón
            if (pieza instanceof HorizontalLayout h && pieza != ed.componente()) {
                String ancho = OptimistTrnCampos.anchoGrilla(c, true);
                h.setWidth(ancho);
                h.setMinWidth(ancho);
                ed.componente().getElement().getStyle().set("flex", "1 1 auto").set("min-width", "0");
            } else if (tieneLupa(det, c) && !f.soloVer) {
                // V48: sin lupa en esta fila (solo lectura), igual ocupa el ancho de la columna, que la incluye
                String ancho = OptimistTrnCampos.anchoGrilla(c, true);
                ed.componente().getElement().getStyle().set("width", ancho).set("min-width", ancho);
            }
            renglon.add(pieza);
        }
        filas.add(fila);
        f.renglonDe.put(fila, renglon);
        if (f.completos.contains(det.nombre().toLowerCase(Locale.ROOT))) estirarRenglon(renglon);   // V38: filas agregadas después
        quitar.addClickListener(e -> { filas.remove(fila); renglones.remove(renglon); f.renglonDe.remove(fila); cambioFila(f, det, null, null); });
        // V35: columnas que las reglas ocultaron, también en las filas nuevas
        for (OptimistTrnCampos.Editor ed : eds.values()) {
            Boolean vis = f.columnas.get(det.nombre() + "." + ed.campo().nombre().toLowerCase(Locale.ROOT));
            if (vis != null) piezaDe(ed).setVisible(vis);
            else if (ed.campo().oculto()) piezaDe(ed).setVisible(false);   // V49o: oculta hasta que su regla la muestre
        }
        estiloRenglon(f, det, fila);   // V50x
        if (f.reglas != null && !f.aplicandoReglas && f.estado != null) aplicarEfecto(f, det, fila,
                f.reglas.aplicar(linea == null ? OptimistReglas.Momento.PANTALLA : OptimistReglas.Momento.CARGA, null, det, fila.valores(), f.valoresCabecera(), null, new HashSet<>(),
                        f.estado, datos.abridor(), null));
        renglones.add(renglon);
    }

    // ------------------------------------------------------------------ borrar

    private void confirmarBorrado(Map<String, Object> fila) {
        ConfirmDialog confirmar = new ConfirmDialog();
        confirmar.setHeader("Borrar " + trn.singular() + " " + clave(fila));
        String detalles = trn.detalles().stream().map(d -> d.titulo().toLowerCase(Locale.ROOT)).reduce((a, b) -> a + ", " + b).orElse("");
        confirmar.setText("¿Querés borrar " + trn.singular() + " " + clave(fila)
                + (trn.dosNiveles() ? " y sus " + detalles : "")
                + "? Esta operación no se puede deshacer. Si existen referencias desde otras tablas, MySQL puede impedir el borrado.");
        confirmar.setCancelable(true);
        confirmar.setCancelText("Cancelar");
        confirmar.setConfirmText("Borrar");
        confirmar.setConfirmButtonTheme("error primary");
        confirmar.addConfirmListener(e -> {
            try { datos.borrar(trn, claveDe(fila)); cargar(); Notification.show(capital(trn.singular()) + " borrad" + trn.genero()); }
            catch (SQLException ex) { error(ex); }
        });
        confirmar.open();
    }

    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private void error(SQLException e) { Notification.show("MySQL: " + e.getMessage(), 12000, Notification.Position.MIDDLE); }
}
