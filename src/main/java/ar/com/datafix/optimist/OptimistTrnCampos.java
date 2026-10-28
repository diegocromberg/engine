package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Campo;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Dominio;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Valor;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** V21 - Fábrica de controles según el dominio del campo. Un solo lugar para todas las TRN. */
public final class OptimistTrnCampos {
    private OptimistTrnCampos() {}

    private static final Locale AR = Locale.of("es", "AR");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DMYHM = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * Control + cómo leerlo/escribirlo, independiente del tipo real de componente.
     * alCambiar: registra una acción que se ejecuta cuando el usuario cambia el valor.
     */
    public record Editor(Campo campo, Component componente, Supplier<Object> leer, Consumer<Object> escribir,
                         Consumer<Boolean> soloLectura, Consumer<Runnable> alCambiar) {
        /** V48: un campo de solo lectura no se detiene con TAB (como GeneXus), en el formulario y en las grillas. */
        public Editor {
            Consumer<Boolean> base = soloLectura;
            Component comp = componente;
            soloLectura = ro -> { base.accept(ro); saltarConTab(comp, Boolean.TRUE.equals(ro)); };
        }
        public boolean vacio() { return OptimistTrnDatos.vacio(leer.get()) || Boolean.FALSE.equals(leer.get()); }
    }

    /** V48: tabindex -1 (TAB lo saltea) o el normal, en el control y en los que tenga adentro. */
    static void saltarConTab(Component c, boolean saltar) {
        if (c == null) return;
        if (c instanceof com.vaadin.flow.component.Focusable<?> fo) fo.setTabIndex(saltar ? -1 : 0);
        c.getChildren().forEach(h -> saltarConTab(h, saltar));
    }

    /** enLinea=true: control de una fila del detalle (sin etiqueta, la etiqueta va en el encabezado). */
    public static Editor crear(Campo c, boolean enLinea) { return crear(c, enLinea, enLinea); }

    /**
     * enLinea: sin etiqueta propia (la pone el encabezado de la grilla o la tabla con etiquetas a la izquierda).
     * enGrilla: control de una fila de detalle (siempre de una línea).
     */
    public static Editor crear(Campo c, boolean enLinea, boolean enGrilla) {
        Dominio d = c.dominio();
        String etiqueta = enLinea ? null : c.etiqueta();
        String ancho = enGrilla ? anchoGrilla(c) : ancho(c);   // V34: en las grillas de detalle, más ajustado
        if (d.esCombo()) {
            ComboBox<Valor> cb = new ComboBox<>(etiqueta);
            cb.setItems(d.valores());
            cb.setItemLabelGenerator(Valor::texto);
            cb.setWidth(ancho);
            cb.setRequiredIndicatorVisible(c.obligatorio());
            if (enLinea) cb.setAriaLabel(c.etiqueta());
            return new Editor(c, cb, () -> cb.getValue() == null ? null : cb.getValue().valor(),
                    v -> cb.setValue(v == null ? null : d.valores().stream().filter(x -> OptimistTrnDatos.igual(x.valor(), v)).findFirst().orElse(null)),
                    cb::setReadOnly, r -> cb.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }));
        }
        switch (d.tipo()) {
            case CHAR, VARCHAR -> {
                // V59d: dominio "Password" -> campo de contraseña (no se ve lo que se escribe; el ojo lo muestra)
                if ("password".equalsIgnoreCase(d.nombre())) {
                    PasswordField pw = new PasswordField(etiqueta);
                    pw.setMaxLength(d.longitud());
                    pw.setWidth(ancho);
                    pw.setRequiredIndicatorVisible(c.obligatorio());
                    if (enLinea) pw.setAriaLabel(c.etiqueta());
                    return new Editor(c, pw, pw::getValue, v -> pw.setValue(v == null ? "" : String.valueOf(v)), pw::setReadOnly,
                            r -> pw.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }));
                }
                // V24: texto largo (o 'renglones: N') -> área multirenglón. En las grillas de detalle, siempre una línea.
                int renglones = c.renglones();
                if (renglones > 1 && !enGrilla) {
                    TextArea a = new TextArea(etiqueta);
                    a.setMaxLength(d.longitud());
                    a.setWidth(ancho);
                    a.setHeight("calc(" + renglones + " * 1.5em + 1.3em)");
                    a.setRequiredIndicatorVisible(c.obligatorio());
                    if (enLinea) a.setAriaLabel(c.etiqueta());
                    return new Editor(c, a, a::getValue, v -> a.setValue(v == null ? "" : String.valueOf(v)), a::setReadOnly,
                            r -> a.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }));
                }
                TextField t = new TextField(etiqueta);
                t.setMaxLength(d.longitud());
                t.setWidth(ancho);
                t.setRequiredIndicatorVisible(c.obligatorio());
                if (enLinea) t.setAriaLabel(c.etiqueta());
                return new Editor(c, t, t::getValue, v -> t.setValue(v == null ? "" : String.valueOf(v)), t::setReadOnly,
                        r -> t.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }));
            }
            case NUMERIC -> {
                if (d.miles()) return conMiles(c, etiqueta, ancho, enLinea);   // V33: 1.234.567,89
                BigDecimalField n = new BigDecimalField(etiqueta);
                n.setWidth(ancho);
                n.addThemeVariants(TextFieldVariant.LUMO_ALIGN_RIGHT);
                n.setPlaceholder("0");   // V48: vacío se ve 0, así se sabe que es un número (como GeneXus)
                n.setRequiredIndicatorVisible(c.obligatorio());
                if (enLinea) n.setAriaLabel(c.etiqueta());
                Supplier<Object> leerN = () -> {
                    java.math.BigDecimal b = n.getValue();
                    return b == null ? null : d.esEntero() ? (Object) b.setScale(0, RoundingMode.DOWN).longValue() : b;
                };
                Consumer<Object> escribirN = v -> n.setValue(v == null ? null : OptimistTrnDatos.decimal(v));
                List<Runnable> cambiosN = new java.util.concurrent.CopyOnWriteArrayList<>();   // V71: para la calculadora
                OptimistCalculadora.enganchar(c, n, leerN, escribirN, cambiosN);
                return new Editor(c, n, leerN, escribirN, n::setReadOnly,
                        r -> { cambiosN.add(r); n.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }); });
            }
            case DATE -> {
                DatePicker f = new DatePicker(etiqueta);
                f.setLocale(AR);
                f.setI18n(i18nFecha());
                fechaRapida(f);   // V50: 0410 -> 04/10/año actual
                f.setWidth(ancho);
                f.setRequiredIndicatorVisible(c.obligatorio());
                if (enLinea) f.setAriaLabel(c.etiqueta());
                return new Editor(c, f, f::getValue, v -> f.setValue(OptimistTrnDatos.fechaVacia(v) ? null : v instanceof LocalDateTime t ? t.toLocalDate() : (LocalDate) v), f::setReadOnly,
                        r -> f.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }));
            }
            case DATETIME -> {
                DateTimePicker f = new DateTimePicker(etiqueta);
                f.setLocale(AR);
                f.setWidth(ancho);
                f.setRequiredIndicatorVisible(c.obligatorio());
                return new Editor(c, f, f::getValue, v -> f.setValue(OptimistTrnDatos.fechaVacia(v) ? null : v instanceof LocalDate d2 ? d2.atStartOfDay() : (LocalDateTime) v), f::setReadOnly,
                        r -> f.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }));
            }
            case BOOLEAN -> {
                Checkbox b = new Checkbox(enLinea ? "" : c.etiqueta());
                if (enLinea) b.setAriaLabel(c.etiqueta());
                b.setWidth(ancho);
                return new Editor(c, b, b::getValue, v -> b.setValue(Boolean.TRUE.equals(v)), b::setReadOnly,
                        r -> b.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }));
            }
        }
        throw new IllegalStateException("Tipo no soportado: " + d.tipo());
    }

    /**
     * V33: número con punto de miles (dominio 'miles'). Se escribe como se quiera ("1234,5", "1.234,50", o con punto
     * decimal del teclado numérico) y al salir del campo queda formateado: 1.234,50.
     */
    private static Editor conMiles(Campo c, String etiqueta, String ancho, boolean enLinea) {
        Dominio d = c.dominio();
        TextField t = new TextField(etiqueta);
        t.setWidth(ancho);
        t.addThemeVariants(TextFieldVariant.LUMO_ALIGN_RIGHT);
        t.setAllowedCharPattern("[0-9.,-]");
        t.setPlaceholder("0");   // V48
        t.setRequiredIndicatorVisible(c.obligatorio());
        if (enLinea) t.setAriaLabel(c.etiqueta());
        DecimalFormat fmt = formatoMiles(d);
        java.util.function.Supplier<Object> leer = () -> {
            java.math.BigDecimal b = leerNumero(t.getValue());
            if (b == null) return null;
            return d.esEntero() ? (Object) b.setScale(0, RoundingMode.DOWN).longValue() : b.setScale(d.decimales(), RoundingMode.HALF_UP);
        };
        Consumer<Object> escribir = v -> t.setValue(v == null ? "" : fmt.format(OptimistTrnDatos.decimal(v)));
        t.addBlurListener(e -> { if (!t.isReadOnly()) { Object v = leer.get(); if (v != null) escribir.accept(v); } });
        List<Runnable> cambios = new java.util.concurrent.CopyOnWriteArrayList<>();   // V71: para la calculadora
        OptimistCalculadora.enganchar(c, t, leer, escribir, cambios);
        return new Editor(c, t, leer, escribir, t::setReadOnly,
                r -> { cambios.add(r); t.addValueChangeListener(e -> { if (e.isFromClient()) r.run(); }); });
    }

    static DecimalFormat formatoMiles(Dominio d) {
        return new DecimalFormat(d.decimales() > 0 ? "#,##0." + "0".repeat(d.decimales()) : "#,##0", DecimalFormatSymbols.getInstance(AR));
    }

    /** "1.234,56" / "1234,56" / "1234.56" (punto del teclado numérico) -> 1234.56. Inválido o vacío -> null. */
    static java.math.BigDecimal leerNumero(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (s.contains(",")) s = s.replace(".", "").replace(',', '.');
        else if (s.indexOf('.') >= 0 && s.indexOf('.') == s.lastIndexOf('.') && s.length() - s.indexOf('.') - 1 != 3) { /* punto decimal */ }
        else s = s.replace(".", "");
        try { return new java.math.BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    /** Ancho según el dominio: el encabezado del detalle usa el mismo, así las columnas quedan alineadas. */
    /** Ancho del campo: el del YAML ('ancho') o el calculado según el dominio. */
    public static String ancho(Campo c) { return c.ancho() != null ? c.ancho() : ancho(c.dominio()); }

    /**
     * V34: ancho en una grilla de detalle: el del YAML ('ancho') o uno más ajustado que en el formulario
     * (la grilla es ancha y cada píxel cuenta: así entran más columnas sin desplazarse).
     */
    /**
     * V40: color de la insignia del campo para esta fila (null si el campo no tiene insignia).
     * Con colorDe, sale del texto del otro campo ("Azul", "Marrón"...); si no, del valor. Sin color: gris.
     */
    public static String colorInsignia(Campo c, OptimistTrnDefinicion.Nivel nivel, Map<String, Object> fila) {
        OptimistTrnDefinicion.Insignia ins = c.insignia();
        if (ins == null || fila == null) return null;
        if (ins.colorDe() != null) {
            Campo cc = nivel.campo(ins.colorDe());
            Object v = cc == null ? null : valorDe(fila, cc.nombre());
            String col = OptimistTrnDefinicion.Insignia.color(cc != null && cc.dominio().esCombo() ? mostrar(cc, v) : v);
            return col == null ? "gris" : col;
        }
        String col = ins.colorDeValor(c.dominio(), valorDe(fila, c.nombre()));
        return col == null ? "gris" : col;
    }

    private static Object valorDe(Map<String, Object> fila, String nombre) {
        if (fila.containsKey(nombre)) return fila.get(nombre);
        for (var e : fila.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(nombre)) return e.getValue();
        return null;
    }

    /** V40: el campo del formulario se ve como pastilla de ese color (CSS: [data-insignia]); null la saca. */
    public static void pintarInsignia(Editor ed, String color) {
        if (color == null) ed.componente().getElement().removeAttribute("data-insignia");
        else ed.componente().getElement().setAttribute("data-insignia", color);
    }

    /** V48: con lupa, la columna lleva además el lugar del botón (si no, el campo se achica: Tipo Artículo quedaba ilegible). */
    public static String anchoGrilla(Campo c, boolean conLupa) {
        String a = anchoGrilla(c);
        if (!conLupa || !a.endsWith("px")) return a;
        try { return (Integer.parseInt(a.substring(0, a.length() - 2)) + 36) + "px"; } catch (NumberFormatException e) { return a; }
    }

    public static String anchoGrilla(Campo c) {
        if (c.ancho() != null) return c.ancho();
        Dominio d = c.dominio();
        if (d.esCombo()) return Math.min(220, Math.max(100, d.valores().stream().mapToInt(v -> v.texto().length()).max().orElse(10) * 7 + 50)) + "px";
        return switch (d.tipo()) {
            // V82h: Character (códigos de largo fijo, casi siempre dígitos: A0000500002909) a 9 px por letra; con 7 se cortaba la última
            case CHAR -> Math.min(260, Math.max(70, d.longitud() * 9 + 30)) + "px";
            case VARCHAR -> Math.min(260, Math.max(70, d.longitud() * 7 + 30)) + "px";
            case NUMERIC -> Math.min(150, Math.max(56, (d.longitud() + (d.miles() ? d.longitud() / 3 : 0)) * 8 + 26)) + "px";
            case DATE -> "130px";
            case DATETIME -> "220px";
            case BOOLEAN -> "60px";
        };
    }

    public static String ancho(Dominio d) {
        if (d.esCombo()) return Math.min(300, Math.max(140, d.valores().stream().mapToInt(v -> v.texto().length()).max().orElse(10) * 8 + 60)) + "px";
        return switch (d.tipo()) {
            case CHAR, VARCHAR -> Math.min(360, Math.max(90, d.longitud() * 8 + 40)) + "px";
            case NUMERIC -> Math.min(200, Math.max(110, d.longitud() * 10 + 30)) + "px";
            case DATE -> "150px";
            case DATETIME -> "260px";
            case BOOLEAN -> "90px";
        };
    }

    /** Texto para grillas y visualización: fechas dd/MM/yyyy, números con formato argentino. */
    public static String mostrar(Campo c, Object v) {
        if (v == null || OptimistTrnDatos.fechaVacia(v)) return "";
        if (c.dominio().esCombo())
            return c.dominio().valores().stream().filter(x -> OptimistTrnDatos.igual(x.valor(), v)).map(Valor::texto).findFirst().orElse(String.valueOf(v));
        if (v instanceof LocalDate f) return f.format(DMY);
        if (v instanceof LocalDateTime f) return f.format(DMYHM);
        if (v instanceof Boolean b) return b ? "Sí" : "No";
        Dominio d = c.dominio();
        if (d.esNumerico() && d.miles()) return formatoMiles(d).format(OptimistTrnDatos.decimal(v));   // V33
        if (d.esNumerico() && !d.esEntero()) {
            DecimalFormat fmt = new DecimalFormat("#,##0." + "0".repeat(d.decimales()), DecimalFormatSymbols.getInstance(AR));
            return fmt.format(OptimistTrnDatos.decimal(v).setScale(d.decimales(), RoundingMode.HALF_UP));
        }
        return String.valueOf(v);
    }

    /** Como mostrar(), pero los enteros con separador de miles (Pedido 45.345). */
    public static String mostrarEncabezado(Campo c, Object v) {
        if (v != null && c.dominio().esEntero() && !c.dominio().esCombo())
            return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(AR)).format(OptimistTrnDatos.decimal(v));
        return mostrar(c, v);
    }

    /**
     * V50: fecha como en GeneXus: 0410 -> 04/10/(año actual), 041026 -> 04/10/2026, 04102026, 4/10, 4/10/26. Se completa al salir
     * del campo o con Enter: el control entiende lo corto y lo muestra completo. Vale para las TRN, los web panels y los filtros.
     */
    public static void fechaRapida(DatePicker f) {
        // se envuelve el parseDate del control (el que arma Flow con el formato dd/MM/yyyy): lo corto se completa antes de leerlo
        f.getElement().executeJs(
                "const p = this; if (p.__optimistFecha) return; p.__optimistFecha = true;"
                + "const corto = t => { const v = (t || '').trim(); const y = new Date().getFullYear(); const d2 = s => String(s).padStart(2, '0'); let m;"
                + "  if (/^\\d{4}$/.test(v)) return v.slice(0, 2) + '/' + v.slice(2, 4) + '/' + y;"
                + "  if (/^\\d{6}$/.test(v)) return v.slice(0, 2) + '/' + v.slice(2, 4) + '/20' + v.slice(4);"
                + "  if (/^\\d{8}$/.test(v)) return v.slice(0, 2) + '/' + v.slice(2, 4) + '/' + v.slice(4);"
                + "  if ((m = v.match(/^(\\d{1,2})[\\/.-](\\d{1,2})$/))) return d2(m[1]) + '/' + d2(m[2]) + '/' + y;"
                + "  if ((m = v.match(/^(\\d{1,2})[\\/.-](\\d{1,2})[\\/.-](\\d{2})$/))) return d2(m[1]) + '/' + d2(m[2]) + '/20' + m[3];"
                + "  if ((m = v.match(/^(\\d{1,2})[\\/.-](\\d{1,2})[\\/.-](\\d{4})$/))) return d2(m[1]) + '/' + d2(m[2]) + '/' + m[3];"
                + "  return t; };"
                + "const envolver = o => { if (o && typeof o.parseDate === 'function' && !o.parseDate.__optimist) { const orig = o.parseDate; const f = t => orig.call(o, corto(t)); f.__optimist = true; o.parseDate = f; } return o; };"
                + "let pr = Object.getPrototypeOf(p), d; while (pr && !(d = Object.getOwnPropertyDescriptor(pr, 'i18n'))) pr = Object.getPrototypeOf(pr);"
                + "if (d && d.get && d.set) Object.defineProperty(p, 'i18n', { configurable: true, get() { return envolver(d.get.call(p)); }, set(v) { d.set.call(p, envolver(v)); } });"
                + "envolver(p.i18n);");
    }

    public static DatePicker.DatePickerI18n i18nFecha() {
        DatePicker.DatePickerI18n i = new DatePicker.DatePickerI18n();
        i.setDateFormat("dd/MM/yyyy");
        i.setMonthNames(List.of("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio",
                "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"));
        i.setWeekdays(List.of("Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado"));
        i.setWeekdaysShort(List.of("Do", "Lu", "Ma", "Mi", "Ju", "Vi", "Sá"));
        i.setFirstDayOfWeek(1);
        i.setToday("Hoy");
        i.setCancel("Cancelar");
        return i;
    }
}
