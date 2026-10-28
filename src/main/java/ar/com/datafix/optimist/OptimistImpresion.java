package ar.com.datafix.optimist;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * V49 - Impresiones (los reportes PDF de GeneXus). El diseño está en impresiones/<Nombre>.yaml (bloques con textos, campos,
 * líneas, recuadros e imágenes, en mm); la lógica, en proc/<Nombre>.proc, que dice qué bloque se imprime y cuándo
 * ("print Encabezado" dentro de un For each, como en GeneXus). Esta clase lleva la hoja: dónde va el próximo bloque,
 * el salto de página cuando no entra, el encabezado y el pie de cada página (Header / Footer del procedimiento).
 *
 *   nombre: PRSPrnGenerico
 *   hoja: {ancho: 217.47, alto: 304.25, izquierda: 7.57, arriba: 9.7, pie: 283.88}   # mm; pie: dónde empieza el pie
 *   letra: {familia: Helvetica, tamaño: 8}
 *   numeros: {miles: ",", decimal: "."}   (V50s: sin poner, formato argentino: miles "." y decimal ",")
 *   bloques:
 *     Encabezado:
 *       alto: 76.45
 *       elementos:
 *         - {texto: "Cliente:", x: 2.03, y: 33.78, tamaño: 8}
 *         - {campo: PRSRazonSocial, x: 25.4, y: 33.78, ancho: 66.04, tamaño: 8}
 *         - {campo: "&Total", x: 177.8, y: 20.32, ancho: 27.43, alinear: derecha, formato: "ZZ,ZZZ,ZZZ,ZZ9.99"}
 *         - {imagen: "&LogoEmpresa", x: 2.03, y: 0, ancho: 76.2, alto: 25.4}
 *         - {linea: true, x: 2.03, y: 76.2, ancho: 205.23}
 *         - {recuadro: true, x: 135.38, y: 0, ancho: 69.85, alto: 13.97}
 */
public final class OptimistImpresion {

    static final double PT = 72 / 25.4;   // puntos por mm
    /** Línea de base del texto: desde el borde de arriba del elemento, en tamaños de letra (medido en los PDF de GeneXus). */
    static final double BASE = 0.98;

    // ------------------------------------------------------------------ diseño

    public record Hoja(double ancho, double alto, double izquierda, double arriba, double pie) {}

    public record Elemento(String tipo, String valor, double x, double y, double ancho, double alto, String alinear,
                           String formato, Double tamano, Boolean negrita, Boolean italica, String familia, String color,
                           boolean ajustar, double grosor, String relleno, boolean proporcional, boolean cortar) {}

    public record Bloque(String nombre, double alto, List<Elemento> elementos) {
        /** Las expresiones que hay que evaluar al imprimirlo (campos e imágenes). */
        public List<String> expresiones() {
            List<String> r = new ArrayList<>();
            for (Elemento e : elementos) if ((e.tipo().equals("campo") || e.tipo().equals("imagen")) && !r.contains(e.valor())) r.add(e.valor());
            return r;
        }
    }

    public record Diseno(String nombre, Hoja hoja, String familia, double tamano, String miles, String decimal,
                         Map<String, Bloque> bloques) {
        public Bloque bloque(String n) {
            Bloque b = bloques.get(n);
            if (b != null) return b;
            for (var e : bloques.entrySet()) if (e.getKey().equalsIgnoreCase(n)) return e.getValue();
            return null;
        }
    }

    /** Lee el YAML ya pasado a Map (SnakeYAML). */
    @SuppressWarnings("unchecked")
    public static Diseno de(String nombre, Map<String, Object> y) {
        Map<String, Object> h = y.get("hoja") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Hoja hoja = new Hoja(num(h.get("ancho"), 210), num(h.get("alto"), 297), num(h.get("izquierda"), 7.57),
                num(h.get("arriba"), 9.7), num(h.get("pie"), num(h.get("alto"), 297) - 20));
        Map<String, Object> l = y.get("letra") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> nu = y.get("numeros") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Bloque> bloques = new LinkedHashMap<>();
        if (y.get("bloques") instanceof Map<?, ?> bs) {
            for (var e : ((Map<String, Object>) bs).entrySet()) {
                Map<String, Object> b = e.getValue() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                List<Elemento> els = new ArrayList<>();
                if (b.get("elementos") instanceof List<?> le)
                    for (Object o : le) if (o instanceof Map<?, ?> em) els.add(elemento(nombre + "." + e.getKey(), (Map<String, Object>) em));
                bloques.put(String.valueOf(e.getKey()), new Bloque(String.valueOf(e.getKey()), num(b.get("alto"), 0), List.copyOf(els)));
            }
        }
        return new Diseno(nombre, hoja, str(l.get("familia"), "Helvetica"), num(l.get("tamaño"), num(l.get("tamano"), 8)),
                str(nu.get("miles"), "."), str(nu.get("decimal"), ","), bloques);   // V50s: formato argentino por defecto (725.880,00)
    }

    private static Elemento elemento(String donde, Map<String, Object> m) {
        String tipo, valor;
        if (m.containsKey("texto")) { tipo = "texto"; valor = str(m.get("texto"), ""); }
        else if (m.containsKey("campo")) { tipo = "campo"; valor = str(m.get("campo"), ""); }
        else if (m.containsKey("imagen")) { tipo = "imagen"; valor = str(m.get("imagen"), ""); }
        else if (m.containsKey("linea")) { tipo = "linea"; valor = null; }
        else if (m.containsKey("recuadro")) { tipo = "recuadro"; valor = null; }
        else throw new IllegalArgumentException(donde + ": elemento sin texto/campo/imagen/linea/recuadro: " + m);
        Object neg = m.get("negrita"), ita = m.containsKey("italica") ? m.get("italica") : m.get("itálica");
        Object tam = m.containsKey("tamaño") ? m.get("tamaño") : m.get("tamano");
        return new Elemento(tipo, valor, num(m.get("x"), 0), num(m.get("y"), 0), num(m.get("ancho"), 0), num(m.get("alto"), 0),
                str(m.get("alinear"), null), str(m.get("formato"), null), tam == null ? null : num(tam, 8),
                neg == null ? null : Boolean.valueOf(String.valueOf(neg)), ita == null ? null : Boolean.valueOf(String.valueOf(ita)),
                str(m.get("letra"), null), str(m.get("color"), null), Boolean.parseBoolean(String.valueOf(m.get("ajustar"))),
                num(m.get("grosor"), 1), str(m.get("relleno"), null), Boolean.parseBoolean(String.valueOf(m.get("proporcional"))),
                Boolean.parseBoolean(String.valueOf(m.get("cortar"))));   // V65: cortar un campo a su ancho
    }

    private static double num(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o).replace("mm", "").trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }

    // ------------------------------------------------------------------ la hoja mientras se imprime

    private final Diseno d;
    private final OptimistPdf pdf;
    private final Function<Object, byte[]> imagenes;
    private double y;               // pt desde arriba de la hoja: dónde va el próximo bloque
    private boolean enPie, enEncabezado, empezada;
    private Runnable encabezado, pie;   // Header / Footer del procedimiento
    private final List<String> avisos = new ArrayList<>();

    /** imagenes: de lo que tenga la variable (bytes de la base, ruta o URL) a los bytes de la imagen; null si no hay. */
    public OptimistImpresion(Diseno d, Function<Object, byte[]> imagenes) {
        this.d = d;
        this.pdf = new OptimistPdf(d.hoja().ancho() * PT, d.hoja().alto() * PT);
        this.imagenes = imagenes;
    }

    public Diseno diseno() { return d; }
    private String archivo;
    /** Output_file de GeneXus: el nombre del PDF (sin la extensión). */
    public void archivo(String nombre) { archivo = nombre; }
    public String archivo() { return archivo; }
    public List<String> avisos() { return avisos; }
    public void encabezado(Runnable r) { encabezado = r; }
    public void pie(Runnable r) { pie(r, null); }
    /** V63: el Footer; si empieza con "Lineno N", el cuerpo de la hoja termina en ese renglón (no se encima con el pie). */
    public void pie(Runnable r, Integer lineaPie) {
        pie = r;
        inicioPie = lineaPie == null ? null : renglonY(lineaPie);
    }

    /**
     * V63: Lineno N de GeneXus: el próximo bloque va en el renglón N. El renglón de GeneXus mide 15 centésimas de pulgada
     * (10,8 pt) y se cuenta desde el margen de arriba (medido en el PDF de GeneXus de una factura de InPack: "Lineno 55"
     * en el Footer lo empieza a 621 pt; 9,7 mm + 55 x 10,8 pt = 621,5 pt). Como en GeneXus, puede ir para atrás.
     */
    public void renglon(int n) {
        empezar();
        if (!enPie && !enEncabezado) abrirPendiente();
        y = renglonY(n);
    }

    static final double RENGLON = 15 * 0.72;   // pt: renglón de GeneXus (15 centésimas de pulgada)
    private double renglonY(int n) { return d.hoja().arriba() * PT + n * RENGLON; }
    private Double inicioPie;       // V63: dónde empieza el pie si el Footer tiene Lineno (pt)
    private double inicioCuerpo;    // V63: dónde terminó el encabezado de la hoja (pt)

    /** Hasta dónde llega el cuerpo de la hoja: el pie del diseño o, si es más arriba, el Lineno del Footer. */
    private double limiteCuerpo() {
        double l = d.hoja().pie() * PT;
        return inicioPie != null && inicioPie < l ? inicioPie : l;
    }

    private void empezar() {
        if (empezada) return;
        empezada = true;
        pdf.nuevaPagina();
        y = d.hoja().arriba() * PT;
        conEncabezado();
    }

    private void conEncabezado() {
        inicioCuerpo = y;
        if (encabezado == null) return;
        enEncabezado = true;
        try { encabezado.run(); } finally { enEncabezado = false; inicioCuerpo = y; }
    }

    private void cerrarPagina() {
        if (pie == null) return;
        double antes = y;
        y = d.hoja().pie() * PT;
        enPie = true;
        try { pie.run(); } finally { enPie = false; y = antes; }
    }

    /**
     * Eject de GeneXus: termina la página (con su pie). V62: la página siguiente (con su encabezado) se abre recién cuando
     * se imprime algo, como en GeneXus: así el encabezado sale con los datos de ese momento (ej. la copia DUPLICADO de
     * una factura, que cambia después del Eject) y un Eject al final no deja una hoja de más.
     */
    public void saltarPagina() {
        if (paginaPendiente) return;   // dos Eject seguidos: GeneXus no deja una hoja en blanco
        empezar();
        cerrarPagina();
        paginaPendiente = true;
    }

    private boolean paginaPendiente;

    private void abrirPendiente() {
        if (!paginaPendiente) return;
        paginaPendiente = false;
        pdf.nuevaPagina();
        y = d.hoja().arriba() * PT;
        conEncabezado();
    }

    /** print Bloque: lo dibuja donde va; si no entra antes del pie, salta de página. valores: expresión -> valor. */
    public void imprimir(String nombre, Map<String, Object> valores) {
        Bloque b = d.bloque(nombre);
        if (b == null) throw new IllegalStateException(d.nombre() + ": no existe el bloque '" + nombre + "' en impresiones/" + d.nombre() + ".yaml");
        empezar();
        if (!enPie && !enEncabezado) abrirPendiente();
        double alto = b.alto() * PT;
        // V63: el límite es el Lineno del Footer si lo tiene; no salta si el bloque ya está arriba de todo de la hoja
        if (!enPie && !enEncabezado && y + alto > limiteCuerpo() + 0.01 && y > Math.max(d.hoja().arriba() * PT, inicioCuerpo) + 0.01) {
            saltarPagina();
            abrirPendiente();
        }
        dibujar(b, y, valores);
        y += alto;
        imprimio = true;
    }

    /** V66t: si se imprimió algún bloque (con el Excel de un listado no se imprime nada: no hay PDF que mostrar). */
    private boolean imprimio;
    public boolean imprimio() { return imprimio; }

    /** El PDF terminado (con el pie de la última página; si terminó con un Eject, ese pie ya está). */
    public byte[] terminar() {
        empezar();
        if (!paginaPendiente) cerrarPagina();
        return pdf.terminar(d.nombre());
    }

    public int paginas() { return pdf.paginas(); }

    // ------------------------------------------------------------------ dibujo

    private void dibujar(Bloque b, double arriba, Map<String, Object> valores) {
        double izq = d.hoja().izquierda() * PT;
        // los recuadros pintados van primero: quedan debajo de los textos (como en GeneXus)
        List<Elemento> orden = new ArrayList<>(b.elementos());
        orden.sort(java.util.Comparator.comparingInt(e -> e.tipo().equals("recuadro") && e.relleno() != null ? 0 : 1));
        for (Elemento e : orden) {
            double x = izq + e.x() * PT, top = arriba + e.y() * PT, w = e.ancho() * PT, h = e.alto() * PT;
            switch (e.tipo()) {
                case "linea" -> {
                    double g = 0.72 * e.grosor();
                    if (e.alto() > 0 && e.ancho() <= 0) pdf.linea(x, top, x, top + h, g);
                    else pdf.linea(x, top, x + w, top + (e.alto() > 0 ? h : 0), g);
                }
                case "recuadro" -> pdf.recuadro(x, top, w, h, e.grosor() <= 0 ? 0 : 0.72 * e.grosor(), e.relleno());
                case "imagen" -> {
                    Object v = valores.get(e.valor());
                    if (v == null && !e.valor().startsWith("&")) v = e.valor();   // imagen fija: el archivo
                    byte[] datos = v == null ? null : imagenes.apply(v);
                    if (datos != null && !pdf.imagen(datos, x, top, w, h, e.proporcional())) avisos.add(b.nombre() + ": " + e.valor() + ": no es una imagen que se pueda leer");
                }
                default -> {
                    Object val = e.tipo().equals("campo") && e.valor().trim().equalsIgnoreCase("&Page")
                            ? java.math.BigDecimal.valueOf(Math.max(1, pdf.paginas())) : valores.get(e.valor());   // V65: &Page = número de hoja
                    String s = e.tipo().equals("texto") ? e.valor() : formatear(val, e.formato());
                    texto(e, s, x, top, w, h);
                }
            }
        }
    }

    private void texto(Elemento e, String s, double x, double top, double w, double h) {
        if (s == null || s.isEmpty()) return;
        double tam = e.tamano() != null ? e.tamano() : d.tamano();
        boolean neg = Boolean.TRUE.equals(e.negrita()), ita = Boolean.TRUE.equals(e.italica());
        OptimistPdf.Letra l = OptimistPdf.Letra.de(e.familia() != null ? e.familia() : d.familia());
        List<String> renglones = e.ajustar() && w > 0 ? partir(s, l, neg, tam, w) : List.of(s.replaceAll("\\s+$", ""));
        double interlinea = tam * 1.15;
        int max = e.ajustar() && h > 0 ? Math.max(1, (int) Math.floor((h - (BASE - 1.15) * tam) / interlinea)) : renglones.size();
        for (int i = 0; i < renglones.size() && i < max; i++) {
            String r = renglones.get(i);
            // como GeneXus: el texto fijo que no entra en su ancho se corta ("BONIFICACIONES" en 79 -> "BONIFICACION");
            // los campos no (el &Tit6 de De Santis se pasa de su caja y GeneXus lo imprime entero), salvo con cortar: true (V65)
            if (w > 0 && (e.tipo().equals("texto") || e.cortar())) while (r.length() > 1 && OptimistPdf.ancho(r, l, neg, tam) > w + 1.5) r = r.substring(0, r.length() - 1);
            double ancho = OptimistPdf.ancho(r, l, neg, tam);
            double xx = x;
            if ("derecha".equals(e.alinear()) && w > 0) xx = x + w - ancho;
            else if ("centro".equals(e.alinear()) && w > 0) xx = x + (w - ancho) / 2;
            pdf.texto(xx, top + BASE * tam + i * interlinea, r, l, neg, ita, tam, e.color());
        }
    }

    /** Parte un texto en renglones que entren en el ancho (por palabras; respeta los saltos de línea). */
    static List<String> partir(String s, OptimistPdf.Letra l, boolean neg, double tam, double ancho) {
        List<String> r = new ArrayList<>();
        for (String parrafo : s.split("\r?\n", -1)) {
            StringBuilder linea = new StringBuilder();
            for (String pal : parrafo.split(" ")) {
                String prueba = linea.length() == 0 ? pal : linea + " " + pal;
                if (OptimistPdf.ancho(prueba, l, neg, tam) <= ancho || linea.length() == 0) { linea.setLength(0); linea.append(prueba); }
                else { r.add(linea.toString()); linea.setLength(0); linea.append(pal); }
            }
            r.add(linea.toString());
        }
        return r;
    }

    /**
     * Como la picture de GeneXus: "ZZ,ZZZ,ZZ9.99" (con separador de miles y 2 decimales), "ZZZ9.999", "99/99/99" (fecha
     * corta), "99/99/9999". Sin formato: el valor como texto (números sin ceros de más, fechas dd/mm/aa).
     */
    String formatear(Object v, String formato) {
        if (v == null) return "";
        if (v instanceof LocalDateTime t) v = formato != null && formato.contains(":") ? t : t.toLocalDate();
        if (v instanceof java.sql.Timestamp t) v = t.toLocalDateTime().toLocalDate();
        if (v instanceof java.sql.Date t) v = t.toLocalDate();
        if (v instanceof LocalDate f) {
            if (f.getYear() <= 1) return "";   // fecha vacía de GeneXus
            boolean largo = formato != null && formato.replace(" ", "").matches("99/99/9999.*");
            return f.format(DateTimeFormatter.ofPattern(largo ? "dd/MM/yyyy" : "dd/MM/yy"));
        }
        if (v instanceof LocalDateTime t) return t.format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"));
        if (v instanceof Boolean b) return b ? "Sí" : "No";
        if (v instanceof Number || (formato != null && formato.matches("[Z9,.\\-+]+") && esNumero(v))) {
            BigDecimal n = v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v).trim().isEmpty() ? "0" : String.valueOf(v).trim());
            if (formato == null) return n.stripTrailingZeros().toPlainString();
            int punto = formato.indexOf('.');
            int dec = punto < 0 ? 0 : formato.length() - punto - 1;
            boolean miles = formato.substring(0, punto < 0 ? formato.length() : punto).contains(",");
            n = n.setScale(dec, RoundingMode.HALF_UP);
            String abs = n.abs().toPlainString();
            String ent = abs.contains(".") ? abs.substring(0, abs.indexOf('.')) : abs;
            String fr = abs.contains(".") ? abs.substring(abs.indexOf('.') + 1) : "";
            if (miles) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ent.length(); i++) {
                    if (i > 0 && (ent.length() - i) % 3 == 0) sb.append(d.miles());
                    sb.append(ent.charAt(i));
                }
                ent = sb.toString();
            }
            return (n.signum() < 0 ? "-" : "") + ent + (dec > 0 ? d.decimal() + fr : "");
        }
        String t = String.valueOf(v).replaceAll("\\s+$", "");
        return formato != null && formato.startsWith("@!") ? t.toUpperCase(Locale.ROOT) : t;   // @! de GeneXus: en mayúsculas
    }

    private static boolean esNumero(Object v) {
        try { new BigDecimal(String.valueOf(v).trim()); return true; } catch (NumberFormatException e) { return false; }
    }
}
