package ar.com.datafix.optimist;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * V66 - La planilla del &ExcelDocument de GeneXus (Ext(ExcelDocument)) en los procedimientos: Cells(fila, columna) con
 * .Text / .Number / .Date / .Bold / .Color, y al hacer .Save() el archivo .xlsx. Sin librerías: el .xlsx es un zip con
 * unos pocos XML (así se arma igual en el servidor y en las pruebas). Lo usan los Libros de IVA (botón Excel).
 *  - Números: con punto de miles y 2 decimales si tienen decimales (0,00); los enteros, tal cual. Se pueden sumar.
 *  - Fechas: dd/mm/aaaa, como fecha de Excel.
 *  - Color: el índice de la paleta de Excel que usa GeneXus (1 negro, 2 blanco, 3 rojo, 5 azul, 11 azul oscuro...).
 *  - AutoFit = 1: el ancho de cada columna según lo que tiene.
 *  - V66y: una columna con algún número de más de 2 decimales (costos) va con 0,0000.
 *  - V66y: leer un Excel subido (.xls o .xlsx, la primera hoja) para &ExcelDocument.Open(&Archivo) + Cells(f, c).Text / .Number /
 *    .Date (Actualización por Archivo: WPDescargaCargaCostosPrecios). Esto sí usa Apache POI (ya está en el proyecto).
 */
public final class OptimistPlanilla {

    /** Una celda: el valor (texto, número o fecha), negrita y color de la letra (paleta de Excel; 0 = automático). */
    public static final class Celda {
        Object valor;
        boolean negrita;
        int color;
    }

    private final TreeMap<Integer, TreeMap<Integer, Celda>> celdas = new TreeMap<>();

    /** La celda (fila y columna desde 1), creándola si no existe. */
    public Celda celda(int fila, int columna) {
        return celdas.computeIfAbsent(Math.max(1, fila), k -> new TreeMap<>()).computeIfAbsent(Math.max(1, columna), k -> new Celda());
    }

    public void limpiar() { celdas.clear(); }

    // ------------------------------------------------------------------ V66y: leer un Excel

    private static final java.time.format.DateTimeFormatter DMY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Carga la primera hoja de un .xls / .xlsx: cada celda con su texto, número (BigDecimal) o fecha (LocalDate). */
    public void leer(java.io.File archivo) throws IOException {
        celdas.clear();
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(archivo, null, true)) {
            if (wb.getNumberOfSheets() == 0) return;
            for (org.apache.poi.ss.usermodel.Row fila : wb.getSheetAt(0))
                for (org.apache.poi.ss.usermodel.Cell c : fila) {
                    Object v = valorCelda(c);
                    if (v != null) celda(fila.getRowNum() + 1, c.getColumnIndex() + 1).valor = v;
                }
        } catch (RuntimeException e) {   // un archivo que no es Excel, roto o con clave
            throw new IOException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    private static Object valorCelda(org.apache.poi.ss.usermodel.Cell c) {
        org.apache.poi.ss.usermodel.CellType t = c.getCellType();
        if (t == org.apache.poi.ss.usermodel.CellType.FORMULA) t = c.getCachedFormulaResultType();
        switch (t) {
            case NUMERIC:
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(c)) {
                    java.util.Date d = c.getDateCellValue();
                    return d == null ? null : d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                }
                return BigDecimal.valueOf(c.getNumericCellValue());
            case STRING: return c.getStringCellValue();
            case BOOLEAN: return c.getBooleanCellValue() ? "true" : "false";
            default: return null;
        }
    }

    /**
     * Lo que se lee de una celda (como Cells(f, c) del ExcelDocument de GeneXus): Text (el número sin formato, con punto
     * decimal; la fecha dd/mm/aaaa), Number (también de un texto "1.234,56" o "1234.56"; si no es un número, 0), Date (también
     * de un texto dd/mm/aaaa; si no, vacía) y Type ("N" número, "D" fecha, "C" texto, "" vacía).
     */
    public Map<String, Object> leerCelda(int fila, int columna) {
        TreeMap<Integer, Celda> f = celdas.get(fila);
        Object v = f == null || f.get(columna) == null ? null : f.get(columna).valor;
        Map<String, Object> r = new LinkedHashMap<>();
        String texto = v == null ? "" : v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString()
                : v instanceof LocalDate d ? d.format(DMY) : String.valueOf(v);
        BigDecimal num = v instanceof BigDecimal b ? b : v instanceof String s ? numeroDeTexto(s) : null;
        LocalDate fecha = v instanceof LocalDate d ? d : v instanceof String s ? fechaDeTexto(s) : null;
        r.put("Text", texto);
        r.put("Value", texto);
        r.put("Number", num == null ? BigDecimal.ZERO : num);
        r.put("Date", fecha);
        r.put("Type", v == null ? "" : v instanceof BigDecimal ? "N" : v instanceof LocalDate ? "D" : "C");
        return r;
    }

    /**
     * Un número escrito como texto: "1.234,56" / "1234,56" (coma decimal, como en la Argentina), "1234.56" (punto decimal) o
     * "1.234.567" (puntos de miles). null si no es un número.
     */
    public static BigDecimal numeroDeTexto(String s) {
        if (s == null) return null;
        String t = s.strip().replace(" ", "").replace("\u00a0", "").replace("$", "");
        if (t.isEmpty() || !t.matches("[-+]?[0-9.,]*[0-9][0-9.,]*")) return null;
        if (t.contains(",")) t = t.replace(".", "").replace(",", ".");
        else if (t.indexOf('.') != t.lastIndexOf('.')) t = t.replace(".", "");
        if (t.indexOf('.') != t.lastIndexOf('.')) return null;   // "1,2,3"
        try { return new BigDecimal(t); } catch (NumberFormatException e) { return null; }
    }

    /** Una fecha escrita como texto: dd/mm/aaaa, d/m/aa, dd-mm-aaaa o aaaa-mm-dd. null si no es una fecha. */
    public static LocalDate fechaDeTexto(String s) {
        if (s == null) return null;
        String t = s.strip();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2}|\\d{4})").matcher(t);
        java.util.regex.Matcher iso = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(t);
        try {
            if (iso.matches()) return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            if (m.matches()) {
                int a = Integer.parseInt(m.group(3));
                if (a < 100) a += 2000;
                return LocalDate.of(a, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            }
        } catch (java.time.DateTimeException e) { return null; }
        return null;
    }

    public boolean vacia() { return celdas.isEmpty(); }

    /** &Excel.Cells(f, c).Propiedad = valor. Devuelve false si la propiedad no se conoce. */
    public boolean poner(int fila, int columna, String propiedad, Object valor) {
        Celda c = celda(fila, columna);
        switch (propiedad.toLowerCase(Locale.ROOT)) {
            case "text", "value" -> c.valor = valor == null ? "" : texto(valor);
            case "number" -> c.valor = numero(valor);
            case "date" -> c.valor = valor instanceof LocalDateTime t ? t.toLocalDate() : valor instanceof LocalDate d ? d : null;
            case "bold" -> c.negrita = valor instanceof Boolean b ? b : numero(valor).signum() != 0;
            case "color" -> c.color = numero(valor).intValue();
            case "italic", "underline", "size", "font", "backcolor", "type", "hAlign", "valign", "halign", "formula" -> { /* no se usan: se ignoran */ }
            default -> { return false; }
        }
        return true;
    }

    private static String texto(Object v) {
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return String.valueOf(v).stripTrailing();
    }

    private static BigDecimal numero(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(v).trim().replace(",", ".")); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /** La paleta de 56 colores de Excel (ColorIndex), la que usa la propiedad Color de GeneXus. */
    private static final String[] PALETA = {
            "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF", "800000", "008000",
            "000080", "808000", "800080", "008080", "C0C0C0", "808080", "9999FF", "993366", "FFFFCC", "CCFFFF",
            "660066", "FF8080", "0066CC", "CCCCFF", "000080", "FF00FF", "FFFF00", "00FFFF", "800080", "800000",
            "008080", "0000FF", "00CCFF", "CCFFFF", "CCFFCC", "FFFF99", "99CCFF", "FF99CC", "CC99FF", "FFCC99",
            "3366FF", "33CCCC", "99CC00", "FFCC00", "FF9900", "FF6600", "666699", "969696", "003366", "339966",
            "003300", "333300", "993300", "993366", "333399", "333333"};

    /** El .xlsx con una hoja. */
    public byte[] xlsx(String hoja, boolean autoAjuste) throws IOException {
        // estilos: (negrita, color, formato) -> índice de cellXfs; las letras: (negrita, color) -> índice de fonts
        List<String> letras = new ArrayList<>(List.of("0|0"));
        List<String> estilos = new ArrayList<>(List.of("0|0|0"));   // letra|formato|-
        StringBuilder filas = new StringBuilder();
        Map<Integer, Integer> anchos = new TreeMap<>();
        // la columna que tiene algún número con decimales va toda con 0,00 (un total en 0 llega sin decimales)
        Set<Integer> conDecimales = new HashSet<>();
        Set<Integer> con4 = new HashSet<>();   // V66y: columnas con algún número de más de 2 decimales (costos): 0,0000
        for (var f : celdas.values())
            for (var e : f.entrySet())
                if (e.getValue().valor instanceof BigDecimal b && b.scale() > 0) {
                    conDecimales.add(e.getKey());
                    if (b.stripTrailingZeros().scale() > 2) con4.add(e.getKey());
                }
        for (var f : celdas.entrySet()) {
            filas.append("<row r=\"").append(f.getKey()).append("\">");
            for (var e : f.getValue().entrySet()) {
                Celda c = e.getValue();
                String ref = columna(e.getKey()) + f.getKey();
                String letra = (c.negrita ? 1 : 0) + "|" + (c.color >= 1 && c.color <= PALETA.length ? c.color : 0);
                int il = letras.indexOf(letra);
                if (il < 0) { letras.add(letra); il = letras.size() - 1; }
                int formato = 0;   // 0 general, 164 #,##0.00, 165 dd/mm/yyyy
                String cuerpo;
                int largo;
                if (c.valor instanceof BigDecimal b) {
                    if (b.scale() > 0 || conDecimales.contains(e.getKey())) formato = con4.contains(e.getKey()) ? 166 : 164;
                    cuerpo = "<v>" + b.toPlainString() + "</v>";
                    largo = b.toPlainString().length() + 4;
                } else if (c.valor instanceof LocalDate d) {
                    formato = 165;
                    cuerpo = "<v>" + ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), d) + "</v>";
                    largo = 11;
                } else {
                    String t = c.valor == null ? "" : String.valueOf(c.valor);
                    cuerpo = null;
                    largo = t.length();
                    filas.append("<c r=\"").append(ref).append("\"").append(estilo(estilos, il, formato))
                         .append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(t)).append("</t></is></c>");
                }
                if (cuerpo != null)
                    filas.append("<c r=\"").append(ref).append("\"").append(estilo(estilos, il, formato)).append('>').append(cuerpo).append("</c>");
                anchos.merge(e.getKey(), largo, Math::max);
            }
            filas.append("</row>");
        }
        StringBuilder cols = new StringBuilder();
        if (autoAjuste && !anchos.isEmpty()) {
            cols.append("<cols>");
            anchos.forEach((c, l) -> cols.append("<col min=\"").append(c).append("\" max=\"").append(c).append("\" width=\"")
                    .append(Math.max(6, Math.min(80, l + 2))).append("\" customWidth=\"1\"/>"));
            cols.append("</cols>");
        }
        String hojaXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + cols + "<sheetData>" + filas + "</sheetData></worksheet>";

        StringBuilder fonts = new StringBuilder("<fonts count=\"" + letras.size() + "\">");
        for (String l : letras) {
            String[] p = l.split("\\|");
            fonts.append("<font>").append(p[0].equals("1") ? "<b/>" : "").append("<sz val=\"10\"/>");
            int color = Integer.parseInt(p[1]);
            if (color > 0) fonts.append("<color rgb=\"FF").append(PALETA[color - 1]).append("\"/>");
            fonts.append("<name val=\"Arial\"/></font>");
        }
        fonts.append("</fonts>");
        StringBuilder xfs = new StringBuilder("<cellXfs count=\"" + estilos.size() + "\">");
        for (String s : estilos) {
            String[] p = s.split("\\|");
            xfs.append("<xf numFmtId=\"").append(p[1]).append("\" fontId=\"").append(p[0]).append("\" fillId=\"0\" borderId=\"0\" xfId=\"0\"")
               .append(p[1].equals("0") ? "" : " applyNumberFormat=\"1\"").append(p[0].equals("0") ? "" : " applyFont=\"1\"").append("/>");
        }
        xfs.append("</cellXfs>");
        String estilosXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"3\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/><numFmt numFmtId=\"165\" formatCode=\"dd/mm/yyyy\"/><numFmt numFmtId=\"166\" formatCode=\"#,##0.0000\"/></numFmts>"
                + fonts + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + xfs + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";

        String nombreHoja = xml(hojaSegura(hoja));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            poner(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "</Types>");
            poner(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            poner(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"" + nombreHoja + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            poner(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            poner(z, "xl/styles.xml", estilosXml);
            poner(z, "xl/worksheets/sheet1.xml", hojaXml);
        }
        return out.toByteArray();
    }

    private static String estilo(List<String> estilos, int letra, int formato) {
        String k = letra + "|" + formato;
        int i = estilos.indexOf(k);
        if (i < 0) { estilos.add(k); i = estilos.size() - 1; }
        return i == 0 ? "" : " s=\"" + i + "\"";
    }

    private static void poner(ZipOutputStream z, String nombre, String contenido) throws IOException {
        z.putNextEntry(new ZipEntry(nombre));
        z.write(contenido.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    /** 1 -> A, 27 -> AA. */
    static String columna(int n) {
        StringBuilder s = new StringBuilder();
        for (int x = n; x > 0; x = (x - 1) / 26) s.insert(0, (char) ('A' + (x - 1) % 26));
        return s.toString();
    }

    private static String hojaSegura(String h) {
        String s = h == null || h.isBlank() ? "Hoja1" : h.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    private static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> { if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') b.append(ch); }
            }
        }
        return b.toString();
    }
}
