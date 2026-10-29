package ar.com.datafix.optimist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * V34 - Catálogo de procedimientos: los proc/*.proc (en desarrollo, directo de src/main/resources;
 * en el WAR, de adentro). Se relee solo si cambió algún archivo (nombre + fecha), igual que las TRN.
 */
public final class OptimistProcedimientos {
    /** V84: los índices en memoria de IndiceGuardar / IndiceBuscar, por hilo (el que corre el proceso). */
    static final ThreadLocal<Map<String, Map<String, java.math.BigDecimal>>> INDICES = ThreadLocal.withInitial(HashMap::new);
    private OptimistProcedimientos() {}
    private static final Logger LOG = LoggerFactory.getLogger(OptimistProcedimientos.class);

    // Procedimientos nativos (Java) que reemplazan a los de GeneXus que usan la sesión web
    /** V48: procedimientos de GeneXus reemplazados por uno nativo (en Java): se usa el nativo aunque exista el .proc. */
    private static final Set<String> REEMPLAZOS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        // V49: impresiones: el diseño de cada reporte (impresiones/<Nombre>.yaml); el PDF lo muestra la pantalla que corrió la acción,
        // así que VerPDF / WPVerPDFGenerico de GeneXus (mandaban el archivo al navegador) no hacen nada
        OptimistProcedimiento.disenoDe = OptimistProcedimientos::diseno;
        // imágenes: las de la base, rutas o URL; y las fijas de un diseño (el sello de IEP), al lado del YAML en impresiones/
        OptimistProcedimiento.cargarImagen = v -> {
            byte[] b = OptimistProcedimiento.imagen(v);
            if (b != null || !(v instanceof String s) || s.isBlank() || s.contains("/") || s.contains("\\")) return b;
            for (String ext : List.of("", ".png", ".jpg", ".jpeg", ".gif")) {   // FromImage(Nombre) viene sin la extensión
                try (InputStream in = OptimistRecursos.abrir("impresiones/" + s.trim() + ext)) { if (in != null) return in.readAllBytes(); }
                catch (Exception e) { /* sigue con la otra extensión */ }
            }
            return null;
        };
        for (String n : List.of("VerPdf", "WPVerPDFGenerico")) {
            OptimistProcedimiento.registrarNativo(n, "in: &A, in: &B, in: &C, in: &D, in: &E", (in, c) -> Map.of());
            reemplazar(n);
        }
        // LoadWWPContext / GetWWPContext: el contexto del usuario (empresa, división, usuario...) como estructura
        OptimistProcedimiento.Nativo cargar = (in, c) -> {
            OptimistContexto x = OptimistContexto.actual();
            OptimistProcedimiento.Sdt sdt = new OptimistProcedimiento.Sdt(x == null ? Map.of() : x.valores());
            // E4d: el WWPContext de WorkWithPlus llama UserId / UserName al usuario (en el motor, SecUserId / SecUserName)
            if (x != null) { sdt.putIfAbsent("UserId", x.valor("SecUserId")); sdt.putIfAbsent("UserName", x.valor("SecUserName")); }
            return Map.of("context", sdt);
        };
        OptimistProcedimiento.registrarNativo("LoadWWPContext", "out: &Context", cargar);
        // V49y: la configuración por usuario (GetUsrProc, VERCampo...) se recuerda por usuario (+ lo que diga la aplicación)
        OptimistProcedimiento.claveUsuario = () -> {
            OptimistContexto x = OptimistContexto.actual();
            if (x == null) return List.of();
            // E3: además del usuario, lo que diga la aplicación (OPTIMIST ERP: empresa, división y la de listas de precios)
            List<Object> r = new ArrayList<>();
            r.add(x.valor("SecUserId"));
            for (OptimistExtension e : OptimistExtension.todas()) for (String v : e.claveUsuario()) r.add(x.valor(v));
            return r;
        };
        OptimistProcedimiento.registrarNativo("GetWWPContext", "out: &Context", cargar);
        // SetWWPContext: guarda los cambios en el contexto de la sesión
        OptimistProcedimiento.registrarNativo("SetWWPContext", "in: &Context", (in, c) -> {
            OptimistContexto x = OptimistContexto.actual();
            if (x != null && in.get("context") instanceof Map<?, ?> m)
                for (var e : m.entrySet()) x.poner(String.valueOf(e.getKey()), e.getValue());
            return Map.of();
        });
        // V48: &X.EnumerationDescription() de GeneXus. El generador (gx2wbp.py / gx2proc.py) lo escribe como
        // EnumTexto.Udp(&X, "1=Activo;2=Inactivo") con los valores del dominio enumerado.
        // V48: Business Components: la TRN por su nombre
        OptimistProcedimiento.trnDe = n -> {
            try {
                for (OptimistTrnDefinicion.Trn t : OptimistTrnCatalogo.cargar().trns().values()) if (t.nombre().equalsIgnoreCase(n)) return t;
            } catch (RuntimeException e) { LOG.warn("TRN {}: {}", n, e.getMessage()); }
            return null;
        };
        // V84: índices en memoria (clave -> número) para acumular sin recorrer la colección entera en cada renglón
        // (GeneXus buscaba el cliente, el artículo o el comprobante con un For &X in &Coleccion por cada renglón).
        // IndiceGuardar(&Nombre, &Clave, &Posicion) / &Pos = IndiceBuscar.Udp(&Nombre, &Clave) (0 si no está) /
        // IndiceLimpiar(&Nombre). Son del hilo que corre el proceso: se limpian al empezar y al terminar.
        OptimistProcedimiento.registrarNativo("IndiceLimpiar", "in: &Nombre", (in, c) -> {
            Map<String, Map<String, java.math.BigDecimal>> t = INDICES.get();
            String n = String.valueOf(in.getOrDefault("nombre", "")).strip().toLowerCase(Locale.ROOT);
            if (n.isEmpty() || n.equals("*")) t.clear(); else t.remove(n);
            return Map.of();
        });
        OptimistProcedimiento.registrarNativo("IndiceGuardar", "in: &Nombre, in: &Clave, in: &Posicion", (in, c) -> {
            String n = String.valueOf(in.getOrDefault("nombre", "")).strip().toLowerCase(Locale.ROOT);
            Object pos = in.get("posicion");
            INDICES.get().computeIfAbsent(n, k -> new HashMap<>())
                    .put(String.valueOf(in.getOrDefault("clave", "")).strip(), pos == null ? java.math.BigDecimal.ZERO : OptimistFormula.num(pos));
            return Map.of();
        });
        OptimistProcedimiento.registrarNativo("IndiceBuscar", "in: &Nombre, in: &Clave, out: &Posicion", (in, c) -> {
            String n = String.valueOf(in.getOrDefault("nombre", "")).strip().toLowerCase(Locale.ROOT);
            Map<String, java.math.BigDecimal> m = INDICES.get().get(n);
            java.math.BigDecimal v = m == null ? null : m.get(String.valueOf(in.getOrDefault("clave", "")).strip());
            Map<String, Object> out = new HashMap<>();
            out.put("posicion", v == null ? java.math.BigDecimal.ZERO : v);
            return out;
        });
        // V48c: todas las TRN (atributos fórmula en los For Each)
        OptimistProcedimiento.trns = () -> {
            try { return OptimistTrnCatalogo.cargar().trns().values(); } catch (RuntimeException e) { return List.of(); }
        };
        // E3: los procedimientos en Java de la aplicación (en OPTIMIST ERP: precios, AFIP, Listado de Stock, OPTIMIST SQL, Logueo...)
        // (como antes de la E3: si una falla, no arranca, en lugar de seguir con procedimientos sin registrar)
        for (OptimistExtension e : OptimistExtension.todas()) e.registrarProcedimientos();
        // V48: la tabla extendida de los For Each usa las referencias de las TRN (subtipos: PRSEntidadesPK -> Entidades)
        OptimistProcedimiento.referenciasDeTabla = tabla -> {
            List<OptimistTrnDefinicion.Referencia> r = new ArrayList<>();
            try {
                for (OptimistTrnDefinicion.Trn t : OptimistTrnCatalogo.cargar().trns().values()) {
                    if (t.cabecera().tabla().equalsIgnoreCase(tabla)) r.addAll(t.cabecera().referencias());
                    for (OptimistTrnDefinicion.Nivel d : t.detalles()) if (d.tabla().equalsIgnoreCase(tabla)) r.addAll(d.referencias());
                }
            } catch (RuntimeException e) { LOG.warn("referencias de {}: {}", tabla, e.getMessage()); }
            return r;
        };
        // V48: grupos de subtipos de la KB (subtipos.yaml): la tabla extendida de los For Each como en GeneXus
        try (InputStream in = OptimistRecursos.abrir("subtipos.yaml")) {
            if (in != null) {
                Map<String, Object> y = new org.yaml.snakeyaml.Yaml().load(in);
                Map<String, Map<String, String>> g = new HashMap<>();
                if (y != null) y.forEach((k, v) -> {
                    if (v instanceof Map<?, ?> m) {
                        Map<String, String> mm = new HashMap<>();
                        m.forEach((sub, sup) -> mm.put(String.valueOf(sub), String.valueOf(sup)));
                        g.put(k, mm);
                    }
                });
                OptimistProcedimiento.subtipos(g);
                LOG.info("subtipos.yaml: {} grupos", g.size());
            }
        } catch (Exception e) { LOG.warn("No se pudo leer subtipos.yaml: {}", e.getMessage()); }
        // V66: Link(&Archivo) de GeneXus: baja el archivo que armó el procedimiento (el Excel de los Libros de IVA).
        // Una dirección que no es un archivo armado (http...) no hace nada todavía.
        OptimistProcedimiento.registrarNativo("Link", "in: &Url", (in, c) -> {
            OptimistProcedimiento.bajar(String.valueOf(Objects.requireNonNullElse(in.get("url"), "")));
            return Map.of();
        });
        // V66: la marca de agua de la empresa en el PDF que se acaba de armar (GetFondoDeAguaEmpresa da la imagen y dónde)
        OptimistProcedimiento.registrarNativo("PDFAddWatermark", "in: &PathSource, in: &PathTarget, in: &Image, in: &OffsetX, in: &OffsetY", (in, c) -> {
            String err = OptimistProcedimiento.marcaDeAgua(String.valueOf(Objects.requireNonNullElse(in.get("pathsource"), "")), in.get("image"),
                    OptimistFormula.num(in.get("offsetx")).doubleValue(), OptimistFormula.num(in.get("offsety")).doubleValue());
            if (!err.isEmpty()) LOG.warn("PDFAddWatermark: {}", err);
            return Map.of();
        });
        // V66x: MostrarProgreso.Call(&Texto): lo que va haciendo un proceso largo, en la ventanita "Procesando…" del web panel
        OptimistProcedimiento.registrarNativo("MostrarProgreso", "in: &Texto", (in, c) -> {
            java.util.function.Consumer<String> f = OptimistProcedimiento.PROGRESO.get();
            if (f != null) f.accept(texto(in.get("texto")));
            return Map.of();
        });
        reemplazar("MostrarProgreso");
        // V66x3: AnchoProgreso.Call(&Letras): el ancho fijo de la ventanita (el largo del texto más el de los campos que muestra)
        OptimistProcedimiento.registrarNativo("AnchoProgreso", "in: &Letras", (in, c) -> {
            java.util.function.IntConsumer f = OptimistProcedimiento.PROGRESO_ANCHO.get();
            Object v = in.get("letras");
            if (f != null && v instanceof Number n) f.accept(n.intValue());
            return Map.of();
        });
        reemplazar("AnchoProgreso");
        // V66x4: % de avance. TotalProgreso.Call(&Total): cuántos va a procesar; desde ahí cada MostrarProgreso cuenta uno y la
        // ventanita muestra "Avance: 37 % (123 de 344)". PorcentajeProgreso.Call(&Porcentaje): el % calculado por el procedimiento
        // (cuando el total no se sabe de antemano, ej. varios almacenes).
        OptimistProcedimiento.registrarNativo("TotalProgreso", "in: &Total", (in, c) -> {
            OptimistProcedimiento.PROGRESO_MANUAL.set(true);   // V66z: el procedimiento informa su avance (no el automático)
            java.util.function.IntConsumer f = OptimistProcedimiento.PROGRESO_TOTAL.get();
            Object v = in.get("total");
            if (f != null && v instanceof Number n) f.accept(n.intValue());
            return Map.of();
        });
        reemplazar("TotalProgreso");
        OptimistProcedimiento.registrarNativo("PorcentajeProgreso", "in: &Porcentaje", (in, c) -> {
            OptimistProcedimiento.PROGRESO_MANUAL.set(true);   // V66z
            java.util.function.DoubleConsumer f = OptimistProcedimiento.PROGRESO_PORCENTAJE.get();
            Object v = in.get("porcentaje");
            if (f != null && v instanceof Number n) f.accept(n.doubleValue());
            return Map.of();
        });
        reemplazar("PorcentajeProgreso");
        OptimistProcedimiento.registrarNativo("EnumTexto", "in: &Valor, in: &Valores, out: &Texto", (in, c) -> Map.of("texto", enumTexto(in.get("valor"), in.get("valores"))));
        // V60c: verifica una duplicación: compara la copia con el original en la base (ver compararRegistros)
        OptimistProcedimiento.registrarNativo("CompararRegistros", "in: &Trn, in: &ClaveOri, in: &ClaveDst, in: &Ignorar, out: &Diferencias",
                (in, c) -> Map.of("diferencias", compararRegistros(c, texto(in.get("trn")), texto(in.get("claveori")),
                        texto(in.get("clavedst")), texto(in.get("ignorar")))));
    }

    /** V73b: la imagen PNG de un QR con ese texto (null si no se pudo). */
    static byte[] qrPng(String texto) {
        try {
            Map<com.google.zxing.EncodeHintType, Object> h = new HashMap<>();
            h.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            h.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            h.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            com.google.zxing.common.BitMatrix bm = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(texto, com.google.zxing.BarcodeFormat.QR_CODE, 400, 400, h);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(bm.getWidth(), bm.getHeight(), java.awt.image.BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < bm.getHeight(); y++)
                for (int x = 0; x < bm.getWidth(); x++) img.setRGB(x, y, bm.get(x, y) ? 0x000000 : 0xFFFFFF);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            LOG.warn("QR: {}", e.toString());
            return null;
        }
    }

    static String texto(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    /**
     * V60c: compara un registro de una TRN (la copia) con otro (el original) directo en la base: la cabecera y cada nivel
     * (también los ocultos), con TODAS las columnas de cada tabla, estén o no en el YAML. Los renglones se aparean por su
     * clave propia. No compara la clave de la cabecera, la auditoría (fecha y usuario de alta y modificación) ni las
     * columnas de 'ignorar' (lo que la duplicación cambia a propósito: "TcbteDescripcion,TcbteTipoDocumentoAFIP"). En
     * 'ignorar' también puede ir el nombre (o la tabla) de un nivel entero que no se compara (V61: "ListasPrecios").
     * Claves: los valores de la clave de la cabecera, en orden, separados por ';' ("C;FAC;0001").
     * Devuelve "" si son iguales, o un renglón por diferencia (máximo 40).
     */
    static String compararRegistros(Connection c, String trnNombre, String claveOri, String claveDst, String ignorar) {
        OptimistTrnDefinicion.Trn t = OptimistProcedimiento.trnDe.apply(trnNombre);
        if (t == null) return "CompararRegistros: no existe la TRN " + trnNombre;
        Set<String> ign = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String s : ignorar.split("[,;]")) if (!s.isBlank()) ign.add(s.trim());
        ign.addAll(t.auditoria().values());
        List<OptimistTrnDefinicion.Campo> claves = t.cabecera().claves();
        for (OptimistTrnDefinicion.Campo k : claves) ign.add(OptimistTrnDatos.col(k.nombre()));
        // V61g: los <Trn>Last* (ArticulosLastFile...) son los numeradores en los que GeneXus se apoya para los renglones de
        // los niveles: no se comparan (tampoco los <Nivel>Last* de un nivel).
        List<String> lasts = new ArrayList<>();
        lasts.add(trnNombre.toLowerCase(Locale.ROOT) + "last");
        lasts.add(t.cabecera().tabla().toLowerCase(Locale.ROOT) + "last");
        for (OptimistTrnDefinicion.Nivel d : t.detalles()) {
            lasts.add(d.nombre().toLowerCase(Locale.ROOT) + "last");
            lasts.add(d.tabla().toLowerCase(Locale.ROOT) + "last");
        }
        List<String> vo = new ArrayList<>(), vd = new ArrayList<>();
        for (String s : claveOri.split(";", -1)) vo.add(s.trim());
        for (String s : claveDst.split(";", -1)) vd.add(s.trim());
        if (vo.size() != claves.size() || vd.size() != claves.size())
            return "CompararRegistros: la clave de " + trnNombre + " tiene " + claves.size() + " valores";
        List<String> dif = new ArrayList<>();
        try {
            String donde = claves.stream().map(k -> OptimistTrnDatos.col(k.nombre()) + "=?").collect(java.util.stream.Collectors.joining(" AND "));
            List<Map<String, Object>> a = filasDe(c, t.cabecera().tabla(), donde, vo, null), b = filasDe(c, t.cabecera().tabla(), donde, vd, null);
            if (a.isEmpty()) return "CompararRegistros: no se encontró el original " + claveOri;
            if (b.isEmpty()) return "CompararRegistros: no se encontró la copia " + claveDst;
            compararFila(t.titulo(), a.get(0), b.get(0), ign, lasts, dif);
            for (OptimistTrnDefinicion.Nivel d : t.detalles()) {
                if (ign.contains(d.nombre()) || ign.contains(d.tabla())) continue;   // V61: un nivel entero que no se compara
                List<String> propias = d.claves().stream().map(k -> OptimistTrnDatos.col(k.nombre())).toList();
                String orden = propias.isEmpty() ? null : String.join(",", propias);
                List<Map<String, Object>> la = filasDe(c, d.tabla(), donde, vo, orden), lb = filasDe(c, d.tabla(), donde, vd, orden);
                if (la.size() != lb.size())
                    dif.add(d.titulo() + ": el original tiene " + la.size() + " renglones y la copia " + lb.size());
                Map<String, Map<String, Object>> pa = new LinkedHashMap<>(), pb = new LinkedHashMap<>();
                for (int i = 0; i < la.size(); i++) pa.put(propias.isEmpty() ? "#" + (i + 1) : claveDe(la.get(i), propias), la.get(i));
                for (int i = 0; i < lb.size(); i++) pb.put(propias.isEmpty() ? "#" + (i + 1) : claveDe(lb.get(i), propias), lb.get(i));
                for (var e : pa.entrySet()) {
                    Map<String, Object> y = pb.get(e.getKey());
                    if (y == null) dif.add(d.titulo() + " [" + e.getKey() + "]: falta en la copia");
                    else compararFila(d.titulo() + " [" + e.getKey() + "]", e.getValue(), y, ign, lasts, dif);
                }
                for (String k : pb.keySet()) if (!pa.containsKey(k)) dif.add(d.titulo() + " [" + k + "]: sobra en la copia");
            }
        } catch (SQLException e) {
            return "CompararRegistros: " + e.getMessage();
        }
        if (dif.size() > 40) { int n = dif.size(); dif = new ArrayList<>(dif.subList(0, 40)); dif.add("... y " + (n - 40) + " diferencias más"); }
        return String.join("\n", dif);
    }

    private static List<Map<String, Object>> filasDe(Connection c, String tabla, String donde, List<String> valores, String orden) throws SQLException {
        List<Map<String, Object>> r = new ArrayList<>();
        try (java.sql.PreparedStatement p = c.prepareStatement("SELECT * FROM " + tabla + " WHERE " + donde + (orden == null ? "" : " ORDER BY " + orden))) {
            for (int i = 0; i < valores.size(); i++) p.setString(i + 1, valores.get(i));
            try (java.sql.ResultSet rs = p.executeQuery()) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> f = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    for (int i = 1; i <= md.getColumnCount(); i++) f.put(md.getColumnLabel(i), rs.getObject(i));
                    r.add(f);
                }
            }
        }
        return r;
    }

    private static String claveDe(Map<String, Object> f, List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (String k : cols) sb.append(sb.length() == 0 ? "" : "/").append(String.valueOf(f.get(k)).trim());
        return sb.toString();
    }

    private static void compararFila(String donde, Map<String, Object> a, Map<String, Object> b, Set<String> ign, List<String> lasts,
                                     List<String> dif) {
        for (var e : a.entrySet()) {
            if (ign.contains(e.getKey())) continue;
            String col = e.getKey().toLowerCase(Locale.ROOT);
            if (lasts.stream().anyMatch(col::startsWith)) continue;
            Object x = e.getValue(), y = b.get(e.getKey());
            if (!igualBase(x, y)) dif.add(donde + ": " + e.getKey() + " original '" + mostrarBase(x) + "', copia '" + mostrarBase(y) + "'");
        }
    }

    /** V65a: como GeneXus, NULL es igual al vacío de su tipo ('' / 0 / false / fecha vacía): el motor graba NULL en los
     *  campos nulo:true y los registros viejos de GeneXus tienen '' (o NULL donde la columna se agregó después). */
    private static boolean igualBase(Object x, Object y) {
        if (vacioBase(x) && vacioBase(y)) return true;
        if (x == null || y == null) return false;
        Object nx0 = numeroBase(x), ny0 = numeroBase(y);
        if (nx0 instanceof Number nx && ny0 instanceof Number ny) return new java.math.BigDecimal(nx.toString()).compareTo(new java.math.BigDecimal(ny.toString())) == 0;
        if (x instanceof byte[] bx && y instanceof byte[] by) return Arrays.equals(bx, by);
        if (x instanceof String sx && y instanceof String sy) return sx.stripTrailing().equals(sy.stripTrailing());
        return x.equals(y) || String.valueOf(x).equals(String.valueOf(y));
    }

    /** Vacío al estilo GeneXus: NULL, texto en blanco, 0, false (también BIT(1) = 0) o la fecha vacía (1000-01-01). */
    private static boolean vacioBase(Object x) {
        if (x == null) return true;
        if (x instanceof String s) return s.isBlank();
        if (x instanceof Boolean b) return !b;
        if (x instanceof Number n) return new java.math.BigDecimal(n.toString()).signum() == 0;
        if (x instanceof byte[] b) { for (byte v : b) if (v != 0) return false; return true; }
        return OptimistTrnDatos.fechaVacia(x);
    }

    /** Booleano o BIT(1) como número (1 / 0), para comparar false con 0 y true con 1; lo demás tal cual. */
    private static Object numeroBase(Object x) {
        if (x instanceof Boolean b) return b ? 1 : 0;
        if (x instanceof byte[] b && b.length == 1) return (int) b[0];
        return x;
    }

    private static String mostrarBase(Object x) {
        if (x == null) return "(nulo)";
        if (x instanceof byte[] b) return b.length == 1 ? String.valueOf(b[0]) : b.length + " bytes";
        String s = String.valueOf(x).stripTrailing();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    /** "1=Activo;2=Inactivo" + 2 -> "Inactivo" (compara como texto y como número). Si no está, el valor tal cual. */
    static String enumTexto(Object valor, Object valores) {
        String v = valor == null ? "" : String.valueOf(valor).trim();
        for (String par : String.valueOf(valores == null ? "" : valores).split(";")) {
            int i = par.indexOf('=');
            if (i < 0) continue;
            String k = par.substring(0, i).trim();
            boolean igual = k.equalsIgnoreCase(v);
            if (!igual) try { igual = new java.math.BigDecimal(k).compareTo(new java.math.BigDecimal(v)) == 0; } catch (NumberFormatException ignorado) { }
            if (igual) return par.substring(i + 1).trim();
        }
        return v;
    }

    // ------------------------------------------------------------------ V49: diseños de impresión (impresiones/*.yaml)

    private record DisenoLeido(OptimistImpresion.Diseno diseno, long fecha) {}
    private static final Map<String, DisenoLeido> DISENOS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Fecha del impresiones/*.yaml con la que se leyó cada .proc (si el diseño cambia, se relee el .proc: sus atributos). */
    private static final Map<String, Long> PRN_DEL_PROC = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Map<String, Resource> prns;
    private static volatile long prnsListados;

    private static Map<String, Resource> prns() {
        Map<String, Resource> m = prns;
        if (m != null && (!OptimistRecursos.desarrollo() || System.currentTimeMillis() - prnsListados < REVISAR_CADA_MS)) return m;
        Map<String, Resource> r = new HashMap<>();
        try {
            for (Resource x : OptimistRecursos.listar("impresiones", ".yaml")) {
                String a = x.getFilename();
                r.put(a.substring(0, a.length() - ".yaml".length()).toLowerCase(Locale.ROOT), x);
            }
        } catch (Exception e) { LOG.warn("No se pudieron listar las impresiones (impresiones/*.yaml): {}", e.getMessage()); }
        prns = r; prnsListados = System.currentTimeMillis();
        return r;
    }

    private static long fechaPrn(String clave) {
        Resource r = prns().get(clave);
        return r == null ? -1 : fecha(r);
    }

    /** El diseño de impresión del procedimiento (impresiones/<nombre>.yaml), o null si no imprime. Se relee si el archivo cambió. */
    public static OptimistImpresion.Diseno diseno(String nombre) {
        String k = nombre.toLowerCase(Locale.ROOT);
        Resource r = prns().get(k);
        if (r == null) return null;
        long f = fecha(r);
        DisenoLeido d = DISENOS.get(k);
        if (d != null && d.fecha() == f) return d.diseno();
        try (InputStream in = r.getInputStream()) {
            Map<String, Object> y = new org.yaml.snakeyaml.Yaml().load(in);
            String archivo = r.getFilename();
            OptimistImpresion.Diseno nuevo = OptimistImpresion.de(archivo.substring(0, archivo.length() - ".yaml".length()), y == null ? Map.of() : y);
            DISENOS.put(k, new DisenoLeido(nuevo, f));
            return nuevo;
        } catch (Exception e) {
            throw new IllegalStateException("impresiones/" + r.getFilename() + ": " + e.getMessage(), e);
        }
    }

    /** Asegura que los procedimientos nativos (LoadWWPContext...) estén registrados. */
    static void asegurarNativos() { }

    /** procs: por nombre en minúscula. errores: archivo -> motivo (el .proc con errores no se puede usar). */
    public record Resultado(Map<String, OptimistProcedimiento> procs, Map<String, String> errores) {}
    /** recursos/fechas: el archivo de cada proc y su fecha al leerlo (en desarrollo se mira solo el que se usa). */
    private record Cache(String firma, Resultado resultado, long revisado, Map<String, Resource> recursos, Map<String, Long> fechas) {}
    private static volatile Cache cache;
    /** V36: la TRN se mira como mucho cada 2 segundos (en desarrollo). */
    static final long REVISAR_CADA_MS = 2000;
    /** V37: la carpeta entera de procedimientos (para ver si hay nuevos) como mucho cada 30 segundos, en desarrollo. */
    static final long REVISAR_CARPETA_MS = 30_000;

    public static Resultado cargar() { return cargar(false); }

    private static synchronized Resultado cargar(boolean forzar) {
        Cache previo = cache;
        if (!forzar && previo != null && (!OptimistRecursos.desarrollo() || System.currentTimeMillis() - previo.revisado() < REVISAR_CARPETA_MS))
            return previo.resultado();   // V36: antes se listaba la carpeta (o el WAR) en CADA llamada a un procedimiento
        Resource[] archivos;
        try { archivos = OptimistRecursos.listar("proc", ".proc"); }
        catch (Exception e) {
            LOG.error("No se pudieron listar los procedimientos", e);
            return new Resultado(Map.of(), Map.of("proc/*.proc", String.valueOf(e.getMessage())));
        }
        StringBuilder b = new StringBuilder();
        Map<String, Resource> recursos = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, Long> fechas = new java.util.concurrent.ConcurrentHashMap<>();
        for (Resource r : archivos) {
            long f = fecha(r);
            String archivo = r.getFilename();
            String clave = archivo.substring(0, archivo.length() - ".proc".length()).toLowerCase(Locale.ROOT);
            recursos.put(clave, r);
            fechas.put(clave, f);
            b.append(archivo).append('@').append(f).append(';');
        }
        String firma = b.toString();
        long ahora = System.currentTimeMillis();
        if (previo != null && firma.equals(previo.firma())) {
            cache = new Cache(firma, previo.resultado(), ahora, recursos, fechas);
            return previo.resultado();
        }
        Map<String, OptimistProcedimiento> procs = new TreeMap<>();
        Map<String, String> errores = new TreeMap<>();
        for (Resource r : archivos) leerUno(r, procs, errores);
        Resultado nuevo = new Resultado(Collections.unmodifiableMap(procs), Collections.unmodifiableMap(errores));
        cache = new Cache(firma, nuevo, ahora, recursos, fechas);
        return nuevo;
    }

    private static long fecha(Resource r) {
        try { return r.lastModified(); } catch (Exception e) { return -1; }
    }

    private static void leerUno(Resource r, Map<String, OptimistProcedimiento> procs, Map<String, String> errores) {
        String archivo = r.getFilename();
        String nombre = archivo.substring(0, archivo.length() - ".proc".length());
        procs.remove(nombre.toLowerCase(Locale.ROOT));
        errores.remove(archivo);
        try (InputStream in = r.getInputStream()) {
            PRN_DEL_PROC.put(nombre.toLowerCase(Locale.ROOT), fechaPrn(nombre.toLowerCase(Locale.ROOT)));   // V49
            procs.put(nombre.toLowerCase(Locale.ROOT), OptimistProcedimiento.leer(nombre, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            errores.put(archivo, e.getMessage());
            LOG.error("Procedimiento {}: {}", archivo, e.getMessage());
        }
    }

    /**
     * V37: en desarrollo, si el .proc que se va a usar cambió desde que se leyó, se relee solo ese
     * (una consulta de fecha por llamada, en lugar de revisar los 650 archivos).
     */
    private static synchronized void refrescarSiCambio(String clave) {
        Cache c = cache;
        if (c == null || !OptimistRecursos.desarrollo()) return;
        Resource r = c.recursos().get(clave);
        if (r == null) return;
        long f = fecha(r);
        // V49: también si cambió su diseño de impresión (los atributos que trae cada "print" salen del diseño)
        if (Objects.equals(c.fechas().get(clave), f) && Objects.equals(PRN_DEL_PROC.getOrDefault(clave, -1L), fechaPrn(clave))) return;
        Map<String, OptimistProcedimiento> procs = new TreeMap<>(c.resultado().procs());
        Map<String, String> errores = new TreeMap<>(c.resultado().errores());
        leerUno(r, procs, errores);
        c.fechas().put(clave, f);
        cache = new Cache(c.firma() + "~", new Resultado(Collections.unmodifiableMap(procs), Collections.unmodifiableMap(errores)),
                c.revisado(), c.recursos(), c.fechas());
    }

    /** El procedimiento, o null si no hay proc/<nombre>.proc. Si el archivo tiene errores, los informa. */
    static void reemplazar(String nombre) { REEMPLAZOS.add(nombre.toLowerCase(Locale.ROOT)); }

    public static OptimistProcedimiento buscar(String nombre) {
        String clave = nombre.toLowerCase(Locale.ROOT);
        if (REEMPLAZOS.contains(clave)) { OptimistProcedimiento n = OptimistProcedimiento.nativo(nombre); if (n != null) return n; }
        Resultado r = cargar();
        if (OptimistRecursos.desarrollo()) {
            refrescarSiCambio(clave);
            r = cache.resultado();
            // un procedimiento que no estaba: quizás el archivo es nuevo (se relee la carpeta, como mucho cada 2 segundos)
            if (!r.procs().containsKey(clave) && System.currentTimeMillis() - cache.revisado() > REVISAR_CADA_MS) r = cargar(true);
        }
        OptimistProcedimiento p = r.procs().get(clave);
        if (p != null) return p;
        for (var e : r.errores().entrySet())
            if (e.getKey().equalsIgnoreCase(nombre + ".proc")) throw new IllegalStateException(e.getValue());
        // V48: un nativo (LoadWWPContext...) se registra al inicializar esta clase: si el intérprete lo buscó antes, está acá
        OptimistProcedimiento n = OptimistProcedimiento.nativo(nombre);
        if (n != null) return n;
        return navegacion(nombre);
    }

    /**
     * V48: un procedimiento de GeneXus que al final navega a otra pantalla: WWPED.Call() (volver al Trabajar con)
     * no hace nada (la pantalla que llamó ya actualiza su lista); NotAuthorized.Call(...) corta con "No autorizado".
     */
    private static OptimistProcedimiento navegacion(String nombre) {
        if (nombre.matches("(?i)WW[A-Z][A-Za-z0-9_]*")) {
            OptimistProcedimiento p = OptimistProcedimiento.nativo(nombre);
            if (p != null) return p;
            OptimistProcedimiento.registrarNativo(nombre, "", (in, c) -> Map.of());
            return OptimistProcedimiento.nativo(nombre);
        }
        // V48: Trn.Call(TrnMode.Update, &Clave) dentro de un procedimiento: la pantalla abre esa TRN al terminar
        OptimistTrnDefinicion.Trn t = OptimistProcedimiento.trnDe.apply(nombre);
        if (t != null) {
            OptimistProcedimiento p = OptimistProcedimiento.nativo(nombre);
            if (p != null) return p;
            String nom = t.nombre();
            OptimistProcedimiento.registrarNativo(nom, "in: &Modo, in: &Clave", (in, c) -> {
                // V58: con el modo (TrnMode.Display = "DSP": se abre en Visualizar, ej. Ver Despacho)
                OptimistProcedimiento.TRN_A_ABRIR.set(new Object[]{nom, in.get("clave"), in.get("modo")});
                return Map.of();
            });
            return OptimistProcedimiento.nativo(nom);
        }
        if (nombre.equalsIgnoreCase("NotAuthorized")) {
            OptimistProcedimiento p = OptimistProcedimiento.nativo(nombre);
            if (p != null) return p;
            OptimistProcedimiento.registrarNativo("NotAuthorized", "in: &Programa", (in, c) -> {
                throw new IllegalStateException("No está autorizado para " + in.getOrDefault("programa", "esta acción"));
            });
            return OptimistProcedimiento.nativo(nombre);
        }
        return null;
    }

    public static boolean existe(String nombre) {
        if (OptimistProcedimiento.nativo(nombre) != null) return true;
        try { return buscar(nombre) != null; } catch (RuntimeException conErrores) { return true; }
    }

    /** Ejecuta con los parámetros en orden. Devuelve los out/inout (nombre en minúscula); los Msg() van a mensajes. */
    /**
     * Todo el procedimiento es una transacción (commit al terminar, rollback si hay un error). V51f: si la conexión ya está en
     * una transacción (una regla que llama a un procedimiento mientras se graba la TRN), no confirma ni deshace: lo decide
     * quien abrió la transacción, al final de toda la operación.
     */
    public static Map<String, Object> ejecutar(String nombre, List<Object> args, Connection c, List<String> mensajes) throws SQLException {
        OptimistProcedimiento p = buscarONativo(nombre);
        boolean auto = c.getAutoCommit();
        if (!auto) return p.ejecutar(args, c, OptimistProcedimientos::buscar, mensajes, 0);
        c.setAutoCommit(false);
        try {
            Map<String, Object> r = p.ejecutar(args, c, OptimistProcedimientos::buscar, mensajes, 0);
            c.commit();
            return r;
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignorado) { }
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static OptimistProcedimiento buscarONativo(String nombre) {
        OptimistProcedimiento p = OptimistProcedimiento.nativo(nombre);
        if (p == null) p = buscar(nombre);
        if (p == null) throw new IllegalStateException("No existe el procedimiento " + nombre + " (falta proc/" + nombre + ".proc)");
        return p;
    }

    /**
     * V55: si el proceso está habilitado para el usuario ({proceso: N} de los YAML). E3: lo contesta la aplicación
     * (OptimistExtension.procesoHabilitado; OPTIMIST ERP: GetUsrProc); sin ninguna que conteste, no.
     */
    public static boolean procesoHabilitado(Connection c, int id) {
        for (OptimistExtension e : OptimistExtension.todas()) {
            Boolean b = e.procesoHabilitado(c, id);
            if (b != null) return b;
        }
        return false;
    }

    /** Como una función (Nombre.udp(...)): devuelve el último parámetro out/inout. */
    public static Object udp(String nombre, List<Object> args, Connection c) throws SQLException {
        OptimistProcedimiento p = buscarONativo(nombre);
        Map<String, Object> salida = ejecutar(nombre, args, c, new ArrayList<>());
        for (int k = p.parametros.size() - 1; k >= 0; k--)
            if (!p.parametros.get(k).modo().equals("in")) return salida.get(p.parametros.get(k).nombre().toLowerCase(Locale.ROOT));
        return null;
    }
}
