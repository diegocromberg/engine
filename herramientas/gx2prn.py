#!/usr/bin/env python3
"""
V49 - Impresiones de GeneXus (procedimientos con Layout) -> impresiones/<Nombre>.yaml (el diseño que dibuja el motor en PDF).

La lógica del reporte (For each, cuentas, "print Bloque", Header/Footer) va aparte, con gx2proc.py, a proc/<Nombre>.proc.
Acá sale solo el diseño: los bloques (print blocks) con sus elementos, en milímetros.

Medidas: GeneXus dibuja en centésimas de pulgada (1 unidad = 0,254 mm = 0,72 pt). La letra del PDF de GeneXus es la
del diseño redondeada (9,75 -> 10 pt; 8,25 -> 8 pt): en el YAML va el tamaño impreso.

Uso:
  python3 gx2prn.py OptimistU12NEW.xml PRSPrnGenerico PRSPrnIEP --salida ../src/main/resources/prn
"""
import argparse, html, os, re, sys
import xml.etree.ElementTree as ET

T_PROC = '84a12160-f59b-4ad7-a683-ea4481ac23e9'
T_DOM = '00972a17-9975-449e-aab1-d26165d51393'
T_SUB = '87313f43-5eb2-41d7-9b8c-e8d9f5bf9588'
T_IMG = '9fb193d9'   # objeto Image de la KB (imágenes fijas de los reportes)
P_LAYOUT = 'c414ed00'
P_VARIABLES = 'e4c4ade7'
P_RULES = '9b0a32a3'
P_SOURCE = '528d1c06'

MM = 0.254              # mm por unidad de GeneXus
ANCHO_NUM = 6.33        # unidades por carácter de la picture (ancho por defecto de un número, medido en los PDF)
ANCHO_TXT = 5.21        # unidades por carácter de un texto (ancho por defecto)
LETRA_DEFECTO = 'Microsoft Sans Serif, 8.25pt'


def props(el):
    return {p.findtext('Name'): p.findtext('Value') for p in el.iter('Property')}


def leer(xml, nombres):
    """Atributos, dominios y los reportes pedidos (layout, variables, reglas)."""
    kb = {'attrs': {}, 'doms': {}, 'subs': {}, 'reportes': {}, 'imagenes': {}}
    stack = []
    for ev, el in ET.iterparse(xml, events=('start', 'end')):
        if ev == 'start':
            stack.append(el.tag)
            continue
        stack.pop()
        if len(stack) != 2:
            continue
        if el.tag == 'Attribute':
            kb['attrs'][el.get('name')] = props(el)
        elif el.tag == 'Object':
            t, n = el.get('type'), el.get('name')
            if t == T_DOM:
                kb['doms'][n] = props(el)
            elif (t or '').startswith(T_IMG):   # imagen fija: nombre del archivo y sus bytes
                im = el.find('.//Image')
                b64 = ''.join(im.itertext()) if im is not None else ''
                if im is not None and b64.strip():
                    import base64
                    try:
                        kb['imagenes'][n] = (im.get('name') or (n + '.jpg'), base64.b64decode(''.join(b64.split())))
                    except Exception:
                        pass
            elif t == T_SUB:   # subtipos: PRSVendedoresNombre -> VendedoresNombre
                for sb in el.iter('Subtype'):
                    kb['subs'][sb.findtext('Name')] = (sb.findtext('Supertype') or '').strip()
            elif t == T_PROC and n in nombres:
                partes = {(p.get('type') or '')[:8]: p for p in el.findall('Part')}
                lay = partes.get(P_LAYOUT)
                vars_ = {}
                if partes.get(P_VARIABLES) is not None:
                    for v in partes[P_VARIABLES].findall('Variable'):
                        vars_[v.get('Name').lower()] = props(v)
                reglas = partes[P_RULES].findtext('Source') if partes.get(P_RULES) is not None else ''
                fuente = partes[P_SOURCE].findtext('Source') if partes.get(P_SOURCE) is not None else ''
                kb['reportes'][n] = {'desc': el.get('description'), 'layout': lay, 'vars': vars_, 'reglas': reglas or '', 'fuente': fuente or ''}
            el.clear()
    return kb


# ----------------------------------------------------------------------------- tipo y picture de un campo

def resolver(p, kb, visto=0):
    """Propiedades efectivas (tipo, largo, decimales, separador de miles, picture), siguiendo dominio / atributo base."""
    r = {}
    base = p.get('idBasedOn') or ''
    if visto < 6 and base.startswith('Attribute:') and base.split(':', 1)[1] in kb['subs'] \
            and not (kb['attrs'].get(base.split(':', 1)[1], {}).get('ATTCUSTOMTYPE') or kb['attrs'].get(base.split(':', 1)[1], {}).get('idBasedOn')):
        base = 'Attribute:' + kb['subs'][base.split(':', 1)[1]]
    if visto < 6 and base.startswith('Domain:'):
        r.update(resolver(kb['doms'].get(base.split(':', 1)[1], {}), kb, visto + 1))
    elif visto < 6 and base.startswith('Attribute:'):
        r.update(resolver(kb['attrs'].get(base.split(':', 1)[1], {}), kb, visto + 1))
    for k in ('ATTCUSTOMTYPE', 'Length', 'Decimals', 'idTHOUSANDSEP', 'ATT_PICTURE', 'IDEnumDefinedValues'):
        if p.get(k) not in (None, ''):
            r[k] = p[k]
    if base.startswith('Domain:') and not r.get('ATTCUSTOMTYPE'):
        corto = base.split(':', 1)[1].split(',')[0].strip()
        if corto in ('Address', 'Email', 'Url', 'Html'):
            r.update({'ATTCUSTOMTYPE': 'bas:VarChar', 'Length': '1024'})
        elif corto == 'Phone':
            r.update({'ATTCUSTOMTYPE': 'bas:Character', 'Length': '20'})
    return r


def tipo(nombre, rep, kb):
    if nombre.startswith('&'):
        p = rep['vars'].get(nombre[1:].lower())
        if p is None:
            return {}
        r = resolver(p, kb)
        base = p.get('idBasedOn') or ''
        if not r.get('ATTCUSTOMTYPE') and base.startswith('Attribute:'):
            r = tipo(base.split(':', 1)[1], rep, kb)   # basada en un atributo sin tipo: el del atributo (Numeric(4) si no tiene)
        return r
    p = kb['attrs'].get(nombre)
    if p is None or not (p.get('ATTCUSTOMTYPE') or p.get('idBasedOn')):
        sup = kb['subs'].get(nombre)
        if sup:
            p = kb['attrs'].get(sup, p)
    r = resolver(p or {}, kb)
    if p is not None and not r.get('ATTCUSTOMTYPE'):
        r.update({'ATTCUSTOMTYPE': 'bas:Numeric', 'Length': r.get('Length') or '4'})   # sin tipo: Numeric(4), como GeneXus
    return r


def picture(t):
    """'ZZ,ZZZ,ZZZ,ZZ9.99' (como GeneXus) para números y fechas; None para textos e imágenes."""
    if not t:
        return None
    if t.get('ATT_PICTURE'):
        return t['ATT_PICTURE']
    ct = (t.get('ATTCUSTOMTYPE') or 'bas:Numeric').split(':')[-1].split(',')[0].strip()
    if ct == 'Numeric' and not t.get('IDEnumDefinedValues'):
        lon, dec = int(t.get('Length') or 4), int(t.get('Decimals') or 0)
        enteros = max(1, lon - dec - (1 if dec else 0))
        digitos = 'Z' * (enteros - 1) + '9'
        if t.get('idTHOUSANDSEP') == 'True':
            grupos = []
            while len(digitos) > 3:
                grupos.insert(0, digitos[-3:])
                digitos = digitos[:-3]
            digitos = ','.join([digitos] + grupos)
        return digitos + ('.' + '9' * dec if dec else '')
    if ct == 'Date':
        return '99/99/99'
    if ct == 'DateTime':
        return '99/99/99 99:99'
    return None


def es_imagen(t):
    ct = (t.get('ATTCUSTOMTYPE') or '').split(':')[-1].split(',')[0].strip()
    return ct in ('Image', 'Blob', 'BinaryFile')


def largo_texto(t):
    try:
        return int(t.get('Length') or 20)
    except ValueError:
        return 20


# ----------------------------------------------------------------------------- letra

def letra(texto):
    """'Microsoft Sans Serif, 9.75pt, style=Bold' -> (tamaño impreso, negrita, itálica)."""
    m = re.match(r'\s*([^,]+),\s*([\d.]+)pt(?:,\s*style=(.*))?', texto or LETRA_DEFECTO)
    if not m:
        return 8, False, False
    pt = float(m.group(2))
    estilo = (m.group(3) or '').lower()
    impreso = int(pt + 0.5)
    return impreso, 'bold' in estilo, 'italic' in estilo


def escala_letra(texto):
    """El ancho por defecto de GeneXus crece con la letra (medido: 8,25 pt = 1)."""
    m = re.match(r'\s*[^,]+,\s*([\d.]+)pt(?:,\s*style=(.*))?', texto or LETRA_DEFECTO)
    if not m:
        return 1
    return float(m.group(1)) / 8.25 * (1.1 if 'bold' in (m.group(2) or '').lower() else 1)


ARCHIVOS = {}   # imágenes fijas para escribir al lado del YAML


def tam_imagen(d):
    """(ancho, alto) en píxeles de un JPEG, PNG o GIF."""
    if d[:8] == b'\x89PNG\r\n\x1a\n':
        return int.from_bytes(d[16:20], 'big'), int.from_bytes(d[20:24], 'big')
    if d[:3] == b'GIF':
        return int.from_bytes(d[6:8], 'little'), int.from_bytes(d[8:10], 'little')
    if d[:2] == b'\xff\xd8':
        i = 2
        while i + 9 < len(d):
            if d[i] != 0xFF:
                i += 1
                continue
            m = d[i + 1]
            if m in (0xD8, 0x01) or 0xD0 <= m <= 0xD7:
                i += 2
                continue
            ln = int.from_bytes(d[i + 2:i + 4], 'big')
            if 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
                return int.from_bytes(d[i + 7:i + 9], 'big'), int.from_bytes(d[i + 5:i + 7], 'big')
            i += 2 + ln
    return None


def prop_hoja(lay, nombre):
    return next(((x.findtext('Value') or '').strip() for x in lay.iter('Property') if x.findtext('Name') == nombre), '')


def hoja_mm(lay, fuente):
    """V65: (ancho, alto, pie) de la hoja en mm según RPT_PAPER_SIZE y RPT_PAPER_ORIENTATION. Carta / A4 vertical: la hoja
    medida en los PDF de GeneXus (217,47 x 304,25). Oficio (Legal): la misma proporción que GeneXus aplica a la carta
    (8,5 x 14 pulgadas -> 217,47 x 387,2). Apaisada: ancho y alto al revés y el pie a la misma distancia del borde de abajo."""
    alto = 304.25
    if prop_hoja(lay, 'RPT_PAPER_SIZE').upper() == 'LEGAL':
        alto = round(304.25 * 14 / 11, 2)
    ancho = 217.47
    pie = pie_mm(fuente, es_a4(lay))
    pie = round(pie + (alto - 304.25), 2)
    if prop_hoja(lay, 'RPT_PAPER_ORIENTATION').upper() == 'LANDSCAPE':
        ancho, alto = alto, ancho
        pie = round(alto - (304.25 - pie_mm(fuente, es_a4(lay))), 2)
    return num(round(ancho, 2)), num(round(alto, 2)), pie


def es_a4(lay):
    return any((x.findtext('Name') == 'RPT_PAPER_SIZE' and (x.findtext('Value') or '').strip().upper() == 'A4') for x in lay.iter('Property'))


def color_gx(v):
    """'0, 192, 192, 192' (alfa, rojo, verde, azul) -> '#c0c0c0'. Blanco o vacío: None."""
    n = [int(x) for x in re.findall(r'\d+', v or '')]
    if len(n) == 4:
        n = n[1:]
    if len(n) != 3 or n == [255, 255, 255]:
        return None
    return '#%02x%02x%02x' % tuple(n)


def pie_mm(fuente, a4=False):
    """Dónde empieza el Footer (mm desde arriba). Medido en los PDF de GeneXus: sin MB, 804,7 pt (PRSPrnGenerico);
    cada unidad de MB (margen de abajo) lo sube 1,53 pt (IEP, MB 7: 794); con hoja A4, 28,2 pt más arriba
    (De Santis, A4 y MB 10: 761,2)."""
    m = re.search(r'(?im)^\s*MB\s+(\d+)', fuente or '')
    mb = int(m.group(1)) if m else 0
    return round(((776.5 if a4 else 804.7) - 1.53 * mb) * 25.4 / 72, 2)


def mm(u):
    v = round(float(u) * MM, 2)
    return int(v) if v == int(v) else v


def num(v):
    return int(v) if float(v) == int(v) else v


# ----------------------------------------------------------------------------- armado

def elementos(bloque_el, rep, kb, avisos, nombre_bloque):
    out = []
    ctl = bloque_el.find('Controls')
    if ctl is None:
        return out
    for c in ctl:
        p = {x.findtext('Name'): x.findtext('Value') for x in c.iter('Property')}
        if p.get('RPT_VISIBLE') == 'False':
            continue
        tipo_ctl = c.tag
        x, y = float(p.get('RPT_X') or 0), float(p.get('RPT_Y') or 0)
        w = float(p['RPT_WIDTH']) if p.get('RPT_WIDTH') else None
        h = float(p['RPT_HEIGHT']) if p.get('RPT_HEIGHT') else None
        e = {}
        if tipo_ctl == 'ReportLine':
            e['linea'] = True
            e.update({'x': mm(x), 'y': mm(y)})
            if w:
                e['ancho'] = mm(w)
            if h:
                e['alto'] = mm(h)
            if p.get('RPT_LINEWIDTH') and p['RPT_LINEWIDTH'] != '1':
                e['grosor'] = num(float(p['RPT_LINEWIDTH']))
            out.append(e)
            continue
        if tipo_ctl == 'ReportRectangle':
            e['recuadro'] = True
            e.update({'x': mm(x), 'y': mm(y), 'ancho': mm(w or 100), 'alto': mm(h or 28)})
            fondo = color_gx(p.get('RPT_BACKCOLOR'))
            if fondo:
                e['relleno'] = fondo   # sin alto: 28 (medido en el PDF de IEP)
            if p.get('RPT_LINEWIDTH') and p['RPT_LINEWIDTH'] != '1':
                e['grosor'] = num(float(p['RPT_LINEWIDTH']))
            out.append(e)
            continue
        if tipo_ctl == 'ReportBitmap' or tipo_ctl == 'ReportImage':
            ref = p.get('imagereference') or ''
            nom = ref[37:] if len(ref) > 37 and ref[36] == '-' else ref
            img = kb['imagenes'].get(nom)
            if img is None:
                avisos.append(f'{nombre_bloque}: imagen fija {nom or "?"}: no está en la KB (cargar el archivo a mano)')
                e['imagen'] = (nom or 'imagen') + '.jpg'
                e.update({'x': mm(x), 'y': mm(y), 'ancho': mm(w or 100), 'alto': mm(h or 100)})
                out.append(e)
                continue
            archivo, datos = img
            ARCHIVOS[archivo] = datos
            pw, ph = tam_imagen(datos) or (100, 100)
            # sin tamaño en el diseño: el natural de la imagen a 96 puntos por pulgada (como la imprimía GeneXus)
            e['imagen'] = archivo
            e.update({'x': mm(x), 'y': mm(y), 'ancho': mm(w or pw * 100 / 96), 'alto': mm(h or ph * 100 / 96)})
            out.append(e)
            continue
        tam, neg, ita = letra(p.get('RPT_FONT'))
        alin = (p.get('RPT_ALIGNMENT') or '').lower()
        if tipo_ctl == 'ReportLabel':
            texto = html.unescape(p.get('RPT_TEXT') or '')
            e['texto'] = texto
            ancho = w or len(texto) * 7.5 * escala_letra(p.get('RPT_FONT'))   # sin ancho: GeneXus lo ajusta al texto (no se corta)
            alinear = 'centro' if 'center' in alin else 'derecha' if 'right' in alin else None
        elif tipo_ctl == 'ReportAttribute':
            att = (p.get('AttID') or '').strip()
            # V66j: un campo de un SDT (&SDTErroresContaItem con FieldSpecifier = Modulo) es &SDTErroresContaItem.Modulo
            if (p.get('FieldSpecifier') or '').strip():
                att = f"{att}.{p['FieldSpecifier'].strip()}"
            t = tipo(att, rep, kb)
            if es_imagen(t):
                e['imagen'] = att
                e.update({'x': mm(x), 'y': mm(y), 'ancho': mm(w or 100), 'alto': mm(h or 100)})
                out.append(e)
                continue
            if not t:
                avisos.append(f'{nombre_bloque}: {att}: no encontré su tipo (va como texto)')
            pic = picture(t)
            e['campo'] = att
            if pic:
                e['formato'] = pic
            if pic and not pic.startswith('@'):
                ancho = w or round(len(pic) * ANCHO_NUM * escala_letra(p.get('RPT_FONT')))
            else:
                # V65: "@!" (texto en mayúsculas) es un texto: el ancho por el largo del atributo, no por la picture
                # sin ancho: GeneXus lo ajusta al largo del atributo (5,21 por carácter con 8,25 pt: así centra el pie);
                # alineado a la izquierda se le da más, para no cortar lo que GeneXus no cortaba
                # (sin escalar por la letra: así centraba GeneXus el &Tit6 de De Santis, 9 pt)
                if 'center' in alin or 'right' in alin or p.get('RPT_WORDWRAP') == 'True':
                    # medido en los PDF de GeneXus: 5,21 por carácter del atributo (VarChar(200) -> 1042, VarChar(60) -> 313,
                    # VarChar(50) -> 260, Character(20) -> 104), sin importar la letra
                    ancho = w or round(min(largo_texto(t), 200) * ANCHO_TXT)
                else:
                    ancho = w or round(min(largo_texto(t), 200) * 7.5 * escala_letra(p.get('RPT_FONT')))
            numerico = pic is not None and not pic.startswith('99/') and not pic.startswith('@')
            alinear = ('centro' if 'center' in alin else 'derecha' if 'right' in alin else
                       'izquierda' if alin.endswith('left') and numerico else None)
            if alinear is None and numerico:
                alinear = 'derecha'
            if alinear == 'izquierda' and not numerico:
                alinear = None
        else:
            avisos.append(f'{nombre_bloque}: control {tipo_ctl} no soportado')
            continue
        e.update({'x': mm(x), 'y': mm(y)})
        if w or alinear in ('centro', 'derecha') or e.get('formato') or p.get('RPT_WORDWRAP') == 'True':
            e['ancho'] = mm(ancho)   # sin ancho en GeneXus (alineado a la izquierda): el que necesite el texto, sin cortarlo
        if h and h > 15:
            e['alto'] = mm(h)
        if alinear:
            e['alinear'] = alinear
        if tam != 8:
            e['tamaño'] = num(tam)
        if neg:
            e['negrita'] = True
        if ita:
            e['italica'] = True
        if p.get('RPT_WORDWRAP') == 'True':
            e['ajustar'] = True
        out.append(e)
    return out


def q(v):
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if isinstance(v, (int, float)):
        return str(v)
    # V64: un salto de línea dentro del texto se escribía tal cual: el YAML quedaba en dos renglones (SnakeYAML lo leía como un
    # espacio, pero el Designer no lo abría). Va el espacio directamente.
    s = re.sub(r'\s*[\r\n]+\s*', ' ', str(v))
    if re.fullmatch(r"[A-Za-zÁÉÍÓÚáéíóúÑñ_][\w ÁÉÍÓÚáéíóúÑñ.()/-]*", s) and s.strip() == s and s.lower() not in ('true', 'false', 'null', 'yes', 'no', 'si', 'sí'):
        return s
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def flow(e):
    orden = ['texto', 'campo', 'imagen', 'linea', 'recuadro', 'x', 'y', 'ancho', 'alto', 'alinear', 'formato',
             'tamaño', 'negrita', 'italica', 'ajustar', 'grosor', 'relleno']
    return '{' + ', '.join(f'{k}: {q(e[k])}' for k in orden if k in e) + '}'


def armar(nombre, rep, kb):
    avisos = []
    lay = rep['layout']
    if lay is None:
        return None, ['no tiene Layout (no es una impresión)']
    salida = re.search(r'(?i)output_file\s*\(([^)]*)\)', rep['reglas'])
    L = [f'# Impresión {nombre} ({rep["desc"]}) - generada desde la KB con herramientas/gx2prn.py',
         '# Medidas en mm desde la esquina de cada bloque; tamaño de letra en pt (el impreso).',
         '# La lógica (qué bloque se imprime y cuándo) está en proc/' + nombre + '.proc ("print Bloque").',
         f'nombre: {nombre}',
         'hoja: {{ancho: {}, alto: {}, izquierda: 7.57, arriba: 9.7, pie: {}}}'.format(*hoja_mm(lay, rep['fuente'])),
         'letra: {familia: Helvetica, tamaño: 8}']
    if salida:
        L.append('# Output_file de GeneXus: ' + salida.group(1).strip())
    L.append('bloques:')
    for b in lay.iter('PrintBlock'):
        bp = {x.findtext('Name'): x.findtext('Value') for x in b.findall('Properties/Property')}
        bn = bp.get('RPT_INTERNAL_NAME') or 'Bloque'
        alto = mm(float(bp.get('RPT_HEIGHT') or 15))
        els = elementos(b, rep, kb, avisos, bn)
        L.append(f'  {bn}:')
        L.append(f'    alto: {alto}')
        if els:
            L.append('    elementos:')
            for e in sorted(els, key=lambda e: (e.get('y', 0), e.get('x', 0))):
                L.append('      - ' + flow(e))
        else:
            L.append('    elementos: []')
    return '\n'.join(L) + '\n', avisos


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('nombres', nargs='+')
    ap.add_argument('--salida', default='.')
    a = ap.parse_args()
    kb = leer(a.xml, set(a.nombres))
    os.makedirs(a.salida, exist_ok=True)
    for n in a.nombres:
        rep = kb['reportes'].get(n)
        if rep is None:
            print(f'!! {n}: no está en la KB')
            continue
        texto, avisos = armar(n, rep, kb)
        if texto is None:
            print(f'!! {n}: {avisos[0]}')
            continue
        open(os.path.join(a.salida, n + '.yaml'), 'w', encoding='utf-8').write(texto)
        # imágenes de la KB que el reporte carga con &Var.FromImage(Nombre) (ej. el logo de cada empresa de De Santis)
        for nom in sorted(set(re.findall(r'(?i)\.FromImage\s*\(\s*(\w+)\s*\)', rep['fuente']))):
            img = kb['imagenes'].get(nom)
            if img:
                ext = os.path.splitext(img[0])[1] or '.jpg'
                ARCHIVOS[nom + ext] = img[1]
            else:
                print(f'   !! {n}: la imagen {nom} (FromImage) no está en la KB')
        for arch, datos in ARCHIVOS.items():   # imágenes fijas (ej. el sello de IEP), al lado del YAML
            open(os.path.join(a.salida, arch), 'wb').write(datos)
            print(f'      imagen fija: {arch}')
        ARCHIVOS.clear()
        print(f'   {n}: listo' + (f' (revisar: {"; ".join(avisos)})' if avisos else ''))


if __name__ == '__main__':
    main()
