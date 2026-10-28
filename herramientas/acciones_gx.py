#!/usr/bin/env python3
"""
V48 - Acciones del renglón del WorkWith, sacadas del WorkWithPlus de GeneXus -> 'workwith.acciones' de cada TRN.

    python3 acciones_gx.py OptimistU12NEW.xml --trn ..\\src\\main\\resources\\trn --probar            (solo informa)
    python3 acciones_gx.py OptimistU12NEW.xml --trn ..\\src\\main\\resources\\trn --solo PRS,PED      (escribe esas)

Lee la grilla del "Trabajar con" (selection) de cada WorkWithPlus:
- Update / Display / Delete: si están, con su condición; si la grilla no los tiene, esa acción no va
  (ej. en PRS no hay Eliminar: los presupuestos se cancelan).
- userAction: la acción propia (procedimiento, web panel u otra TRN), con su condición, ícono, confirmación y parámetros.
  La que abre la misma TRN en Display (el DSP de DVelop) es el Visualizar.
Condiciones: enumerados por su valor (StatusPRS.EnTramite -> "T"), 'X in (a, b)' -> (X = a or X = b).
Lo que no puede traducir (&variables en la condición, métodos, impresiones) queda como comentario REVISAR.
No regenera la TRN: agrega (o reemplaza) solo el bloque 'acciones:' dentro de 'workwith:'.
"""
import argparse, html, os, re, sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gx2proc as gp

T_PROC = '84a12160-f59b-4ad7-a683-ea4481ac23e9'
T_WBP = 'c9584656-94b6-4ccd-890f-332d11fc2c25'
T_TRN = '1db606f2-af09-4cf9-a3b5-b481519d28f6'

ICONOS = [('Reloj', 'CLOCK'), ('ActionDelete', 'BAN'), ('Printer', 'PRINT'), ('Generar', 'FILE_ADD'), ('Perdido', 'THUMBS_DOWN'),
          ('Clonar', 'COPY'), ('TxPED', 'EXCHANGE'), ('Mail', 'ENVELOPE'), ('Aprob', 'CHECK'), ('Anul', 'BAN'), ('Cancel', 'BAN'),
          ('Ver', 'EYE'), ('Display', 'SEARCH'), ('Update', 'PENCIL'), ('Excel', 'FILE_TABLE'), ('Pdf', 'FILE_TEXT'), ('Cta', 'BOOK')]


def leer_wwp(xml):
    """{trn: texto de la instancia del WorkWithPlus} (solo los de TRN)."""
    r, stack = {}, []
    for ev, el in ET.iterparse(xml, events=('start', 'end')):
        if ev == 'start':
            stack.append(el.tag)
            continue
        stack.pop()
        if len(stack) == 2 and el.tag == 'Object':
            n = el.get('name') or ''
            if n.startswith('WorkWithPlus') and el.get('parentType') == T_TRN:
                for p in el.findall('Part'):
                    src = (p.findtext('Data') or '') + (p.findtext('Source') or '')   # la instancia del patrón va en <Data>
                    if '<selection' in src:
                        r[el.get('parent')] = src
                        break
            el.clear()
    return r


def grilla_seleccion(src):
    m = re.search(r'<selection\b.*?<grid\b[^>]*>(.*?)</grid>', src, re.S)
    return m.group(1) if m else ''


def acciones_de(grid):
    """[(tipo, attrs, [parametros])] en orden, solo los hijos directos de la grilla."""
    r = []
    for m in re.finditer(r'<(standardAction|userAction)\b([^>]*?)(/>|>(.*?)</userAction>)', grid, re.S):
        a = {k: html.unescape(v) for k, v in re.findall(r'(\w+)="([^"]*)"', m.group(2))}
        ps = [html.unescape(x) for x in re.findall(r'<parameter name="([^"]*)"', m.group(4) or '')]
        r.append((m.group(1), a, ps))
    return r


def condicion(c, enums, revisar, donde):
    if not c or not c.strip():
        return None
    c = ' '.join(c.split())
    c = gp.reemplazar_enums(c, enums)

    def en(m):
        vals = [v.strip() for v in m.group(2).split(',')]
        return '(' + ' or '.join(f'{m.group(1)} = {v}' for v in vals) + ')'
    c = re.sub(r'([A-Za-z_]\w*)\s+in\s*\(([^)]*)\)', en, c, flags=re.I)
    c = c.replace('"', "'")   # textos entre comillas simples: más fácil de leer en el YAML
    c = re.sub(r"\s*(<>|<=|>=|=|<|>)\s*", r' \1 ', c)
    if '&' in c or re.search(r'\.\w+\(', c):
        revisar.append(f'{donde}: condición "{c}" (usa variables o métodos de GeneXus): se muestra siempre; completar a mano')
        return None
    return c


def icono(img):
    for k, v in ICONOS:
        if k.lower() in (img or '').lower():
            return v
    return None


def q(s):
    s = str(s)
    if re.fullmatch(r'[A-Za-z_][\w ]*', s) and s.strip() == s and s.lower() not in ('true', 'false', 'yes', 'no', 'null'):
        return s
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def generar(trn, src, enums, campos, procs, wbps):
    """(líneas YAML del bloque acciones, revisar)"""
    revisar = []
    grid = grilla_seleccion(src)
    acc = acciones_de(grid)
    if not acc:
        return None, ['la grilla del WorkWith no tiene acciones']
    std = {a.get('name'): a for t, a, ps in acc if t == 'standardAction'}
    hay_std = any(n in std for n in ('Update', 'Display', 'Delete'))
    visualizar_dsp = any(t == 'userAction' and a.get('gxobject', '').endswith('-' + trn) and any('Display' in p for p in ps)
                         for t, a, ps in acc)
    out = ['  acciones:   # V48: del WorkWithPlus de GeneXus (herramientas/acciones_gx.py)']

    def estandar(nombre, clave):
        a = std.get(nombre)
        if a is None or a.get('includeAction') == 'No':
            if clave == 'visualizar' and visualizar_dsp:
                return
            if hay_std or visualizar_dsp:
                out.append(f'    {clave}: false')
            return
        c = condicion(a.get('condition'), enums, revisar, clave)
        if c:
            out.append(f'    {clave}: {q(c)}')
    estandar('Update', 'modificar')
    estandar('Display', 'visualizar')
    estandar('Delete', 'borrar')

    renglon = []
    for t, a, ps in acc:
        if t != 'userAction':
            continue
        obj = a.get('gxobject', '')
        tipo_guid, _, nombre = obj.rpartition('-')
        titulo = a.get('tooltip') or a.get('caption') or a.get('name')
        if nombre == trn and any('Display' in p for p in ps):
            continue   # el DSP: es el Visualizar
        if not nombre:
            revisar.append(f'acción "{titulo}": no llama a un objeto (código en un evento del WorkWith): falta')
            continue
        if tipo_guid == T_PROC:
            if nombre.lower().endswith('prn') or nombre.lower().startswith('rpt'):
                revisar.append(f'acción "{titulo}": {nombre} es una impresión (PDF): todavía no')
                continue
            tipo = 'proc'
            if nombre.lower() not in procs:
                revisar.append(f'acción "{titulo}": falta proc/{nombre}.proc (gx2proc.py)')
        elif tipo_guid == T_WBP:
            tipo = 'panel'
            if nombre.lower() not in wbps:
                revisar.append(f'acción "{titulo}": falta wbp/{nombre}.yaml (gx2wbp.py)')
        elif tipo_guid == T_TRN:
            tipo = 'trn'
        else:
            revisar.append(f'acción "{titulo}": {nombre} es un objeto que todavía no se soporta')
            continue
        params, ok, modo = [], True, None
        for p in ps:
            if p.startswith('TrnMode.'):
                modo = p.split('.', 1)[1]
                continue
            pv = gp.reemplazar_enums(p, gp_enums)
            if pv.startswith('&'):
                params.append(q(pv))
            elif pv.lower() in ('true', 'false'):
                params.append(pv.lower())
            elif re.fullmatch(r'-?\d+(\.\d+)?', pv):
                params.append(pv)
            elif pv.startswith('"') or pv.startswith("'"):
                params.append(q("'" + pv.strip('"\'') + "'"))
            elif pv.lower() in campos:
                params.append(campos[pv.lower()])
            else:
                revisar.append(f'acción "{titulo}": el parámetro {p} no es un campo de la TRN')
                ok = False
        if not ok:
            continue
        c = condicion(a.get('condition'), enums, revisar, f'acción "{titulo}"')
        bc = usa_bc(nombre, tipo) if tipo in ('proc', 'panel') else None
        partes = [f'titulo: {q(titulo)}']
        ic = icono(a.get('image'))
        if ic:
            partes.append(f'icono: {ic}')
        if c:
            partes.append(f'si: {q(c)}')
        partes.append(f'{tipo}: {nombre}')
        if modo:
            partes.append(f'modo: {modo}')
        if params:
            partes.append('parametros: [' + ', '.join(params) + ']')
        if a.get('confirm') == 'True':
            partes.append(f'confirmar: {q(a.get("confirmMessage") or "¿Confirma " + titulo + "?")}')
        if bc:
            revisar.append(f'acción "{titulo}": {bc} usa métodos de Business Component que el motor todavía no ejecuta (Insert / Update / InsertOrUpdate): queda comentada')
            renglon.append('      # - {' + ', '.join(partes) + '}')
        else:
            renglon.append('      - {' + ', '.join(partes) + '}')
    if renglon:
        out.append('    renglon:')
        out += renglon
    if len(out) == 1:
        return None, revisar   # todo como siempre (Modificar, Visualizar y Eliminar sin condiciones): no hace falta el bloque
    return out, revisar


gp_enums = {}
DIRS_PROC = []
DIR_WBP = None


def texto_objeto(nombre, tipo):
    if tipo == 'panel' and DIR_WBP:
        p = os.path.join(DIR_WBP, nombre + '.yaml')
        return open(p, encoding='utf-8').read() if os.path.exists(p) else None
    for d in DIRS_PROC:
        p = os.path.join(d, nombre + '.proc')
        if os.path.exists(p):
            return open(p, encoding='utf-8').read()
    return None


def usa_bc(nombre, tipo, vistos=None):
    """El objeto o algo que llama usa métodos de Business Component que el motor todavía no ejecuta.
    V50m: Load / Save / Delete / Check / Success / GetMessages / Clone sí corren; faltan Insert / Update / InsertOrUpdate."""
    vistos = set() if vistos is None else vistos
    if (nombre.lower(), tipo) in vistos:
        return None
    vistos.add((nombre.lower(), tipo))
    t = texto_objeto(nombre, tipo)
    if t is None:
        return None
    sin = re.sub(r'//.*', '', t)
    bcs = {m.group(1).lower() for m in re.finditer(r'&(\w+)\s+Bc\(', sin)}
    for m in re.finditer(r'&(\w+)\.(Insert|Update|InsertOrUpdate)\s*\(', sin, re.I):
        if m.group(1).lower() in bcs:
            return nombre
    for m in re.finditer(r'\b([A-Z]\w+)\.(?:Call|Udp)\s*\(', sin):
        r = usa_bc(m.group(1), 'proc', vistos)
        if r:
            return r
    return None


def campos_yaml(texto):
    """{nombre_en_minuscula: Nombre} de los campos de la cabecera (los 'nombre:' antes de 'detalles:')."""
    cab = texto.split('\ndetalles:')[0]
    return {n.lower(): n for n in re.findall(r'^\s*- \{nombre: ([A-Za-z_]\w*)', cab, re.M)}


def insertar(texto, bloque):
    """Pone (o reemplaza) el bloque acciones dentro de workwith:."""
    lineas = texto.split('\n')
    try:
        i = next(k for k, l in enumerate(lineas) if l.startswith('workwith:'))
    except StopIteration:
        return None
    j = i + 1
    while j < len(lineas) and (lineas[j].startswith('  ') or not lineas[j].strip()):
        j += 1
    # sacar un bloque 'acciones:' anterior
    k = i + 1
    nuevo = []
    while k < j:
        if lineas[k].startswith('  acciones:'):
            k += 1
            while k < j and lineas[k].startswith('    '):
                k += 1
            continue
        nuevo.append(lineas[k])
        k += 1
    while nuevo and not nuevo[-1].strip():
        nuevo.pop()
    return '\n'.join(lineas[:i + 1] + nuevo + bloque + lineas[j:])


def main():
    global gp_enums
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('--trn', required=True, help='carpeta trn/ con los YAML')
    ap.add_argument('--solo', help='TRN separadas por coma (sin esto, todas las que tengan YAML)')
    ap.add_argument('--probar', action='store_true', help='no graba: muestra lo que haría')
    ap.add_argument('--procs-extra', nargs='*', help='otras carpetas con .proc (para probar antes de copiarlos a proc/)')
    ap.add_argument('--wbp-extra', help='otra carpeta con los wbp/*.yaml')
    a = ap.parse_args()
    kb = gp.cargar_kb(a.xml)
    gp_enums = gp.enumerados(kb)
    raiz = os.path.dirname(os.path.abspath(a.trn.rstrip('/\\')))
    global DIR_WBP
    DIRS_PROC.append(os.path.join(raiz, 'proc'))
    DIRS_PROC.extend(a.procs_extra or [])
    procs = set()
    for d in DIRS_PROC:
        if os.path.isdir(d):
            procs |= {f[:-5].lower() for f in os.listdir(d) if f.endswith('.proc')}
    wdir = a.wbp_extra or os.path.join(raiz, 'wbp')
    DIR_WBP = wdir
    wbps = {f[:-5].lower() for f in os.listdir(wdir) if f.endswith('.yaml')} if os.path.isdir(wdir) else set()
    wwp = leer_wwp(a.xml)
    solo = {x.strip().lower() for x in a.solo.split(',')} if a.solo else None
    total = cambiadas = 0
    for trn in sorted(wwp):
        if solo and trn.lower() not in solo:
            continue
        ruta = os.path.join(a.trn, trn + '.yaml')
        if not os.path.exists(ruta):
            continue
        texto = open(ruta, encoding='utf-8').read()
        bloque, revisar = generar(trn, wwp[trn], gp_enums, campos_yaml(texto), procs, wbps)
        total += 1
        if bloque is None:
            continue
        print(f'== {trn}')
        for l in bloque:
            print(l)
        for r in revisar:
            print('   REVISAR:', r)
        if a.probar:
            continue
        nuevo = insertar(texto, bloque + ['  # REVISAR: ' + r for r in revisar])
        if nuevo and nuevo != texto:
            open(ruta, 'w', encoding='utf-8').write(nuevo)
            cambiadas += 1
    print(f'\n{total} TRN con WorkWithPlus revisadas' + ('' if a.probar else f'; {cambiadas} YAML modificados'))


if __name__ == '__main__':
    main()
