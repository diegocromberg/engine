package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Dominio;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * V50k: la lupa (el Prompt de GeneXus), UNA sola para todo OPTIMIST: las de las TRN (con columnas de 'workwith.prompt'
 * o simples, código y descripción) y las de los web panels. Mismo diseño y mismo comportamiento en todas:
 * - una columna por dato, cada una con su filtro como en el WorkWith: texto (contiene), número (=, !=, <, <=, >, >=, entre)
 *   y fecha (desde - hasta);
 * - V68: por páginas como el WorkWith (primera / anterior / siguiente / última, registros por página y el total), para tablas
 *   de 40.000 artículos (antes iba pidiendo filas al bajar con la barra: llegar al final no servía);
 * - V68: clic en el título de una columna ordena por ella (otro clic, al revés);
 * - V68: abre con el cursor en el filtro de la descripción (o el primero de texto): escribir filtra, Enter elige el primero,
 *   flecha abajo pasa a la grilla, y ahí las flechas mueven y Enter elige;
 * - la ventana toma el ancho de sus columnas;
 * - tilde a la izquierda o doble clic: elige y cierra.
 */
public final class OptimistLupa {
    private OptimistLupa() {}

    /** Una columna: clave en la fila, título, dominio (el tipo de filtro sale de acá; null = texto) y cómo se muestra. */
    public record Columna(String clave, String titulo, Dominio dominio, Function<Object, String> mostrar) {
        public String tipoFiltro() {
            if (dominio == null || dominio.esCombo()) return null;
            if (dominio.esNumerico()) return "numero";
            if (dominio.tipo() == OptimistTrnDefinicion.Tipo.DATE || dominio.tipo() == OptimistTrnDefinicion.Tipo.DATETIME) return "fecha";
            return null;
        }
    }

    /** De dónde salen las filas: filtros por columna (texto canónico del filtro del WorkWith), cuántas y desde cuál. */
    @FunctionalInterface
    public interface Fuente {
        List<Map<String, Object>> traer(Map<String, String> filtros, int limite, int desde) throws SQLException;
    }

    public static void abrir(String titulo, List<Columna> columnas, Fuente fuente, Consumer<Map<String, Object>> elegir) {
        Dialog d = new Dialog();
        OptimistVisuals.estilizarDialogo(d);
        d.setHeaderTitle(titulo);
        OptimistVisuals.botonCerrar(d);   // V65
        Grid<Map<String, Object>> g = new Grid<>();
        g.addThemeName("optimist-grid");
        Consumer<Map<String, Object>> tomar = fila -> { if (fila == null) return; d.close(); elegir.accept(fila); };
        g.addComponentColumn(m -> {
            Button tilde = new Button(VaadinIcon.CHECK.create(), e -> tomar.accept(m));
            tilde.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            tilde.getStyle().set("color", "var(--lumo-primary-text-color)").set("cursor", "pointer");
            tilde.getElement().setAttribute("title", "Elegir");
            return tilde;
        }).setWidth("56px").setFlexGrow(0);

        Map<String, String> filtros = new HashMap<>();
        Map<Grid.Column<Map<String, Object>>, Columna> cols = new LinkedHashMap<>();
        Map<String, Span> titulos = new LinkedHashMap<>();
        List<TextField> primeros = new ArrayList<>();
        boolean[] medido = {false};
        // V68: por páginas, como el WorkWith (primera / anterior / siguiente / última y el total)
        int[] pagina = {0};
        long[] total = {0};
        List<Map<String, Object>> filas = new ArrayList<>();
        ComboBox<Integer> porPagina = OptimistWorkWith.selectorFilas(10);
        Span estado = new Span();
        Button primera = new Button("Primera"), anterior = new Button("Anterior"), siguiente = new Button("Siguiente"), ultima = new Button("Última");
        Runnable[] mostrar = new Runnable[1];
        Runnable[] llenar = new Runnable[1];
        TextField[] primerFiltro = new TextField[1];
        for (Columna c : columnas) {
            TextField canon = new TextField();
            canon.setPlaceholder("Filtrar...");
            canon.setClearButtonVisible(true);
            canon.setWidthFull();
            canon.setValueChangeMode(ValueChangeMode.LAZY);
            canon.addValueChangeListener(e -> { filtros.put(c.clave(), e.getValue()); llenar[0].run(); });
            // V68: escribir y Enter elige el primero; flecha abajo pasa a la grilla
            com.vaadin.flow.component.Shortcuts.addShortcutListener(canon, () -> {
                        if (!Objects.equals(canon.getValue(), filtros.getOrDefault(c.clave(), ""))) {   // lo último que escribió
                            filtros.put(c.clave(), canon.getValue());
                            llenar[0].run();
                        }
                        tomar.accept(filas.isEmpty() ? null : filas.get(0));
                    }, com.vaadin.flow.component.Key.ENTER).listenOn(canon);
            com.vaadin.flow.component.Shortcuts.addShortcutListener(canon, () -> { if (!filas.isEmpty()) { g.focus(); } },
                    com.vaadin.flow.component.Key.ARROW_DOWN).listenOn(canon);
            Component control = canon;
            if ("numero".equals(c.tipoFiltro())) control = OptimistWorkWith.filtroNumero(canon);
            else if ("fecha".equals(c.tipoFiltro())) control = OptimistWorkWith.filtroFecha(canon);
            else primeros.add(canon);
            if (primerFiltro[0] == null) primerFiltro[0] = canon;
            // V68: clic en el título = ordenar por esa columna; otro clic, al revés
            Span tituloCol = new Span(c.titulo());
            tituloCol.getStyle().set("cursor", "pointer").set("user-select", "none");
            tituloCol.getElement().setAttribute("title", "Ordenar por " + c.titulo());
            tituloCol.addClickListener(e -> {
                String o = filtros.get(OptimistTrnDatos.ORDEN);
                filtros.put(OptimistTrnDatos.ORDEN, c.clave().equals(o) ? "-" + c.clave() : c.clave());
                titulos.forEach((k, t) -> {
                    String actual = filtros.get(OptimistTrnDatos.ORDEN);
                    String base = columnas.stream().filter(x -> x.clave().equals(k)).findFirst().map(Columna::titulo).orElse(k);
                    t.setText(base + (k.equals(actual) ? " ▲" : ("-" + k).equals(actual) ? " ▼" : ""));
                });
                llenar[0].run();
            });
            titulos.put(c.clave(), tituloCol);
            OptimistWorkWith.marcarFiltrado(tituloCol, canon);   // V64i: negrita mientras se filtra, como en los WW
            VerticalLayout cabecera = new VerticalLayout(tituloCol, control);
            cabecera.setPadding(false);
            cabecera.setSpacing(false);
            Grid.Column<Map<String, Object>> col = g.addColumn(m -> c.mostrar().apply(m.get(c.clave())))
                    .setHeader(cabecera).setResizable(true).setFlexGrow(1);
            cols.put(col, c);
        }
        java.text.NumberFormat nf = java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-AR"));
        mostrar[0] = () -> {
            int tam = porPagina.getValue() == null ? 10 : porPagina.getValue();
            int paginas = (int) Math.max(1, (total[0] + tam - 1) / tam);
            pagina[0] = Math.max(0, Math.min(pagina[0], paginas - 1));
            try {
                filas.clear();
                filas.addAll(fuente.traer(filtros, tam, pagina[0] * tam));
                g.setItems(new ArrayList<>(filas));
                if (!filas.isEmpty()) g.scrollToStart();
            } catch (SQLException ex) {
                Notification.show(titulo + ": " + ex.getMessage(), 10000, Notification.Position.MIDDLE);
            } catch (IllegalArgumentException ex) {
                Notification.show("Elegí primero " + ex.getMessage(), 4000, Notification.Position.MIDDLE);
            }
            estado.setText(nf.format(total[0]) + (total[0] == 1 ? " registro" : " registros") + " · página " + nf.format(pagina[0] + 1) + " de " + nf.format(paginas));
            primera.setEnabled(pagina[0] > 0);
            anterior.setEnabled(pagina[0] > 0);
            siguiente.setEnabled(pagina[0] < paginas - 1);
            ultima.setEnabled(pagina[0] < paginas - 1);
        };
        llenar[0] = () -> {
            try {
                List<Map<String, Object>> t = fuente.traer(filtros, -1, 0);   // V68: el total
                Object n = t.isEmpty() ? null : t.get(0).get(OptimistTrnDatos.TOTAL);
                total[0] = n instanceof Number x ? x.longValue() : 0;
                pagina[0] = 0;
                if (!medido[0]) {   // el ancho justo para ver las columnas sin desplazarse (una sola vez: no salta al filtrar)
                    medido[0] = true;
                    List<Map<String, Object>> muestra = fuente.traer(filtros, 50, 0);
                    int ancho = 56;
                    for (var e : cols.entrySet()) {
                        Columna c = e.getValue();
                        int largo = c.titulo().length() + 2;
                        for (Map<String, Object> m : muestra) largo = Math.max(largo, c.mostrar().apply(m.get(c.clave())).length());
                        int px = Math.max(c.tipoFiltro() == null ? 100 : 120, Math.min(largo, 50) * 8 + 32);
                        e.getKey().setWidth(px + "px");
                        ancho += px;
                    }
                    d.setWidth("min(" + Math.max(ancho + 70, 620) + "px,96vw)");
                }
            } catch (SQLException ex) {
                Notification.show(titulo + ": " + ex.getMessage(), 10000, Notification.Position.MIDDLE);
                return;
            } catch (IllegalArgumentException ex) {
                Notification.show("Elegí primero " + ex.getMessage(), 4000, Notification.Position.MIDDLE);
                return;
            }
            mostrar[0].run();
        };
        primera.addClickListener(e -> { pagina[0] = 0; mostrar[0].run(); });
        anterior.addClickListener(e -> { pagina[0]--; mostrar[0].run(); });
        siguiente.addClickListener(e -> { pagina[0]++; mostrar[0].run(); });
        ultima.addClickListener(e -> { pagina[0] = Integer.MAX_VALUE; mostrar[0].run(); });
        porPagina.addValueChangeListener(e -> { if (e.isFromClient()) { pagina[0] = 0; mostrar[0].run(); } });
        // V68: en la grilla, flechas para moverse y Enter para elegir la fila marcada
        @SuppressWarnings("unchecked")
        Map<String, Object>[] foco = new Map[1];
        g.addCellFocusListener(e -> foco[0] = e.getItem().orElse(null));
        com.vaadin.flow.component.Shortcuts.addShortcutListener(g, () -> tomar.accept(foco[0]), com.vaadin.flow.component.Key.ENTER).listenOn(g);
        g.addItemDoubleClickListener(e -> tomar.accept(e.getItem()));
        g.setAllRowsVisible(true);
        llenar[0].run();
        d.add(g, OptimistWorkWith.piePagina(porPagina, estado, primera, anterior, siguiente, ultima));
        OptimistVisuals.pieIzquierda(d, new Button("Cancelar", e -> d.close()));
        d.open();
        if (!primeros.isEmpty()) primeros.get(0).focus();
        else if (primerFiltro[0] != null) primerFiltro[0].focus();
    }

    /** Texto para mostrar un valor crudo (sin dominio): vacío si es null, sin espacios al final. */
    public static String texto(Object v) {
        return v == null ? "" : String.valueOf(v).stripTrailing();
    }
}
