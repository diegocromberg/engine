package ar.com.datafix.optimist;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.function.Function;

/**
 * V84b: el dibujo (SVG) del gráfico de columnas de OptimistGrafico, sin Vaadin (se prueba en el banco). Un grupo de barras
 * por renglón, una barra por serie; eje izquierdo para unas series y derecho para las de 'derecha'; los valores en formato
 * argentino; el texto con currentColor (el color de la Apariencia).
 */
final class OptimistGraficoSvg {

    /** Colores de las series: la primera, el color de la Apariencia; después, colores que se distinguen en claro y oscuro. */
    static final String[] COLORES = {"var(--lumo-primary-color, #1676f3)", "#f59e0b", "#10b981", "#ef4444", "#8b5cf6", "#06b6d4", "#ec4899", "#84cc16"};

    private final OptimistPanel.Grafico def;
    private final Map<String, String> etiquetas;
    private final Map<String, Integer> decimales;
    private final Function<Object, String> textoCategoria;

    OptimistGraficoSvg(OptimistPanel.Grafico def, Map<String, String> etiquetas, Map<String, Integer> decimales,
                       Function<Object, String> textoCategoria) {
        this.def = def;
        this.etiquetas = etiquetas;
        this.decimales = decimales;
        this.textoCategoria = textoCategoria;
    }

    // ------------------------------------------------------------------ el dibujo (SVG)

    private static final int ANCHO = 760, ALTO = 430, IZQ = 78, DER_CON = 70, DER_SIN = 16, ARRIBA = 16, ABAJO = 112;

    String svg(List<Map<String, Object>> rs, List<String> series) {
        StringBuilder b = new StringBuilder();
        b.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ").append(ANCHO).append(' ').append(ALTO)
         .append("' style='width:100%;height:auto;display:block;font-family:var(--lumo-font-family);font-size:11px;color:var(--optimist-texto, var(--lumo-body-text-color))'>");
        String texto = "currentColor";   // el color del texto de la Apariencia (el 'color' del svg)
        if (rs.isEmpty() || series.isEmpty()) {
            b.append("<text x='").append(ANCHO / 2).append("' y='").append(ALTO / 2).append("' text-anchor='middle' fill='").append(texto)
             .append("' style='font-size:14px;opacity:.7'>").append(rs.isEmpty() ? "Sin datos para graficar" : "Elegí al menos una serie")
             .append("</text></svg>");
            return b.toString();
        }
        List<String> izq = new ArrayList<>(), der = new ArrayList<>();
        for (String s : series) (def.derecha().contains(s) ? der : izq).add(s);
        if (izq.isEmpty()) { izq = der; der = List.of(); }   // todas a la derecha: van con un solo eje, el de la izquierda
        int derecha = der.isEmpty() ? DER_SIN : DER_CON;
        int x0 = IZQ, x1 = ANCHO - derecha, y0 = ARRIBA, y1 = ALTO - ABAJO;
        // los dos ejes con el cero a la misma altura: la misma cantidad de divisiones bajo cero en los dos
        int neg = Math.max(negativas(rs, izq), der.isEmpty() ? 0 : negativas(rs, der));
        double[] escI = escala(rs, izq, neg), escD = der.isEmpty() ? null : escala(rs, der, neg);

        // grilla y ejes
        for (int i = 0; i <= 5; i++) {
            double v = escI[0] + (escI[1] - escI[0]) * i / 5;
            double y = y1 - (y1 - y0) * (double) i / 5;
            b.append("<line x1='").append(x0).append("' x2='").append(x1).append("' y1='").append(f(y)).append("' y2='").append(f(y))
             .append("' stroke='").append(texto).append("' stroke-opacity='.15'/>");
            b.append("<text x='").append(x0 - 6).append("' y='").append(f(y + 4)).append("' text-anchor='end' fill='").append(texto)
             .append("' fill-opacity='.8'>").append(esc(corto(v, escI))).append("</text>");
            if (escD != null) {
                double vd = escD[0] + (escD[1] - escD[0]) * i / 5;
                b.append("<text x='").append(x1 + 6).append("' y='").append(f(y + 4)).append("' fill='").append(texto)
                 .append("' fill-opacity='.8'>").append(esc(corto(vd, escD))).append("</text>");
            }
        }
        // los nombres de los ejes (las series de cada uno)
        b.append("<text transform='translate(12,").append((y0 + y1) / 2).append(") rotate(-90)' text-anchor='middle' fill='").append(texto)
         .append("' fill-opacity='.7'>").append(esc(nombres(izq))).append("</text>");
        if (escD != null)
            b.append("<text transform='translate(").append(ANCHO - 8).append(',').append((y0 + y1) / 2).append(") rotate(90)' text-anchor='middle' fill='")
             .append(texto).append("' fill-opacity='.7'>").append(esc(nombres(der))).append("</text>");

        // las barras
        int nCat = rs.size(), nSer = izq.size() + der.size();
        double ancho = (double) (x1 - x0) / nCat;
        double grupo = ancho * 0.78, barra = Math.max(1.5, grupo / nSer);
        double cero = yDe(0, escI, y0, y1), ceroD = escD == null ? 0 : yDe(0, escD, y0, y1);
        b.append("<line x1='").append(x0).append("' x2='").append(x1).append("' y1='").append(f(cero)).append("' y2='").append(f(cero))
         .append("' stroke='").append(texto).append("' stroke-opacity='.5'/>");
        for (int i = 0; i < nCat; i++) {
            Map<String, Object> r = rs.get(i);
            String cat = textoCategoria.apply(r.get(def.categoria()));
            double gx = x0 + ancho * i + (ancho - grupo) / 2;
            int k = 0;
            for (String s : series) {
                boolean enDer = escD != null && der.contains(s);
                double[] e = enDer ? escD : escI;
                double base = enDer ? ceroD : cero;
                double v = valor(r.get(s));
                double y = yDe(v, e, y0, y1);
                double bx = gx + barra * k;
                String color = COLORES[def.series().indexOf(s) % COLORES.length];
                b.append("<rect x='").append(f(bx)).append("' y='").append(f(Math.min(y, base))).append("' width='").append(f(Math.max(1, barra - 1)))
                 .append("' height='").append(f(Math.max(0.5, Math.abs(base - y)))).append("' rx='1.5' style='fill:").append(color).append("'>")
                 .append("<title>").append(esc(cat + " — " + etiquetas.getOrDefault(s, s) + ": " + largo(s, v))).append("</title></rect>");
                k++;
            }
            // el nombre, inclinado y cortado (el completo, al pasar el mouse)
            double cx = x0 + ancho * i + ancho / 2;
            String c = cat.length() > 22 ? cat.substring(0, 21).strip() + "…" : cat;
            b.append("<text transform='translate(").append(f(cx + 3)).append(',').append(y1 + 10).append(") rotate(-40)' text-anchor='end' fill='")
             .append(texto).append("'><title>").append(esc(cat)).append("</title>").append(esc(c)).append("</text>");
        }
        b.append("</svg>");
        return b.toString();
    }

    /** El nombre del eje: las series que van con él ("Total / Costo"). */
    private String nombres(List<String> s) {
        List<String> r = new ArrayList<>();
        for (String x : s) r.add(etiquetas.getOrDefault(x, x));
        return String.join(" / ", r);
    }

    /** Mínimo y máximo de las series en estos renglones (con el cero adentro: las notas de crédito pueden dar negativos). */
    private double[] rango(List<Map<String, Object>> rs, List<String> series) {
        double min = 0, max = 0;
        for (Map<String, Object> r : rs) for (String s : series) { double v = valor(r.get(s)); min = Math.min(min, v); max = Math.max(max, v); }
        return new double[]{min, max};
    }

    /** Cuántas de las 5 divisiones del eje van bajo cero (0 si no hay negativos). */
    private int negativas(List<Map<String, Object>> rs, List<String> series) {
        double[] r = rango(rs, series);
        if (r[0] >= 0) return 0;
        if (r[1] <= 0) return 5;
        int k = (int) Math.round(5 * (-r[0]) / (r[1] - r[0]));
        return Math.max(1, Math.min(4, k));
    }

    /** El eje: 5 divisiones "redondas", 'neg' de ellas bajo cero. Devuelve {mínimo, máximo}. */
    private double[] escala(List<Map<String, Object>> rs, List<String> series, int neg) {
        double[] r = rango(rs, series);
        double min = r[0], max = r[1];
        if (max == 0 && min == 0) max = 1;
        double paso = Math.max(neg < 5 ? max / (5 - neg) : 0, neg > 0 ? -min / neg : 0);
        if (paso <= 0) paso = Math.max(max, -min) / 5;
        paso = redondo(paso);
        // con el paso redondo puede no entrar (ej. el mínimo con pocas divisiones abajo): se agranda
        while (max > paso * (5 - neg) + 1e-9 || -min > paso * neg + 1e-9) paso = redondo(paso * 1.01);
        return new double[]{-paso * neg, paso * (5 - neg)};
    }

    private static double redondo(double x) {
        double m = Math.pow(10, Math.floor(Math.log10(x)));
        double f = x / m;
        return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * m;
    }

    private static double yDe(double v, double[] e, int y0, int y1) { return y1 - (v - e[0]) / (e[1] - e[0]) * (y1 - y0); }

    private static double valor(Object o) {
        if (o == null) return 0;
        try { return OptimistFormula.num(o).doubleValue(); } catch (RuntimeException e) { return 0; }
    }

    private static final DecimalFormatSymbols AR = new DecimalFormatSymbols(new Locale("es", "AR"));
    static { AR.setGroupingSeparator('.'); AR.setDecimalSeparator(','); }

    /** El valor del eje: con el eje en millones, "12,5 M"; si no, con punto de miles ("12.500"). */
    private static String corto(double v, double[] eje) {
        if (v == 0) return "0";
        double a = Math.max(Math.abs(eje[0]), Math.abs(eje[1]));
        if (a >= 1e10) return new DecimalFormat("#,##0.##", AR).format(v / 1e9) + " MM";
        if (a >= 1e7) return new DecimalFormat("#,##0.#", AR).format(v / 1e6) + " M";
        return new DecimalFormat(a < 10 ? "#,##0.##" : "#,##0", AR).format(v);
    }

    /** El valor completo de una barra, con los decimales de su columna. */
    private String largo(String serie, double v) {
        int d = decimales.getOrDefault(serie, 2);
        return new DecimalFormat(d > 0 ? "#,##0." + "0".repeat(d) : "#,##0", AR).format(new BigDecimal(String.valueOf(v)));
    }

    private static String f(double d) { return String.format(Locale.ROOT, "%.1f", d); }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
