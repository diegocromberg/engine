package ar.com.datafix.optimist;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * V49 - PDF sin librerías (las impresiones de GeneXus: presupuestos, pedidos...). Lo mismo que usaba el PDF de GeneXus:
 * letras estándar del lector (Helvetica, Times, Courier; no se embeben), texto WinAnsi (acentos, ñ), líneas, recuadros
 * e imágenes (JPEG tal cual; PNG/GIF/BMP pasadas a RGB comprimido, con transparencia).
 * Coordenadas en puntos (1/72 pulgada) desde la esquina de ARRIBA a la izquierda (se dan vuelta al escribir).
 */
public final class OptimistPdf {

    public enum Letra {
        HELVETICA("Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique"),
        TIMES("Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic"),
        COURIER("Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique");
        final String[] nombres;
        Letra(String... n) { nombres = n; }
        /** "Arial", "Helvetica", "Times New Roman", "Courier New"... -> la estándar más parecida. */
        public static Letra de(String familia) {
            String f = familia == null ? "" : familia.toLowerCase(Locale.ROOT);
            if (f.contains("times") || f.contains("georgia") || f.contains("serif") && !f.contains("sans")) return TIMES;
            if (f.contains("courier") || f.contains("consolas") || f.contains("mono")) return COURIER;
            return HELVETICA;
        }
    }

    private static final Charset WIN = Charset.forName("windows-1252");
    static final short[] HELV = {278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,334,584,761,556,0,222,556,333,1000,556,556,333,1000,667,333,1000,0,611,0,0,222,222,333,333,350,556,1000,333,1000,500,333,944,0,500,667,278,333,556,556,556,556,260,556,333,737,370,556,584,333,737,333,400,584,333,333,333,556,537,278,333,333,365,556,834,834,834,611,667,667,667,667,667,667,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,556,556,556,556,556,556,889,500,556,556,556,556,278,278,278,278,556,556,556,556,556,556,556,584,611,556,556,556,556,500,556,500};
    static final short[] HELV_B = {278,333,474,556,556,889,722,238,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,333,333,584,584,584,611,975,722,722,722,722,667,611,778,722,278,556,722,611,833,722,778,667,778,722,667,611,722,667,944,667,667,611,333,278,333,584,556,333,556,611,556,611,556,333,611,611,278,278,556,278,889,611,611,611,611,389,556,333,611,556,778,556,556,500,389,280,389,584,761,556,0,278,556,500,1000,556,556,333,1000,667,333,1000,0,611,0,0,278,278,500,500,350,556,1000,333,1000,556,333,944,0,500,667,278,333,556,556,556,556,280,556,333,737,370,556,584,333,737,333,400,584,333,333,333,611,556,278,333,333,365,556,834,834,834,611,722,722,722,722,722,722,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,556,556,556,556,556,556,889,556,556,556,556,556,278,278,278,278,611,611,611,611,611,611,611,584,611,611,611,611,611,556,611,556};
    static final short[] TIMES = {250,333,408,500,500,833,778,180,333,333,500,564,250,333,250,278,500,500,500,500,500,500,500,500,500,500,278,278,564,564,564,444,921,722,667,667,722,611,556,722,722,333,389,722,611,889,722,722,556,722,667,556,611,722,722,944,722,722,611,333,278,333,469,500,333,444,500,444,500,444,333,500,500,278,278,500,278,778,500,500,500,500,333,389,278,500,500,722,500,500,444,480,200,480,541,761,500,0,333,500,444,1000,500,500,333,1000,556,333,889,0,611,0,0,333,333,444,444,350,500,1000,333,980,389,333,722,0,444,722,250,333,500,500,500,500,200,500,333,760,276,500,564,333,760,333,400,564,300,300,333,500,453,250,333,300,310,500,750,750,750,444,722,722,722,722,722,722,889,667,611,611,611,611,333,333,333,333,722,722,722,722,722,722,722,564,722,722,722,722,722,722,556,500,444,444,444,444,444,444,667,444,444,444,444,444,278,278,278,278,500,500,500,500,500,500,500,564,500,500,500,500,500,500,500,500};
    static final short[] TIMES_B = {250,333,555,500,500,1000,833,278,333,333,500,570,250,333,250,278,500,500,500,500,500,500,500,500,500,500,333,333,570,570,570,500,930,722,667,722,722,667,611,778,778,389,500,778,667,944,722,778,611,778,722,556,667,722,722,1000,722,722,667,333,278,333,581,500,333,500,556,444,556,444,333,500,556,278,333,556,278,833,556,500,556,556,444,389,333,556,500,722,500,500,444,394,220,394,520,761,500,0,333,500,500,1000,500,500,333,1000,556,333,1000,0,667,0,0,333,333,500,500,350,500,1000,333,1000,389,333,722,0,444,722,250,333,500,500,500,500,220,500,333,747,300,500,570,333,747,333,400,570,300,300,333,556,540,250,333,300,330,500,750,750,750,500,722,722,722,722,722,722,1000,722,667,667,667,667,389,389,389,389,722,722,778,778,778,778,778,570,778,722,722,722,722,722,611,556,500,500,500,500,500,500,722,444,444,444,444,444,278,278,278,278,500,556,500,500,500,500,500,570,500,556,556,556,556,500,556,500};

    /** Ancho de un texto en puntos. */
    public static double ancho(String s, Letra l, boolean negrita, double tamano) {
        if (s == null || s.isEmpty()) return 0;
        if (l == Letra.COURIER) return s.length() * 600 * tamano / 1000;
        short[] t = l == Letra.TIMES ? (negrita ? TIMES_B : TIMES) : (negrita ? HELV_B : HELV);
        double w = 0;
        for (byte b : s.getBytes(WIN)) {
            int c = b & 0xff;
            w += c >= 32 ? t[c - 32] : 0;
        }
        return w * tamano / 1000;
    }

    private final double anchoHoja, altoHoja;
    private final List<StringBuilder> paginas = new ArrayList<>();
    private StringBuilder actual;
    private final Map<String, Integer> letras = new LinkedHashMap<>();   // "Helvetica-Bold" -> F1
    private final List<byte[]> imagenes = new ArrayList<>();             // objetos ya armados (sin número)
    private final List<double[]> tamImg = new ArrayList<>();

    public OptimistPdf(double anchoPt, double altoPt) { this.anchoHoja = anchoPt; this.altoHoja = altoPt; }

    public double anchoHoja() { return anchoHoja; }
    public double altoHoja() { return altoHoja; }
    public int paginas() { return paginas.size(); }

    public void nuevaPagina() { actual = new StringBuilder(); paginas.add(actual); }

    private StringBuilder p() { if (actual == null) nuevaPagina(); return actual; }

    private static String n(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }

    /** Texto con su línea de base en (x, base). color: "#rrggbb" o null. */
    public void texto(double x, double base, String s, Letra l, boolean negrita, boolean italica, double tamano, String color) {
        if (s == null || s.isEmpty()) return;
        String fuente = l.nombres[(negrita ? 1 : 0) + (italica ? 2 : 0)];
        int f = letras.computeIfAbsent(fuente, k -> letras.size() + 1);
        StringBuilder b = p();
        b.append("BT ");
        if (color != null) b.append(rgb(color)).append(" rg ");
        b.append("/F").append(f).append(' ').append(n(tamano)).append(" Tf ")
         .append(n(x)).append(' ').append(n(altoHoja - base)).append(" Td (");
        for (byte by : s.getBytes(WIN)) {
            int c = by & 0xff;
            if (c == '(' || c == ')' || c == '\\') b.append('\\').append((char) c);
            else if (c < 32) b.append(' ');
            else if (c > 126) b.append('\\').append(String.format("%03o", c));
            else b.append((char) c);
        }
        b.append(") Tj ET");
        if (color != null) b.append(" 0 g");
        b.append('\n');
    }

    public void linea(double x1, double y1, double x2, double y2, double grosor) {
        p().append(n(grosor)).append(" w ").append(n(x1)).append(' ').append(n(altoHoja - y1)).append(" m ")
           .append(n(x2)).append(' ').append(n(altoHoja - y2)).append(" l S\n");
    }

    public void recuadro(double x, double y, double ancho, double alto, double grosor, String relleno) {
        StringBuilder b = p();
        if (relleno != null) b.append("q ").append(rgb(relleno)).append(" rg ").append(n(x)).append(' ').append(n(altoHoja - y - alto))
                .append(' ').append(n(ancho)).append(' ').append(n(alto)).append(" re f Q\n");
        if (grosor > 0) b.append(n(grosor)).append(" w ").append(n(x)).append(' ').append(n(altoHoja - y - alto)).append(' ')
                .append(n(ancho)).append(' ').append(n(alto)).append(" re S\n");
    }

    private static String rgb(String color) {
        String c = color.startsWith("#") ? color.substring(1) : color;
        if (c.length() == 3) c = "" + c.charAt(0) + c.charAt(0) + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2);
        try {
            int v = Integer.parseInt(c, 16);
            return n(((v >> 16) & 255) / 255.0) + " " + n(((v >> 8) & 255) / 255.0) + " " + n((v & 255) / 255.0);
        } catch (NumberFormatException e) { return "0 0 0"; }
    }

    /**
     * Imagen en la caja (x, y, ancho, alto). Como GeneXus, la estira hasta llenar la caja; proporcional = true la deja
     * sin deformar (arriba a la izquierda). Devuelve false si los bytes no son una imagen que se pueda leer.
     */
    public boolean imagen(byte[] datos, double x, double y, double ancho, double alto, boolean proporcional) {
        Img im = leerImagen(datos);
        if (im == null) return false;
        double w = ancho, h = alto;
        if (proporcional) { double esc = Math.min(ancho / im.w, alto / im.h); w = im.w * esc; h = im.h * esc; }
        int k = imagenes.size();
        imagenes.add(im.objeto); tamImg.add(new double[]{im.w, im.h});
        if (im.mascara != null) { imagenes.add(im.mascara); tamImg.add(null); }
        p().append("q ").append(n(w)).append(" 0 0 ").append(n(h)).append(' ').append(n(x)).append(' ')
           .append(n(altoHoja - y - h)).append(" cm /Im").append(k + 1).append(" Do Q\n");
        return true;
    }

    private record Img(int w, int h, byte[] objeto, byte[] mascara) {}

    /** Objeto XObject de la imagen; "{MASK}" se reemplaza por la referencia a la máscara (transparencia) al armar el PDF. */
    private static Img leerImagen(byte[] d) {
        if (d == null || d.length < 8) return null;
        try {
            if ((d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) {   // JPEG: va tal cual
                int[] t = tamJpeg(d);
                if (t != null && (t[2] == 1 || t[2] == 3)) {
                    String dic = "<< /Type /XObject /Subtype /Image /Width " + t[0] + " /Height " + t[1] + " /ColorSpace "
                            + (t[2] == 1 ? "/DeviceGray" : "/DeviceRGB") + " /BitsPerComponent 8 /Filter /DCTDecode /Length " + d.length + " >>";
                    return new Img(t[0], t[1], objeto(dic, d), null);
                }
            }
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(d));
            if (bi == null) return null;
            int w = bi.getWidth(), h = bi.getHeight();
            byte[] rgb = new byte[w * h * 3];
            byte[] alfa = bi.getColorModel().hasAlpha() ? new byte[w * h] : null;
            boolean hayTransparencia = false;
            int k = 0;
            for (int yy = 0; yy < h; yy++)
                for (int xx = 0; xx < w; xx++, k++) {
                    int px = bi.getRGB(xx, yy);
                    rgb[k * 3] = (byte) (px >> 16); rgb[k * 3 + 1] = (byte) (px >> 8); rgb[k * 3 + 2] = (byte) px;
                    if (alfa != null) { alfa[k] = (byte) (px >>> 24); if ((px >>> 24) != 255) hayTransparencia = true; }
                }
            byte[] z = comprimir(rgb);
            String dic = "<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode" + (hayTransparencia ? " /SMask {MASK}" : "")
                    + " /Length " + z.length + " >>";
            byte[] m = null;
            if (hayTransparencia) {
                byte[] za = comprimir(alfa);
                m = objeto("<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h
                        + " /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length " + za.length + " >>", za);
            }
            return new Img(w, h, objeto(dic, z), m);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** {ancho, alto, componentes} de un JPEG (marcador SOF). */
    private static int[] tamJpeg(byte[] d) {
        int i = 2;
        while (i + 9 < d.length) {
            if ((d[i] & 0xff) != 0xFF) { i++; continue; }
            int m = d[i + 1] & 0xff;
            if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; }
            int len = ((d[i + 2] & 0xff) << 8) | (d[i + 3] & 0xff);
            if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC)
                return new int[]{((d[i + 7] & 0xff) << 8) | (d[i + 8] & 0xff), ((d[i + 5] & 0xff) << 8) | (d[i + 6] & 0xff), d[i + 9] & 0xff};
            i += 2 + len;
        }
        return null;
    }

    private static byte[] objeto(String dic, byte[] datos) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(dic.getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes("\nstream\n".getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(datos);
        o.writeBytes("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
        return o.toByteArray();
    }

    private static byte[] comprimir(byte[] d) {
        Deflater z = new Deflater(Deflater.BEST_COMPRESSION);
        z.setInput(d); z.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, d.length / 4));
        byte[] buf = new byte[65536];
        while (!z.finished()) { int c = z.deflate(buf); o.write(buf, 0, c); }
        z.end();
        return o.toByteArray();
    }

    /**
     * V66: la marca de agua de la empresa (PDFAddWatermark de GeneXus): pone la imagen DEBAJO de lo impreso en todas las
     * páginas de un PDF armado por OptimistPdf, en (x, y) puntos desde abajo a la izquierda y con su tamaño (1 píxel =
     * 1 punto), como el PdfStamper de iText que usaba GeneXus. Devuelve null si la imagen no se puede leer o el PDF no es
     * de los que arma el motor.
     */
    public static byte[] conFondo(byte[] pdf, byte[] imagen, double x, double y) {
        Img im = leerImagen(imagen);
        if (im == null || pdf == null) return null;
        String t = new String(pdf, StandardCharsets.ISO_8859_1);
        try {
            int sx = t.lastIndexOf("startxref");
            if (sx < 0) return null;
            int xref = Integer.parseInt(t.substring(sx + 9).trim().split("\\s+")[0]);
            String[] cab = t.substring(xref).split("\n", 3);   // "xref", "0 N", entradas...
            if (!cab[0].trim().equals("xref")) return null;
            int n = Integer.parseInt(cab[1].trim().split("\\s+")[1]) - 1;
            List<String> objs = new ArrayList<>();
            int pos = xref + cab[0].length() + 1 + cab[1].length() + 1 + 20;   // salta la entrada 0
            for (int i = 1; i <= n; i++, pos += 20) {
                int ini = Integer.parseInt(t.substring(pos, pos + 10));
                String head = i + " 0 obj\n";
                if (!t.startsWith(head, ini)) return null;
                int fin = t.indexOf("\nendobj\n", ini);
                objs.add(t.substring(ini + head.length(), fin));
            }
            String tr = t.substring(t.indexOf("trailer", xref));
            // los objetos nuevos: la imagen (y su máscara) y el dibujo de la marca
            int nImg = objs.size() + 1;
            String img = new String(im.objeto, StandardCharsets.ISO_8859_1);
            objs.add(img);
            if (im.mascara != null) {
                objs.add(new String(im.mascara, StandardCharsets.ISO_8859_1));
                objs.set(nImg - 1, img.replace("{MASK}", (nImg + 1) + " 0 R"));
            }
            String dibujo = "q " + n(im.w) + " 0 0 " + n(im.h) + " " + n(x) + " " + n(y) + " cm /OFondo Do Q\n";
            objs.add("<< /Length " + dibujo.length() + " >>\nstream\n" + dibujo + "\nendstream");
            int nDibujo = objs.size();
            boolean hayPagina = false;
            for (int i = 0; i < n; i++) {
                String o = objs.get(i);
                if (o.startsWith("<< /Type /Page ")) {
                    String o2 = o.replaceFirst("/Contents (\\d+) 0 R", "/Contents [" + nDibujo + " 0 R $1 0 R]");
                    if (o2.equals(o)) return null;
                    objs.set(i, o2);
                    hayPagina = true;
                } else if (o.startsWith("<< /ProcSet ") && o.contains("/XObject <<")) {
                    objs.set(i, o.replace("/XObject <<", "/XObject << /OFondo " + nImg + " 0 R"));
                }
            }
            if (!hayPagina) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(t.substring(0, t.indexOf("1 0 obj\n")).getBytes(StandardCharsets.ISO_8859_1));
            long[] p = new long[objs.size()];
            for (int i = 0; i < objs.size(); i++) {
                p[i] = out.size();
                out.writeBytes(((i + 1) + " 0 obj\n" + objs.get(i) + "\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
            }
            long nx = out.size();
            StringBuilder xr = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
            for (long q : p) xr.append(String.format("%010d 00000 n \n", q));
            xr.append(tr.replaceFirst("/Size \\d+", "/Size " + (objs.size() + 1)).replaceFirst("startxref\\s+\\d+", "startxref\n" + nx));
            out.writeBytes(xr.toString().getBytes(StandardCharsets.ISO_8859_1));
            return out.toByteArray();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** El archivo PDF. titulo: lo que muestra el lector en la pestaña. */
    public byte[] terminar(String titulo) {
        if (paginas.isEmpty()) nuevaPagina();
        List<byte[]> objs = new ArrayList<>();          // objeto n = objs.get(n - 1)
        int nCatalogo = 1, nPaginas = 2, nInfo = 3;
        objs.add(null); objs.add(null); objs.add(null);
        // letras
        Map<String, Integer> numLetra = new LinkedHashMap<>();
        for (String f : letras.keySet()) {
            objs.add(("<< /Type /Font /Subtype /Type1 /BaseFont /" + f
                    + (f.startsWith("Symbol") ? "" : " /Encoding /WinAnsiEncoding") + " >>").getBytes(StandardCharsets.ISO_8859_1));
            numLetra.put(f, objs.size());
        }
        // imágenes (la máscara va justo después de su imagen)
        List<Integer> numImg = new ArrayList<>();
        for (int i = 0; i < imagenes.size(); i++) {
            objs.add(null);
            numImg.add(objs.size());
        }
        for (int i = 0; i < imagenes.size(); i++) {
            byte[] o = imagenes.get(i);
            String s = new String(o, StandardCharsets.ISO_8859_1);
            if (s.contains("{MASK}")) o = s.replace("{MASK}", (numImg.get(i + 1)) + " 0 R").getBytes(StandardCharsets.ISO_8859_1);
            objs.set(numImg.get(i) - 1, o);
        }
        StringBuilder recursos = new StringBuilder("<< /ProcSet [/PDF /Text /ImageC /ImageB] /Font <<");
        for (var e : letras.entrySet()) recursos.append(" /F").append(e.getValue()).append(' ').append(numLetra.get(e.getKey())).append(" 0 R");
        recursos.append(" >> /XObject <<");
        for (int i = 0; i < imagenes.size(); i++) if (tamImg.get(i) != null) recursos.append(" /Im").append(i + 1).append(' ').append(numImg.get(i)).append(" 0 R");
        recursos.append(" >> >>");
        objs.add(recursos.toString().getBytes(StandardCharsets.ISO_8859_1));
        int nRecursos = objs.size();
        List<Integer> numPag = new ArrayList<>();
        for (StringBuilder pg : paginas) {
            byte[] z = comprimir(pg.toString().getBytes(StandardCharsets.ISO_8859_1));
            objs.add(objeto("<< /Length " + z.length + " /Filter /FlateDecode >>", z));
            int nCont = objs.size();
            objs.add(("<< /Type /Page /Parent " + nPaginas + " 0 R /MediaBox [0 0 " + n(anchoHoja) + " " + n(altoHoja)
                    + "] /Resources " + nRecursos + " 0 R /Contents " + nCont + " 0 R >>").getBytes(StandardCharsets.ISO_8859_1));
            numPag.add(objs.size());
        }
        StringBuilder kids = new StringBuilder();
        for (int k : numPag) kids.append(k).append(" 0 R ");
        objs.set(nCatalogo - 1, ("<< /Type /Catalog /Pages " + nPaginas + " 0 R >>").getBytes(StandardCharsets.ISO_8859_1));
        objs.set(nPaginas - 1, ("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + numPag.size() + " >>").getBytes(StandardCharsets.ISO_8859_1));
        objs.set(nInfo - 1, ("<< /Producer (" + OptimistDatabase.nombreSistema().replaceAll("[()\\\\]", "") + ") /Title (" + (titulo == null ? "" : titulo.replaceAll("[()\\\\]", ""))
                + ") >>").getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        long[] pos = new long[objs.size()];
        for (int i = 0; i < objs.size(); i++) {
            pos[i] = o.size();
            o.writeBytes(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
            o.writeBytes(objs.get(i));
            o.writeBytes("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        long xref = o.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
        for (long p : pos) x.append(String.format("%010d 00000 n \n", p));
        x.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root ").append(nCatalogo).append(" 0 R /Info ").append(nInfo)
         .append(" 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        o.writeBytes(x.toString().getBytes(StandardCharsets.ISO_8859_1));
        return o.toByteArray();
    }
}
