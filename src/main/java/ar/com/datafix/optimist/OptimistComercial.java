package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Campo;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Nivel;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Trn;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

/**
 * V36 - Cálculo comercial de un comprobante (presupuesto, pedido, factura...), en Java y en un orden fijo,
 * en lugar de las reglas de GeneXus que dependían del orden en que se disparaban. Sección 'calculo:' del YAML:
 *
 *   calculo:
 *     detalle: Det
 *     decimales: "&Decimales"                    (decimales de los precios; si no hay, lo que diga la aplicación: en el ERP, el parámetro 30)
 *     ivaIncluido: "PRSCatIvaDesglosa = false"   (el precio trae el IVA: factura B)
 *     afectaIva: "PRSAfectaIva"                  (si no, el IVA es 0)
 *     linea:
 *       cantidad: PRSDetCantidad       bultos: PRSDetBultos      porBultos: "ArticulosManejoStock = 1"
 *       precio: PRSDetPrecio           descuento: PRSDetDescuento   tasaIva: PRSDetTasasIvaTasa
 *       precioNeto: PRSDetPrecioNeto   precioConDto: PRSDetPrecioNetoConDto   valorDescuento: PRSDetValorDescuento
 *       subtotal: PRSDetSubtotal       iva: PRSDetImporteIva
 *       noCalcular: "ArticulosTotalizadorItemizado = true"   (el subtotal lo calcula el itemizado al grabar)
 *     itemizado:                         (V50w: presupuestos / pedidos itemizados, recálculo en pantalla)
 *       activo: PRSItemizado   item: PRSDetItem   totalizador: ArticulosTotalizadorItemizado
 *     pie:
 *       base: PRSSubtotal               (expresión: sobre qué se aplica el descuento de pie)
 *       porcentajeDescuento: PRSPorBonPie   descuento: PRSBonPie
 *       porcentajeRecargo: PRSPorRecPie     recargo: PRSRecPie
 *
 * Renglón (redondeo de GeneXus: precios con 'decimales', importes a 2, cada renglón):
 *   precio neto   = precio / (1 + tasa/100) si el precio trae IVA; si no, el precio
 *   descuento     = cascada "20+10+5" sobre el precio neto (como AplicarDescuentos) -> precio con descuento
 *   subtotal      = cantidad (o bultos) x precio con descuento
 *   IVA           = subtotal x tasa / 100 (0 si no afecta IVA)
 *   valor descuento = descuento por unidad x cantidad (o bultos)
 * Itemizado (como TotalizarIva al grabar): por cada ítem, el renglón totalizador toma
 *   precio = suma de cantidad x precio de sus componentes; precio neto (y con descuento) = suma de cantidad x precio neto;
 *   subtotal = cantidad del totalizador x ese precio neto; IVA = cantidad del totalizador x suma del IVA de los componentes.
 * Pie: descuento en cascada sobre la base; recargo en cascada sobre (base - descuento).
 * Los netos e IVA por tasa y el total siguen siendo fórmulas de la TRN (sum() de los renglones).
 */
public final class OptimistComercial {

    final Trn trn;
    final Nivel detalle;
    final String decimales, ivaIncluido, afectaIva, porBultos, noCalcular;
    /** V50w: itemizado: activo (expresión de cabecera), item y totalizador (campos / expresiones del renglón); null si no hay. */
    final String itemActivo, itemCampo, itemTotalizador;
    final Map<String, String> linea;   // rol -> campo
    final Map<String, String> pie;     // rol -> campo o expresión

    static final List<String> ROLES_LINEA = List.of("cantidad", "bultos", "precio", "descuento", "tasaIva",
            "precioNeto", "precioConDto", "valorDescuento", "subtotal", "iva");
    static final List<String> SALIDAS_LINEA = List.of("precioNeto", "precioConDto", "valorDescuento", "subtotal", "iva");
    static final List<String> ROLES_PIE = List.of("base", "porcentajeDescuento", "descuento", "porcentajeRecargo", "recargo");

    private static final Map<Trn, OptimistComercial> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /** El cálculo de la TRN, o null si no tiene 'calculo:'. IllegalArgumentException si está mal armado. */
    public static OptimistComercial de(Trn trn) {
        if (trn.calculo() == null) return null;
        return CACHE.computeIfAbsent(trn, OptimistComercial::new);
    }

    @SuppressWarnings("unchecked")
    private OptimistComercial(Trn trn) {
        this.trn = trn;
        Map<String, Object> c = trn.calculo();
        String det = texto(c.get("detalle"));
        this.detalle = det == null ? (trn.detalles().isEmpty() ? null : trn.detalles().get(0)) : trn.detalle(det);
        if (detalle == null) throw error("detalle '" + det + "' no existe");
        this.decimales = texto(c.get("decimales"));
        this.ivaIncluido = texto(c.get("ivaIncluido"));
        this.afectaIva = texto(c.get("afectaIva"));
        Map<String, Object> l = c.get("linea") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        this.porBultos = texto(l.get("porBultos"));
        this.noCalcular = texto(l.get("noCalcular"));
        Map<String, String> ls = new LinkedHashMap<>();
        for (var e : l.entrySet()) {
            if (e.getKey().equals("porBultos") || e.getKey().equals("noCalcular")) continue;
            if (!ROLES_LINEA.contains(e.getKey())) throw error("linea." + e.getKey() + ": no se conoce (" + String.join(", ", ROLES_LINEA) + ")");
            String campo = texto(e.getValue());
            Campo cp = detalle.campo(campo);
            if (cp == null) throw error("linea." + e.getKey() + ": el detalle " + detalle.nombre() + " no tiene " + campo);
            ls.put(e.getKey(), cp.nombre());
        }
        for (String obligatorio : List.of("cantidad", "precio", "subtotal"))
            if (!ls.containsKey(obligatorio)) throw error("falta linea." + obligatorio);
        this.linea = Collections.unmodifiableMap(ls);
        Map<String, Object> p = c.get("pie") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, String> ps = new LinkedHashMap<>();
        for (var e : p.entrySet()) {
            if (!ROLES_PIE.contains(e.getKey())) throw error("pie." + e.getKey() + ": no se conoce (" + String.join(", ", ROLES_PIE) + ")");
            String v = texto(e.getValue());
            if (!e.getKey().equals("base") && trn.cabecera().campo(v) == null) throw error("pie." + e.getKey() + ": la cabecera no tiene " + v);
            ps.put(e.getKey(), e.getKey().equals("base") ? v : trn.cabecera().campo(v).nombre());
        }
        this.pie = Collections.unmodifiableMap(ps);
        Map<String, Object> it = c.get("itemizado") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        this.itemActivo = texto(it.get("activo"));
        this.itemCampo = texto(it.get("item"));
        this.itemTotalizador = texto(it.get("totalizador"));
        if (!it.isEmpty() && (itemActivo == null || itemCampo == null || itemTotalizador == null))
            throw error("itemizado: faltan activo, item o totalizador");
        // las expresiones se validan al leer la TRN
        for (String x : new String[]{decimales, ivaIncluido, afectaIva, porBultos, noCalcular, ps.get("base"), itemActivo, itemCampo, itemTotalizador})
            if (x != null) try { OptimistFormula.de(x); } catch (IllegalArgumentException ex) { throw error(ex.getMessage()); }
    }

    private static String texto(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim(); }
    private IllegalArgumentException error(String m) { return new IllegalArgumentException("calculo: " + m); }

    public Nivel detalle() { return detalle; }

    /** Campos que calcula (no los tocan las reglas de GeneXus). */
    public Set<String> salidas() {
        Set<String> r = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String s : SALIDAS_LINEA) if (linea.containsKey(s)) r.add(linea.get(s));
        if (pie.containsKey("descuento")) r.add(pie.get("descuento"));
        if (pie.containsKey("recargo")) r.add(pie.get("recargo"));
        return r;
    }

    // ------------------------------------------------------------------ cálculo

    /** Contexto para evaluar las expresiones: renglón, cabecera, &variables del formulario, find() en la base. */
    private record Ctx(Map<String, Object> linea, Map<String, Object> cab, Function<String, Object> vars,
                       OptimistCalculo.Abridor abridor) implements OptimistFormula.Contexto {
        public Object valor(String a) {
            if (a.startsWith("&")) return vars == null ? null : vars.apply(a.substring(1));
            for (Map<String, Object> m : new Map[]{linea, cab}) {
                if (m == null) continue;
                for (var e : m.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(a)) return e.getValue();
            }
            return null;
        }
        public Object procedimiento(String nombre, List<Object> args) {
            Object b = OptimistProcedimiento.Funciones.llamar(nombre, args);
            if (b != OptimistProcedimiento.Funciones.NO_ES) return b;
            throw new IllegalStateException("calculo: la función " + nombre + " no se puede usar acá");
        }
        public Object buscar(String tabla, String columna, Map<String, Object> igualdades) {
            if (abridor == null) return null;
            try (var c = abridor.abrir()) { return OptimistCalculo.buscar(c, tabla, columna, igualdades); }
            catch (SQLException e) { throw new IllegalStateException("find: " + e.getMessage(), e); }
        }
    }

    private static Object eval(String expr, Ctx c) { return expr == null ? null : OptimistFormula.de(expr).evaluar(c); }

    /** Decimales de los precios: la expresión 'decimales' (ej. &Decimales) o lo que diga la aplicación (E3; OPTIMIST ERP: el parámetro global 30). */
    public int decimales(Map<String, Object> cab, Function<String, Object> vars, OptimistCalculo.Abridor abridor) {
        Ctx c = new Ctx(null, cab, vars, abridor);
        Object v = null;
        try { v = eval(decimales, c); } catch (RuntimeException ignorado) { }
        if (v == null || OptimistFormula.num(v).signum() == 0 && decimales == null)
            for (OptimistExtension e : OptimistExtension.todas()) {
                OptimistExtension.Buscar b = e.decimalesPrecios();
                if (b == null) continue;
                try { v = c.buscar(b.tabla(), b.columna(), b.clave()); } catch (RuntimeException ignorado) { }
                break;
            }
        return v == null ? 2 : Math.max(0, Math.min(6, OptimistFormula.num(v).intValue()));
    }

    /** Calcula un renglón. Devuelve los valores de las salidas (campo -> valor). */
    public Map<String, Object> linea(Map<String, Object> l, Map<String, Object> cab, int dec, Function<String, Object> vars) {
        Map<String, Object> r = new LinkedHashMap<>();
        Ctx c = new Ctx(l, cab, vars, null);
        // renglón totalizador del itemizado: el subtotal lo pone el cálculo posterior a grabar (DetSubtotalPRS lo deja como está)
        boolean fijo = noCalcular != null && OptimistFormula.verdad(eval(noCalcular, c));
        BigDecimal precio = num(c.valor(linea.get("precio")));
        BigDecimal tasa = linea.containsKey("tasaIva") ? num(c.valor(linea.get("tasaIva"))) : BigDecimal.ZERO;
        boolean afecta = afectaIva == null || OptimistFormula.verdad(eval(afectaIva, c));
        if (!afecta) tasa = BigDecimal.ZERO;
        boolean incluido = ivaIncluido != null && OptimistFormula.verdad(eval(ivaIncluido, c));
        boolean bultos = porBultos != null && linea.containsKey("bultos") && OptimistFormula.verdad(eval(porBultos, c));
        BigDecimal unidades = num(c.valor(linea.get(bultos ? "bultos" : "cantidad")));

        BigDecimal neto = incluido && tasa.signum() != 0
                ? precio.divide(BigDecimal.ONE.add(tasa.movePointLeft(2)), MathContext.DECIMAL64) : precio;
        neto = neto.setScale(dec, RoundingMode.HALF_UP);
        String dto = linea.containsKey("descuento") ? texto(c.valor(linea.get("descuento"))) : null;
        BigDecimal dtoUnidad = descuentoCascada(neto, dto, dec);
        BigDecimal conDto = neto.subtract(dtoUnidad).setScale(dec, RoundingMode.HALF_UP);
        BigDecimal subtotal = fijo ? num(c.valor(linea.get("subtotal"))).setScale(2, RoundingMode.HALF_UP)
                                   : unidades.multiply(conDto).setScale(2, RoundingMode.HALF_UP);
        BigDecimal iva = subtotal.multiply(tasa).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        BigDecimal valorDto = dtoUnidad.multiply(unidades).setScale(dec, RoundingMode.HALF_UP);

        poner(r, "precioNeto", neto);
        poner(r, "precioConDto", conDto);
        poner(r, "valorDescuento", valorDto);
        if (!fijo) poner(r, "subtotal", subtotal);
        poner(r, "iva", iva);
        return r;
    }

    private void poner(Map<String, Object> r, String rol, BigDecimal v) {
        String campo = linea.get(rol);
        if (campo == null) return;
        Campo cp = detalle.campo(campo);
        r.put(campo, cp == null ? v : OptimistCalculo.redondear(cp, v));
    }

    /**
     * V50w: renglones totalizadores del itemizado, como TotalizarIva al grabar: índice del renglón -> valores (campo -> valor).
     * Vacío si la TRN no tiene 'itemizado' o el comprobante no es itemizado.
     */
    public Map<Integer, Map<String, Object>> itemizar(List<Map<String, Object>> lineas, Map<String, Object> cab, Function<String, Object> vars) {
        Map<Integer, Map<String, Object>> r = new LinkedHashMap<>();
        if (itemActivo == null || !OptimistFormula.verdad(eval(itemActivo, new Ctx(null, cab, vars, null)))) return r;
        Map<BigDecimal, BigDecimal[]> sumas = new HashMap<>();   // ítem -> {precio, precio neto, iva} de sus componentes
        List<Integer> totalizadores = new ArrayList<>();
        List<BigDecimal> items = new ArrayList<>();
        for (int i = 0; i < lineas.size(); i++) {
            Ctx c = new Ctx(lineas.get(i), cab, vars, null);
            BigDecimal item = num(eval(itemCampo, c)).stripTrailingZeros();
            items.add(item);
            if (OptimistFormula.verdad(eval(itemTotalizador, c))) { totalizadores.add(i); continue; }
            BigDecimal cant = num(c.valor(linea.get("cantidad")));
            BigDecimal[] s = sumas.computeIfAbsent(item, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            s[0] = s[0].add(cant.multiply(num(c.valor(linea.get("precio")))));
            s[1] = s[1].add(cant.multiply(num(linea.containsKey("precioNeto") ? c.valor(linea.get("precioNeto")) : c.valor(linea.get("precio")))));
            if (linea.containsKey("iva")) s[2] = s[2].add(num(c.valor(linea.get("iva"))));
        }
        for (int i : totalizadores) {
            BigDecimal[] s = sumas.getOrDefault(items.get(i), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal cant = num(new Ctx(lineas.get(i), cab, vars, null).valor(linea.get("cantidad")));
            Map<String, Object> v = new LinkedHashMap<>();
            ponerEn(v, "precio", s[0]);
            ponerEn(v, "precioNeto", s[1]);
            ponerEn(v, "precioConDto", s[1]);
            ponerEn(v, "subtotal", cant.multiply(s[1]).setScale(2, RoundingMode.HALF_UP));
            ponerEn(v, "iva", cant.multiply(s[2]).setScale(2, RoundingMode.HALF_UP));
            r.put(i, v);
        }
        return r;
    }

    private void ponerEn(Map<String, Object> r, String rol, BigDecimal v) {
        String campo = linea.get(rol);
        if (campo == null) return;
        Campo cp = detalle.campo(campo);
        r.put(campo, cp == null ? v : OptimistCalculo.redondear(cp, v));
    }

    /** Descuento y recargo de pie (campo -> valor). Vacío si la TRN no tiene 'pie'. */
    public Map<String, Object> pie(Map<String, Object> cab, int dec, Function<String, Object> vars) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (!pie.containsKey("base")) return r;
        Ctx c = new Ctx(null, cab, vars, null);
        BigDecimal base = num(eval(pie.get("base"), c));
        BigDecimal desc = BigDecimal.ZERO;
        if (pie.containsKey("descuento")) {
            String por = pie.containsKey("porcentajeDescuento") ? texto(c.valor(pie.get("porcentajeDescuento"))) : null;
            desc = descuentoCascada(base, por, dec);
            r.put(pie.get("descuento"), redondearCab(pie.get("descuento"), desc));
        }
        if (pie.containsKey("recargo")) {
            String por = pie.containsKey("porcentajeRecargo") ? texto(c.valor(pie.get("porcentajeRecargo"))) : null;
            r.put(pie.get("recargo"), redondearCab(pie.get("recargo"), recargoCascada(base.subtract(desc), por, dec)));
        }
        return r;
    }

    private Object redondearCab(String campo, BigDecimal v) {
        Campo cp = trn.cabecera().campo(campo);
        return cp == null ? v : OptimistCalculo.redondear(cp, v);
    }

    // ------------------------------------------------------------------ descuentos en cascada (como AplicarDescuentos / AplicarRecargos)

    /** Cuánto se descuenta de 'importe' con "20+10+5" (cada uno sobre lo que queda). Cero si no hay descuento. */
    public static BigDecimal descuentoCascada(BigDecimal importe, String porcentajes, int dec) {
        List<BigDecimal> ps = porcentajes(porcentajes);
        if (ps.isEmpty()) return BigDecimal.ZERO;
        BigDecimal temp = importe;
        for (BigDecimal p : ps)   // como GeneXus: el importe intermedio queda con 4 decimales
            temp = temp.multiply(BigDecimal.valueOf(100).subtract(p)).divide(BigDecimal.valueOf(100), 4, RoundingMode.DOWN);
        temp = temp.setScale(dec, RoundingMode.HALF_UP);
        return importe.subtract(temp).setScale(dec, RoundingMode.HALF_UP);
    }

    /** Cuánto se recarga a 'importe' con "10+5". */
    public static BigDecimal recargoCascada(BigDecimal importe, String porcentajes, int dec) {
        List<BigDecimal> ps = porcentajes(porcentajes);
        if (ps.isEmpty()) return BigDecimal.ZERO;
        BigDecimal temp = importe;
        for (BigDecimal p : ps)
            temp = temp.multiply(BigDecimal.valueOf(100).add(p)).divide(BigDecimal.valueOf(100), 4, RoundingMode.DOWN);
        temp = temp.setScale(dec, RoundingMode.HALF_UP);
        return temp.subtract(importe).setScale(dec, RoundingMode.HALF_UP);
    }

    static List<BigDecimal> porcentajes(String s) {
        List<BigDecimal> r = new ArrayList<>();
        if (s == null) return r;
        for (String x : s.replace(',', '.').split("\\+")) {
            String t = x.trim();
            if (t.isEmpty()) continue;
            try {
                BigDecimal p = new BigDecimal(t.replaceAll("[^0-9.\\-]", ""));
                if (p.signum() != 0) r.add(p);
            } catch (NumberFormatException ignorado) { }
        }
        return r;
    }

    private static BigDecimal num(Object o) { return OptimistFormula.num(o); }
}
