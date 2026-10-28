package ar.com.datafix.optimist;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * V66k - El botón PDF de los WorkWith y las consultas (el "Export to PDF" del WorkWithPlus de GeneXus): la lista que muestran
 * los filtros (todas las páginas), con las columnas que ve el usuario y en su orden. Hoja A4 apaisada; en cada hoja el
 * título, la empresa, la fecha y "Página N de M", y los títulos de las columnas. Números a la derecha con el formato
 * argentino (1.234,56), fechas dd/mm/aaaa, Sí / No. Un texto que no entra en su columna se corta con "...".
 */
public final class OptimistPdfLista {
    private OptimistPdfLista() {}

    /** Una columna: título, cómo se saca el valor de un renglón y el formato numérico del Excel ("#,##0.00", "0") o null. */
    public record Columna<T>(String titulo, Function<T, ?> valor, String formato) {}

    private static final double ANCHO = 842, ALTO = 595, MARGEN = 28;   // A4 apaisada, en puntos
    private static final double TAM = 7.5, TAM_TIT = 7.5, RENGLON = 11.5;
    private static final OptimistPdf.Letra LETRA = OptimistPdf.Letra.HELVETICA;
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DIA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public static <T> byte[] generar(String titulo, String empresa, List<Columna<T>> cols, List<T> filas) {
        int n = cols.size();
        // los textos de cada celda (una sola vez) y si la columna es numérica
        List<String[]> textos = new ArrayList<>(filas.size());
        boolean[] numerica = new boolean[n];
        for (int j = 0; j < n; j++) numerica[j] = cols.get(j).formato() != null;
        for (T f : filas) {
            String[] r = new String[n];
            for (int j = 0; j < n; j++) {
                Object v;
                try { v = cols.get(j).valor().apply(f); } catch (RuntimeException e) { v = null; }
                if (v instanceof Number) numerica[j] = true;
                r[j] = texto(v, cols.get(j).formato());
            }
            textos.add(r);
        }
        // anchos: el título o el texto más largo (de los primeros 2.000 renglones), con un tope; si no entran, se achican
        double util = ANCHO - 2 * MARGEN, sep = 8;
        double[] ancho = new double[n];
        for (int j = 0; j < n; j++) {
            double a = OptimistPdf.ancho(cols.get(j).titulo(), LETRA, true, TAM_TIT);
            for (int i = 0; i < Math.min(textos.size(), 2000); i++) a = Math.max(a, OptimistPdf.ancho(textos.get(i)[j], LETRA, false, TAM));
            ancho[j] = Math.min(a, 230) + sep;
        }
        double total = 0;
        for (double a : ancho) total += a;
        if (total > util) {
            // se achican las anchas (las de texto) hasta que entre todo; las angostas quedan como están
            double sobra = total - util, achicable = 0;
            for (double a : ancho) achicable += Math.max(0, a - 45);
            for (int j = 0; j < n; j++) if (achicable > 0) ancho[j] -= Math.min(Math.max(0, ancho[j] - 45), sobra * Math.max(0, ancho[j] - 45) / achicable);
        }
        double arriba = MARGEN + 44 + 4 + RENGLON;                       // donde empiezan los renglones (la base del primero)
        int porHoja = Math.max(1, (int) ((ALTO - MARGEN - 14 - arriba) / RENGLON));
        int hojas = Math.max(1, (textos.size() + porHoja - 1) / porHoja);
        String ahora = LocalDateTime.now().format(DIA_HORA);
        OptimistPdf pdf = new OptimistPdf(ANCHO, ALTO);
        for (int h = 0; h < hojas; h++) {
            pdf.nuevaPagina();
            pdf.texto(MARGEN, MARGEN + 12, titulo, LETRA, true, false, 13, null);
            if (empresa != null && !empresa.isBlank()) pdf.texto(MARGEN, MARGEN + 26, empresa, LETRA, false, false, 8.5, null);
            String der1 = "Fecha: " + ahora, der2 = "Página " + (h + 1) + " de " + hojas;
            pdf.texto(ANCHO - MARGEN - OptimistPdf.ancho(der1, LETRA, false, 8), MARGEN + 12, der1, LETRA, false, false, 8, null);
            pdf.texto(ANCHO - MARGEN - OptimistPdf.ancho(der2, LETRA, false, 8), MARGEN + 26, der2, LETRA, false, false, 8, null);
            double y = MARGEN + 44, x = MARGEN;
            for (int j = 0; j < n; j++) {
                String t = cortar(cols.get(j).titulo(), ancho[j] - sep, true, TAM_TIT);
                double tx = numerica[j] ? x + ancho[j] - sep / 2 - OptimistPdf.ancho(t, LETRA, true, TAM_TIT) : x + sep / 2;
                pdf.texto(tx, y, t, LETRA, true, false, TAM_TIT, null);
                x += ancho[j];
            }
            pdf.linea(MARGEN, y + 3, ANCHO - MARGEN, y + 3, 0.6);
            int desde = h * porHoja, hasta = Math.min(textos.size(), desde + porHoja);
            for (int i = desde; i < hasta; i++) {
                double base = arriba + (i - desde) * RENGLON;
                if ((i - desde) % 2 == 1) pdf.recuadro(MARGEN, base - RENGLON + 3, util, RENGLON, 0, "#f1f4f7");
                x = MARGEN;
                String[] r = textos.get(i);
                for (int j = 0; j < n; j++) {
                    String t = cortar(r[j], ancho[j] - sep, false, TAM);
                    double tx = numerica[j] ? x + ancho[j] - sep / 2 - OptimistPdf.ancho(t, LETRA, false, TAM) : x + sep / 2;
                    pdf.texto(tx, base, t, LETRA, false, false, TAM, null);
                    x += ancho[j];
                }
            }
            if (h == hojas - 1) {
                double base = arriba + (hasta - desde) * RENGLON + 4;
                pdf.linea(MARGEN, base - RENGLON + 4, ANCHO - MARGEN, base - RENGLON + 4, 0.4);
                pdf.texto(MARGEN, base + 2, textos.size() + (textos.size() == 1 ? " registro" : " registros"), LETRA, false, true, 7.5, null);
            }
        }
        return pdf.terminar(titulo);
    }

    /** El texto de una celda: números con el formato argentino, fechas dd/mm/aaaa, Sí / No. */
    static String texto(Object v, String formato) {
        if (v == null) return "";
        if (v instanceof Boolean b) return b ? "Sí" : "No";
        if (v instanceof java.sql.Timestamp ts) v = ts.toLocalDateTime();
        if (v instanceof java.sql.Date d) v = d.toLocalDate();
        if (v instanceof LocalDateTime dt) {
            if (dt.getYear() <= 1000) return "";   // la fecha vacía de GeneXus
            return dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? dt.toLocalDate().format(DIA) : dt.format(DIA_HORA);
        }
        if (v instanceof LocalDate d) return d.getYear() <= 1000 ? "" : d.format(DIA);
        if (v instanceof Number nu) {
            BigDecimal b = nu instanceof BigDecimal x ? x : new BigDecimal(nu.toString());
            String f = formato;
            if (f == null || f.isBlank()) f = b.scale() > 0 ? "#,##0." + "0".repeat(Math.min(b.scale(), 6)) : "0";
            DecimalFormatSymbols s = new DecimalFormatSymbols();
            s.setDecimalSeparator(',');
            s.setGroupingSeparator('.');
            return new DecimalFormat(f, s).format(b);
        }
        return String.valueOf(v).strip();
    }

    private static String cortar(String s, double max, boolean negrita, double tam) {
        if (s == null || s.isEmpty() || OptimistPdf.ancho(s, LETRA, negrita, tam) <= max) return s;
        String t = s;
        while (t.length() > 1 && OptimistPdf.ancho(t + "...", LETRA, negrita, tam) > max) t = t.substring(0, t.length() - 1);
        return t + "...";
    }
}
