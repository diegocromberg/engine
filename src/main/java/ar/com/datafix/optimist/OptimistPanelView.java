package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Campo;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Trn;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import org.springframework.beans.factory.annotation.Autowired;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

/**
 * V48 - Pantalla de un WEB PANEL definido en wbp/<Nombre>.yaml (ver OptimistPanel).
 *  - Desde el menú: ruta wbp/<nombre> (ej. {titulo: Alta de Presupuesto, ruta: wbp/prsaltawbp} en menu.yaml).
 *  - Como ventana: desde el "+ Nuevo" de un WorkWith con 'workwith.alta: PRSAltaWbp'.
 * REGLA DE DISEÑO: web panels -> pantalla y botones centrados.
 */
@Route("wbp/:nombre")
public class OptimistPanelView extends VerticalLayout implements BeforeEnterObserver {

    private final OptimistDatabase db;

    public OptimistPanelView(@Autowired OptimistDatabase db) { this.db = db; }

    @Override
    public void beforeEnter(BeforeEnterEvent e) {
        if (OptimistShell.usuario() == null) { OptimistShell.alLogin(e); return; }
        if (OptimistVisorPdf.volviendoDePdf() && getChildren().findAny().isPresent()) return;   // V84h: el Atrás cerró una ventana
        String nombre = e.getRouteParameters().get("nombre").orElse("");
        OptimistShell.rutaActual("wbp/" + nombre.toLowerCase(Locale.ROOT));
        if (OptimistShell.sinPermiso(this, e, "wbp/" + nombre.toLowerCase(Locale.ROOT))) return;   // V48e
        OptimistPanel p;
        try { p = cargar(nombre); }
        catch (RuntimeException ex) {
            Span aviso = new Span("No se pudo abrir el panel " + nombre + ": " + ex.getMessage());
            aviso.getStyle().set("padding", "28px").set("color", "#ff8585");
            OptimistShell.montar(this, aviso, "Panel");
            return;
        }
        // V74: al volver con el navegador (ej. el Volver de la consulta que abrió un vínculo de una grilla de resultados) el
        // panel queda como estaba (lo cargado y lo procesado), en lugar de arrancar de nuevo
        // V76: ?resultado=1 es la pantalla del resultado (resultado: del YAML) del mismo panel: usa el que quedó guardado
        Map<String, List<String>> qps = new TreeMap<>(e.getLocation().getQueryParameters().getParameters());
        boolean pideResultado = qps.remove(RESULTADO) != null;
        qps.remove(OptimistVisorPdf.PARAM_PDF);   // V84h: el paso del PDF en el historial
        String claveUi = "optimist.panel." + nombre.toLowerCase(Locale.ROOT) + "|" + qps;
        Object guardado = com.vaadin.flow.component.ComponentUtil.getData(UI.getCurrent(), claveUi);
        if (pideResultado) {
            if (guardado instanceof Pantalla pg && pg.p.resultado != null) { montarResultado(pg); return; }
            e.forwardTo("wbp/" + nombre);   // sin el panel procesado (ej. recargó la página): al formulario
            return;
        }
        if ((e.getTrigger() == com.vaadin.flow.router.NavigationTrigger.HISTORY || OptimistVisorPdf.volviendoDePdf()) && guardado instanceof Pantalla pg) {
            montarPagina(pg, pg.p);
            return;
        }
        Pantalla pant = new Pantalla(p, db, null, ab -> {
            // V84e: OtroPanel.Call(...) desde un panel abierto como página: ese panel, en una ventana
            if (ab.panel()) {
                abrirDialogo(ab.trn(), db, ab.parametros(), ab2 -> {
                    Trn t2 = trn(ab2.trn());
                    if (t2 == null) return;
                    if (ab2.lista()) UI.getCurrent().navigate("trn/" + t2.ruta());
                    else UI.getCurrent().navigate("trn/" + t2.ruta(), com.vaadin.flow.router.QueryParameters.of("abrir", String.valueOf(ab2.clave())));
                }, null);
                return;
            }
            Trn t = trn(ab.trn());
            if (t == null) return;
            if (ab.lista()) { UI.getCurrent().navigate("trn/" + t.ruta()); return; }   // V66: WWxxx.Call()
            UI.getCurrent().navigate("trn/" + t.ruta(), com.vaadin.flow.router.QueryParameters.of("abrir", String.valueOf(ab.clave())));
        });
        com.vaadin.flow.component.ComponentUtil.setData(UI.getCurrent(), claveUi, pant);
        if (p.resultado != null) {   // V76: después de procesar, a la pantalla del resultado (queda en el historial del navegador)
            Map<String, List<String>> conResultado = new TreeMap<>(qps);
            conResultado.put(RESULTADO, List.of("1"));
            pant.irAResultado = () -> UI.getCurrent().navigate("wbp/" + nombre, new com.vaadin.flow.router.QueryParameters(conResultado));
        }
        montarPagina(pant, p);
        pant.iniciar();
    }

    private static final String RESULTADO = "resultado";

    /**
     * V76: la pantalla del resultado: el subtítulo, las variables del resultado (ej. el Ordenamiento) como barra, las grillas
     * a todo el ancho y los botones del resultado más Volver (vuelve al formulario como estaba, como el Atrás del navegador).
     */
    private void montarResultado(Pantalla pant) {
        pant.enResultado = true;
        VerticalLayout v = pant.armarResultado();
        OptimistShell.montar(this, v, pant.p.resultado.titulo());
        pant.refrescar();
    }

    private void montarPagina(Pantalla pant, OptimistPanel p) {
        pant.enResultado = false;
        pant.devolverAlFormulario();
        VerticalLayout centro = new VerticalLayout(pant.cuerpo, pant.botones);
        centro.setAlignItems(FlexComponent.Alignment.CENTER);
        // V66p: como página también respeta la densidad de los formularios (Apariencia), igual que en ventana
        centro.addClassName("optimist-formulario");
        pant.botones.addClassName("optimist-pie");
        // V82q: como página (no como ventana), el PostIt de la pantalla a la derecha de los botones, una sola vez
        if (pant.postIt == null) {
            Button sigma = pant.botonTotales();   // V84i: Σ, a la izquierda del PostIt
            if (sigma != null) pant.botones.add(sigma);
            pant.postIt = OptimistPostIts.boton(); pant.botones.add(pant.postIt);
        }
        centro.getStyle().set("padding", "var(--lumo-space-l)");
        OptimistShell.montar(this, centro, p.titulo);
    }

    /** Abre el panel como ventana (ej. el alta desde el WorkWith). alAbrir: lo que pide el panel al terminar (abrir una TRN). */
    public static void abrirDialogo(String nombre, OptimistDatabase db, Consumer<OptimistPanel.Abrir> alAbrir) {
        abrirDialogo(nombre, db, List.of(), alAbrir, null);
    }

    /**
     * V48: con parámetros (el Parm del panel, ej. desde una acción del renglón) y qué hacer al cerrarse
     * (ej. actualizar la lista del WorkWith).
     */
    public static void abrirDialogo(String nombre, OptimistDatabase db, List<Object> parametros,
                                    Consumer<OptimistPanel.Abrir> alAbrir, Runnable alCerrar) {
        OptimistPanel p;
        try { p = cargar(nombre); }
        catch (RuntimeException ex) {
            Notification.show("No se pudo abrir el panel " + nombre + ": " + ex.getMessage(), 12000, Notification.Position.MIDDLE);
            return;
        }
        Dialog d = new Dialog();
        OptimistVisuals.estilizarDialogo(d);
        d.setHeaderTitle(p.titulo);
        // V49l: el ancho de sus renglones (hasta el 96% de la ventana): con 980px fijos, un renglón de 4 campos con nombre se partía
        d.setWidth("auto");
        d.setMinWidth("min(600px,96vw)");
        d.setMaxWidth("96vw");
        Button ayudaPanel = OptimistAyuda.boton(nombre);   // V48: ayuda/<panel>.md, si la hay
        if (ayudaPanel != null) d.getHeader().add(ayudaPanel);
        OptimistVisuals.botonCerrar(d);   // V65
        Pantalla pant = new Pantalla(p, db, d, ab -> {
            d.close();
            // V84e: OtroPanel.Call(...): se abre ese panel (con lo que recibe) y sigue con el mismo alAbrir / alCerrar
            if (ab.panel()) { abrirDialogo(ab.trn(), db, ab.parametros(), alAbrir, alCerrar); return; }
            alAbrir.accept(ab);
        });
        p.ponerParametros(pant.estado, parametros);
        pant.refrescar();   // V49h: los parámetros a la pantalla; si no, el Start leía la pantalla vacía y los perdía (Empresa Origen en 0)
        if (alCerrar != null) d.addOpenedChangeListener(e -> { if (!e.isOpened()) alCerrar.run(); });
        // V84e: si desde el panel se abre un registro (un vínculo de su grilla), al cerrarlo se vuelve al panel como estaba
        OptimistVentanas.registrar(d, d::open);
        d.add(pant.cuerpo);
        OptimistVisuals.pieCentrado(d, pant.botones.getChildren().toArray(Component[]::new));
        d.open();
        pant.iniciar();
    }

    // ------------------------------------------------------------------ lectura del YAML

    @SuppressWarnings("unchecked")
    static OptimistPanel cargar(String nombre) {
        if (!nombre.matches("[A-Za-z0-9_]{1,80}")) throw new IllegalArgumentException("nombre inválido");
        Map<String, Object> y = null;
        // el archivo se busca sin distinguir mayúsculas (la ruta llega en minúscula: wbp/prsaltawbp)
        try {
            for (org.springframework.core.io.Resource r : OptimistRecursos.listar("wbp", ".yaml")) {
                String f = r.getFilename();
                if (f != null && f.equalsIgnoreCase(nombre + ".yaml")) {
                    try (InputStream in = r.getInputStream()) { y = new Yaml().load(in); }
                    // V49k: de qué archivo se leyó (si los cambios no se ven, acá se ve si es otro archivo)
                    try { System.out.println("[Panel] " + nombre + " leído de " + r.getURI()); } catch (Exception ignorado) { }
                    break;
                }
            }
        } catch (Exception e) { throw new IllegalArgumentException("no se pudo leer wbp/" + nombre + ".yaml: " + e.getMessage()); }
        if (y == null) throw new IllegalArgumentException("no existe wbp/" + nombre + ".yaml");
        Map<String, Object> dominios = Map.of();
        try (InputStream in = OptimistRecursos.abrir("dominios.yaml")) {
            if (in != null) { Map<String, Object> m = new Yaml().load(in); if (m != null) dominios = m; }
        } catch (Exception ignorado) { }
        return new OptimistPanel(y, dominios, n -> {
            Trn t = trn(n);
            return t == null || t.cabecera().claves().size() != 1 ? null : OptimistPanel.tipoGx(t.cabecera().claves().get(0).dominio());
        });
    }

    static Trn trn(String nombre) {
        return OptimistTrnCatalogo.cargar().trns().values().stream().filter(t -> t.nombre().equalsIgnoreCase(nombre)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ la pantalla (ventana o página)

    static final class Pantalla {
        final OptimistPanel p;
        final OptimistDatabase db;
        final Dialog dialogo;
        final Consumer<OptimistPanel.Abrir> alAbrir;
        final Map<String, Object> estado;
        final Map<String, OptimistTrnCampos.Editor> editores = new LinkedHashMap<>();
        final Map<String, Component> piezas = new LinkedHashMap<>();
        final Map<String, Span> etiquetas = new LinkedHashMap<>();   // V66n: la etiqueta de cada variable (a la izquierda)
        final Map<String, Div> celdas = new LinkedHashMap<>();       // V66q: la celda de la grilla de cada variable
        /** V66t: el título de cada sección y las variables que agrupa (se oculta si se ocultan todas). */
        final Map<Div, List<String>> secciones = new LinkedHashMap<>();
        /** V61: grillas editables: por grilla, la tabla y los editores de cada renglón (columna -> editor). */
        final Map<String, Div> tablasGrilla = new LinkedHashMap<>();
        final Map<String, List<Map<String, OptimistTrnCampos.Editor>>> filasGrilla = new LinkedHashMap<>();
        final Map<String, List<List<Component>>> celdasGrilla = new LinkedHashMap<>();
        /** V65q: lo que trajo cada renglón (las columnas ocultas y lo que no se edita viajan con él) y la caja de cada grilla. */
        final Map<String, List<Map<String, Object>>> basesGrilla = new LinkedHashMap<>();
        final Map<String, Component> cajasGrilla = new LinkedHashMap<>();
        final Map<String, Map<String, Span>> encabezadosGrilla = new LinkedHashMap<>();   // V66i: para ocultar columnas
        /** V74: las grillas 'lista' (resultados): la grilla, sus columnas y el renglón de totales. */
        final Map<String, Grid<Map<String, Object>>> listas = new LinkedHashMap<>();
        final Map<String, Map<String, Grid.Column<Map<String, Object>>>> columnasLista = new LinkedHashMap<>();
        final Map<String, com.vaadin.flow.component.grid.FooterRow> totalesLista = new LinkedHashMap<>();
        // V84i: los totales de las listas, solo para los autorizados (totales.usuarios del optimist.cfg) y si los prendió con Σ
        final Map<String, List<Map<String, Object>>> filasLista = new LinkedHashMap<>();
        Boolean totalesPrendidos;
        final List<Button> botonesTotales = new ArrayList<>();

        /** V84i: alguna lista del panel tiene columnas con total. */
        boolean tieneTotales() { return p.grillas.stream().anyMatch(g -> g.lista() && !g.totales().isEmpty()); }

        /** V84i: si las listas calculan y muestran sus totales (la primera vez, lo que el usuario dejó elegido). */
        boolean conTotales() {
            if (totalesPrendidos == null)
                totalesPrendidos = OptimistTotales.autorizado() && OptimistTotales.prendidos(OptimistTotales.clave("wbp", p.nombre));
            return totalesPrendidos;
        }

        /** V84i: un botón Σ para la pantalla del panel o la del resultado (null si no hace falta); todos cambian juntos. */
        Button botonTotales() {
            if (!tieneTotales() || !OptimistTotales.autorizado()) return null;
            Button b = OptimistTotales.boton(prendidos -> {
                totalesPrendidos = prendidos;
                OptimistTotales.recordar(OptimistTotales.clave("wbp", p.nombre), prendidos);
                for (Button otro : botonesTotales) OptimistTotales.pintar(otro, prendidos);
                for (OptimistPanel.Grilla g : p.grillas) {
                    if (!g.lista() || g.totales().isEmpty()) continue;
                    Grid<Map<String, Object>> grid = listas.get(g.nombre());
                    if (grid == null) continue;
                    if (prendidos) {
                        com.vaadin.flow.component.grid.FooterRow pie = totalesLista.computeIfAbsent(g.nombre(), k -> grid.appendFooterRow());
                        pintarTotales(g, columnasLista.get(g.nombre()), pie, filasLista.getOrDefault(g.nombre(), List.of()));
                    } else {
                        OptimistTotales.sacarPie(grid, totalesLista.remove(g.nombre()));
                    }
                }
            });
            OptimistTotales.pintar(b, conTotales());
            botonesTotales.add(b);
            return b;
        }
        /** V84b: el gráfico de cada grilla 'lista' que tiene 'grafico:', sus renglones (en el orden del proceso) y de dónde salieron. */
        final Map<String, OptimistGrafico> graficos = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> filasGrafico = new LinkedHashMap<>();
        final Div mensajes = new Div();
        final VerticalLayout cuerpo = new VerticalLayout();
        final HorizontalLayout botones = new HorizontalLayout();
        Button postIt;   // V82q: el PostIt de la pantalla (solo como página)
        boolean ocupado;
        /** V76: la pantalla del resultado (resultado: del YAML): si se está mostrando, cómo se llega y sus piezas. */
        boolean enResultado;
        Runnable irAResultado;
        private VerticalLayout vistaResultado;
        private final Span subtituloResultado = new Span();
        private final Div mensajesResultado = new Div();
        private final HorizontalLayout botonesResultado = new HorizontalLayout();
        private final HorizontalLayout barraResultado = new HorizontalLayout();

        private boolean delResultado(String grilla) {
            return p.resultado != null && p.resultado.grillas().stream().anyMatch(x -> x.equalsIgnoreCase(grilla));
        }

        /** V76: arma (la primera vez) la pantalla del resultado y le pone las variables y las grillas del resultado. */
        VerticalLayout armarResultado() {
            if (vistaResultado == null) {
                vistaResultado = new VerticalLayout();
                vistaResultado.setWidthFull();
                vistaResultado.setAlignItems(FlexComponent.Alignment.CENTER);
                vistaResultado.addClassName("optimist-formulario");
                vistaResultado.getStyle().set("padding", "var(--lumo-space-m) var(--lumo-space-l)").set("gap", "var(--lumo-space-s)");
                subtituloResultado.getStyle().set("font-weight", "600").set("color", "var(--optimist-texto)").set("text-align", "center");
                mensajesResultado.getStyle().set("color", "#ff8585").set("font-weight", "600").set("white-space", "pre-line");
                mensajesResultado.setVisible(false);
                barraResultado.setAlignItems(FlexComponent.Alignment.CENTER);
                barraResultado.getStyle().set("flex-wrap", "wrap").set("gap", "var(--lumo-space-m)");
                for (OptimistPanel.Boton b : p.resultado.botones()) {
                    Button bt = new Button(b.titulo(), e -> boton(b));
                    bt.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                    botonesResultado.add(bt);
                }
                botonesResultado.add(new Button("Volver", e -> UI.getCurrent().getPage().getHistory().back()));
                Button sigma = botonTotales();   // V84i: Σ, a la izquierda del PostIt
                if (sigma != null) botonesResultado.add(sigma);
                botonesResultado.add(OptimistPostIts.boton());   // V82q: el PostIt de la pantalla, a la derecha de los botones
                botonesResultado.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
                botonesResultado.addClassName("optimist-pie");
            }
            vistaResultado.removeAll();
            barraResultado.removeAll();
            for (String n : p.resultado.variables()) {
                Campo c = p.variables.campo(n);
                Component et = etiquetas.get(c.nombre()), pz = piezas.get(c.nombre());
                if (pz == null) continue;
                HorizontalLayout par = new HorizontalLayout();
                par.setAlignItems(FlexComponent.Alignment.CENTER);
                if (et != null) par.add(et);
                par.add(pz);
                par.setVisible(pz.isVisible());
                barraResultado.add(par);
            }
            boolean conSub = p.resultado.subtitulo() != null;
            subtituloResultado.setVisible(conSub);
            vistaResultado.add(subtituloResultado, barraResultado, mensajesResultado);
            for (OptimistPanel.Grilla g : p.grillas)
                if (delResultado(g.nombre())) {
                    Component caja = cajasGrilla.get(g.nombre());
                    if (caja instanceof VerticalLayout vl) {
                        vl.setWidthFull();
                        vl.getComponentAt(0).setVisible(false);   // el título de la grilla: ya está el de la pantalla
                    }
                    Grid<Map<String, Object>> grid = listas.get(g.nombre());
                    if (grid != null) grid.setWidthFull();
                    vistaResultado.add(caja);
                }
            vistaResultado.add(botonesResultado);
            return vistaResultado;
        }

        /** V76: las variables del resultado vuelven a su lugar en el formulario (si estaban en él). */
        void devolverAlFormulario() {
            if (p.resultado == null) return;
            for (String n : p.resultado.variables()) {
                Campo c = p.variables.campo(n);
                Div celda = celdas.get(c.nombre());
                if (celda != null && piezas.get(c.nombre()) != null) celda.add(piezas.get(c.nombre()));
            }
        }

        Pantalla(OptimistPanel p, OptimistDatabase db, Dialog dialogo, Consumer<OptimistPanel.Abrir> alAbrir) {
            this.p = p; this.db = db; this.dialogo = dialogo; this.alAbrir = alAbrir;
            this.estado = p.estadoInicial();
            mensajes.getStyle().set("color", "#ff8585").set("font-weight", "600").set("white-space", "pre-line");
            mensajes.setVisible(false);
            cuerpo.setPadding(false);
            cuerpo.setAlignItems(FlexComponent.Alignment.CENTER);
            cuerpo.add(mensajes);
            for (Campo c : p.variables.campos()) {
                // V66n: la etiqueta a la izquierda del campo, como en los formularios de las TRN (el campo sin etiqueta propia)
                OptimistTrnDefinicion.ComboTabla ct = p.combos.get(c.nombre());
                OptimistTrnCampos.Editor ed = ct != null ? comboTabla(c, ct) : OptimistTrnCampos.crear(c, true, false);   // V66t
                OptimistTrnView.aplicarEstilo(c, ed.componente(), null);
                editores.put(c.nombre(), ed);
                String evento = p.eventoAlCambiar(c.nombre());
                ed.alCambiar().accept(() -> cambio(c.nombre(), evento));
                OptimistPanel.Prompt pr = p.promptDe(c.nombre());
                piezas.put(c.nombre(), pr == null ? ed.componente() : conLupa(ed, pr));
                Span et = new Span((c.etiqueta() == null ? "" : c.etiqueta()) + (c.obligatorio() && c.editable() ? " *" : ""));
                et.getElement().setAttribute("title", c.etiqueta() == null ? "" : c.etiqueta());
                et.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--optimist-texto)").set("opacity", "0.85")
                        .set("white-space", "nowrap").set("align-self", "center");
                OptimistTrnView.aplicarEstilo(c, null, et);
                etiquetas.put(c.nombre(), et);
            }
            // V49m: los renglones seguidos con la misma cantidad de campos van en una grilla, así sus columnas quedan alineadas
            // (Empresa / División de origen y de destino, como en la tabla de GeneXus). Cada campo en su celda: si se oculta,
            // la celda queda y no corre a los de al lado.
            List<List<String>> rs = new ArrayList<>();   // sin las variables ocultas (no ocupan lugar)
            // V66t: el título de sección que va antes de cada renglón visible (si una sección quedó sin renglones, no va)
            Map<Integer, String> tituloAntes = new java.util.HashMap<>();
            String pendiente = null;
            for (int i = 0; i < p.renglones.size(); i++) {
                for (OptimistPanel.Seccion sc : p.secciones) if (sc.antesDe() == i) pendiente = sc.titulo();
                List<String> vis = new ArrayList<>();
                for (String v : p.renglones.get(i)) if (!p.variables.campo(v).oculto()) vis.add(v);
                if (vis.isEmpty()) continue;
                if (pendiente != null) { tituloAntes.put(rs.size(), pendiente); pendiente = null; }
                rs.add(vis);
            }
            // V66o: los renglones van en un bloque que se centra en la pantalla, pero adentro van contra el margen izquierdo
            // (un renglón de un solo campo arranca donde arrancan los demás, no en el medio)
            VerticalLayout formulario = new VerticalLayout();
            formulario.setPadding(false);
            formulario.setWidth(null);
            formulario.setAlignItems(FlexComponent.Alignment.START);
            formulario.getStyle().set("max-width", "100%");
            // V66q: TODOS los renglones en UNA sola grilla, como la tabla de las TRN: las etiquetas de una misma columna quedan
            // una debajo de la otra y los campos de la columna de al lado también, tenga cada renglón 1, 2 o más campos.
            // Cada campo son dos columnas (etiqueta | campo). V66r: cada campo queda en su columna, sin pasar a la de al lado
            // (si no, un combo ancho solo en su renglón se metía debajo de las etiquetas de la columna siguiente).
            int maxCampos = 0;
            for (List<String> r : rs) maxCampos = Math.max(maxCampos, r.size());
            if (maxCampos > 0) {
                Div grilla = new Div();
                grilla.getStyle().set("display", "grid").set("grid-template-columns", "repeat(" + maxCampos + ", auto auto)")
                        .set("column-gap", "var(--lumo-space-m)").set("row-gap", "0").set("align-items", "center")
                        .set("justify-content", "start").set("max-width", "100%").set("overflow-x", "auto");
                // el espacio entre renglones va como margen de cada celda (como las TRN): un renglón oculto entero no deja hueco
                String medioRenglon = "calc(var(--optimist-form-row-gap, 10px) / 2) 0";
                int renglon = 0;
                List<String> deLaSeccion = null;
                for (int r = 0; r < rs.size(); r++) {
                    if (tituloAntes.containsKey(r)) {
                        // V66t: el título de la sección ocupa todo el ancho de la grilla (las columnas siguen alineadas en todo el panel)
                        renglon++;
                        Div sec = new Div(new Span(tituloAntes.get(r)));
                        sec.addClassName("optimist-seccion");
                        sec.getStyle().set("grid-row", String.valueOf(renglon)).set("grid-column", "1 / -1")
                                .set("font-weight", "700").set("color", "var(--optimist-acento, var(--lumo-primary-color))")
                                .set("border-bottom", "2px solid var(--optimist-acento, var(--lumo-primary-color))")
                                .set("padding-bottom", "2px")
                                .set("margin", (renglon == 1 ? "0" : "var(--lumo-space-m)") + " 0 var(--lumo-space-xs) 0");
                        deLaSeccion = new ArrayList<>();
                        secciones.put(sec, deLaSeccion);
                        grilla.add(sec);
                    }
                    renglon++;
                    List<String> fila = rs.get(r);
                    for (int k = 0; k < fila.size(); k++) {
                        String nom = p.variables.campo(fila.get(k)).nombre();
                        if (deLaSeccion != null) deLaSeccion.add(nom);
                        Span et = etiquetas.get(nom);
                        et.getStyle().set("grid-row", String.valueOf(renglon)).set("grid-column", (2 * k + 1) + " / span 1")
                                .set("margin", medioRenglon);
                        Div celda = new Div(piezas.get(nom));
                        celda.getStyle().set("grid-row", String.valueOf(renglon)).set("grid-column", (2 * k + 2) + " / span 1")
                                .set("margin", medioRenglon).set("min-width", "0");
                        celdas.put(nom, celda);
                        grilla.add(et, celda);
                    }
                }
                // V66y: los controles para subir archivos, como un renglón más (etiqueta a la izquierda, el control al lado)
                for (OptimistPanel.Archivo a : p.archivos) {
                    renglon++;
                    Span et = new Span(a.etiqueta());
                    et.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--optimist-texto)").set("opacity", "0.85")
                            .set("white-space", "nowrap").set("align-self", "start").set("padding-top", "var(--lumo-space-s)")
                            .set("grid-row", String.valueOf(renglon)).set("grid-column", "1 / span 1").set("margin", medioRenglon);
                    Div celda = new Div(subida(a));
                    celda.getStyle().set("grid-row", String.valueOf(renglon)).set("grid-column", "2 / -1").set("margin", medioRenglon).set("min-width", "0");
                    grilla.add(et, celda);
                }
                formulario.add(grilla);
            }
            if (formulario.getComponentCount() > 0) cuerpo.add(formulario);
            // V61: las grillas editables, debajo de las variables
            for (OptimistPanel.Grilla g : p.grillas) {
                if (g.ventana()) continue;   // V84: se ve solo en la ventana de una lupa
                Component gc = armarGrilla(g);
                if (!delResultado(g.nombre())) cuerpo.add(gc);   // V76: las del resultado van en su pantalla
            }
            for (OptimistPanel.Boton b : p.botones) {
                Button bt = new Button(b.titulo(), e -> boton(b));
                if (!OptimistPanel.normalizar(b.evento()).equals("cancel")) bt.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                botones.add(bt);
            }
            botones.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
            refrescar();
        }

        void iniciar() { ejecutar("Start"); }

        /**
         * V66y: el control para subir archivos (el FileUpload de GeneXus). Cada archivo que termina de subir queda en una
         * carpeta temporal del servidor y se agrega a la colección &Variable con File (la ruta), Name, Extension y Size; al
         * quitarlo de la lista, sale de la colección.
         */
        @SuppressWarnings("deprecation")
        private Component subida(OptimistPanel.Archivo a) {
            com.vaadin.flow.component.upload.receivers.MultiFileMemoryBuffer buffer = new com.vaadin.flow.component.upload.receivers.MultiFileMemoryBuffer();
            com.vaadin.flow.component.upload.Upload up = new com.vaadin.flow.component.upload.Upload(buffer);
            if (a.maximo() > 0) up.setMaxFiles(a.maximo());
            if (!a.extensiones().isEmpty()) up.setAcceptedFileTypes(a.extensiones().stream().map(x -> "." + x).toArray(String[]::new));
            up.setMaxFileSize(30 * 1024 * 1024);
            boolean uno = a.maximo() == 1;
            com.vaadin.flow.component.upload.UploadI18N i18n = new com.vaadin.flow.component.upload.UploadI18N();
            i18n.setDropFiles(new com.vaadin.flow.component.upload.UploadI18N.DropFiles().setOne("Soltá el archivo acá").setMany("Soltá los archivos acá"))
                .setAddFiles(new com.vaadin.flow.component.upload.UploadI18N.AddFiles().setOne("Elegir archivo…").setMany("Elegir archivos…"))
                .setError(new com.vaadin.flow.component.upload.UploadI18N.Error()
                        .setTooManyFiles(uno ? "Ya hay un archivo: quitalo para elegir otro." : "Son demasiados archivos.")
                        .setFileIsTooBig("El archivo es demasiado grande.")
                        .setIncorrectFileType("El archivo tiene que ser " + String.join(" o ", a.extensiones().stream().map(x -> "." + x).toList()) + "."))
                .setUploading(new com.vaadin.flow.component.upload.UploadI18N.Uploading()
                        .setStatus(new com.vaadin.flow.component.upload.UploadI18N.Uploading.Status().setConnecting("Conectando…")
                                .setStalled("Detenido").setProcessing("Procesando…").setHeld("En espera"))
                        .setRemainingTime(new com.vaadin.flow.component.upload.UploadI18N.Uploading.RemainingTime().setPrefix("falta: ")
                                .setUnknown("tiempo desconocido"))
                        .setError(new com.vaadin.flow.component.upload.UploadI18N.Uploading.Error().setServerUnavailable("El servidor no responde")
                                .setUnexpectedServerError("Error del servidor").setForbidden("No permitido")))
                .setUnits(new com.vaadin.flow.component.upload.UploadI18N.Units().setSize(List.of("B", "kB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB")));
            up.setI18n(i18n);
            up.addSucceededListener(ev -> {
                String nombre = ev.getFileName();
                int punto = nombre.lastIndexOf('.');
                String ext = punto < 0 ? "" : nombre.substring(punto + 1).toLowerCase(java.util.Locale.ROOT);
                try (java.io.InputStream in = buffer.getInputStream(nombre)) {
                    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("optimist-subido-", ext.isEmpty() ? "" : "." + ext);
                    java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    tmp.toFile().deleteOnExit();
                    List<Object> l = archivosDe(a);
                    if (uno) { l.forEach(this::borrarSubido); l.clear(); }
                    OptimistProcedimiento.Sdt d = new OptimistProcedimiento.Sdt();
                    d.put("File", tmp.toString());
                    d.put("Name", nombre);
                    d.put("Extension", ext);
                    d.put("Size", java.math.BigDecimal.valueOf(java.nio.file.Files.size(tmp)));
                    d.put("Type", ev.getMIMEType() == null ? "" : ev.getMIMEType());
                    l.add(d);
                } catch (java.io.IOException ex) {
                    Notification.show("No se pudo recibir el archivo " + nombre + ": " + ex.getMessage(), 8000, Notification.Position.MIDDLE);
                }
            });
            up.getElement().addEventListener("file-remove", ev -> {
                String nombre = ev.getEventData().getString("event.detail.file.name");
                List<Object> l = archivosDe(a);
                for (java.util.Iterator<Object> it = l.iterator(); it.hasNext(); ) {
                    Object o = it.next();
                    if (o instanceof Map<?, ?> m && String.valueOf(m.get("Name")).equals(nombre)) { borrarSubido(o); it.remove(); }
                }
            }).addEventData("event.detail.file.name");
            up.setWidth("min(420px, 100%)");
            return up;
        }

        /** V66y: la colección de archivos subidos de un control (la crea si no está). */
        @SuppressWarnings("unchecked")
        private List<Object> archivosDe(OptimistPanel.Archivo a) {
            Object v = estado.get(a.variable());
            if (v instanceof List<?> l) return (List<Object>) l;
            List<Object> l = new ArrayList<>();
            estado.put(a.variable(), l);
            return l;
        }

        private void borrarSubido(Object o) {
            if (o instanceof Map<?, ?> m && m.get("File") != null)
                try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(String.valueOf(m.get("File")))); } catch (java.io.IOException | RuntimeException ignorado) { }
        }

        /** V66t: combo con los registros de una tabla (como en las TRN), con su 'donde' y el texto de "sin elegir". */
        private OptimistTrnCampos.Editor comboTabla(Campo c, OptimistTrnDefinicion.ComboTabla ct) {
            List<Object[]> items = new ArrayList<>();
            try (Connection cn = db.openConnection(); java.sql.Statement st = cn.createStatement(); java.sql.ResultSet rs = st.executeQuery(ct.sql())) {
                while (rs.next()) items.add(new Object[]{rs.getObject(1), rs.getString(2) == null ? "" : rs.getString(2).strip()});
            } catch (SQLException ex) { System.out.println("[" + p.nombre + "] combo de " + ct.tabla() + ": " + ex.getMessage()); }
            com.vaadin.flow.component.combobox.ComboBox<Object[]> cb = new com.vaadin.flow.component.combobox.ComboBox<>();
            cb.setItems(items);
            cb.setItemLabelGenerator(x -> String.valueOf(x[1]));
            cb.setClearButtonVisible(ct.vacio());
            if (ct.vacioTexto() != null) cb.setPlaceholder(ct.vacioTexto());
            cb.setWidth("min(320px,100%)");
            cb.setRequiredIndicatorVisible(c.obligatorio());
            cb.setAriaLabel(c.etiqueta());
            return new OptimistTrnCampos.Editor(c, cb, () -> cb.getValue() == null ? null : cb.getValue()[0],
                    v -> cb.setValue(v == null ? null : items.stream().filter(x -> OptimistTrnDatos.igual(x[0], v)).findFirst().orElse(null)),
                    cb::setReadOnly, r -> cb.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }));
        }

        private void boton(OptimistPanel.Boton b) {
            String e = OptimistPanel.normalizar(b.evento());
            if (e.equals("cancel") && !p.tieneEvento("cancel")) { cerrar(); return; }
            if (e.equals("cancel")) { ejecutar(b.evento()); cerrar(); return; }
            // V66z: todo proceso de un web panel pide confirmación antes de correr (salvo confirmar: false en el botón)
            String pregunta = b.pregunta();
            if (pregunta == null) { ejecutarConProgreso(b.evento()); return; }
            com.vaadin.flow.component.confirmdialog.ConfirmDialog cd = new com.vaadin.flow.component.confirmdialog.ConfirmDialog(
                    p.titulo, pregunta, "Sí", ev -> ejecutarConProgreso(b.evento()), "No", ev -> { });
            cd.open();
        }

        /**
         * V66x: el evento de un botón corre en segundo plano: si tarda más de medio segundo aparece la ventanita "Procesando…"
         * con lo que va informando el procedimiento (MostrarProgreso.Call(&Texto), ej. "Procesando artículo: 1000"). La pantalla
         * se actualiza cada 300 ms mientras dura (sin Push); al terminar queda como antes.
         */
        private void ejecutarConProgreso(String evento) {
            UI ui = UI.getCurrent();
            // V66x2: con una ventana modal abierta (el panel abierto como ventana) Vaadin no atiende el "poll" de la página:
            // la pantalla no se enteraría de que terminó. Ahí corre como antes, sin la ventanita.
            if (ui == null || dialogo != null) { ejecutar(evento); return; }
            if (ocupado) return;
            ocupado = true;
            try { leerPantalla(); } catch (RuntimeException ex) { ocupado = false; throw ex; }
            OptimistContexto ctx = OptimistContexto.actual();
            OptimistDatabase.Cliente cliente = OptimistDatabase.clienteActual();
            java.util.concurrent.atomic.AtomicReference<String> texto = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean listo = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicInteger letras = new java.util.concurrent.atomic.AtomicInteger(0);   // V66x3
            // V66x4: % de avance: total y cuántos van (cada MostrarProgreso cuenta uno), o el % que manda el procedimiento
            java.util.concurrent.atomic.AtomicInteger total = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger hechos = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicReference<Double> porcentaje = new java.util.concurrent.atomic.AtomicReference<>();
            long inicio = System.currentTimeMillis();
            Dialog espera = new Dialog();
            OptimistVisuals.estilizarDialogo(espera);
            // V66x2: NO modal: con una ventana modal Vaadin deja de atender el "poll" y el texto no cambiaba. La pantalla se
            // bloquea deshabilitando el formulario y los botones mientras dura.
            espera.setModal(false);
            espera.setCloseOnEsc(false);
            espera.setCloseOnOutsideClick(false);
            espera.setHeaderTitle("Procesando…");
            OptimistVisuals.atrasNoCierra(espera);   // V84h: el Atrás no la cierra
            Span detalle = new Span("Procesando…");
            // V66x3: ancho fijo (no cambia con cada texto): el que pide el procedimiento (AnchoProgreso) o uno por defecto;
            // lo que no entra termina en "…"
            detalle.getStyle().set("white-space", "nowrap").set("display", "block").set("overflow", "hidden")
                    .set("text-overflow", "ellipsis");
            espera.setWidth("min(460px, 92vw)");
            com.vaadin.flow.component.progressbar.ProgressBar barra = new com.vaadin.flow.component.progressbar.ProgressBar();
            barra.setIndeterminate(true);
            Span avance = new Span();
            avance.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
            avance.setVisible(false);
            VerticalLayout caja = new VerticalLayout(detalle, barra, avance);
            caja.setPadding(false);
            caja.setSpacing(false);
            caja.getStyle().set("gap", "var(--lumo-space-xs)");
            caja.setWidthFull();
            espera.add(caja);
            cuerpo.setEnabled(false);
            botones.setEnabled(false);
            if (vistaResultado != null) vistaResultado.setEnabled(false);   // V76
            int pollAntes = ui.getPollInterval();
            ui.setPollInterval(300);
            com.vaadin.flow.shared.Registration reg = ui.addPollListener(ev -> {
                if (listo.get() || System.currentTimeMillis() - inicio < 500) return;
                int n = letras.getAndSet(0);
                if (n > 0) espera.setWidth("min(calc(" + n + "ch + 3em), 92vw)");
                if (!espera.isOpened()) espera.open();
                String t = texto.get();
                detalle.setText(t == null || t.isBlank() ? "Procesando…" : t);
                // V66x4: el % de avance (la barra pasa a mostrar el avance real)
                Double pc = porcentaje.get();
                int tot = total.get();
                String txt = null;
                if (pc != null) {
                    pc = Math.max(0, Math.min(100, pc));
                    txt = "Avance: " + (int) Math.floor(pc) + " %";
                } else if (tot > 0) {
                    int h = Math.min(hechos.get(), tot);
                    pc = h * 100.0 / tot;
                    java.text.NumberFormat nf = java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("es-AR"));
                    txt = "Avance: " + (int) Math.floor(pc) + " % (" + nf.format(h) + " de " + nf.format(tot) + ")";
                }
                if (txt != null) {
                    barra.setIndeterminate(false);
                    barra.setValue(pc / 100.0);
                    avance.setText(txt);
                    avance.setVisible(true);
                }
            });
            Thread hilo = new Thread(() -> {
                OptimistPanel.Resultado r;
                try {
                    r = OptimistContexto.conContexto(ctx, () -> OptimistDatabase.conCliente(cliente, () -> {
                        OptimistProcedimiento.PROGRESO.set(t -> { texto.set(t); hechos.incrementAndGet(); });
                        OptimistProcedimiento.PROGRESO_TOTAL.set(n -> { hechos.set(0); porcentaje.set(null); total.set(n); });
                        OptimistProcedimiento.PROGRESO_PORCENTAJE.set(porcentaje::set);
                        OptimistProcedimiento.PROGRESO_ANCHO.set(letras::set);
                        try (Connection c = db.openConnection()) {
                            return p.ejecutar(evento, estado, c);
                        } catch (SQLException ex) {
                            return new OptimistPanel.Resultado(List.of("No se pudo conectar a la base: " + ex.getMessage()), true, null);
                        } finally {
                            OptimistProcedimiento.PROGRESO.remove();
                            OptimistProcedimiento.PROGRESO_ANCHO.remove();
                            OptimistProcedimiento.PROGRESO_TOTAL.remove();
                            OptimistProcedimiento.PROGRESO_PORCENTAJE.remove();
                            OptimistProcedimiento.PROGRESO_MANUAL.remove();   // V66z
                        }
                    }));
                } catch (Throwable ex) {   // también un Error: la pantalla no puede quedar trabada en "Procesando…"
                    r = new OptimistPanel.Resultado(List.of("Error en " + evento + ": " + ex.getMessage()), true, null);
                }
                OptimistPanel.Resultado fin = r;
                try {
                    ui.access(() -> {
                        listo.set(true);
                        reg.remove();
                        ui.setPollInterval(pollAntes);
                        espera.close();
                        cuerpo.setEnabled(true);
                        botones.setEnabled(true);
                        if (vistaResultado != null) vistaResultado.setEnabled(true);
                        try { aplicar(fin); } finally { ocupado = false; }
                        // V76: procesó sin errores y hay resultado: a la pantalla del resultado
                        if (!enResultado && irAResultado != null && !fin.error() && fin.mensajes().isEmpty()
                                && Boolean.TRUE.equals(estado.get(p.resultado.si())))
                            irAResultado.run();
                    });
                } catch (com.vaadin.flow.component.UIDetachedException ignorado) { /* cerraron la pestaña */ }
            }, "optimist-panel-" + p.nombre);
            hilo.setDaemon(true);
            hilo.start();
        }

        private void cerrar() {
            if (dialogo != null) dialogo.close();
            else UI.getCurrent().getPage().getHistory().back();
        }

        /** El usuario cambió una variable: pasa a las variables y corre su Event &X.IsValid (si tiene). */
        private void cambio(String variable, String evento) {
            if (ocupado) return;
            leerPantalla();
            if (evento != null) ejecutar(evento);
            controlarLupa(variable);   // V65
        }

        /** V65: un código escrito a mano en un campo con lupa que no existe en su tabla: el campo queda en rojo con "No existe ...". */
        private void controlarLupa(String variable) {
            OptimistPanel.Prompt pr = p.promptDe(variable);
            OptimistTrnCampos.Editor ed = editores.get(variable);
            if (pr == null || ed == null || !(ed.componente() instanceof com.vaadin.flow.component.HasValidation hv)) return;
            Object v = ed.leer().get();
            boolean falta = false;
            if (!OptimistProcedimiento.Funciones.vacioGx(v)) {
                try (Connection c = db.openConnection()) { falta = !p.existe(pr, v, estado, c); }
                catch (SQLException | RuntimeException ex) { return; }   // si no se puede controlar, no se marca
            }
            if (falta) {
                hv.setErrorMessage("No existe " + ed.campo().etiqueta() + " " + OptimistTrnCampos.mostrar(ed.campo(), v));
                hv.setInvalid(true);
            } else hv.setInvalid(false);
        }

        private void leerPantalla() {
            editores.forEach((n, ed) -> estado.put(n, ed.leer().get()));
            // V61: cada grilla, como colección de renglones (sin los que están vacíos)
            for (OptimistPanel.Grilla g : p.grillas) {
                if (g.lista()) continue;   // V74: la grilla de resultados queda como la dejó el evento
                List<Object> l = new ArrayList<>();
                List<Map<String, Object>> bases = basesGrilla.get(g.nombre());
                int ix = 0;
                for (Map<String, OptimistTrnCampos.Editor> fila : filasGrilla.get(g.nombre())) {
                    OptimistProcedimiento.Sdt r = OptimistPanel.renglonVacio(g);
                    if (bases != null && ix < bases.size()) r.putAll(bases.get(ix));   // V65q
                    ix++;
                    boolean algo = g.fija();   // V65q: en la fija van todos los renglones
                    for (var e : fila.entrySet()) {
                        Object v = e.getValue().leer().get();
                        r.put(e.getKey(), v);
                        if (!vacioGrilla(v)) algo = true;
                    }
                    if (algo) l.add(r);
                }
                estado.put(g.nombre(), l);
            }
        }

        private static boolean vacioGrilla(Object v) {
            return v == null || (v instanceof String s && s.isBlank()) || Boolean.FALSE.equals(v)
                    || (v instanceof java.math.BigDecimal b && b.signum() == 0) || (v instanceof Number n && n.doubleValue() == 0);
        }

        /** V61: la grilla: título, encabezados, los renglones (cada columna con su editor y una × para quitarlo) y "+ Renglón". */
        private Component armarGrilla(OptimistPanel.Grilla g) {
            if (g.lista()) return armarLista(g);   // V74
            List<Campo> cols = g.columnas().campos().stream().filter(c -> !c.oculto()).toList();
            Div tabla = new Div();
            tabla.getStyle().set("display", "grid").set("grid-template-columns", "repeat(" + cols.size() + ", auto)" + (g.fija() ? "" : " min-content"))
                    .set("gap", "var(--lumo-space-xs) var(--lumo-space-s)").set("align-items", "center")
                    .set("max-width", "100%").set("overflow-x", "auto");
            Map<String, Span> encabezados = new LinkedHashMap<>();
            for (Campo c : cols) {
                Span h = new Span(c.etiqueta());
                h.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-s)");
                tabla.add(h);
                encabezados.put(c.nombre(), h);
            }
            encabezadosGrilla.put(g.nombre(), encabezados);
            if (!g.fija()) tabla.add(new Span(""));
            tablasGrilla.put(g.nombre(), tabla);
            filasGrilla.put(g.nombre(), new ArrayList<>());
            celdasGrilla.put(g.nombre(), new ArrayList<>());
            basesGrilla.put(g.nombre(), new ArrayList<>());
            Span titulo = new Span(g.titulo());
            titulo.getStyle().set("font-weight", "700");
            VerticalLayout caja = new VerticalLayout(titulo, tabla);
            if (!g.fija()) {   // V65q: en la fija no se agregan ni se quitan renglones
                Button mas = new Button("Renglón", VaadinIcon.PLUS.create(), e -> agregarRenglon(g, OptimistPanel.renglonVacio(g)));
                mas.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
                caja.add(mas);
            }
            cajasGrilla.put(g.nombre(), caja);
            caja.setPadding(false);
            caja.setSpacing(false);
            caja.setAlignItems(FlexComponent.Alignment.CENTER);
            caja.getStyle().set("gap", "var(--lumo-space-xs)").set("margin-top", "var(--lumo-space-m)");
            return caja;
        }

        /**
         * V74: grilla 'lista' (resultados de un proceso, ej. la Anticuación de la Deuda): solo lectura, con la grilla de los
         * WorkWith (orden con clic en el título, columnas que se agrandan), los números en formato argentino, los totales al
         * pie ('totales') y vínculos ('enlaces') que abren una consulta; al volver, el panel sigue como estaba.
         */
        private Component armarLista(OptimistPanel.Grilla g) {
            Map<String, Grid.Column<Map<String, Object>>> cols = new LinkedHashMap<>();
            Grid<Map<String, Object>> grid = gridLista(g, cols);
            grid.setWidth("min(1500px, 94vw)");
            listas.put(g.nombre(), grid);
            columnasLista.put(g.nombre(), cols);
            if (!g.totales().isEmpty() && conTotales()) totalesLista.put(g.nombre(), grid.appendFooterRow());   // V84i
            Span titulo = new Span(g.titulo());
            titulo.getStyle().set("font-weight", "700");
            VerticalLayout caja = new VerticalLayout(titulo);
            if (g.grafico() != null) {
                // V84b: la lista a la izquierda y el gráfico a la derecha (como las estadísticas de GeneXus); en una pantalla
                // angosta, el gráfico pasa abajo
                Map<String, String> ets = new LinkedHashMap<>();
                Map<String, Integer> decs = new LinkedHashMap<>();
                for (Campo c : g.columnas().campos()) { ets.put(c.nombre(), c.etiqueta()); decs.put(c.nombre(), c.dominio().decimales()); }
                Campo cat = g.columnas().campo(g.grafico().categoria());
                OptimistGrafico gr = new OptimistGrafico(g.grafico(), ets, decs, v -> textoLista(cat, v));
                graficos.put(g.nombre(), gr);
                grid.addSortListener(e -> ordenarGrafico(g, e.getSortOrder()));
                HorizontalLayout fila = new HorizontalLayout(grid, gr);
                fila.setWidthFull();
                fila.setAlignItems(FlexComponent.Alignment.START);
                fila.getStyle().set("flex-wrap", "wrap").set("gap", "var(--lumo-space-m)");
                grid.getStyle().set("flex", "1 1 560px").set("min-width", "0");
                gr.getStyle().set("flex", "1 1 420px").set("max-width", "820px");
                caja.add(fila);
            } else caja.add(grid);
            caja.setPadding(false);
            caja.setSpacing(false);
            caja.setAlignItems(FlexComponent.Alignment.CENTER);
            caja.getStyle().set("gap", "var(--lumo-space-xs)").set("margin-top", "var(--lumo-space-m)");
            cajasGrilla.put(g.nombre(), caja);
            return caja;
        }

        /**
         * V74 / V84: la grilla de solo lectura de una grilla 'lista' (la del panel y la de la ventana de una lupa): orden con
         * clic en el título, columnas que se agrandan y se mueven, vínculos ('enlaces') y lupas al lado del valor ('lupas').
         */
        private Grid<Map<String, Object>> gridLista(OptimistPanel.Grilla g, Map<String, Grid.Column<Map<String, Object>>> cols) {
            Grid<Map<String, Object>> grid = new Grid<>();
            grid.addThemeName("optimist-grid");
            grid.addThemeVariants(com.vaadin.flow.component.grid.GridVariant.LUMO_ROW_STRIPES, com.vaadin.flow.component.grid.GridVariant.LUMO_COMPACT);
            grid.setColumnReorderingAllowed(true);
            for (Campo c : g.columnas().campos()) {
                if (c.oculto()) continue;
                boolean num = c.dominio().esNumerico() && !c.dominio().esCombo();
                OptimistPanel.Enlace en = g.enlaces().get(c.nombre());
                List<OptimistPanel.Lupa> lps = g.lupasDe(c.nombre());
                Grid.Column<Map<String, Object>> col;
                if (en != null || !lps.isEmpty()) col = grid.addComponentColumn(f -> celdaLista(g, c, en, lps, f, num));
                else col = grid.addColumn(f -> textoLista(c, f.get(c.nombre())));
                col.setHeader(c.etiqueta()).setKey(c.nombre()).setSortable(true).setResizable(true).setAutoWidth(true)
                        .setComparator((x, y) -> comparar(x.get(c.nombre()), y.get(c.nombre())));
                if (num) col.setTextAlign(com.vaadin.flow.component.grid.ColumnTextAlign.END);
                cols.put(c.nombre(), col);
            }
            return grid;
        }

        /** V74 / V84: la celda con vínculo (a una consulta o a un registro de una TRN) y/o con sus lupas al lado del valor. */
        private Component celdaLista(OptimistPanel.Grilla g, Campo c, OptimistPanel.Enlace en, List<OptimistPanel.Lupa> lps,
                                     Map<String, Object> f, boolean num) {
            String txt = textoLista(c, f.get(c.nombre()));
            Component valor;
            boolean hay = en != null && (en.si() != null ? OptimistPanel.cumple(en.si(), f) : !vacioGrilla(f.get(c.nombre())));
            if (hay) {
                com.vaadin.flow.component.html.Anchor a = new com.vaadin.flow.component.html.Anchor();
                a.setText(txt);
                // V76: con el color de los vínculos (un Anchor sin href se veía gris y apagado)
                a.getStyle().set("cursor", "pointer").set("text-decoration", "underline")
                        .set("color", "var(--lumo-primary-text-color)");
                a.getElement().setAttribute("title", en.trn() != null ? "Ver el comprobante" : "Ver los comprobantes");
                a.getElement().addEventListener("click", ev -> abrirEnlace(en, f)).addEventData("event.preventDefault()");
                valor = a;
            } else valor = new Span(txt);
            if (lps.isEmpty()) return valor;
            HorizontalLayout h = new HorizontalLayout();
            h.setSpacing(false);
            h.setPadding(false);
            h.setAlignItems(FlexComponent.Alignment.CENTER);
            h.getStyle().set("gap", "6px");
            if (num) { h.setWidthFull(); h.setJustifyContentMode(FlexComponent.JustifyContentMode.END); }
            h.add(valor);
            for (OptimistPanel.Lupa l : lps) {
                if (l.si() != null && !OptimistPanel.cumple(l.si(), f)) continue;
                VaadinIcon vi;
                try { vi = VaadinIcon.valueOf(l.icono().toUpperCase(Locale.ROOT).replace('-', '_')); }
                catch (IllegalArgumentException e) { vi = VaadinIcon.SEARCH; }
                com.vaadin.flow.component.icon.Icon ic = vi.create();
                ic.getStyle().set("width", "14px").set("height", "14px").set("cursor", "pointer")
                        .set("color", "var(--lumo-primary-text-color)").set("flex-shrink", "0");
                ic.getElement().setAttribute("title", l.ayuda() != null ? l.ayuda() : "Ver el detalle");
                ic.addClickListener(e -> abrirVentana(g, l, f));
                h.add(ic);
            }
            return h;
        }

        /**
         * V84: la ventana de una lupa: los renglones de la otra grilla que coinciden con este renglón según 'por', con su
         * orden, vínculos, lupas (se pueden encadenar) y totales; las columnas ocultas por &Columna.Visible no se ven.
         */
        @SuppressWarnings("unchecked")
        private void abrirVentana(OptimistPanel.Grilla origen, OptimistPanel.Lupa l, Map<String, Object> fila) {
            OptimistPanel.Grilla gv = p.grillas.stream().filter(x -> x.nombre().equalsIgnoreCase(l.grilla())).findFirst().orElse(null);
            if (gv == null) return;
            List<Map<String, Object>> filas = new ArrayList<>();
            Object v = estado.get(gv.nombre());
            if (v instanceof List<?> lst) for (Object o : lst) if (o instanceof Map<?, ?> m) {
                Map<String, Object> r = (Map<String, Object>) m;
                boolean ok = true;
                for (Map.Entry<String, String> e : l.por().entrySet())
                    if (!igualesLupa(valorDe(r, e.getKey()), valorDe(fila, e.getValue()))) { ok = false; break; }
                if (ok) filas.add(r);
            }
            Map<String, Grid.Column<Map<String, Object>>> cols = new LinkedHashMap<>();
            Grid<Map<String, Object>> grid = gridLista(gv, cols);
            grid.setWidthFull();
            grid.setItems(filas);
            if (filas.size() <= 15) grid.setAllRowsVisible(true);
            else grid.setHeight("min(62vh, 560px)");
            for (Campo c : gv.columnas().campos()) {
                Grid.Column<Map<String, Object>> col = cols.get(c.nombre());
                if (col != null) col.setVisible(columnaVisible(c));
            }
            if (!gv.totales().isEmpty() && conTotales()) pintarTotales(gv, cols, grid.appendFooterRow(), filas);   // V84i
            Dialog d = new Dialog();
            OptimistVisuals.estilizarDialogo(d);
            d.setHeaderTitle(tituloVentana(origen, l, gv, fila));
            d.setWidth("min(1300px, 96vw)");
            d.setMaxWidth("96vw");
            d.setDraggable(true);
            d.setResizable(true);
            d.add(grid);
            OptimistVisuals.pieCentrado(d, new Button("Cerrar", e -> d.close()));   // web panel: botones centrados
            d.open();
            // V84c: si desde esta ventana se abre un comprobante, al cerrarlo se vuelve a esta ventana (no a la pantalla de abajo)
            OptimistVentanas.registrar(d, () -> abrirVentana(origen, l, fila));
        }

        /** V84: el título de la ventana de una lupa: {Columna} = el valor (como se ve) de esa columna del renglón. */
        private static String tituloVentana(OptimistPanel.Grilla origen, OptimistPanel.Lupa l, OptimistPanel.Grilla gv, Map<String, Object> fila) {
            String t = l.titulo() != null ? l.titulo() : gv.titulo();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([A-Za-z_]\\w*)\\}").matcher(t);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                Campo c = origen.columnas().campo(m.group(1));
                Object x = valorDe(fila, m.group(1));
                String r = c != null ? textoLista(c, x) : (x == null ? "" : String.valueOf(x).strip());
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(r.strip()));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static Object valorDe(Map<String, Object> fila, String col) {
            if (fila.containsKey(col)) return fila.get(col);
            for (Map.Entry<String, Object> e : fila.entrySet()) if (e.getKey().equalsIgnoreCase(col)) return e.getValue();
            return null;
        }

        /** V84: dos valores de 'por' son iguales (números por su valor; texto sin los blancos de los costados; vacío = vacío). */
        private static boolean igualesLupa(Object a, Object b) {
            boolean va = a == null || (a instanceof String s && s.isBlank()), vb = b == null || (b instanceof String s && s.isBlank());
            if (va || vb) return va && vb;
            if (a instanceof Number || b instanceof Number) {
                try { return OptimistFormula.num(a).compareTo(OptimistFormula.num(b)) == 0; }
                catch (RuntimeException e) { return String.valueOf(a).strip().equals(String.valueOf(b).strip()); }
            }
            return String.valueOf(a).strip().equals(String.valueOf(b).strip());
        }

        @SuppressWarnings("unchecked")
        private void refrescarLista(OptimistPanel.Grilla g) {
            Grid<Map<String, Object>> grid = listas.get(g.nombre());
            if (grid == null) return;
            List<Map<String, Object>> filas = new ArrayList<>();
            Object v = estado.get(g.nombre());
            if (v instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) filas.add((Map<String, Object>) m);
            grid.setItems(filas);
            OptimistGrafico gr = graficos.get(g.nombre());
            if (gr != null && !mismosRenglones(filasGrafico.get(g.nombre()), filas)) {
                // V84b: lo que dejó el proceso (u otro orden del evento): al gráfico, en el orden en que quedó la lista
                filasGrafico.put(g.nombre(), filas);
                ordenarGrafico(g, grid.getSortOrder());
            }
            if (filas.size() <= 15) grid.setAllRowsVisible(true);
            else if (delResultado(g.nombre())) { grid.setAllRowsVisible(false); grid.setHeight("max(320px, calc(100vh - 290px))"); }   // V76
            else { grid.setAllRowsVisible(false); grid.setHeight("65vh"); }
            filasLista.put(g.nombre(), filas);   // V84i: para pintar los totales si se prenden después
            com.vaadin.flow.component.grid.FooterRow pie = totalesLista.get(g.nombre());
            if (pie == null) return;
            pintarTotales(g, columnasLista.get(g.nombre()), pie, filas);
        }

        /** V84b: los mismos renglones (los mismos objetos) en el mismo orden: no hace falta volver a dibujar. */
        private static boolean mismosRenglones(List<Map<String, Object>> a, List<Map<String, Object>> b) {
            if (a == null || a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
            return true;
        }

        /** V84b: las columnas de la grilla que se ven (las que se pueden graficar). */
        private Set<String> columnasQueSeVen(OptimistPanel.Grilla g) {
            Set<String> r = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Campo c : g.columnas().campos()) if (columnaVisible(c)) r.add(c.nombre());
            return r;
        }

        /** V84b: el orden de la lista (las columnas tocadas, con su sentido) como comparador; null = el del proceso. */
        private Comparator<Map<String, Object>> comparadorLista(List<com.vaadin.flow.component.grid.GridSortOrder<Map<String, Object>>> orden) {
            Comparator<Map<String, Object>> cmp = null;
            for (var o : orden) {
                String k = o.getSorted().getKey();
                if (k == null) continue;
                Comparator<Map<String, Object>> c = (x, y) -> comparar(x.get(k), y.get(k));
                if (o.getDirection() == com.vaadin.flow.data.provider.SortDirection.DESCENDING) c = c.reversed();
                cmp = cmp == null ? c : cmp.thenComparing(c);
            }
            return cmp;
        }

        /** V84b: el gráfico sigue el orden de la lista (desde los primeros) al llenarla y al ordenarla. */
        private void ordenarGrafico(OptimistPanel.Grilla g, List<com.vaadin.flow.component.grid.GridSortOrder<Map<String, Object>>> orden) {
            OptimistGrafico gr = graficos.get(g.nombre());
            List<Map<String, Object>> filas = filasGrafico.get(g.nombre());
            if (gr == null || filas == null) return;
            List<Map<String, Object>> o = new ArrayList<>(filas);
            Comparator<Map<String, Object>> cmp = comparadorLista(orden);
            if (cmp != null) o.sort(cmp);
            gr.datos(o, columnasQueSeVen(g));
        }

        /** V74: el renglón de totales ("TOTALES (n)" y la suma de las columnas de 'totales'). V84: también en las ventanas. */
        private void pintarTotales(OptimistPanel.Grilla g, Map<String, Grid.Column<Map<String, Object>>> cols,
                                   com.vaadin.flow.component.grid.FooterRow pie, List<Map<String, Object>> filas) {
            boolean primera = true;
            for (Campo c : g.columnas().campos()) {
                Grid.Column<Map<String, Object>> col = cols.get(c.nombre());
                if (col == null) continue;
                Span celda = new Span();
                celda.getStyle().set("font-weight", "700");
                if (g.totales().contains(c.nombre())) {
                    java.math.BigDecimal t = java.math.BigDecimal.ZERO;
                    for (Map<String, Object> f : filas) { Object x = f.get(c.nombre()); if (x != null) t = t.add(OptimistFormula.num(x)); }
                    celda.setText(textoLista(c, t));
                } else if (primera) celda.setText(filas.size() == 1 ? "TOTAL (1)" : "TOTALES (" + filas.size() + ")");
                primera = false;
                pie.getCell(col).setComponent(celda);
            }
        }

        /** El valor como se ve: números con punto de miles y coma decimal (los enteros sin miles: son códigos), combos con su texto. */
        private static String textoLista(Campo c, Object v) {
            if (v == null) return "";
            if (c.dominio().esCombo()) return OptimistTrnCampos.mostrar(c, v);
            if (c.dominio().esNumerico()) {
                java.math.BigDecimal b = OptimistFormula.num(v);
                // V84: un entero con "miles" en el dominio (ej. "Numeric(13) miles": cantidades) también lleva punto de miles
                return c.dominio().decimales() > 0 || c.dominio().miles() ? OptimistTrnCampos.formatoMiles(c.dominio()).format(b) : b.stripTrailingZeros().toPlainString();
            }
            return OptimistTrnCampos.mostrar(c, v);
        }

        private static int comparar(Object a, Object b) {
            if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
            if (a instanceof Number || b instanceof Number) return OptimistFormula.num(a).compareTo(OptimistFormula.num(b));
            return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
        }

        /** V74: abre la consulta del vínculo con lo que recibe: una columna del renglón, una &Variable del panel o una constante. */
        private void abrirEnlace(OptimistPanel.Enlace en, Map<String, Object> fila) {
            if (en.trn() != null) {   // V84: el registro de la TRN en modo ver (como las lupas de stock)
                OptimistVentanas.abrirRegistro(en.trn(), valorDe(fila, en.clave()));
                return;
            }
            Map<String, String> qp = new LinkedHashMap<>();
            en.parametros().forEach((recibe, origen) -> {
                Object v;
                if (origen.startsWith("&")) v = estado.get(origen.substring(1));
                else if (fila.containsKey(origen)) v = fila.get(origen);
                else {
                    Object x = null;
                    for (var e2 : fila.entrySet()) if (e2.getKey().equalsIgnoreCase(origen)) x = e2.getValue();
                    v = x != null ? x : origen.replaceAll("^[\"']|[\"']$", "");
                }
                if (v != null) qp.put(recibe, v instanceof java.math.BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v));
            });
            // V84c: desde la ventana de una lupa, al volver de la consulta se vuelven a abrir las ventanas
            OptimistVentanas.navegar("cns/" + en.cns().toLowerCase(Locale.ROOT), com.vaadin.flow.router.QueryParameters.simple(qp));
        }

        private void agregarRenglon(OptimistPanel.Grilla g, Map<String, Object> valores) {
            Div tabla = tablasGrilla.get(g.nombre());
            Map<String, OptimistTrnCampos.Editor> fila = new LinkedHashMap<>();
            List<Component> celdas = new ArrayList<>();
            for (Campo c : g.columnas().campos()) {
                if (c.oculto()) continue;
                OptimistTrnCampos.Editor ed = OptimistTrnCampos.crear(c, true, true);
                try { ed.escribir().accept(valores.get(c.nombre())); } catch (RuntimeException ignorado) { }
                ed.soloLectura().accept(c.soloLectura());
                ed.componente().setVisible(columnaVisible(c));   // V66i: &Columna.Visible
                fila.put(c.nombre(), ed);
                // V84e: una columna de solo lectura con 'enlaces' (ej. el remito y el pedido en el multiremito): el valor es un
                // vínculo que abre el registro; al cerrarlo se vuelve a esta pantalla
                OptimistPanel.Enlace en = g.enlaces().get(c.nombre());
                if (en != null && c.soloLectura()) {
                    Object v = valores.get(c.nombre());
                    boolean hay = en.si() != null ? OptimistPanel.cumple(en.si(), valores) : !vacioGrilla(v);
                    com.vaadin.flow.component.html.Anchor a = new com.vaadin.flow.component.html.Anchor();
                    a.setText(textoLista(c, v));
                    a.getStyle().set("align-self", "center").set("padding", "0 var(--lumo-space-xs)");
                    if (hay) {
                        Map<String, Object> copia = new HashMap<>(valores);
                        a.getStyle().set("cursor", "pointer").set("text-decoration", "underline").set("color", "var(--lumo-primary-text-color)");
                        a.getElement().addEventListener("click", ev -> abrirEnlace(en, copia)).addEventData("event.preventDefault()");
                    }
                    a.setVisible(columnaVisible(c));
                    celdas.add(a);
                    continue;
                }
                celdas.add(ed.componente());
            }
            basesGrilla.get(g.nombre()).add(new HashMap<>(valores));   // V65q
            estilosRenglon(g, fila, valores);   // V66
            if (!g.calcular().isEmpty())   // V66: al cambiar un valor del renglón se recalculan las columnas con 'calcular'
                for (OptimistTrnCampos.Editor ed : fila.values())
                    ed.alCambiar().accept(() -> recalcularRenglon(g, fila));
            if (g.fija()) {
                filasGrilla.get(g.nombre()).add(fila);
                celdasGrilla.get(g.nombre()).add(celdas);
                celdas.forEach(tabla::add);
                return;
            }
            Button quitar = new Button(VaadinIcon.CLOSE_SMALL.create());
            quitar.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            quitar.getElement().setAttribute("title", "Quitar renglón");
            celdas.add(quitar);
            filasGrilla.get(g.nombre()).add(fila);
            celdasGrilla.get(g.nombre()).add(celdas);
            quitar.addClickListener(e -> {
                int i = celdasGrilla.get(g.nombre()).indexOf(celdas);
                if (i < 0) return;
                celdas.forEach(tabla::remove);
                celdasGrilla.get(g.nombre()).remove(i);
                filasGrilla.get(g.nombre()).remove(i);
                basesGrilla.get(g.nombre()).remove(i);
            });
            celdas.forEach(tabla::add);
        }

        /**
         * V66: 'resaltar' y 'habilitar' de la grilla con los valores del renglón (como el &Columna.Class y el
         * &Columna.Enabled del Grid.Load de GeneXus).
         */
        private void estilosRenglon(OptimistPanel.Grilla g, Map<String, OptimistTrnCampos.Editor> fila, Map<String, Object> valores) {
            for (OptimistPanel.Resaltado r : g.resaltar()) {
                if (!OptimistPanel.cumple(r.si(), valores)) continue;
                fila.forEach((n, ed) -> {
                    if (r.columnas().isEmpty() || r.columnas().contains(n)) OptimistTrnView.aplicarEstilo(r.estilo(), ed.componente(), null);
                });
            }
            g.habilitar().forEach((n, f) -> {
                OptimistTrnCampos.Editor ed = fila.get(n);
                if (ed != null && !ed.campo().soloLectura()) ed.soloLectura().accept(!OptimistPanel.cumple(f, valores));
            });
        }

        /** V66: 'calcular' de la grilla: con lo que hay en el renglón (lo que trajo y lo tipeado) recalcula esas columnas. */
        private void recalcularRenglon(OptimistPanel.Grilla g, Map<String, OptimistTrnCampos.Editor> fila) {
            int i = filasGrilla.get(g.nombre()).indexOf(fila);
            Map<String, Object> valores = new OptimistProcedimiento.Sdt();
            List<Map<String, Object>> bases = basesGrilla.get(g.nombre());
            if (i >= 0 && i < bases.size()) valores.putAll(bases.get(i));
            fila.forEach((n, ed) -> valores.put(n, ed.leer().get()));
            g.calcular().forEach((n, f) -> {
                Object v = OptimistPanel.evaluarRenglon(f, valores);
                OptimistTrnCampos.Editor ed = fila.get(n);
                if (v == null) return;   // ninguna alternativa aplica: queda lo que estaba
                if (ed != null) { try { ed.escribir().accept(v); } catch (RuntimeException ignorado) { } }
                else if (i >= 0 && i < bases.size()) bases.get(i).put(n, v);   // columna oculta: viaja con el renglón
                valores.put(n, v);
            });
        }

        /** V61: los renglones de la grilla vuelven a la pantalla (lo que dejó el evento) y se completan con vacíos. */
        @SuppressWarnings("unchecked")
        private void refrescarGrilla(OptimistPanel.Grilla g) {
            if (g.lista()) { refrescarLista(g); return; }   // V74
            Div tabla = tablasGrilla.get(g.nombre());
            for (List<Component> celdas : celdasGrilla.get(g.nombre())) celdas.forEach(tabla::remove);
            celdasGrilla.get(g.nombre()).clear();
            filasGrilla.get(g.nombre()).clear();
            basesGrilla.get(g.nombre()).clear();
            Object v = estado.get(g.nombre());
            int n = 0;
            if (v instanceof List<?> l)
                for (Object o : l) if (o instanceof Map<?, ?> m) { agregarRenglon(g, new OptimistProcedimiento.Sdt((Map<String, ?>) m)); n++; }
            for (; n < g.renglones(); n++) agregarRenglon(g, OptimistPanel.renglonVacio(g));
        }

        /** Corre un evento con lo que hay en pantalla y vuelca el resultado (variables, mensajes, visibles). */
        private void ejecutar(String evento) {
            if (ocupado) return;
            ocupado = true;
            try {
                leerPantalla();
                OptimistPanel.Resultado r;
                try (Connection c = db.openConnection()) {
                    r = p.ejecutar(evento, estado, c);
                } catch (SQLException ex) {
                    r = new OptimistPanel.Resultado(List.of("No se pudo conectar a la base: " + ex.getMessage()), true, null);
                }
                aplicar(r);
            } finally { ocupado = false; }
        }

        /** Lo que dejó un evento: las variables a la pantalla, los mensajes, el PDF, la TRN a abrir, el cierre. */
        private void aplicar(OptimistPanel.Resultado r) {
            {
                refrescar();
                mostrarMensajes(r.mensajes());
                // V65: el PDF que generó el evento (el último), en la misma ventana; el panel queda abierto para volver a pedirlo
                if (!r.error() && r.pdfs() != null && !r.pdfs().isEmpty()) {
                    Object[] ultimo = r.pdfs().get(r.pdfs().size() - 1);
                    OptimistVisorPdf.abrir(p.titulo, String.valueOf(ultimo[0]), (byte[]) ultimo[1]);
                }
                if (r.abrir() != null && !r.error()) alAbrir.accept(r.abrir());
                // V49f: el aviso de los procedimientos (&WWPContext.MensajeError) y el Return, que cierra el panel
                if (!r.error() && r.cerrar()) {
                    if (r.aviso() != null && !r.aviso().isBlank())
                        Notification.show(r.aviso(), 8000, Notification.Position.BOTTOM_START);
                    if (r.mensajes().isEmpty()) cerrar();
                } else if (r.aviso() != null && !r.aviso().isBlank()) {
                    List<String> todos = new ArrayList<>(r.mensajes());
                    todos.add(r.aviso());
                    mostrarMensajes(todos);
                }
            }
        }

        private void mostrarMensajes(List<String> ms) {
            mensajes.setText(String.join("\n", ms));
            mensajes.setVisible(!ms.isEmpty());
            mensajesResultado.setText(String.join("\n", ms));   // V76
            mensajesResultado.setVisible(!ms.isEmpty());
        }

        /** Las variables vuelven a la pantalla, con su Visible / Enabled. */
        private void refrescar() {
            boolean antes = ocupado;
            ocupado = true;
            try {
                for (OptimistPanel.Grilla g : p.grillas) {
                    if (g.ventana()) continue;   // V84
                    refrescarGrilla(g);   // V61
                    cajasGrilla.get(g.nombre()).setVisible(!Boolean.FALSE.equals(estado.get("WbpVisible_" + g.nombre())));   // V65q
                    columnasVisibles(g);   // V66i
                }
                for (Campo c : p.variables.campos()) {
                    OptimistTrnCampos.Editor ed = editores.get(c.nombre());
                    try { ed.escribir().accept(estado.get(c.nombre())); } catch (RuntimeException ignorado) { }
                    boolean visible = !c.oculto() && !Boolean.FALSE.equals(estado.get("WbpVisible_" + c.nombre()));
                    piezas.get(c.nombre()).setVisible(visible);
                    if (etiquetas.containsKey(c.nombre())) etiquetas.get(c.nombre()).setVisible(visible);   // V66n
                    if (celdas.containsKey(c.nombre())) celdas.get(c.nombre()).setVisible(visible);   // V66q: sin su margen
                    boolean habilitado = !c.soloLectura() && !Boolean.FALSE.equals(estado.get("WbpEnabled_" + c.nombre()));
                    ed.soloLectura().accept(!habilitado);
                }
                // V76: el subtítulo y las variables de la barra del resultado (el par etiqueta + campo se oculta junto)
                if (p.resultado != null) {
                    if (p.resultado.subtitulo() != null) {
                        Object st = estado.get(p.resultado.subtitulo());
                        subtituloResultado.setText(st == null ? "" : String.valueOf(st).strip());
                    }
                    barraResultado.getChildren().forEach(par -> par.setVisible(
                            par.getChildren().reduce((a, b) -> b).map(Component::isVisible).orElse(true)));
                }
                // V66t: una sección cuyas variables están todas ocultas no se ve
                secciones.forEach((sec, vars) -> sec.setVisible(vars.stream().anyMatch(n -> celdas.get(n) != null && celdas.get(n).isVisible())));
            } finally { ocupado = antes; }
        }

        /** V66i: la columna de grilla se ve salvo &Columna.Visible = false. */
        private boolean columnaVisible(Campo c) {
            return !c.oculto() && !Boolean.FALSE.equals(estado.get("WbpVisible_" + c.nombre()));
        }

        /** V66i: encabezados y ancho de la grilla según las columnas que se ven (las celdas ya vienen con su Visible). */
        private void columnasVisibles(OptimistPanel.Grilla g) {
            if (g.lista()) {   // V74
                Map<String, Grid.Column<Map<String, Object>>> cs = columnasLista.get(g.nombre());
                if (cs != null) for (Campo c : g.columnas().campos()) { var col = cs.get(c.nombre()); if (col != null) col.setVisible(columnaVisible(c)); }
                OptimistGrafico gr = graficos.get(g.nombre());
                if (gr != null) gr.columnas(columnasQueSeVen(g));   // V84b: solo se grafica lo que se ve
                return;
            }
            Map<String, Span> enc = encabezadosGrilla.get(g.nombre());
            if (enc == null) return;
            int n = 0;
            for (Campo c : g.columnas().campos()) {
                if (c.oculto()) continue;
                boolean v = columnaVisible(c);
                Span h = enc.get(c.nombre());
                if (h != null) h.setVisible(v);
                if (v) n++;
            }
            tablasGrilla.get(g.nombre()).getStyle().set("grid-template-columns", "repeat(" + Math.max(n, 1) + ", auto)" + (g.fija() ? "" : " min-content"));
        }

        /** El campo con su lupa (el Prompt de GeneXus): al elegir, completa lo que devuelve y corre el IsValid. */
        private Component conLupa(OptimistTrnCampos.Editor ed, OptimistPanel.Prompt pr) {
            Button lupa = new Button(VaadinIcon.SEARCH.create(), e -> buscador(pr));
            lupa.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            lupa.getElement().setAttribute("title", "Buscar " + ed.campo().etiqueta());
            HorizontalLayout h = new HorizontalLayout(ed.componente(), lupa);
            h.setSpacing(false);
            h.setAlignItems(FlexComponent.Alignment.END);
            return h;
        }

        /**
         * V50k: la lupa del panel es la misma de todo OPTIMIST (OptimistLupa): si la tabla es la de una TRN con 'workwith.prompt'
         * (ej. Entidades: Razón Social, Código, Tipo Documento, CUIT, Nro Documento) muestra esas columnas; si no, código y
         * descripción. Cada columna con su filtro como en el WorkWith, y carga por páginas (antes se quedaba en las primeras 300).
         */
        private void buscador(OptimistPanel.Prompt pr) {
            leerPantalla();
            Consumer<Map<String, Object>> elegir = fila -> {
                pr.devuelve().forEach((var, col) -> estado.put(p.variables.campo(var).nombre(), fila.get(col)));
                refrescar();
                String ev = p.eventoAlCambiar(pr.variable());
                if (ev != null) ejecutar(ev);
            };
            Trn destino = OptimistTrnCatalogo.cargar().trns().values().stream()
                    .filter(t -> t.cabecera().tabla().equalsIgnoreCase(pr.tabla()) && !t.workwith().prompt().isEmpty())
                    .findFirst().orElse(null);
            if (destino != null) {
                List<OptimistLupa.Columna> cols = new ArrayList<>();
                for (String n : destino.workwith().prompt()) {
                    Campo c = destino.cabecera().campo(n);
                    cols.add(new OptimistLupa.Columna(c.nombre(), destino.workwith().tituloPrompt(c), c.dominio(), v -> OptimistTrnCampos.mostrar(c, v)));
                }
                OptimistTrnDatos datos = new OptimistTrnDatos(db);
                Map<String, Object> iguales = p.dondeResuelto(pr, estado);
                OptimistLupa.abrir("Buscar " + destino.singular(), cols,
                        (filtros, limite, desde) -> datos.buscarTabla(destino, pr.devuelve().values(), iguales, filtros, limite, desde),
                        elegir);
                return;
            }
            List<OptimistLupa.Columna> cols = new ArrayList<>();
            cols.add(new OptimistLupa.Columna(pr.codigo(), "Código", null, OptimistLupa::texto));
            if (pr.descripcion() != null) cols.add(new OptimistLupa.Columna(pr.descripcion(), "Descripción", null, OptimistLupa::texto));
            OptimistLupa.abrir("Buscar en " + pr.tabla(), cols,
                    (filtros, limite, desde) -> {
                        try (Connection c = db.openConnection()) { return p.buscar(pr, null, filtros, estado, c, limite, desde); }
                    },
                    elegir);
        }
    }
}
