package ar.com.datafix.optimist;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;

import java.util.*;
import java.util.function.Function;

/**
 * V84b: el gráfico de columnas de una grilla 'lista' de un web panel (el GoogleCharts de las Estadísticas de Ventas y de
 * Compras de GeneXus), al lado de la lista. Se dibuja en el servidor como SVG (sin librerías ni Internet: los servidores de
 * los clientes no llegan a los repositorios), con los colores y la letra de la Apariencia.
 * <ul>
 *   <li>Una columna por renglón, en el orden en que se ve la lista (si se ordena la lista, el gráfico la sigue), de a
 *       "Ver de a" (10, 20, 50) con ‹ › para recorrerla, como el "Visualizo" de GeneXus.</li>
 *   <li>Las series se eligen con casillas (solo las columnas que se ven); los importes con el eje de la izquierda y las de
 *       'derecha' (cantidades, kilos, %) con el de la derecha, para que no se aplasten.</li>
 *   <li>Al pasar el mouse por una barra: el nombre, la serie y el valor en formato argentino.</li>
 * </ul>
 */
final class OptimistGrafico extends VerticalLayout {

    /** Colores de las series: la primera, el color de la Apariencia; después, colores que se distinguen en claro y oscuro. */
    private static final String[] COLORES = OptimistGraficoSvg.COLORES;
    private final OptimistGraficoSvg dibujo;

    private final OptimistPanel.Grafico def;
    private final Map<String, String> etiquetas;
    private final Map<String, Integer> decimales;
    private final Function<Object, String> textoCategoria;
    private final Map<String, Checkbox> casillas = new LinkedHashMap<>();
    private final Map<String, HorizontalLayout> items = new LinkedHashMap<>();
    private final HorizontalLayout barraSeries = new HorizontalLayout();
    private final Div lienzo = new Div();
    private final Span posicion = new Span();
    private final Button primero = boton(VaadinIcon.ANGLE_DOUBLE_LEFT, "Primeros");
    private final Button anterior = boton(VaadinIcon.ANGLE_LEFT, "Anteriores");
    private final Button siguiente = boton(VaadinIcon.ANGLE_RIGHT, "Siguientes");
    private final Button ultimo = boton(VaadinIcon.ANGLE_DOUBLE_RIGHT, "Últimos");
    private final Select<Integer> deA = new Select<>();
    private List<Map<String, Object>> filas = List.of();
    private Set<String> visibles = Set.of();
    private int inicio = 0;

    /**
     * etiquetas: el título de cada columna (el de la lista); decimales: los de su dominio (para el texto de los valores);
     * textoCategoria: cómo se ve el valor de la columna 'categoria'.
     */
    OptimistGrafico(OptimistPanel.Grafico def, Map<String, String> etiquetas, Map<String, Integer> decimales,
                    Function<Object, String> textoCategoria) {
        this.def = def;
        this.etiquetas = etiquetas;
        this.decimales = decimales;
        this.textoCategoria = textoCategoria;
        this.dibujo = new OptimistGraficoSvg(def, etiquetas, decimales, textoCategoria);
        setPadding(false);
        setSpacing(false);
        getStyle().set("gap", "var(--lumo-space-xs)").set("min-width", "0");
        barraSeries.setAlignItems(FlexComponent.Alignment.CENTER);
        barraSeries.getStyle().set("flex-wrap", "wrap").set("gap", "2px var(--lumo-space-m)");
        for (int i = 0; i < def.series().size(); i++) {
            String s = def.series().get(i);
            Checkbox c = new Checkbox(etiquetas.getOrDefault(s, s), def.marcadas().contains(s));
            c.getStyle().set("font-size", "var(--lumo-font-size-s)");
            c.addValueChangeListener(e -> dibujar());
            Span color = new Span();   // el color de la serie, como la leyenda del gráfico
            color.getStyle().set("display", "inline-block").set("width", "12px").set("height", "12px").set("border-radius", "2px")
                    .set("background", COLORES[i % COLORES.length]).set("flex-shrink", "0");
            HorizontalLayout it = new HorizontalLayout(color, c);
            it.setAlignItems(FlexComponent.Alignment.CENTER);
            it.getStyle().set("gap", "2px");
            casillas.put(s, c);
            items.put(s, it);
            barraSeries.add(it);
        }
        deA.setItems(10, 20, 50);
        deA.setItemLabelGenerator(n -> "Ver de a " + n);
        deA.setValue(List.of(10, 20, 50).contains(def.deA()) ? def.deA() : 10);
        deA.setWidth("130px");
        deA.addValueChangeListener(e -> { inicio = 0; dibujar(); });
        primero.addClickListener(e -> { inicio = 0; dibujar(); });
        anterior.addClickListener(e -> { inicio = Math.max(0, inicio - paso()); dibujar(); });
        siguiente.addClickListener(e -> { if (inicio + paso() < filas.size()) inicio += paso(); dibujar(); });
        ultimo.addClickListener(e -> { inicio = filas.isEmpty() ? 0 : ((filas.size() - 1) / paso()) * paso(); dibujar(); });
        posicion.getStyle().set("font-size", "var(--lumo-font-size-s)").set("min-width", "110px").set("text-align", "center");
        HorizontalLayout navegar = new HorizontalLayout(primero, anterior, posicion, siguiente, ultimo, deA);
        navegar.setAlignItems(FlexComponent.Alignment.CENTER);
        navegar.getStyle().set("gap", "2px").set("flex-wrap", "wrap");
        lienzo.setWidthFull();
        lienzo.getStyle().set("min-height", "320px");
        if (def.titulo() != null) {
            Span t = new Span(def.titulo());
            t.getStyle().set("font-weight", "700");
            add(t);
        }
        add(barraSeries, lienzo, navegar);
        setHorizontalComponentAlignment(FlexComponent.Alignment.CENTER, navegar);
    }

    private static Button boton(VaadinIcon i, String ayuda) {
        Button b = new Button(i.create());
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        b.getElement().setAttribute("title", ayuda);
        return b;
    }

    private int paso() { return deA.getValue() == null ? 10 : deA.getValue(); }

    /**
     * Los renglones en el orden de la lista y las columnas que se ven. nuevos = el proceso volvió a llenar la lista (vuelve
     * al principio); si no (se ordenó), también vuelve al principio para mostrar los primeros del nuevo orden.
     */
    void datos(List<Map<String, Object>> filas, Set<String> columnasVisibles) {
        this.filas = filas == null ? List.of() : filas;
        this.visibles = columnasVisibles;
        this.inicio = 0;
        // solo las series de columnas que se ven (ej. sin "ver la rentabilidad" no se grafica el costo)
        for (Map.Entry<String, HorizontalLayout> e : items.entrySet()) e.getValue().setVisible(visibles.contains(e.getKey()));
        dibujar();
    }

    /** Solo cambian las columnas que se ven (sin volver al principio). */
    void columnas(Set<String> columnasVisibles) {
        if (columnasVisibles.equals(visibles)) return;
        this.visibles = columnasVisibles;
        for (Map.Entry<String, HorizontalLayout> e : items.entrySet()) e.getValue().setVisible(visibles.contains(e.getKey()));
        dibujar();
    }

    private void dibujar() {
        int n = filas.size(), p = paso();
        if (inicio >= n) inicio = Math.max(0, ((n - 1) / p) * p);
        int hasta = Math.min(n, inicio + p);
        posicion.setText(n == 0 ? "Sin datos" : (inicio + 1) + " a " + hasta + " de " + n);
        primero.setEnabled(inicio > 0);
        anterior.setEnabled(inicio > 0);
        siguiente.setEnabled(hasta < n);
        ultimo.setEnabled(hasta < n);
        List<String> series = new ArrayList<>();
        for (Map.Entry<String, Checkbox> e : casillas.entrySet())
            if (visibles.contains(e.getKey()) && Boolean.TRUE.equals(e.getValue().getValue())) series.add(e.getKey());
        lienzo.getElement().setProperty("innerHTML", dibujo.svg(filas.subList(inicio, hasta), series));
    }

}
