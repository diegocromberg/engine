#!/usr/bin/env python3
"""
E5b - Permisos de las acciones del WorkWith, sacados del WorkWithPlus de GeneXus -> 'permiso:' en cada acción del YAML.

    python3 permisos_gx.py OptimistU12NEW.xml --trn ..\\src\\main\\resources\\trn --probar       (solo informa)
    python3 permisos_gx.py OptimistU12NEW.xml --trn ..\\src\\main\\resources\\trn --solo PRS,PED  (escribe esas)

En la seguridad avanzada de WorkWithPlus cada acción del "Trabajar con" tiene su clave (SecFuntionKey: PEDAutorizar,
PEDCancelar, PrintPedido...): el WorkWith la pregunta con SecIsAuthByFunctionalityKey y, si el usuario no la tiene, esconde la
acción. Acá se lee esa clave de cada acción (userAction de la grilla de selección y de la barra) y se le agrega 'permiso: Clave' a
la acción correspondiente del YAML (workwith.acciones.renglon y workwith.botones), sin tocar nada más de la línea.

Cómo se aparea una acción del YAML con la de GeneXus:
  1. por el título (tooltip / caption de GeneXus; sin acentos ni mayúsculas), si es único;
  2. si no, por el objeto que llama (proc / panel / trn) y, si hay varios, por los parámetros constantes ('V', 'P'...).
Las que no se pueden aparear o que en GeneXus no tienen clave quedan informadas (REVISAR) y sin 'permiso:'.
"""
import argparse, html, os, re, sys, unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import acciones_gx as ag


def normal(s):
    s = unicodedata.normalize('NFKD', s or '').encode('ascii', 'ignore').decode()
    return re.sub(r'[^a-z0-9]+', ' ', s.lower()).strip()


def acciones_gx(src):
    """[{nombre, titulo, objeto, params, clave}] de todas las userAction del WorkWithPlus (grilla y barra de la selección)."""
    m = re.search(r'<selection\b(.*?)</selection>', src, re.S)
    sel = m.group(1) if m else src
    r = []
    for m in re.finditer(r'<userAction\b([^>]*?)(/>|>(.*?)</userAction>)', sel, re.S):
        a = {k: html.unescape(v) for k, v in re.findall(r'(\w+)="([^"]*)"', m.group(1))}
        ps = [html.unescape(x) for x in re.findall(r'<parameter name="([^"]*)"', m.group(3) or '')]
        obj = a.get('gxobject', '').rpartition('-')[2]
        r.append({'nombre': a.get('name', ''), 'titulo': a.get('tooltip') or a.get('caption') or a.get('name', ''),
                  'objeto': obj.lower(), 'params': ps, 'clave': (a.get('SecFuntionKey') or '').strip()})
    return r


def constantes(params):
    return {p.strip('"\'').lower() for p in params if p.startswith(("'", '"')) or re.fullmatch(r'-?\d+', p)}


def partes_yaml(linea):
    """(titulo, objeto, params) de una línea '- {titulo: ..., proc|panel|trn: X, parametros: [...]}'."""
    t = re.search(r'titulo: ("([^"]*)"|\'([^\']*)\'|([^,}]*))', linea)
    titulo = (t.group(2) or t.group(3) or t.group(4) or '').strip() if t else ''
    o = re.search(r'\b(proc|panel|trn|cns): ([A-Za-z0-9_.]+)', linea)
    objeto = o.group(2).lower() if o else ''
    p = re.search(r'parametros: \[(.*?)\]', linea)
    params = [x.strip() for x in re.split(r',\s*(?=(?:[^"]*"[^"]*")*[^"]*$)', p.group(1))] if p else []
    params = [x.strip('"') if x.startswith('"') else x for x in params]
    return titulo, objeto, params


def aparear(titulo, objeto, params, gx):
    """La acción de GeneXus que corresponde, o None."""
    n = normal(titulo)
    por_titulo = [g for g in gx if normal(g['titulo']) == n]
    if len(por_titulo) == 1:
        return por_titulo[0]
    claves = {g['clave'] for g in por_titulo if g['clave']}
    if por_titulo and len(claves) == 1:
        return por_titulo[0]
    if objeto:
        por_obj = [g for g in gx if g['objeto'] == objeto]
        if len(por_obj) == 1:
            return por_obj[0]
        if por_obj:
            c = constantes(params)
            fino = [g for g in por_obj if constantes(g['params']) & c] if c else []
            if len(fino) == 1:
                return fino[0]
            claves = {g['clave'] for g in por_obj if g['clave']}
            if len(claves) == 1:
                return por_obj[0]
    return None


def parchar(texto, gx):
    """(texto nuevo, [agregadas], [revisar]) para un YAML de TRN."""
    lineas = texto.split('\n')
    try:
        i = next(k for k, l in enumerate(lineas) if l.startswith('workwith:'))
    except StopIteration:
        return texto, [], []
    j = i + 1
    while j < len(lineas) and (lineas[j].startswith('  ') or not lineas[j].strip()):
        j += 1
    agregadas, revisar = [], []
    en = None
    for k in range(i + 1, j):
        l = lineas[k]
        if re.match(r'\s*(renglon|botones):', l):
            en = l.strip()
            continue
        m = re.match(r'(\s*- \{)(.*)\}\s*$', l)
        if not m or not en:
            continue
        titulo, objeto, params = partes_yaml(l)
        if re.search(r'\bpermiso: ', l):
            continue
        g = aparear(titulo, objeto, params, gx)
        if g is None:
            revisar.append(f'"{titulo}" ({objeto or "sin objeto"}): no está en el WorkWithPlus de GeneXus; sin permiso')
            continue
        if not g['clave']:
            revisar.append(f'"{titulo}": en GeneXus ({g["nombre"]}) no tiene clave de permiso')
            continue
        lineas[k] = m.group(1) + m.group(2) + ', permiso: ' + g['clave'] + '}'
        agregadas.append((titulo, g['clave']))
    return '\n'.join(lineas), agregadas, revisar


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('--trn', required=True, help='carpeta trn/ con los YAML')
    ap.add_argument('--solo', help='TRN separadas por coma')
    ap.add_argument('--probar', action='store_true', help='no graba: muestra lo que haría')
    a = ap.parse_args()
    wwp = ag.leer_wwp(a.xml)
    solo = {x.strip().lower() for x in a.solo.split(',')} if a.solo else None
    total = cambiadas = claves = 0
    for trn in sorted(wwp):
        if solo and trn.lower() not in solo:
            continue
        ruta = os.path.join(a.trn, trn + '.yaml')
        if not os.path.exists(ruta):
            continue
        texto = open(ruta, encoding='utf-8').read()
        nuevo, agregadas, revisar = parchar(texto, acciones_gx(wwp[trn]))
        total += 1
        if not agregadas and not revisar:
            continue
        print(f'== {trn}')
        for t, c in agregadas:
            print(f'   {t} -> {c}')
        for r in revisar:
            print('   REVISAR:', r)
        claves += len(agregadas)
        if a.probar or nuevo == texto:
            continue
        open(ruta, 'w', encoding='utf-8').write(nuevo)
        cambiadas += 1
    print(f'\n{total} TRN revisadas, {claves} permisos' + ('' if a.probar else f'; {cambiadas} YAML modificados'))


if __name__ == '__main__':
    main()
