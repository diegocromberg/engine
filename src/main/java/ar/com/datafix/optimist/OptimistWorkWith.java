package ar.com.datafix.optimist;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.List;
import java.util.stream.Collectors;

/** Controles reutilizables de todos los WorkWith. No contiene reglas de ninguna TRN. */
public final class OptimistWorkWith {
    private OptimistWorkWith() {}

    /** Encabezado y filtro por columna de TODAS las TRN. */
    public static <T> Grid.Column<T> columna(Grid<T> grid, String clave, String etiqueta,
            Function<T, ?> extractor, Map<String, TextField> filtros, Runnable alFiltrar) {
        return columna(grid, clave, etiqueta, extractor, filtros, alFiltrar, null);
    }

    /** V40: color != null: el valor va en una "pastilla" de ese color (paleta de optimist-global.css). */
    public static <T> Grid.Column<T> columna(Grid<T> grid, String clave, String etiqueta,
            Function<T, ?> extractor, Map<String, TextField> filtros, Runnable alFiltrar, Function<T, String> color) {
        return columna(grid, clave, etiqueta, extractor, filtros, alFiltrar, color, null);
    }

    /** V48e: estilo != null: las celdas de la columna con ese estilo (la clase del WorkWith: {font-weight: 700, color: ...}). */
    public static <T> Grid.Column<T> columna(Grid<T> grid, String clave, String etiqueta,
            Function<T, ?> extractor, Map<String, TextField> filtros, Runnable alFiltrar, Function<T, String> color,
            Map<String, String> estilo) {
        return columna(grid, clave, etiqueta, extractor, filtros, alFiltrar, color, estilo, null);
    }

    /**
     * V64i: el título de la columna va en negrita mientras se está filtrando por ella. Todos los filtros (texto, número,
     * fecha, combo) terminan en el texto del filtro, así que alcanza con mirarlo (también al restaurar o "Limpiar filtros").
     */
    static void marcarFiltrado(Span titulo, TextField filtro) {
        Runnable marcar = () -> {
            String v = filtro.getValue();
            if (v != null && !v.isBlank()) titulo.getStyle().set("font-weight", "700");
            else titulo.getStyle().remove("font-weight");
        };
        filtro.addValueChangeListener(e -> marcar.run());
        marcar.run();
    }

    /** V50: tipoFiltro "numero" (=, !=, <, <=, >, >=, entre), "fecha" (desde - hasta) o null (texto: contiene). */
    public static <T> Grid.Column<T> columna(Grid<T> grid, String clave, String etiqueta,
            Function<T, ?> extractor, Map<String, TextField> filtros, Runnable alFiltrar, Function<T, String> color,
            Map<String, String> estilo, String tipoFiltro) {
        return columna(grid, clave, etiqueta, extractor, filtros, alFiltrar, color, estilo, tipoFiltro, null);
    }

    /** V50e: enlace != null: el valor es un vínculo (router-link) a esa dirección, por ejemplo trn/ped?ver=123. */
    public static <T> Grid.Column<T> columna(Grid<T> grid, String clave, String etiqueta,
            Function<T, ?> extractor, Map<String, TextField> filtros, Runnable alFiltrar, Function<T, String> color,
            Map<String, String> estilo, String tipoFiltro, Function<T, String> enlace) {
        return columna(grid, clave, etiqueta, extractor, filtros, alFiltrar, color, estilo, tipoFiltro, enlace, null);
    }

    /** V60: tipoFiltro "combo" + valores: el filtro es un combo de selección múltiple con los valores del dominio. */
    public static <T> Grid.Column<T> columna(Grid<T> grid, String clave, String etiqueta,
            Function<T, ?> extractor, Map<String, TextField> filtros, Runnable alFiltrar, Function<T, String> color,
            Map<String, String> estilo, String tipoFiltro, Function<T, String> enlace,
            List<OptimistTrnDefinicion.Valor> valores) {
        String css = estiloCss(estilo);
        TextField filtro = new TextField();
        filtro.setPlaceholder("Filtrar...");
        filtro.setClearButtonVisible(true);
        filtro.setWidthFull();
        filtro.setValueChangeMode(ValueChangeMode.LAZY);
        filtro.addValueChangeListener(e -> alFiltrar.run());
        filtros.put(clave, filtro);
        com.vaadin.flow.component.Component control = filtro;
        if ("numero".equals(tipoFiltro)) control = filtroNumero(filtro);
        else if ("fecha".equals(tipoFiltro)) control = filtroFecha(filtro);
        else if ("combo".equals(tipoFiltro) && valores != null && !valores.isEmpty()) control = filtroMultiple(filtro, valores);
        Span titulo = new Span(etiqueta);
        marcarFiltrado(titulo, filtro);
        VerticalLayout cabecera = new VerticalLayout(titulo, control);
        cabecera.setPadding(false);
        cabecera.setSpacing(false);
        Grid.Column<T> col;
        if (enlace != null && color == null) {
            col = grid.addColumn(com.vaadin.flow.data.renderer.LitRenderer.<T>of(
                            "<a router-link href=\"${item.href}\" ?hidden=\"${!item.href}\" title=\"Ver\""
                            + " style=\"color:var(--lumo-primary-text-color);text-decoration:underline;cursor:pointer;" + (css == null ? "" : css) + "\""
                            + ">${item.texto}</a>"   // V50g: sin cortar el clic: lo toma el ruteo de Vaadin (no recarga la página)
                            + "<span ?hidden=\"${item.href}\"" + (css == null ? "" : " style=\"" + css + "\"") + ">${item.texto}</span>")
                    .withProperty("texto", item -> { Object v = extractor.apply(item); return v == null ? "" : String.valueOf(v); })
                    .withProperty("href", item -> { String h = enlace.apply(item); return h == null ? "" : h; }));
        } else if (color == null && css != null) {
            col = grid.addColumn(com.vaadin.flow.data.renderer.LitRenderer.<T>of("<span style=\"" + css + "\">${item.texto}</span>")
                    .withProperty("texto", item -> { Object v = extractor.apply(item); return v == null ? "" : String.valueOf(v); }));
        } else if (color == null) {
            col = grid.addColumn(item -> {
                Object valor = extractor.apply(item);
                return valor == null ? "" : String.valueOf(valor);
            });
        } else {
            col = grid.addColumn(com.vaadin.flow.data.renderer.LitRenderer.<T>of(
                            "<span class=\"optimist-insignia\" data-color=\"${item.color}\" ?hidden=\"${!item.texto}\""
                            + (css == null ? "" : " style=\"" + css + "\"") + ">${item.texto}</span>")
                    .withProperty("texto", item -> { Object v = extractor.apply(item); return v == null ? "" : String.valueOf(v); })
                    .withProperty("color", item -> { String c = color.apply(item); return c == null ? "gris" : c; }));
        }
        return col.setHeader(cabecera).setSortable(true).setAutoWidth(true).setKey(clave);
    }

    /**
     * V50: marca de un filtro por tipo dentro del texto del filtro: "\u0002N" op \u0001 valor \u0001 hasta / "\u0002F" desde \u0001 hasta.
     * V60: "\u0002M" valor \u0003 texto \u0001 valor \u0003 texto ... (combo de selección múltiple: los valores elegidos).
     */
    static final String NUM = "\u0002N", FEC = "\u0002F", MUL = "\u0002M", SEP = "\u0001", VT = "\u0003";

    /** V60: los valores (tal como están en el dominio, como texto) de un filtro de selección múltiple. */
    static List<String> valoresMultiple(String filtro) {
        List<String> r = new ArrayList<>();
        if (filtro == null || !filtro.startsWith(MUL)) return r;
        for (String x : filtro.substring(2).split(SEP, -1)) {
            if (x.isEmpty()) continue;
            int i = x.indexOf(VT);
            r.add(i < 0 ? x : x.substring(0, i));
        }
        return r;
    }

    /** V60: los textos (los que ve el usuario) de un filtro de selección múltiple. */
    static List<String> textosMultiple(String filtro) {
        List<String> r = new ArrayList<>();
        if (filtro == null || !filtro.startsWith(MUL)) return r;
        for (String x : filtro.substring(2).split(SEP, -1)) {
            int i = x.indexOf(VT);
            if (i >= 0) r.add(x.substring(i + 1));
        }
        return r;
    }

    /**
     * V60: filtro de una columna con dominio enumerado (ClaseCbte, CPI, Status...): combo de selección múltiple con sus
     * valores. Se tildan varios y la lista trae los que tienen cualquiera de ellos (IN). Sin ninguno tildado, no filtra.
     * Se puede escribir para buscar dentro de la lista.
     */
    static com.vaadin.flow.component.Component filtroMultiple(TextField canon, List<OptimistTrnDefinicion.Valor> valores) {
        com.vaadin.flow.component.combobox.MultiSelectComboBox<OptimistTrnDefinicion.Valor> m =
                new com.vaadin.flow.component.combobox.MultiSelectComboBox<>();
        m.setItems(valores);
        m.setItemLabelGenerator(v -> v.texto() == null ? String.valueOf(v.valor()) : v.texto());
        m.setPlaceholder("Filtrar...");
        m.setClearButtonVisible(true);
        m.setWidthFull();
        m.setMinWidth("0");
        boolean[] armando = {false};
        m.addValueChangeListener(e -> {
            if (armando[0]) return;
            armando[0] = true;
            try {
                StringBuilder sb = new StringBuilder();
                // en el orden del dominio (así el mismo filtro da siempre el mismo texto)
                for (OptimistTrnDefinicion.Valor v : valores)
                    if (e.getValue().contains(v)) {
                        sb.append(sb.length() == 0 ? MUL : SEP).append(String.valueOf(v.valor()).trim())
                          .append(VT).append(v.texto() == null ? "" : v.texto());
                    }
                canon.setValue(sb.toString());
            } finally { armando[0] = false; }
        });
        // "Limpiar filtros" (o un filtro puesto desde el programa): el combo muestra lo que dice el texto del filtro
        canon.addValueChangeListener(e -> {
            if (armando[0]) return;
            armando[0] = true;
            try {
                List<String> vs = valoresMultiple(e.getValue());
                Set<OptimistTrnDefinicion.Valor> sel = new LinkedHashSet<>();
                for (OptimistTrnDefinicion.Valor v : valores) if (vs.contains(String.valueOf(v.valor()).trim())) sel.add(v);
                m.setValue(sel);
            } finally { armando[0] = false; }
        });
        return m;
    }

    /** V50: filtro de una columna numérica: operador (=, !=, <, <=, >, >=, entre) y el valor (o los dos de "entre"). */
    static com.vaadin.flow.component.Component filtroNumero(TextField canon) {
        com.vaadin.flow.component.select.Select<String> op = new com.vaadin.flow.component.select.Select<>();
        op.setItems("=", "!=", "<", "<=", ">", ">=", "entre");
        op.setValue("=");
        op.setWidthFull();
        op.getElement().setAttribute("title", "Cómo comparar");
        TextField v1 = new TextField(), v2 = new TextField();
        for (TextField t : List.of(v1, v2)) {
            t.setPlaceholder(t == v1 ? "Filtrar..." : "hasta");
            t.setClearButtonVisible(true);
            t.setValueChangeMode(ValueChangeMode.LAZY);
            t.setWidthFull();
            t.setMinWidth("0");
            t.setAllowedCharPattern("[0-9.,-]");
        }
        v2.setVisible(false);
        boolean[] armando = {false};
        Runnable armar = () -> {
            if (armando[0]) return;
            v2.setVisible("entre".equals(op.getValue()));
            String a = v1.getValue().trim(), b = v2.isVisible() ? v2.getValue().trim() : "";
            armando[0] = true;
            try { canon.setValue(a.isEmpty() && b.isEmpty() ? "" : NUM + op.getValue() + SEP + a + SEP + b); }
            finally { armando[0] = false; }
        };
        op.addValueChangeListener(e -> armar.run());
        v1.addValueChangeListener(e -> armar.run());
        v2.addValueChangeListener(e -> armar.run());
        // "Limpiar filtros" vacía el texto del filtro: también los controles
        canon.addValueChangeListener(e -> {
            if (armando[0] || !e.getValue().isEmpty()) return;
            armando[0] = true;
            try { v1.clear(); v2.clear(); op.setValue("="); v2.setVisible(false); } finally { armando[0] = false; }
        });
        // V50b: uno debajo del otro (operador, valor, hasta): en una columna angosta se ve dónde escribir el número
        VerticalLayout h = new VerticalLayout(op, v1, v2);
        h.setPadding(false);
        h.setSpacing(false);
        h.getStyle().set("gap", "2px");
        h.setWidthFull();
        return h;
    }

    /** V50: filtro de una columna fecha: desde - hasta (cualquiera de los dos puede ir vacío). Se escribe como en GeneXus (0410). */
    static com.vaadin.flow.component.Component filtroFecha(TextField canon) {
        com.vaadin.flow.component.datepicker.DatePicker d = new com.vaadin.flow.component.datepicker.DatePicker(),
                h = new com.vaadin.flow.component.datepicker.DatePicker();
        for (var x : List.of(d, h)) {
            x.setPlaceholder(x == d ? "desde" : "hasta");
            x.setClearButtonVisible(true);
            x.setLocale(Locale.of("es", "AR"));
            x.setI18n(OptimistTrnCampos.i18nFecha());
            OptimistTrnCampos.fechaRapida(x);
            x.setWidthFull();
            x.setMinWidth("0");
        }
        boolean[] armando = {false};
        Runnable armar = () -> {
            if (armando[0]) return;
            armando[0] = true;
            try {
                canon.setValue(d.getValue() == null && h.getValue() == null ? ""
                        : FEC + (d.getValue() == null ? "" : d.getValue()) + SEP + (h.getValue() == null ? "" : h.getValue()));
            } finally { armando[0] = false; }
        };
        d.addValueChangeListener(e -> armar.run());
        h.addValueChangeListener(e -> armar.run());
        canon.addValueChangeListener(e -> {
            if (armando[0] || !e.getValue().isEmpty()) return;
            armando[0] = true;
            try { d.clear(); h.clear(); } finally { armando[0] = false; }
        });
        VerticalLayout fila = new VerticalLayout(d, h);   // V50b: desde arriba, hasta abajo
        fila.setPadding(false);
        fila.setSpacing(false);
        fila.getStyle().set("gap", "2px");
        fila.setWidthFull();
        return fila;
    }

    /** V50: número escrito en un filtro ("1.234,56", "1234.5", "-3") o null. */
    static java.math.BigDecimal numeroFiltro(String t) {
        if (t == null || t.isBlank()) return null;
        String x = t.trim();
        if (x.contains(",")) x = x.replace(".", "").replace(",", ".");
        else if (x.matches("-?\\d{1,3}(\\.\\d{3})+")) x = x.replace(".", "");   // 1.000 / 12.500.000: punto de miles
        try { return new java.math.BigDecimal(x); } catch (NumberFormatException e) { return null; }
    }

    /** V50: si el valor real de la fila cumple el filtro por tipo. */
    static boolean cumpleTipo(String filtro, Object real) {
        if (filtro.startsWith(MUL)) {   // V60: el valor de la fila es uno de los elegidos (por su valor o por el texto que se ve)
            List<String> vs = valoresMultiple(filtro), ts = textosMultiple(filtro);
            if (vs.isEmpty()) return true;
            String r = real == null ? "" : String.valueOf(real).trim();
            return vs.stream().anyMatch(v -> v.equalsIgnoreCase(r)) || ts.stream().anyMatch(x -> x.trim().equalsIgnoreCase(r));
        }
        String[] p = filtro.substring(2).split(SEP, -1);
        if (filtro.startsWith(NUM)) {
            String op = p[0];
            java.math.BigDecimal a = numeroFiltro(p.length > 1 ? p[1] : null), b = numeroFiltro(p.length > 2 ? p[2] : null);
            if (a == null && b == null) return true;
            if (!(real instanceof Number) && !(real instanceof String s0 && numeroFiltro(s0) != null)) return false;
            java.math.BigDecimal v = real instanceof Number n ? new java.math.BigDecimal(n.toString()) : numeroFiltro((String) real);
            if (op.equals("entre")) return (a == null || v.compareTo(a) >= 0) && (b == null || v.compareTo(b) <= 0);
            if (a == null) return true;
            int c = v.compareTo(a);
            return switch (op) { case "!=" -> c != 0; case "<" -> c < 0; case "<=" -> c <= 0; case ">" -> c > 0; case ">=" -> c >= 0; default -> c == 0; };
        }
        if (filtro.startsWith(FEC)) {
            java.time.LocalDate d = p[0].isEmpty() ? null : java.time.LocalDate.parse(p[0]);
            java.time.LocalDate h = p.length < 2 || p[1].isEmpty() ? null : java.time.LocalDate.parse(p[1]);
            java.time.LocalDate v = real instanceof java.time.LocalDate ld ? ld : real instanceof java.time.LocalDateTime lt ? lt.toLocalDate()
                    : real instanceof java.sql.Date sd ? sd.toLocalDate() : real instanceof java.sql.Timestamp ts ? ts.toLocalDateTime().toLocalDate() : null;
            if (v == null) return d == null && h == null;
            return (d == null || !v.isBefore(d)) && (h == null || !v.isAfter(h));
        }
        return true;
    }

    /** V48e: el estilo como texto para style="..." (sin comillas ni símbolos que rompan la plantilla). null si no hay. */
    static String estiloCss(Map<String, String> estilo) {
        if (estilo == null || estilo.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        estilo.forEach((k, v) -> {
            if (k == null || v == null || !k.matches("[a-z-]+")) return;
            sb.append(k).append(':').append(v.replaceAll("[\"<>$`\\\\;{}]", "")).append(';');
        });
        return sb.length() == 0 ? null : sb.toString();
    }

    /** V29: compara por el valor REAL (números como números, fechas como fechas, textos sin importar mayúsculas). Vacíos al final. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> java.util.Comparator<T> comparador(Function<T, ?> valor) {
        java.text.Collator col = java.text.Collator.getInstance(Locale.of("es", "AR"));
        col.setStrength(java.text.Collator.SECONDARY);
        return (a, b) -> {
            Object x = valor.apply(a), y = valor.apply(b);
            boolean vx = x == null || (x instanceof String sx && sx.isBlank());
            boolean vy = y == null || (y instanceof String sy && sy.isBlank());
            if (vx || vy) return vx == vy ? 0 : (vx ? 1 : -1);
            if (x instanceof Number nx && y instanceof Number ny)
                return new java.math.BigDecimal(nx.toString()).compareTo(new java.math.BigDecimal(ny.toString()));
            if (x instanceof String || y instanceof String) return col.compare(String.valueOf(x), String.valueOf(y));
            if (x instanceof Comparable cx && x.getClass().isInstance(y)) return cx.compareTo(y);
            return col.compare(String.valueOf(x), String.valueOf(y));
        };
    }

    /** Aplica simultáneamente todos los filtros por columna. */
    public static <T> List<T> filtrar(List<T> datos, Map<String, TextField> filtros,
            Map<String, Function<T, ?>> extractores) {
        return filtrar(datos, filtros, extractores, Map.of());
    }

    /** V50: reales = el valor de verdad de cada columna (número, fecha), para los filtros por tipo. */
    public static <T> List<T> filtrar(List<T> datos, Map<String, TextField> filtros,
            Map<String, Function<T, ?>> extractores, Map<String, Function<T, ?>> reales) {
        return datos.stream().filter(item -> filtros.entrySet().stream().allMatch(entry -> {
            String texto = entry.getValue().getValue();
            if (texto == null || texto.isBlank()) return true;
            if (texto.startsWith(NUM) || texto.startsWith(FEC) || texto.startsWith(MUL)) {
                Function<T, ?> r = reales.getOrDefault(entry.getKey(), extractores.get(entry.getKey()));
                return cumpleTipo(texto, r == null ? null : r.apply(item));
            }
            Function<T, ?> extractor = extractores.get(entry.getKey());
            Object valor = extractor == null ? null : extractor.apply(item);
            return valor != null && String.valueOf(valor).toLowerCase(Locale.ROOT)
                    .contains(texto.trim().toLowerCase(Locale.ROOT));
        })).collect(Collectors.toList());
    }

    /** V41: una acción del menú del renglón: ícono, texto y qué hace. (Las de cada TRN se suman a la lista.) */
    public record Accion(VaadinIcon icono, String texto, Runnable hacer, String porQueNo) {
        public Accion(VaadinIcon icono, String texto, Runnable hacer) { this(icono, texto, hacer, null); }
        /** V65o: no corresponde a este renglón pero se muestra en gris, con el motivo (como las acciones deshabilitadas de GeneXus). */
        public boolean enGris() { return hacer == null && porQueNo != null; }
    }

    /** V65o: tocar una acción en gris muestra por qué no corresponde. */
    private static void avisarPorQueNo(Accion a) {
        com.vaadin.flow.component.notification.Notification.show(a.texto() + ": " + a.porQueNo(), 9000,
                com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
    }

    /** Menú del renglón: Modificar, Visualizar y Eliminar. */
    public static Button acciones(String descripcion, Runnable modificar, Runnable visualizar, Runnable borrar) {
        return acciones(descripcion, List.of(
                new Accion(VaadinIcon.PENCIL, "Modificar", modificar),
                new Accion(VaadinIcon.SEARCH, "Visualizar", visualizar),
                new Accion(VaadinIcon.CLOSE, "Eliminar", borrar)));
    }

    /** V41: menú del renglón con ícono y texto en cada acción (como el de GeneXus). */
    public static Button acciones(String descripcion, List<Accion> acciones) {
        Button boton = new Button(VaadinIcon.ELLIPSIS_DOTS_V.create());
        boton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        // V50: un recuadro muy suave marca dónde se toca (antes, solo los tres puntitos)
        boton.addClassName("optimist-boton-acciones");
        boton.getStyle().set("border", "1px solid color-mix(in srgb, currentColor 18%, transparent)")
                .set("background", "color-mix(in srgb, currentColor 5%, transparent)")
                .set("border-radius", "6px").set("padding", "2px 6px").set("min-width", "0");
        boton.getElement().setAttribute("title", "Acciones de " + descripcion);
        boton.getElement().setAttribute("aria-label", "Acciones de " + descripcion);
        ContextMenu menu = new ContextMenu(boton);
        menu.setOpenOnClick(true);
        menu.getElement().getThemeList().add("optimist-acciones");   // el overlay toma el tema del menú
        for (Accion a : acciones) if (a != null && (a.hacer() != null || a.enGris())) agregar(menu, a);
        return boton;
    }

    /**
     * V56: las acciones en el renglón (Apariencia "Acciones del renglón: Renglón"): un botón con el ícono por acción y su
     * nombre al pasar el mouse. Se toca una sola vez (con el menú son dos).
     */
    public static HorizontalLayout accionesEnRenglon(String descripcion, List<Accion> acciones) {
        return accionesEnRenglon(descripcion, acciones, null);
    }

    /**
     * V56e: lugares = qué posiciones se usan en la página (null = todas). En una posición usada, la acción que no
     * corresponde a este renglón deja un lugar vacío del mismo ancho, así los íconos quedan alineados.
     */
    public static HorizontalLayout accionesEnRenglon(String descripcion, List<Accion> acciones, boolean[] lugares) {
        HorizontalLayout fila = new HorizontalLayout();
        fila.setSpacing(false);
        fila.setPadding(false);
        fila.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        fila.getStyle().set("gap", "2px").set("flex-wrap", "nowrap");
        for (int i = 0; i < acciones.size(); i++) {
            Accion a = acciones.get(i);
            boolean usado = lugares == null || i < lugares.length && lugares[i];   // V56g: sin lugares, todos
            if (!usado) continue;
            if (a == null || (a.hacer() == null && !a.enGris())) {   // V56e: el lugar vacío, del ancho de un ícono con su margen
                Span vacio = new Span();
                vacio.getStyle().set("display", "inline-block").set("width", "24px").set("height", "24px").set("flex", "none");
                fila.add(vacio);
                continue;
            }
            // V56d: como WorkWithPlus: solo el ícono, lleno, de su color y sin recuadro. Lumo le pone un margen interno al
            // ícono (por eso el dibujo salía de la mitad del tamaño): se saca, así el dibujo ocupa los 18 px.
            Icon icono = iconoAccion(a.icono()).create();
            icono.addClassName("optimist-accion-icono");
            icono.setSize("18px");
            icono.getStyle().set("padding", "0").set("min-width", "18px").set("min-height", "18px")
                    .set("color", a.enGris() ? "var(--lumo-disabled-text-color, #9aa0a6)" : colorAccion(a.icono()));
            if (a.enGris()) icono.getStyle().set("opacity", "0.45");
            Button b = new Button(icono, e -> { if (a.enGris()) avisarPorQueNo(a); else a.hacer().run(); });
            b.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON);
            b.addClassName("optimist-boton-accion-renglon");
            b.getStyle().set("border-radius", "5px").set("padding", "3px").set("min-width", "0").set("margin", "0")
                    .set("background", "transparent").set("border", "none");
            b.setTooltipText(a.enGris() ? a.texto() + " — " + a.porQueNo() : a.texto());
            // V59: el nombre a la derecha del ícono (arriba, el puntero grande del mouse lo tapaba)
            b.getTooltip().withPosition(com.vaadin.flow.component.shared.Tooltip.TooltipPosition.END);
            b.getElement().setAttribute("aria-label", a.texto() + " " + descripcion);
            fila.add(b);
        }
        return fila;
    }

    /** V56d: el color de cada acción en el renglón (como los íconos de WorkWithPlus): cada tipo de acción, el suyo. */
    static String colorAccion(VaadinIcon icono) {
        if (icono == null) return "#0f9d8a";
        return switch (icono) {
            case PENCIL, EDIT -> "#e09b00";                 // Modificar: amarillo
            case SEARCH, EYE -> "#1e7fd8";                  // Visualizar: azul
            case CLOSE, CLOSE_CIRCLE, TRASH -> "#d93a3f";   // Eliminar: rojo
            case BAN, CLOSE_BIG -> "#a8231d";               // Cancelar: bordó
            case CHECK, CHECK_CIRCLE -> "#2e9e5b";          // Autorizar / dar por cumplida: verde
            case PRINT -> "#4f6b7a";                        // Imprimir: gris azulado
            case COPY -> "#8e44ad";                         // Duplicar: violeta
            case ENVELOPE -> "#e67e22";                     // Mail: naranja
            case FILE_ADD -> "#0f9d8a";                     // Generar comprobante / pedido: verde agua
            case EXCHANGE -> "#3f51b5";                     // Transferir: índigo
            case CLOCK -> "#8d6e63";                        // Pasar a en trámite / pendiente: marrón
            case CALENDAR -> "#c2185b";                     // Fecha de entrega: fucsia
            case THUMBS_DOWN -> "#d35400";                  // Dar por perdido: naranja oscuro
            case LOCK -> "#a8231d";                         // Bloquear (despacho): bordó
            case UNLOCK -> "#2e9e5b";                       // Liberar (despacho): verde
            case PACKAGE -> "#0f9d8a";                      // Preparar despacho: verde agua
            case CLIPBOARD_CHECK -> "#1e7fd8";              // Revisar despacho: azul
            case BARCODE -> "#4f6b7a";                      // Asignar partidas: gris azulado
            case ROTATE_LEFT, REFRESH -> "#e67e22";         // Reiniciar / Reautorizar: naranja
            case TRUCK -> "#3f51b5";                        // Ver despacho: índigo
            default -> {                                    // otro ícono: un color fijo según el ícono
                String[] p = {"#00897b", "#5e35b1", "#f4511e", "#3949ab", "#6d4c41", "#d81b60", "#43a047", "#fb8c00"};
                yield p[Math.floorMod(icono.name().hashCode(), p.length)];
            }
        };
    }

    /** V56f: el ícono que se dibuja (el mismo en el menú y en el renglón): Eliminar, la cruz en círculo. */
    /**
     * V66l: el "flex-grow" de una columna con ancho fijo: su ancho en px. Así, si la pantalla es más ancha que la grilla, lo
     * que sobra se reparte entre las columnas en proporción a su ancho y la grilla ocupa todo el ancho; si es más angosta, cada
     * columna queda en su ancho (barra horizontal). La columna de las acciones no crece.
     */
    static int crecimiento(String ancho) {
        if (ancho == null) return 1;
        String s = ancho.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (s.endsWith("px")) return Math.max(1, (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 2).trim())));
            if (s.endsWith("em")) return Math.max(1, (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 2).trim()) * 16));
            return Math.max(1, (int) Math.round(Double.parseDouble(s)));
        } catch (NumberFormatException e) { return 1; }
    }

    static VaadinIcon iconoAccion(VaadinIcon icono) {
        return icono == VaadinIcon.CLOSE ? VaadinIcon.CLOSE_CIRCLE : icono;
    }

    private static void agregar(ContextMenu menu, Accion a) {
        Icon icono = iconoAccion(a.icono()).create();
        icono.addClassName("optimist-accion-icono");
        icono.getStyle().set("color", colorAccion(a.icono()));   // V56f: el mismo color que en el renglón
        Span texto = new Span(a.texto());
        HorizontalLayout fila = new HorizontalLayout(icono, texto);
        fila.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        fila.setSpacing(false);
        fila.addClassName("optimist-accion");
        com.vaadin.flow.component.Component contenido = fila;
        if (a.enGris()) {
            // V65o: en gris, con el motivo abajo en letra chica (se ve sin pasar el mouse); al tocarla, el aviso con el motivo
            icono.getStyle().set("color", "var(--lumo-disabled-text-color, #9aa0a6)");
            texto.getStyle().set("color", "var(--lumo-disabled-text-color, #9aa0a6)");
            Span motivo = new Span(a.porQueNo());
            motivo.getStyle().set("font-size", "11px").set("color", "var(--lumo-secondary-text-color, #8a8f98)")
                    .set("white-space", "normal").set("max-width", "360px").set("line-height", "1.25");
            com.vaadin.flow.component.orderedlayout.VerticalLayout v = new com.vaadin.flow.component.orderedlayout.VerticalLayout(fila, motivo);
            v.setPadding(false); v.setSpacing(false);
            v.getStyle().set("gap", "1px");
            contenido = v;
        }
        MenuItem item = menu.addItem(contenido, e -> { if (a.enGris()) avisarPorQueNo(a); else a.hacer().run(); });
        item.getElement().setAttribute("aria-label", a.texto());
    }

    /** Alterna el ancho del panel principal; cada TRN puede reutilizarlo. */
    public static Button alternarAncho(VerticalLayout panel, String anchoNormal) {
        Button boton = new Button("Pantalla completa", VaadinIcon.EXPAND.create());
        boton.getElement().setAttribute("title", "Alternar vista normal y ancho completo");
        boton.addClickListener(e -> {
            boolean completa = !Boolean.TRUE.equals(boton.getElement().getProperty("optimistCompleta", false));
            boton.getElement().setProperty("optimistCompleta", completa);
            panel.setMaxWidth(completa ? "none" : anchoNormal);
            boton.setText(completa ? "Vista normal" : "Pantalla completa");
            boton.setIcon(completa ? VaadinIcon.COMPRESS.create() : VaadinIcon.EXPAND.create());
        });
        return boton;
    }

    /** Selector idéntico para todas las grillas; devuelve el componente para registrar listeners. */
    public static ComboBox<Integer> selectorFilas(int valorInicial) {
        ComboBox<Integer> selector = new ComboBox<>();
        selector.setItems(10, 20, 50, 100);
        selector.setValue(valorInicial);
        selector.setWidth("110px");
        selector.setAriaLabel("Registros por página");
        selector.getStyle().set("--lumo-primary-color", "var(--optimist-acento)");
        return selector;
    }

    public static HorizontalLayout piePagina(ComboBox<Integer> selector, Span estado, Button anterior, Button siguiente) {
        return piePagina(selector, estado, null, anterior, siguiente, null);
    }

    /** Pie con navegación completa: Primera, Anterior, Siguiente, Última (primera/ultima pueden ser null). */
    public static HorizontalLayout piePagina(ComboBox<Integer> selector, Span estado, Button primera, Button anterior,
                                             Button siguiente, Button ultima) {
        HorizontalLayout izquierda = new HorizontalLayout(new Span("Registros por página:"), selector);
        izquierda.setAlignItems(HorizontalLayout.Alignment.CENTER);
        HorizontalLayout derecha = new HorizontalLayout(estado);
        if (primera != null) derecha.add(primera);
        derecha.add(anterior, siguiente);
        if (ultima != null) derecha.add(ultima);
        derecha.setAlignItems(HorizontalLayout.Alignment.CENTER);
        HorizontalLayout pie = new HorizontalLayout(izquierda, derecha);
        pie.setWidthFull();
        pie.setAlignItems(HorizontalLayout.Alignment.CENTER);
        pie.setJustifyContentMode(HorizontalLayout.JustifyContentMode.BETWEEN);
        pie.getStyle().set("flex-wrap", "wrap").set("gap", "12px");
        return pie;
    }
    /** Pantalla maestra: estructura, márgenes, barra, grilla y pie idénticos para todas las TRN. */
    public static final class Pantalla<T> {
        public final VerticalLayout contenido = new VerticalLayout();
        public final VerticalLayout tarjeta = new VerticalLayout();
        public final Grid<T> grilla;
        public final ComboBox<Integer> filas = selectorFilas(10);
        public final Span estado = new Span();
        public final Button primera = new Button("Primera");
        public final Button anterior = new Button("Anterior");
        public final Button siguiente = new Button("Siguiente");
        public final Button ultima = new Button("Última");
        private int paginas = 1;
        public final Button nuevo;
        public final Button apariencia;
        /** V48: los botones de arriba (+ Nuevo, Excel...): ahí se agrega, por ejemplo, el de ayuda. */
        public HorizontalLayout barraAcciones;
        public final OptimistApariencia configuracion;
        private final Map<String, TextField> filtros = new LinkedHashMap<>();
        private final Map<String, Function<T, ?>> extractores = new LinkedHashMap<>();
        private final List<T> registros = new java.util.ArrayList<>();
        private int pagina = 0;
        private final String sustantivo;
        /** V26: columnas para el Excel (las mismas del WorkWith, con el dato "crudo" y su formato). */
        private final List<OptimistExcel.Columna<T>> columnasExcel = new java.util.ArrayList<>();
        private String archivoExcel;
        private com.vaadin.flow.server.StreamRegistration ultimaDescarga;
        /** V66k: el título de la lista (el del PDF). */
        private com.vaadin.flow.component.html.H1 tituloLista;
        // V29: columnas por clave, orden global y preferencias del usuario (por TRN)
        private final Map<String, Grid.Column<T>> columnas = new LinkedHashMap<>();
        private final Map<String, OptimistExcel.Columna<T>> excelPorClave = new LinkedHashMap<>();
        private final Map<String, java.util.Comparator<T>> comparadores = new LinkedHashMap<>();
        private final Map<String, String> titulos = new LinkedHashMap<>();
        private Grid.Column<T> columnaAcciones;
        /** V69: lo que se lee antes de mostrar cada página (ej. los PostIts) y la marca chiquita de cada fila (o null). */
        private java.util.function.Consumer<List<T>> alCargarPagina;
        private Function<T, com.vaadin.flow.component.Component> marcaRenglon;
        private String ordenClave;
        private boolean ordenDesc;
        private String idPreferencias;
        private boolean preferenciasAplicadas;
        /** V66s: el ancho de fábrica de cada columna (ancho, crecimiento, automático) y el que eligió el usuario (px). */
        private record AnchoOriginal(String ancho, int crece, boolean auto) {}
        private final Map<String, AnchoOriginal> anchosOriginales = new LinkedHashMap<>();
        private final Map<String, Integer> anchosUsuario = new LinkedHashMap<>();
        private final OptimistUserConfig preferencias = new OptimistUserConfig();
        private final String usuarioPref = OptimistShell.usuario() == null ? "anonimo" : OptimistShell.usuario();
        // V32: renglones pares más oscuros; el renglón tocado (o con el que se trabajó en update/view) más oscuro todavía
        private final Map<T, Integer> indicePagina = new java.util.IdentityHashMap<>();
        /** V50: valor real de cada columna (el del Excel) y el tipo de filtro que se pidió para ella ("numero", "fecha"). */
        private final Map<String, Function<T, ?>> reales = new LinkedHashMap<>();
        private final Map<String, String> tiposFiltro = new LinkedHashMap<>();

        /** V50: la próxima columna con esta clave lleva filtro por tipo: "numero" (=, <, entre...) o "fecha" (desde - hasta). */
        public void tipoFiltro(String clave, String tipo) { if (tipo != null) tiposFiltro.put(clave, tipo); }
        /** V60: la próxima columna con esta clave filtra con un combo de selección múltiple con estos valores (dominio enumerado). */
        public void filtroValores(String clave, List<OptimistTrnDefinicion.Valor> valores) {
            if (valores == null || valores.isEmpty()) return;
            tiposFiltro.put(clave, "combo");
            valoresFiltro.put(clave, valores);
        }
        private final Map<String, List<OptimistTrnDefinicion.Valor>> valoresFiltro = new LinkedHashMap<>();
        /** V50e: la próxima columna con esta clave muestra su valor como vínculo a esta dirección (null en la fila = texto común). */
        public void enlaceColumna(String clave, Function<T, String> href) { if (href != null) enlaces.put(clave, href); }
        private final Map<String, Function<T, String>> enlaces = new LinkedHashMap<>();
        private Function<T, Object> identificador = x -> x;
        private Object trabajado;

        /**
         * V50o: de dónde salen los renglones cuando la lista se pagina en la base (workwith.paginarEnServidor). filtros: clave
         * de la columna -> texto del filtro (el mismo que usa el filtro en memoria); orden: clave de la columna o null.
         * cantidad < 0 = todos (Excel). Sin fuente, la lista trabaja en memoria con lo que recibe cargar().
         */
        public interface Fuente<T> {
            int contar(Map<String, String> filtros) throws Exception;
            List<T> pagina(Map<String, String> filtros, String orden, boolean desc, int desde, int cantidad) throws Exception;
            /** V75: la cantidad y la suma de esas columnas en la misma consulta (sin redefinir: sin totales). */
            default OptimistTrnDatos.Conteo contarYSumar(Map<String, String> filtros, java.util.Set<String> sumar) throws Exception {
                return OptimistTrnDatos.Conteo.de(contar(filtros));
            }
            /** V77: con la columna de la moneda (o null): los totales por moneda. */
            default OptimistTrnDatos.Conteo contarYSumar(Map<String, String> filtros, java.util.Set<String> sumar, String moneda) throws Exception {
                return contarYSumar(filtros, sumar);
            }
        }

        // V75: totales al pie (Diego, 25/10/2026): la suma de las columnas de importes de TODO lo filtrado (no solo la página)
        private final java.util.Set<String> totalizar = new java.util.LinkedHashSet<>();
        private final Map<String, String> formatoTotal = new LinkedHashMap<>();
        private com.vaadin.flow.component.grid.FooterRow pieTotales;
        private OptimistTrnDatos.Conteo conteoGuardado;
        private Map<String, String> filtrosConteo;
        private boolean soloCambioPagina;
        /** V77: la columna con la moneda del renglón (el código de la tabla Monedas): un renglón de totales por moneda. */
        private String monedaClave;
        private Function<T, ?> monedaValor;
        public void totalesMoneda(String clave, Function<T, ?> valor) { monedaClave = clave; monedaValor = valor; }

        /** V75: esta columna lleva su total al pie; formato: el del Excel ("#,##0.00"), o null (2 decimales). */
        public void totalizar(String clave, String formato) {
            totalizar.add(clave);
            formatoTotal.put(clave, formato == null ? "#,##0.00" : formato);
        }

        // V84i: los totales, solo para los autorizados (totales.usuarios del optimist.cfg) y si los prendió con Σ
        private Button botonTotales;
        private boolean totalesPrendidos;
        private boolean totalesLeidos;

        /** V84i: si esta lista calcula y muestra los totales (la primera vez lee si el usuario los dejó prendidos). */
        private boolean conTotales() {
            if (totalizar.isEmpty()) return false;
            if (!totalesLeidos) {
                totalesLeidos = true;
                boolean aut = OptimistTotales.autorizado();
                totalesPrendidos = aut && OptimistTotales.prendidos(OptimistTotales.clave("ww", idPreferencias == null ? sustantivo : idPreferencias));
                if (botonTotales != null) { botonTotales.setVisible(aut); OptimistTotales.pintar(botonTotales, totalesPrendidos); }
            }
            return totalesPrendidos;
        }

        /** V84i: sin totales: el renglón del pie se saca. */
        private void sacarTotales() {
            if (pieTotales == null) return;
            OptimistTotales.sacarPie(grilla, pieTotales);
            pieTotales = null;
        }

        private void mostrarTotales(int cantidad, Map<String, java.math.BigDecimal> sumas) {
            mostrarTotales(cantidad, sumas, List.of());
        }

        /** V77: con monedas, un renglón por moneda ("Total en $", "Total en U$S"); sin, "TOTALES". */
        private void mostrarTotales(int cantidad, Map<String, java.math.BigDecimal> sumas, List<OptimistTrnDatos.PorMoneda> porMoneda) {
            if (totalizar.isEmpty()) return;
            if (monedaClave != null && !porMoneda.isEmpty()) { mostrarTotalesPorMoneda(porMoneda); return; }
            if (pieTotales == null) pieTotales = grilla.appendFooterRow();
            Grid.Column<T> rotulo = columnaRotulo();
            for (Grid.Column<T> c : grilla.getColumns()) {
                String k = c.getKey();
                java.math.BigDecimal v = k == null ? null : sumas.get(k);
                Span sp = new Span();
                sp.getStyle().set("font-weight", "700");
                if (v != null && totalizar.contains(k)) sp.setText(OptimistPdfLista.texto(v, formatoTotal.get(k)));
                else if (c == rotulo) { sp.setText("TOTALES"); sp.getStyle().set("display", "block").set("text-align", "right").set("white-space", "nowrap"); }
                pieTotales.getCell(c).setComponent(sp);
            }
        }

        /**
         * V77: dónde va el rótulo de los totales ("TOTALES", "Total en $"): la columna visible anterior a la primera que suma
         * (en el orden en que se ven, aunque el usuario las haya movido); si la primera que suma es la primera, la de acciones
         * o la primera que no suma.
         */
        private Grid.Column<T> columnaRotulo() {
            Grid.Column<T> antes = null;
            for (Grid.Column<T> c : grilla.getColumns()) {
                if (!c.isVisible()) continue;
                if (c.getKey() != null && totalizar.contains(c.getKey())) { if (antes != null) return antes; break; }
                antes = c;
            }
            if (columnaAcciones != null && columnaAcciones.isVisible()) return columnaAcciones;
            for (Grid.Column<T> c : grilla.getColumns())
                if (c.isVisible() && c.getKey() != null && !totalizar.contains(c.getKey())) return c;
            return null;
        }

        private void mostrarTotalesPorMoneda(List<OptimistTrnDatos.PorMoneda> porMoneda) {
            if (pieTotales == null) pieTotales = grilla.appendFooterRow();
            Grid.Column<T> rotulo = columnaRotulo();
            for (Grid.Column<T> c : grilla.getColumns()) {
                String k = c.getKey();
                com.vaadin.flow.component.html.Div celda = new com.vaadin.flow.component.html.Div();
                celda.getStyle().set("display", "flex").set("flex-direction", "column").set("font-weight", "700");
                boolean suma = k != null && totalizar.contains(k);
                for (OptimistTrnDatos.PorMoneda pm : porMoneda) {
                    Span sp = new Span();
                    sp.getStyle().set("white-space", "nowrap");
                    if (suma) {
                        java.math.BigDecimal v = pm.sumas().get(k);
                        sp.setText(v == null ? "" : OptimistPdfLista.texto(v, formatoTotal.get(k)));
                    } else if (c == rotulo) {
                        sp.setText(pm.moneda().isEmpty() ? "TOTALES" : "Total en " + pm.moneda());
                        sp.getStyle().set("text-align", "right");   // V77: pegado a los importes
                    }
                    else sp.setText("\u00a0");
                    celda.add(sp);
                }
                pieTotales.getCell(c).setComponent(celda);
            }
        }

        /** V77: la lista en memoria, por moneda (en el orden de la moneda). */
        private List<OptimistTrnDatos.PorMoneda> sumarPorMonedaEnMemoria(List<T> encontrados) {
            if (monedaValor == null) return List.of();
            Map<String, List<T>> grupos = new java.util.TreeMap<>();
            for (T item : encontrados) {
                Object m = monedaValor.apply(item);
                grupos.computeIfAbsent(m == null ? "" : String.valueOf(m).strip(), x -> new java.util.ArrayList<>()).add(item);
            }
            List<OptimistTrnDatos.PorMoneda> r = new java.util.ArrayList<>();
            grupos.forEach((m, l) -> r.add(new OptimistTrnDatos.PorMoneda(m, l.size(), sumarEnMemoria(l))));
            return r;
        }

        /** V75: la lista en memoria: las sumas de lo filtrado con el valor real de cada columna. */
        private Map<String, java.math.BigDecimal> sumarEnMemoria(List<T> encontrados) {
            Map<String, java.math.BigDecimal> m = new LinkedHashMap<>();
            for (String k : totalizar) {
                Function<T, ?> f = reales.get(k);
                if (f == null) continue;
                java.math.BigDecimal t = java.math.BigDecimal.ZERO;
                for (T item : encontrados) {
                    Object v = f.apply(item);
                    if (v instanceof java.math.BigDecimal b) t = t.add(b);
                    else if (v instanceof Number n) t = t.add(new java.math.BigDecimal(n.toString()));
                }
                m.put(k, t);
            }
            return m;
        }
        private Fuente<T> fuente;
        /** V50o: la lista pide cada página a la base (ver Fuente). */
        public void fuente(Fuente<T> f) { this.fuente = f; }
        public boolean conFuente() { return fuente != null; }

        private Map<String, String> textosFiltros() {
            Map<String, String> m = new LinkedHashMap<>();
            filtros.forEach((k, t) -> { String v = t.getValue(); if (v != null && !v.isBlank()) m.put(k, v); });
            return m;
        }

        private void errorFuente(Exception ex) {
            com.vaadin.flow.component.notification.Notification.show("No se pudo leer la lista: " + ex.getMessage(), 8000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
        }

        /** V48: las acciones de cada renglón (según sus condiciones). null = Modificar, Visualizar y Eliminar siempre. */
        private Function<T, List<Accion>> accionesDeFila;
        private boolean enRenglon;   // V56: las acciones en el renglón (Apariencia)
        private Consumer<T> modificar, visualizar, borrar;   // V56e: las acciones comunes (sin acciones del YAML)
        public void acciones(Function<T, List<Accion>> deFila) {
            this.accionesDeFila = deFila;
            accionesPagina.clear(); lugares = null;
            grilla.getDataProvider().refreshAll();
        }
        /**
         * V56e: las acciones de cada renglón de la página (se calculan una vez al cargarla: las condiciones pueden llamar
         * procedimientos) y qué lugares se usan: una acción que no corresponde a ningún renglón de la página no ocupa lugar;
         * si corresponde a alguno, en los demás deja su lugar vacío (los íconos quedan alineados, como GeneXus).
         */
        private final Map<T, List<Accion>> accionesPagina = new java.util.IdentityHashMap<>();
        private boolean[] lugares;

        private List<Accion> accionesDe(T item) {
            List<Accion> l = accionesPagina.get(item);
            if (l != null) return l;
            l = accionesDeFila != null
                ? accionesDeFila.apply(item).stream()
                    .map(a -> a == null || a.hacer() == null ? a : new Accion(a.icono(), a.texto(), () -> { marcar(item); a.hacer().run(); }))
                    .toList()
                : List.of(new Accion(VaadinIcon.PENCIL, "Modificar", () -> { marcar(item); modificar.accept(item); }),
                          new Accion(VaadinIcon.SEARCH, "Visualizar", () -> { marcar(item); visualizar.accept(item); }),
                          new Accion(VaadinIcon.CLOSE, "Eliminar", () -> { marcar(item); borrar.accept(item); }));
            accionesPagina.put(item, l);
            return l;
        }

        private void prepararAcciones(List<T> visibles) {
            accionesPagina.clear();
            lugares = null;
            if (!enRenglon) return;
            // V56g: cada acción que puede corresponder tiene siempre su lugar (vacío en el renglón donde no corresponde),
            // aunque en esta página no corresponda a ninguno: los íconos quedan en la misma columna en todas las páginas
            int n = 0;
            for (T item : visibles) n = Math.max(n, accionesDe(item).size());
            if (columnaAcciones != null) columnaAcciones.setWidth(Math.max(90, 14 + 26 * n) + "px");
        }

        public Pantalla(VerticalLayout vista, Grid<T> grilla, String sustantivo,
                String etiquetaNuevo, Runnable alNuevo, Function<T,String> descripcion,
                Consumer<T> modificar, Consumer<T> visualizar, Consumer<T> borrar,
                com.vaadin.flow.component.html.H1 titulo, Span marca) {
            this.modificar = modificar; this.visualizar = visualizar; this.borrar = borrar;
            this.grilla = grilla;
            this.tituloLista = titulo;   // V66k: el título del PDF
            this.sustantivo = sustantivo;
            vista.setPadding(false); vista.setSpacing(false); vista.setWidthFull();
            vista.getStyle().set("background", "var(--optimist-fondo)").set("color", "var(--optimist-texto)");
            contenido.setWidthFull(); contenido.setMaxWidth("1450px");
            contenido.getStyle().set("margin", "auto").set("padding", "28px").set("box-sizing", "border-box");
            tarjeta.setWidthFull();
            tarjeta.getStyle().set("background", "var(--optimist-fondo)")
                .set("border", "1px solid var(--optimist-acento)").set("border-radius", "16px")
                .set("padding", "20px").set("box-sizing", "border-box");
            contenido.add(tarjeta);
            nuevo = new Button(etiquetaNuevo, e -> alNuevo.run());
            nuevo.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            nuevo.getStyle().set("background", "var(--optimist-acento)").set("color", "var(--optimist-texto)");
            configuracion = new OptimistApariencia(vista, contenido, tarjeta, grilla, titulo, marca, nuevo, filas, "1450px");
            apariencia = configuracion.boton();
            Button excel = new Button("Excel", VaadinIcon.FILE_TABLE.create(), e -> exportarExcel());
            excel.getElement().setAttribute("title", "Exportar a Excel lo que muestran los filtros (todas las páginas)");
            Button pdf = new Button("PDF", VaadinIcon.FILE_TEXT_O.create(), e -> exportarPdf());   // V66k
            pdf.getElement().setAttribute("title", "Exportar a PDF lo que muestran los filtros (todas las páginas)");
            Button limpiar = new Button("↺ Limpiar filtros", e -> { filtros.values().forEach(TextField::clear); pagina=0; refrescar(); });
            // V29: elegir y ordenar columnas (por usuario, queda guardado)
            Button elegirColumnas = new Button(VaadinIcon.COG.create(), e -> dialogoColumnas());
            elegirColumnas.getElement().setAttribute("title", "Columnas: mostrar, ocultar, ordenar y ancho");
            // V84i: Σ (los totales al pie, solo para los autorizados en el optimist.cfg), entre Apariencia y PostIt
            botonTotales = OptimistTotales.boton(prendidos -> {
                totalesPrendidos = prendidos;
                OptimistTotales.recordar(OptimistTotales.clave("ww", idPreferencias == null ? sustantivo : idPreferencias), prendidos);
                conteoGuardado = null;   // con los totales prendidos, la cantidad se vuelve a pedir con las sumas
                refrescar();
            });
            botonTotales.setVisible(false);   // se ve cuando la lista tiene columnas con total y el usuario está autorizado
            HorizontalLayout acciones = new HorizontalLayout(nuevo, excel, pdf, elegirColumnas, apariencia, botonTotales, OptimistPostIts.boton());   // V82q: PostIt, como los demás
            barraAcciones = acciones;
            acciones.setAlignItems(HorizontalLayout.Alignment.CENTER);
            acciones.getStyle().set("flex-wrap", "wrap");          // V28: en el celular los botones bajan de renglón
            HorizontalLayout barra = new HorizontalLayout(acciones, limpiar);
            barra.getStyle().set("flex-wrap", "wrap").set("gap", "8px");
            contenido.addClassName("optimist-ww");
            barra.setWidthFull(); barra.setJustifyContentMode(HorizontalLayout.JustifyContentMode.BETWEEN);
            barra.setAlignItems(HorizontalLayout.Alignment.CENTER);
            tarjeta.add(barra);
            // V32: la grilla crece hasta mostrar todas las filas de la página (10, 20, 50 o 100); la página se desplaza
            grilla.setWidthFull(); grilla.setAllRowsVisible(true);
            // V32: el texto que no entra en el ancho de la columna sigue en otro renglón (no se corta)
            grilla.addThemeVariants(com.vaadin.flow.component.grid.GridVariant.LUMO_WRAP_CELL_CONTENT);
            grilla.getStyle().set("background", "var(--optimist-fondo)").set("border-radius", "10px").set("overflow", "hidden");
            grilla.addThemeName("optimist-grid");
            // V36: la densidad de la grilla (Apariencia) ahora también se aplica al WorkWith, no solo a la muestra
            String densidad = preferencias.cargar(usuarioPref).getProperty("densidad", "Normal");
            switch (densidad) {
                case "Supercompacta" -> grilla.addThemeName("optimist-grid-supercompacta");
                case "Compacta" -> grilla.addThemeName("optimist-grid-compacta");
                case "Cómoda" -> grilla.addThemeName("optimist-grid-comoda");
                default -> { }
            }
            grilla.setPartNameGenerator(item -> {
                String partes = "";
                Integer i = indicePagina.get(item);
                if (i != null && i % 2 == 1) partes = "optimist-par";
                if (trabajado != null && trabajado.equals(identificador.apply(item))) partes = (partes + " optimist-elegida").trim();
                String extra = parteRenglon == null ? null : parteRenglon.apply(item);   // V61h: estiloRenglon del WorkWith
                if (extra != null) partes = (partes + " " + extra).trim();
                return partes.isEmpty() ? null : partes;
            });
            grilla.addItemClickListener(e -> marcar(e.getItem()));
            // V56: en el menú de los 3 puntitos (por defecto) o un ícono por acción en el renglón (Apariencia, como GeneXus)
            enRenglon = OptimistShell.accionesEnRenglon(preferencias.cargar(usuarioPref));
            columnaAcciones = grilla.addComponentColumn(item -> {
                List<Accion> lista = accionesDe(item);
                com.vaadin.flow.component.Component comp = !enRenglon ? OptimistWorkWith.acciones(descripcion.apply(item), lista)
                        : OptimistWorkWith.accionesEnRenglon(descripcion.apply(item), lista, lugares);
                com.vaadin.flow.component.Component marcaPostIt = marcaRenglon == null ? null : marcaRenglon.apply(item);   // V69
                if (marcaPostIt == null) return comp;
                HorizontalLayout h = new HorizontalLayout(marcaPostIt, comp);
                h.setSpacing(false);
                h.setPadding(false);
                h.setAlignItems(HorizontalLayout.Alignment.CENTER);
                h.getStyle().set("gap", "3px");
                return h;
            }).setHeader("Acciones").setWidth("90px").setFlexGrow(0).setKey("_acciones");
            // V29: ordenar por una columna ordena TODAS las filas filtradas (no solo la página) y queda guardado.
            // Tercer clic en el encabezado = sin orden (vuelve al orden original del YAML).
            grilla.addSortListener(e -> {
                List<com.vaadin.flow.component.grid.GridSortOrder<T>> so = e.getSortOrder();
                if (so.isEmpty()) { ordenClave = null; ordenDesc = false; }
                else {
                    ordenClave = so.get(0).getSorted().getKey();
                    ordenDesc = so.get(0).getDirection() == com.vaadin.flow.data.provider.SortDirection.DESCENDING;
                }
                pagina = 0;
                refrescar();
                if (e.isFromClient()) guardarPref("orden", ordenClave == null ? null : ordenClave + (ordenDesc ? ":DESC" : ":ASC"));
            });
            // V29: también se pueden reordenar las columnas arrastrando el encabezado
            grilla.setColumnReorderingAllowed(true);
            grilla.addColumnReorderListener(e -> { if (e.isFromClient()) guardarColumnas(); protegerDesplazamiento(); });
            // V49q: la grilla NUNCA se mueve sola (pedido de Diego): ni al refrescar, ni al marcar una fila, ni al mover una columna
            grilla.addAttachListener(e -> grilla.getElement().executeJs(FIJAR_DESPLAZAMIENTO));
            tarjeta.add(grilla);
            filas.addValueChangeListener(e -> {
                if (cargandoFilas) return;
                // V53c: la cantidad de renglones es de cada WorkWith (por usuario, y por cliente en la instalación única)
                if (e.isFromClient() && e.getValue() != null) guardarPref("filas", String.valueOf(e.getValue()));
                pagina=0; refrescar();
            });
            // V75: pasar de página no vuelve a contar ni a sumar (mismos filtros)
            anterior.addClickListener(e -> { if(pagina>0){pagina--;soloCambioPagina=true;refrescar();} });
            siguiente.addClickListener(e -> {pagina++;soloCambioPagina=true;refrescar();});
            primera.addClickListener(e -> { pagina=0; soloCambioPagina=true; refrescar(); });
            ultima.addClickListener(e -> { pagina=Math.max(0,paginas-1); soloCambioPagina=true; refrescar(); });
            estado.getStyle().set("color", "var(--optimist-texto)");
            // V50j: el pie (Registros por página / Anterior - Siguiente) queda SIEMPRE abajo de la pantalla, como Confirmar/Cancelar
            // en las TRN: la lista y su tarjeta ocupan al menos todo el alto visible y el pie se pega al borde de abajo
            // (sticky) cuando la página es más alta que la pantalla. Antes subía o bajaba según los renglones de dos líneas.
            // Sin margen abajo (la tarjeta queda abierta abajo, como el pie de un formulario): así el pie está a la misma
            // altura con una lista corta y con una larga.
            contenido.getStyle().set("flex", "1 0 auto").set("padding-bottom", "0");
            tarjeta.getStyle().set("flex", "1 0 auto").set("padding-bottom", "0").set("border-bottom", "none")
                    .set("border-radius", "16px 16px 0 0");
            HorizontalLayout pie = piePagina(filas,estado,primera,anterior,siguiente,ultima);
            pie.addClassName("optimist-ww-pie");
            tarjeta.add(pie);
        }
        public void columna(String clave, String etiqueta, Function<T,?> extractor) {
            columna(clave, etiqueta, extractor, extractor, null);
        }

        /** V26: valorExcel es el dato para el Excel (número, fecha...); formatoExcel, su formato numérico (o null). */
        public Grid.Column<T> columna(String clave, String etiqueta, Function<T,?> extractor, Function<T,?> valorExcel, String formatoExcel) {
            return columna(clave, etiqueta, extractor, valorExcel, formatoExcel, null);
        }

        /** V40: color != null = columna con insignia (pastilla de color). */
        public Grid.Column<T> columna(String clave, String etiqueta, Function<T,?> extractor, Function<T,?> valorExcel, String formatoExcel,
                                      Function<T, String> color) {
            return columna(clave, etiqueta, extractor, valorExcel, formatoExcel, color, null);
        }

        /** V48e: estilo de las celdas (clase del WorkWith), o null. */
        public Grid.Column<T> columna(String clave, String etiqueta, Function<T,?> extractor, Function<T,?> valorExcel, String formatoExcel,
                                      Function<T, String> color, Map<String, String> estilo) {
            extractores.put(clave,extractor);
            reales.put(clave, valorExcel);   // V50: para los filtros por tipo (número, fecha)
            OptimistExcel.Columna<T> ce = new OptimistExcel.Columna<>(etiqueta, valorExcel, formatoExcel);
            columnasExcel.add(ce);
            excelPorClave.put(clave, ce);
            titulos.put(clave, etiqueta);
            java.util.Comparator<T> cmp = OptimistWorkWith.comparador(valorExcel);
            comparadores.put(clave, cmp);
            Grid.Column<T> col = OptimistWorkWith.columna(grilla,clave,etiqueta,extractor,filtros,()->{pagina=0;refrescar();}, color, estilo,
                    tiposFiltro.get(clave), enlaces.get(clave), valoresFiltro.get(clave));
            col.setComparator(cmp);   // la página visible se ordena igual que el total
            // V52: el estilo de cada celda según su valor (ej. el stock: verde si es positivo, rojo si es negativo)
            Function<T, String> porFila = estilosPorFila.get(clave);
            Lupa<T> lupa = lupas.get(clave);
            if (porFila != null || lupa != null) {
                String fijo = estiloCss(estilo);
                // V54: la lupa al lado del número (como en OPTIMIST): abre el detalle de esa celda
                String icono = lupa == null ? "" : "<vaadin-icon icon=\"vaadin:search\" title=\"" + lupa.titulo().replaceAll("[\"<>$`\\\\{}]", "")
                        + "\" ?hidden=\"${!item.lupa}\" @click=\"${abrir}\" style=\"width:13px;height:13px;margin-left:5px;cursor:pointer;"
                        + "vertical-align:-1px;color:var(--lumo-primary-text-color)\"></vaadin-icon>";
                // V66l: con estilo por renglón y vínculo a la vez (ej. el saldo de una consulta que abre otra): el vínculo con su estilo
                Function<T, String> enl = enlaces.get(clave);
                String cuerpo = enl == null ? "<span style=\"${item.estilo}\">${item.texto}</span>"
                        : "<a router-link href=\"${item.href}\" ?hidden=\"${!item.href}\" title=\"Ver\""
                          + " style=\"color:var(--lumo-primary-text-color);text-decoration:underline;cursor:pointer;${item.estilo}\">${item.texto}</a>"
                          + "<span ?hidden=\"${item.href}\" style=\"${item.estilo}\">${item.texto}</span>";
                com.vaadin.flow.data.renderer.LitRenderer<T> r = com.vaadin.flow.data.renderer.LitRenderer.<T>of(cuerpo + icono)
                        .withProperty("texto", item -> { Object v = extractor.apply(item); return v == null ? "" : String.valueOf(v); })
                        .withProperty("estilo", item -> {
                            String e = porFila == null ? null : porFila.apply(item);
                            return (fijo == null ? "" : fijo) + (e == null ? "" : e.replaceAll("[\"<>$`\\\\{}]", ""));
                        });
                if (enl != null) r = r.withProperty("href", item -> { String hh = enl.apply(item); return hh == null ? "" : hh; });
                if (lupa != null)
                    r = r.withProperty("lupa", item -> lupa.hay() == null || lupa.hay().test(item))
                         .withFunction("abrir", item -> { marcar(item); lupa.abrir().accept(item); });
                col.setRenderer(r);
            }
            columnas.put(clave, col);
            return col;
        }

        /** V54: la lupa de una celda: cuándo se ve (null = siempre), qué abre y su texto de ayuda. */
        public record Lupa<T>(java.util.function.Predicate<T> hay, Consumer<T> abrir, String titulo) {}
        private final Map<String, Lupa<T>> lupas = new LinkedHashMap<>();
        /** V54: la próxima columna con esta clave lleva una lupa al lado del valor (como en OPTIMIST), que abre su detalle. */
        public void lupa(String clave, java.util.function.Predicate<T> hay, Consumer<T> abrir, String titulo) {
            if (abrir != null) lupas.put(clave, new Lupa<>(hay, abrir, titulo == null ? "Ver detalle" : titulo));
        }

        /** V52: la próxima columna con esta clave pinta cada celda con este CSS según la fila (ej. "color:#2e7d32;"), o null. */
        public void estiloPorFila(String clave, Function<T, String> css) { if (css != null) estilosPorFila.put(clave, css); }
        /** V61h: parte (CSS) extra de todo el renglón según el renglón (ej. la fórmula por defecto en negrita), o null. */
        public void parteRenglon(Function<T, String> f) { parteRenglon = f; }
        private Function<T, String> parteRenglon;
        private final Map<String, Function<T, String>> estilosPorFila = new LinkedHashMap<>();

        /** V52: vuelve a la primera página y pide la lista de nuevo (ej. cambió un parámetro de una consulta). */
        public void inicio() { pagina = 0; refrescar(); }

        /** V52: sin la columna de acciones del renglón (una consulta que no tiene ninguna). */
        public void sinAcciones() { columnaAcciones.setVisible(false); }

        /** V69: antes de mostrar cada página, cargar (con sus filas) lo que necesitan las marcas; la marca de cada fila o null. */
        public void marcas(java.util.function.Consumer<List<T>> cargar, Function<T, com.vaadin.flow.component.Component> marca) {
            alCargarPagina = cargar;
            marcaRenglon = marca;
        }

        /** V59: un tilde por renglón para elegir varios (los botones porRenglon de las vistas). Se borra al cambiar de página. */
        public void seleccionMultiple() { grilla.setSelectionMode(Grid.SelectionMode.MULTI); }

        /** V59: los renglones tildados (en el orden de la página). */
        public List<T> seleccionados() {
            List<T> r = new java.util.ArrayList<>(grilla.getSelectedItems());
            r.sort(java.util.Comparator.comparingInt(x -> indicePagina.getOrDefault(x, Integer.MAX_VALUE)));
            return r;
        }

        /** Nombre base del archivo Excel (por defecto, el plural de la TRN). También identifica las preferencias del usuario. */
        public void archivoExcel(String nombre) {
            this.archivoExcel = nombre;
            if (idPreferencias == null) {
                idPreferencias = nombre;
                // V53c: los renglones por página que eligió el usuario en ESTE WorkWith (si nunca eligió: 10)
                int n = 10;
                try { n = Integer.parseInt(preferencias.cargar(usuarioPref).getProperty(clavePref("filas"), "10").trim()); }
                catch (NumberFormatException ignorado) { }
                if (!List.of(10, 20, 50, 100).contains(n)) n = 10;
                cargandoFilas = true;
                try { filas.setValue(n); } finally { cargandoFilas = false; }
            }
        }
        private boolean cargandoFilas;

        // ------------------------------------------------------------------ V29: preferencias del usuario por TRN

        private String clavePref(String k) {
            return "ww." + (idPreferencias == null ? sustantivo : idPreferencias).replaceAll("[^A-Za-z0-9_.-]", "_") + "." + k;
        }

        private void guardarPref(String k, String valor) {
            if (idPreferencias == null) return;
            preferencias.guardarClave(usuarioPref, clavePref(k), valor);
        }

        /** "clave1,clave2,-clave3": orden de las columnas; '-' = oculta. */
        private void guardarColumnas() {
            List<String> partes = new java.util.ArrayList<>();
            for (Grid.Column<T> c : grilla.getColumns()) {
                String k = c.getKey();
                if (k == null || !columnas.containsKey(k)) continue;
                partes.add(c.isVisible() ? k : "-" + k);
            }
            guardarPref("columnas", String.join(",", partes));
        }

        // ------------------------------------------------------------------ V66s: ancho de cada columna, por usuario

        /** "clave:px,clave:px": los anchos que eligió el usuario (las demás columnas, con el de fábrica). */
        private void guardarAnchos() {
            List<String> partes = new java.util.ArrayList<>();
            anchosUsuario.forEach((k, px) -> partes.add(k + ":" + px));
            guardarPref("anchos", partes.isEmpty() ? null : String.join(",", partes));
        }

        /** El ancho de fábrica en px (para mostrarlo), o null si la columna se ajusta sola a su contenido. */
        private Integer anchoOriginalPx(String clave) {
            AnchoOriginal o = anchosOriginales.get(clave);
            if (o == null || o.ancho() == null || !o.ancho().trim().endsWith("px")) return null;
            return crecimiento(o.ancho());
        }

        /**
         * Pone en la columna el ancho del usuario (px) o, con null, el de fábrica. El ancho del usuario es el mínimo: si la
         * pantalla es más ancha, la columna crece en proporción a su ancho como las demás (V66l).
         */
        private void ponerAncho(String clave, Integer px) {
            Grid.Column<T> c = columnas.get(clave);
            AnchoOriginal o = anchosOriginales.get(clave);
            if (c == null) return;
            if (px != null) {
                c.setAutoWidth(false);
                c.setWidth(px + "px");
                c.setFlexGrow(px);
            } else if (o != null) {
                c.setAutoWidth(o.auto());
                c.setWidth(o.ancho());
                c.setFlexGrow(o.crece());
            }
        }

        /**
         * Al arrastrar un borde, la grilla (en el navegador) deja fijas esa columna y las de su izquierda: se les vuelve a poner
         * el ancho y el crecimiento que tienen en el servidor, así lo que sobra se sigue repartiendo entre todas.
         */
        private void reponerAnchosEnPantalla() {
            for (Grid.Column<T> c : grilla.getColumns()) {
                if (!c.isVisible()) continue;
                if (c.getWidth() == null) c.getElement().executeJs("this.flexGrow = $0;", c.getFlexGrow());
                else c.getElement().executeJs("this.width = $0; this.flexGrow = $1;", c.getWidth(), c.getFlexGrow());
            }
        }

        /** Una sola vez, con todas las columnas armadas: guarda el ancho de fábrica y deja arrastrar los bordes. */
        private void prepararAnchos(java.util.Properties p) {
            for (Map.Entry<String, Grid.Column<T>> e : columnas.entrySet()) {
                Grid.Column<T> c = e.getValue();
                anchosOriginales.put(e.getKey(), new AnchoOriginal(c.getWidth(), c.getFlexGrow(), c.isAutoWidth()));
                c.setResizable(true);
            }
            String anchos = p.getProperty(clavePref("anchos"));
            if (anchos != null && !anchos.isBlank())
                for (String par : anchos.split(",")) {
                    String[] kv = par.trim().split(":");
                    if (kv.length != 2 || !columnas.containsKey(kv[0])) continue;   // una columna que el YAML ya no tiene
                    try {
                        int px = Math.max(40, Math.min(1500, Integer.parseInt(kv[1].trim())));
                        anchosUsuario.put(kv[0], px);
                        ponerAncho(kv[0], px);
                    } catch (NumberFormatException ignorado) { }
                }
            // arrastrando el borde del título, como en Excel: queda guardado para el usuario
            grilla.addColumnResizeListener(ev -> {
                if (!ev.isFromClient()) return;
                String clave = null;
                for (Map.Entry<String, Grid.Column<T>> e : columnas.entrySet())
                    if (e.getValue() == ev.getResizedColumn()) { clave = e.getKey(); break; }
                if (clave == null) return;
                final String k = clave;
                // el ancho se lee de la pantalla (lo que quedó al soltar el borde)
                ev.getResizedColumn().getElement().executeJs("return String(this.width || (this.offsetWidth + 'px'));")
                        .then(String.class, ancho -> {
                            String w = ancho == null ? "" : ancho.trim().toLowerCase(Locale.ROOT);
                            if (!w.endsWith("px")) return;
                            int px;
                            try { px = (int) Math.round(Double.parseDouble(w.substring(0, w.length() - 2))); }
                            catch (NumberFormatException ex) { return; }
                            px = Math.max(40, Math.min(1500, px));
                            anchosUsuario.put(k, px);
                            Grid.Column<T> c = columnas.get(k);
                            c.setWidth("1px");   // así el servidor manda el ancho aunque crea que ya lo tenía
                            ponerAncho(k, px);
                            guardarAnchos();
                            reponerAnchosEnPantalla();
                        });
            });
        }

        /** Aplica lo guardado (una sola vez, cuando ya están todas las columnas). */
        private void aplicarPreferencias() {
            if (preferenciasAplicadas || idPreferencias == null) return;
            preferenciasAplicadas = true;
            java.util.Properties p = preferencias.cargar(usuarioPref);
            prepararAnchos(p);   // V66s
            String cols = p.getProperty(clavePref("columnas"));
            if (cols != null && !cols.isBlank()) aplicarColumnas(java.util.Arrays.asList(cols.split(",")));
            String orden = p.getProperty(clavePref("orden"));
            if (orden != null && orden.contains(":")) {
                String[] o = orden.split(":");
                Grid.Column<T> c = columnas.get(o[0]);
                if (c != null && c.isVisible()) {
                    grilla.sort(List.of(new com.vaadin.flow.component.grid.GridSortOrder<>(c,
                            "DESC".equals(o[1]) ? com.vaadin.flow.data.provider.SortDirection.DESCENDING
                                                : com.vaadin.flow.data.provider.SortDirection.ASCENDING)));
                }
            }
        }

        /** lista: claves en orden, '-' adelante = oculta. Las columnas que no figuran (nuevas en el YAML) van al final, visibles. */
        private void aplicarColumnas(List<String> lista) {
            List<Grid.Column<T>> orden = new java.util.ArrayList<>();
            orden.add(columnaAcciones);
            java.util.Set<String> vistas = new java.util.HashSet<>();
            for (String x : lista) {
                String k = x.trim();
                boolean oculta = k.startsWith("-");
                if (oculta) k = k.substring(1);
                Grid.Column<T> c = columnas.get(k);
                if (c == null || !vistas.add(k)) continue;   // una columna que el YAML ya no tiene: se ignora
                c.setVisible(!oculta);
                if (oculta && filtros.get(k) != null) filtros.get(k).clear();
                orden.add(c);
            }
            // V42: una columna que el YAML agregó después de que el usuario guardó su orden va al lado de la que la
            // precede en el YAML (antes iba al final de todo)
            Grid.Column<T> anterior = columnaAcciones;
            for (Map.Entry<String, Grid.Column<T>> e : columnas.entrySet()) {
                if (vistas.add(e.getKey())) {
                    e.getValue().setVisible(true);
                    orden.add(orden.indexOf(anterior) + 1, e.getValue());
                }
                anterior = e.getValue();
            }
            grilla.setColumnOrder(orden);
        }

        /** Diálogo: tildar/destildar columnas y ordenarlas arrastrando. */
        private void dialogoColumnas() {
            // V66s: cada renglón lleva también el ancho que eligió el usuario (null = el de fábrica)
            final class Fila { final String clave; boolean visible; Integer ancho; Fila(String c, boolean v, Integer a) { clave = c; visible = v; ancho = a; } }
            List<Fila> lista = new java.util.ArrayList<>();
            for (Grid.Column<T> c : grilla.getColumns())
                if (c.getKey() != null && columnas.containsKey(c.getKey()))
                    lista.add(new Fila(c.getKey(), c.isVisible(), anchosUsuario.get(c.getKey())));
            com.vaadin.flow.component.dialog.Dialog d = new com.vaadin.flow.component.dialog.Dialog();
            OptimistVisuals.estilizarDialogo(d);
            d.setHeaderTitle("Columnas");
            d.setWidth("min(500px, 96vw)");
            Grid<Fila> g = new Grid<>();
            g.addThemeName("optimist-grid");
            g.addComponentColumn(f -> {
                com.vaadin.flow.component.checkbox.Checkbox cb = new com.vaadin.flow.component.checkbox.Checkbox(f.visible);
                cb.addValueChangeListener(ev -> f.visible = ev.getValue());
                return cb;
            }).setWidth("60px").setFlexGrow(0);
            g.addColumn(f -> titulos.getOrDefault(f.clave, f.clave)).setHeader("Arrastrar para ordenar");
            // V66s: el ancho de la columna: el ícono abre una ventanita para cambiarlo (o volver al de fábrica)
            if (preferenciasAplicadas) g.addComponentColumn(f -> {
                Integer orig = anchoOriginalPx(f.clave);
                Span valor = new Span(f.ancho != null ? f.ancho + " px" : orig != null ? orig + " px" : "auto");
                valor.getStyle().set("font-size", "var(--lumo-font-size-s)").set("min-width", "52px").set("text-align", "right");
                if (f.ancho == null) valor.getStyle().set("opacity", "0.6");
                else valor.getStyle().set("font-weight", "600");
                valor.getElement().setAttribute("title", f.ancho != null ? "Ancho elegido por vos" : "Ancho original");
                Button b = new Button(VaadinIcon.ARROWS_LONG_H.create(), ev -> dialogoAncho(titulos.getOrDefault(f.clave, f.clave),
                        f.ancho, orig, nuevo -> { f.ancho = nuevo; g.getDataProvider().refreshItem(f); }));
                b.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
                b.getElement().setAttribute("title", "Ancho de la columna");
                HorizontalLayout h = new HorizontalLayout(valor, b);
                h.setSpacing(false);
                h.setAlignItems(FlexComponent.Alignment.CENTER);
                h.getStyle().set("gap", "6px");
                return h;
            }).setHeader("Ancho").setWidth("110px").setFlexGrow(0);
            g.setItems(lista);
            g.setHeight("420px");
            g.setRowsDraggable(true);
            g.setDropMode(com.vaadin.flow.component.grid.dnd.GridDropMode.BETWEEN);
            final Object[] arrastrada = {null};   // Object: no se puede crear un arreglo de una clase local genérica
            g.addDragStartListener(ev -> arrastrada[0] = ev.getDraggedItems().isEmpty() ? null : ev.getDraggedItems().get(0));
            g.addDragEndListener(ev -> arrastrada[0] = null);
            g.addDropListener(ev -> {
                Fila destino = ev.getDropTargetItem().orElse(null);
                if (arrastrada[0] == null || destino == null || destino == arrastrada[0]) return;
                lista.remove(arrastrada[0]);
                int i = lista.indexOf(destino);
                if (ev.getDropLocation() == com.vaadin.flow.component.grid.dnd.GridDropLocation.BELOW) i++;
                lista.add(i, (Fila) arrastrada[0]);
                g.setItems(lista);
            });
            d.add(g);
            Button aplicar = new Button("Aplicar", ev -> {
                if (lista.stream().noneMatch(f -> f.visible)) {
                    com.vaadin.flow.component.notification.Notification.show("Tiene que quedar al menos una columna", 3000,
                            com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
                    return;
                }
                aplicarColumnas(lista.stream().map(f -> f.visible ? f.clave : "-" + f.clave).toList());
                guardarColumnas();
                if (preferenciasAplicadas) {   // V66s
                    anchosUsuario.clear();
                    for (Fila f : lista) {
                        if (f.ancho != null) anchosUsuario.put(f.clave, f.ancho);
                        ponerAncho(f.clave, f.ancho);
                    }
                    guardarAnchos();
                    reponerAnchosEnPantalla();
                }
                if (ordenClave != null && !columnas.get(ordenClave).isVisible()) grilla.sort(List.of());
                refrescar();
                d.close();
            });
            aplicar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            Button restablecer = new Button("Restablecer", ev -> {
                aplicarColumnas(new java.util.ArrayList<>(columnas.keySet()));
                guardarPref("columnas", null);
                // V66s: también los anchos de fábrica
                for (String k : new java.util.ArrayList<>(anchosUsuario.keySet())) ponerAncho(k, null);
                anchosUsuario.clear();
                guardarPref("anchos", null);
                reponerAnchosEnPantalla();
                refrescar();
                d.close();
            });
            restablecer.getElement().setAttribute("title", "Volver a las columnas, el orden y los anchos originales");
            OptimistVisuals.pieIzquierda(d, new Button("Cancelar", ev -> d.close()), restablecer, aplicar);
            d.open();
        }

        /**
         * V66s: la ventanita del ancho de una columna: el ancho en px; Restablecer vuelve SOLO esta columna a su ancho original.
         * Lo elegido se aplica con el Aplicar de la ventana Columnas (con Cancelar no cambia nada).
         */
        private void dialogoAncho(String titulo, Integer actual, Integer original, Consumer<Integer> alElegir) {
            com.vaadin.flow.component.dialog.Dialog d = new com.vaadin.flow.component.dialog.Dialog();
            OptimistVisuals.estilizarDialogo(d);
            d.setHeaderTitle("Ancho — " + titulo);
            com.vaadin.flow.component.textfield.IntegerField campo = new com.vaadin.flow.component.textfield.IntegerField("Ancho (px)");
            campo.setMin(40);
            campo.setMax(1500);
            campo.setStepButtonsVisible(true);
            campo.setStep(10);
            campo.setValue(actual != null ? actual : original);
            campo.setPlaceholder("auto");
            campo.setHelperText("Original: " + (original != null ? original + " px" : "se ajusta al contenido") + ". Entre 40 y 1500 px.");
            campo.setWidth("220px");
            d.add(campo);
            Button aceptar = new Button("Aceptar", ev -> {
                Integer v = campo.getValue();
                if (v == null) { alElegir.accept(null); d.close(); return; }
                if (v < 40 || v > 1500) { campo.setInvalid(true); campo.setErrorMessage("Entre 40 y 1500 px"); return; }
                alElegir.accept(original != null && v.equals(original) ? null : v);
                d.close();
            });
            aceptar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            aceptar.addClickShortcut(com.vaadin.flow.component.Key.ENTER);
            Button restablecer = new Button("Restablecer", ev -> { alElegir.accept(null); d.close(); });
            restablecer.getElement().setAttribute("title", "Volver esta columna a su ancho original");
            OptimistVisuals.pieIzquierda(d, new Button("Cancelar", ev -> d.close()), restablecer, aceptar);
            d.open();
            campo.focus();
        }

        /** V26: exporta TODAS las filas que pasan los filtros (no solo la página visible). */
        private void exportarExcel() {
            List<T> encontrados;
            if (fuente != null) {   // V50o: todos los que pasan los filtros, ya ordenados por la base
                try { encontrados = fuente.pagina(textosFiltros(), ordenClave, ordenDesc, 0, -1); }
                catch (Exception ex) { errorFuente(ex); return; }
            } else encontrados = OptimistWorkWith.filtrar(registros, filtros, extractores, reales);
            String base = java.text.Normalizer.normalize(archivoExcel == null || archivoExcel.isBlank() ? sustantivo : archivoExcel,
                    java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9_-]", "");
            String nombre = (base.isEmpty() ? "Datos" : base) + "_"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".xlsx";
            byte[] bytes;
            // V29: el Excel sale con las columnas que el usuario ve, en su orden, y con el orden de filas elegido
            List<OptimistExcel.Columna<T>> visibles = new java.util.ArrayList<>();
            for (Grid.Column<T> c : grilla.getColumns())
                if (c.isVisible() && c.getKey() != null && excelPorClave.containsKey(c.getKey())) visibles.add(excelPorClave.get(c.getKey()));
            if (visibles.isEmpty()) visibles = columnasExcel;
            if (fuente == null) encontrados = ordenar(encontrados);
            final List<OptimistExcel.Columna<T>> colsExcel = visibles;
            try { bytes = OptimistExcel.generar(base, colsExcel, encontrados); }
            catch (java.io.IOException | RuntimeException ex) {
                com.vaadin.flow.component.notification.Notification.show("No se pudo generar el Excel: " + ex.getMessage(), 8000,
                        com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
                return;
            }
            com.vaadin.flow.server.StreamResource recurso =
                    new com.vaadin.flow.server.StreamResource(nombre, () -> new java.io.ByteArrayInputStream(bytes));
            recurso.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            // se guarda solo la última descarga, para no acumular archivos en la sesión
            if (ultimaDescarga != null) ultimaDescarga.unregister();
            ultimaDescarga = com.vaadin.flow.server.VaadinSession.getCurrent().getResourceRegistry().registerResource(recurso);
            com.vaadin.flow.component.UI.getCurrent().getPage().executeJs(
                    "const a=document.createElement('a');a.href=$0;a.download=$1;document.body.appendChild(a);a.click();a.remove();",
                    ultimaDescarga.getResourceUri().toString(), nombre);
            com.vaadin.flow.component.notification.Notification.show("Excel: " + encontrados.size() + " " + sustantivo, 3000,
                    com.vaadin.flow.component.notification.Notification.Position.BOTTOM_START);
        }
        /**
         * V66k: exporta a PDF TODAS las filas que pasan los filtros, con las columnas que ve el usuario y en su orden (como
         * el Excel). Se abre en la misma ventana, como las impresiones.
         */
        private void exportarPdf() {
            List<T> encontrados;
            if (fuente != null) {
                try { encontrados = fuente.pagina(textosFiltros(), ordenClave, ordenDesc, 0, -1); }
                catch (Exception ex) { errorFuente(ex); return; }
            } else encontrados = ordenar(OptimistWorkWith.filtrar(registros, filtros, extractores, reales));
            List<OptimistPdfLista.Columna<T>> cols = new java.util.ArrayList<>();
            for (Grid.Column<T> c : grilla.getColumns())
                if (c.isVisible() && c.getKey() != null && excelPorClave.containsKey(c.getKey())) {
                    OptimistExcel.Columna<T> ce = excelPorClave.get(c.getKey());
                    // lo que se ve en la grilla (combos con su texto, códigos...), salvo los números, que van con su formato
                    Function<T, ?> visto = extractores.get(c.getKey());
                    Function<T, ?> valor = ce.formato() != null || visto == null ? ce.valor() : visto;
                    cols.add(new OptimistPdfLista.Columna<>(ce.titulo(), valor, ce.formato()));
                }
            if (cols.isEmpty()) for (OptimistExcel.Columna<T> ce : columnasExcel) cols.add(new OptimistPdfLista.Columna<>(ce.titulo(), ce.valor(), ce.formato()));
            String titulo = tituloLista == null || tituloLista.getText() == null || tituloLista.getText().isBlank() ? sustantivo : tituloLista.getText().strip();
            OptimistContexto ctx = OptimistContexto.actual();
            String base = java.text.Normalizer.normalize(archivoExcel == null || archivoExcel.isBlank() ? sustantivo : archivoExcel,
                    java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9_-]", "");
            String nombre = (base.isEmpty() ? "Datos" : base) + "_"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".pdf";
            byte[] bytes;
            try { bytes = OptimistPdfLista.generar(titulo + (subtituloPdf == null || subtituloPdf.isBlank() ? "" : " - " + subtituloPdf),
                    ctx == null ? null : ctx.caption(), cols, encontrados); }
            catch (RuntimeException ex) {
                com.vaadin.flow.component.notification.Notification.show("No se pudo generar el PDF: " + ex.getMessage(), 8000,
                        com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
                return;
            }
            OptimistVisorPdf.abrir(titulo, nombre, bytes);
        }

        /** V66k: un texto que va al lado del título en el PDF (ej. la consulta de imputaciones: "Asiento 25 del 30/09/2026"). */
        private String subtituloPdf;
        public void subtituloPdf(String s) { subtituloPdf = s; }

        /** V32: cómo reconocer un registro después de recargar la lista (la clave), para que siga marcado. */
        public void identificador(Function<T, Object> f) { identificador = f == null ? x -> x : f; }

        private void marcar(T item) {
            protegerDesplazamiento();
            trabajado = item == null ? null : identificador.apply(item);
            grilla.getDataProvider().refreshAll();
        }

        /** V49q: lo que va a redibujar la grilla avisa antes: el navegador la deja donde estaba (horizontal y vertical). */
        private void protegerDesplazamiento() {
            grilla.getElement().executeJs("if (this.__optimistFijar) this.__optimistFijar();");
        }

        /**
         * V49q: JS de la grilla. Recuerda dónde la dejó el usuario (scrollLeft / scrollTop de su tabla interna) y, durante
         * un rato después de cada redibujado que avisa el servidor (o de mover una columna), si el navegador la corre sola
         * la vuelve a ese lugar. El usuario la sigue moviendo cuando quiere (rueda, barra, teclado).
         */
        public static final String FIJAR_DESPLAZAMIENTO =
              "const g = this; if (g.__optimistFijarListo) return; g.__optimistFijarListo = true;"
            + "const tabla = () => (g.$ && g.$.table) || (g.shadowRoot && g.shadowRoot.querySelector('#table')) || (g.localName === 'vaadin-grid' ? null : g);"
            // s.l / s.t: donde lo dejó el USUARIO (rueda, barra, flechas); un corrimiento que no hizo él se deshace
            + "const s = {l: 0, t: 0, usuario: 0, abajo: false};"
            + "const esUsuario = () => s.abajo || Date.now() - s.usuario < 250;"
            + "const enganchar = () => { const tb = tabla(); if (!tb) { setTimeout(enganchar, 100); return; }"
            + "  tb.addEventListener('wheel', () => { s.usuario = Date.now(); }, {passive: true});"
            + "  tb.addEventListener('touchmove', () => { s.usuario = Date.now(); }, {passive: true});"
            + "  tb.addEventListener('pointerdown', () => { s.abajo = true; }, {passive: true});"
            + "  window.addEventListener('pointerup', () => { if (s.abajo) { s.abajo = false; s.usuario = Date.now(); } }, {passive: true});"
            + "  g.addEventListener('keydown', ev => { if (/^(Arrow|Page|Home|End|Tab)/.test(ev.key)) s.usuario = Date.now(); }, true);"
            + "  tb.addEventListener('scroll', () => {"
            + "    if (esUsuario()) { s.l = tb.scrollLeft; s.t = tb.scrollTop; return; }"
            + "    if (tb.scrollLeft !== s.l) tb.scrollLeft = s.l;"
            + "    if (tb.scrollTop !== s.t) tb.scrollTop = s.t;"
            + "  }, {passive: true});"
            + "};"
            + "enganchar();"
            // después de un redibujado (filtrar, ordenar, marcar una fila, mover una columna): vuelve a donde estaba
            + "g.__optimistFijar = () => { const tb = tabla(); if (!tb) return;"
            + "  const volver = () => { if (esUsuario()) return; if (tb.scrollLeft !== s.l) tb.scrollLeft = s.l; if (tb.scrollTop !== s.t) tb.scrollTop = s.t; };"
            + "  requestAnimationFrame(volver); [30, 100, 250, 500, 900].forEach(ms => setTimeout(volver, ms)); };"
            + "g.addEventListener('column-reorder', () => g.__optimistFijar());";

        public void cargar(List<T> nuevos) {
            if (fuente != null) {   // V50o: la lista no se guarda: se vuelve a pedir la página donde estaba
                if (!preferenciasAplicadas) aplicarPreferencias();
                refrescar();
                return;
            }
            registros.clear(); registros.addAll(nuevos);
            if (!preferenciasAplicadas) aplicarPreferencias();   // V29: columnas y orden guardados por el usuario
            if (trabajado != null) {
                // V32: si al grabar el registro trabajado cambió de página (por el orden), se va a su página
                List<T> encontrados = ordenar(OptimistWorkWith.filtrar(registros, filtros, extractores, reales));
                int n = filas.getValue() == null ? 10 : filas.getValue();
                for (int i = 0; i < encontrados.size(); i++)
                    if (trabajado.equals(identificador.apply(encontrados.get(i)))) { pagina = i / n; break; }
            }
            refrescar();
        }

        /** V29: orden elegido por el usuario sobre TODAS las filas (estable: a igualdad, queda el orden original). */
        private List<T> ordenar(List<T> filas) {
            if (ordenClave == null || !comparadores.containsKey(ordenClave)) return filas;
            java.util.Comparator<T> c = comparadores.get(ordenClave);
            List<T> r = new java.util.ArrayList<>(filas);
            r.sort(ordenDesc ? c.reversed() : c);
            return r;
        }

        public void refrescar() {
            if (fuente != null) { refrescarDesdeFuente(); return; }
            List<T> encontrados = ordenar(OptimistWorkWith.filtrar(registros,filtros,extractores,reales));
            int n=filas.getValue()==null?10:filas.getValue();
            paginas=Math.max(1,(encontrados.size()+n-1)/n);
            pagina=Math.min(pagina,paginas-1);
            int desde=pagina*n;
            List<T> visibles = encontrados.subList(desde,Math.min(desde+n,encontrados.size()));
            if (conTotales()) mostrarTotales(encontrados.size(), sumarEnMemoria(encontrados), sumarPorMonedaEnMemoria(encontrados));   // V75 / V77
            else sacarTotales();   // V84i
            indicePagina.clear();
            for (int i = 0; i < visibles.size(); i++) indicePagina.put(visibles.get(i), i);
            prepararAcciones(visibles);   // V56e
            if (alCargarPagina != null) alCargarPagina.accept(visibles);   // V69
            protegerDesplazamiento();
            grilla.setItems(visibles);
            estado.setText("Página "+(pagina+1)+" de "+paginas+" · "+encontrados.size()+" "+sustantivo);
            anterior.setEnabled(pagina>0);siguiente.setEnabled(pagina<paginas-1);
            primera.setEnabled(pagina>0);ultima.setEnabled(pagina<paginas-1);
        }

        /** V50o: la página visible, pedida a la base: COUNT(*) con los filtros y la página con LIMIT. */
        private void refrescarDesdeFuente() {
            int n = filas.getValue() == null ? 10 : filas.getValue();
            Map<String, String> fs = textosFiltros();
            int total;
            List<T> visibles;
            long t0 = System.nanoTime(), t1 = t0, t2 = t0;   // V84i: cuánto tarda cada parte (si pasa de medio segundo, a la consola)
            boolean conteoNuevo = !(soloCambioPagina && conteoGuardado != null && fs.equals(filtrosConteo));
            try {
                // V75: la cantidad y los totales en una sola consulta; al pasar de página con los mismos filtros, no se repite
                OptimistTrnDatos.Conteo cn = soloCambioPagina && conteoGuardado != null && fs.equals(filtrosConteo) ? conteoGuardado
                        : !conTotales() ? OptimistTrnDatos.Conteo.de(fuente.contar(fs)) : fuente.contarYSumar(fs, totalizar, monedaClave);   // V84i
                conteoGuardado = cn; filtrosConteo = fs;
                total = cn.cantidad();
                if (conTotales()) mostrarTotales(total, cn.sumas(), cn.porMoneda()); else sacarTotales();   // V84i
                t1 = System.nanoTime();
                paginas = Math.max(1, (total + n - 1) / n);
                pagina = Math.min(pagina, paginas - 1);
                visibles = fuente.pagina(fs, ordenClave, ordenDesc, pagina * n, n);
                t2 = System.nanoTime();
            } catch (Exception ex) {
                errorFuente(ex);
                total = 0; paginas = 1; pagina = 0; visibles = List.of();
                conteoGuardado = null;
            }
            soloCambioPagina = false;
            indicePagina.clear();
            for (int i = 0; i < visibles.size(); i++) indicePagina.put(visibles.get(i), i);
            if (t2 == t0) t1 = t2 = System.nanoTime();
            prepararAcciones(visibles);   // V56e
            long t3 = System.nanoTime();
            if (alCargarPagina != null) alCargarPagina.accept(visibles);   // V69
            long t4 = System.nanoTime();
            if ((t4 - t0) >= 500_000_000L)
                System.out.println("[OPTIMIST] " + sustantivo + ": lista en " + (t4 - t0) / 1_000_000 + " ms (cantidad y totales "
                        + (conteoNuevo ? (t1 - t0) / 1_000_000 + " ms" : "guardados") + ", página " + (t2 - t1) / 1_000_000
                        + " ms, acciones " + (t3 - t2) / 1_000_000 + " ms, PostIts " + (t4 - t3) / 1_000_000 + " ms)");
            protegerDesplazamiento();
            grilla.setItems(visibles);
            estado.setText("Página " + (pagina + 1) + " de " + paginas + " · " + total + " " + sustantivo);
            anterior.setEnabled(pagina > 0); siguiente.setEnabled(pagina < paginas - 1);
            primera.setEnabled(pagina > 0); ultima.setEnabled(pagina < paginas - 1);
        }
    }
}
