#!/usr/bin/env python3
"""
V34 - Traductor de procedimientos GeneXus -> proc/<Nombre>.proc (lenguaje GeneXus que interpreta el motor).

Lee el export XML de la KB (una sola vez; después usa la caché procs_v1.json al lado del XML) y por cada
procedimiento pedido escribe:

    /* Qué hace:
       <descripción en castellano: la del archivo de descripciones, o la de GeneXus + "(completar)">
    */
    Parm(in: &A, out: &B);
    Variables
      &A   Numeric(10)
      ...
    EndVariables
    <código fuente de GeneXus, con los valores de los dominios enumerados reemplazados (Parametro.IvaTipo1 -> 16)>

Uso:
  python3 gx2proc.py OptimistU12NEW.xml AplicarDescuentos CalcularIva ... --salida ../src/main/resources/proc
  python3 gx2proc.py OptimistU12NEW.xml --todos --salida /tmp/procs          (los 2.943, para ver cuántos entiende)
  --descripciones descripciones.yaml   (Nombre: "texto de Qué hace")
"""
import argparse, json, os, re, sys
import xml.etree.ElementTree as ET

T_PROC = '84a12160-f59b-4ad7-a683-ea4481ac23e9'
P_SOURCE = '528d1c06'
P_RULES = '9b0a32a3'
P_VARIABLES = 'e4c4ade7'

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def leer_procs(xml):
    cache = os.path.join(os.path.dirname(os.path.abspath(xml)), 'procs_v1.json')
    if os.path.exists(cache) and os.path.getmtime(cache) >= os.path.getmtime(xml):
        return json.load(open(cache, encoding='utf-8'))
    procs, stack = {}, []
    for ev, el in ET.iterparse(xml, events=('start', 'end')):
        if ev == 'start':
            stack.append(el.tag)
            continue
        stack.pop()
        if len(stack) == 2 and el.tag == 'Object':
            if el.get('type') == T_PROC:
                partes = {}
                for p in el.findall('Part'):
                    t = (p.get('type') or '')[:8]
                    if t in (P_SOURCE, P_RULES):
                        partes[t] = p.findtext('Source') or ''
                    elif t == P_VARIABLES:
                        partes[t] = [{'nombre': v.get('Name'),
                                      'props': {x.findtext('Name'): x.findtext('Value') for x in v.iter('Property')}}
                                     for v in p.findall('Variable')]
                procs[el.get('name')] = {'desc': el.get('description'), 'partes': partes}
            el.clear()
    json.dump(procs, open(cache, 'w', encoding='utf-8'))
    return procs


def cargar_kb(xml):
    """Atributos y dominios (usa la caché kb_v7.json del generador de YAML si existe)."""
    import gx2yaml
    cache = os.path.join(os.path.dirname(os.path.abspath(xml)), 'kb_v7.json')
    if os.path.exists(cache):
        return gx2yaml.normalizar_kb(json.load(open(cache, encoding='utf-8')))   # V82m: DomainKey
    return gx2yaml.leer_kb(xml, '*')


# ----------------------------------------------------------------------------- tipos

MAPA = {'Numeric': 'Numeric', 'Character': 'Character', 'VarChar': 'VarChar', 'LongVarChar': 'VarChar',
        'Date': 'Date', 'DateTime': 'DateTime', 'Boolean': 'Boolean'}
GENEXUS = {'Address': ('VarChar', 1024, 0), 'Email': ('VarChar', 100, 0), 'Url': ('VarChar', 1000, 0),
           'Phone': ('Character', 20, 0), 'Html': ('VarChar', 2000, 0), 'Time': ('DateTime', 0, 0),
           'TrnMode': ('Character', 3, 0)}   # V50m: INS / UPD / DLT / DSP (antes salía Numeric(4))


def tipo_de(props, kb, visto=0):
    """'Numeric(14.4)' o None si el tipo todavía no lo maneja el intérprete (SDT, colección, Blob...)."""
    if props.get('AttCollection') == 'True' and not (props.get('ATTCUSTOMTYPE') or '').startswith(('sdt:', 'bc:', 'ext:')):
        t = tipo_de({k: v for k, v in props.items() if k != 'AttCollection'}, kb, visto)
        return None if t is None else f'Coleccion({t.split("(")[0]})'
    base = props.get('idBasedOn') or ''
    if base.startswith('Domain:'):
        d = base.split(':', 1)[1]
        corto = d.split(',')[0].strip()
        if d not in kb['doms'] and corto in GENEXUS:
            t, l, dec = GENEXUS[corto]
            return f'{t}({l})' if l else t
        if visto < 5:
            return tipo_de(kb['doms'].get(d, {}), kb, visto + 1)
    if base.startswith('Attribute:'):
        a = base.split(':', 1)[1]
        p = kb['attrs'].get(a, {})
        if not (p.get('ATTCUSTOMTYPE') or p.get('idBasedOn')):
            sup = (kb['subs'].get(a) or {}).get('super')
            p = kb['attrs'].get(sup, p) if sup else p
        if visto < 5:
            return tipo_de(p, kb, visto + 1)
    if base and not base.startswith(('Domain:', 'Attribute:')):
        return None
    ct = props.get('ATTCUSTOMTYPE') or 'bas:Numeric'
    pref, _, t = ct.partition(':')
    t = t.split(',')[0].strip()    # "Messages.Message, GeneXus.Common" -> Messages.Message
    if pref in ('sdt', 'bc', 'ext') or t in ('Image', 'Blob', 'Video', 'Audio', 'GUID'):
        # V34: estructuras (Sdt), business components (Bc) y objetos externos (Ext)
        clase = {'sdt': 'Sdt', 'bc': 'Bc', 'ext': 'Ext'}.get(pref, 'Ext')
        return f'Coleccion({t})' if props.get('AttCollection') == 'True' else f'{clase}({t})'
    tt = MAPA.get(t)
    if tt is None:
        return None
    lon, dec = int(props.get('Length') or 0), int(props.get('Decimals') or 0)
    if tt == 'Numeric':
        lon = lon or 4
        return f'Numeric({lon}.{dec})' if dec else f'Numeric({lon})'
    if tt in ('Character', 'VarChar'):
        return f'{tt}({lon or (2000 if t == "LongVarChar" else 20)})'
    return tt


# ----------------------------------------------------------------------------- enumerados

def enumerados(kb):
    """{'parametro.ivatipo1': '16', ...} con los valores ya escritos como constante (texto entre comillas)."""
    r = {}
    for dn, d in kb['doms'].items():
        vals = d.get('IDEnumDefinedValues')
        if not vals:
            continue
        texto = (d.get('ATTCUSTOMTYPE') or '').split(':')[-1] in ('Character', 'VarChar', 'LongVarChar')
        for parte in re.split(r'(?<!\\);', vals):
            v = [x.strip() for x in parte.split(',', 2)]
            if len(v) >= 2 and v[1]:
                val = v[0].strip('"')
                r[f'{dn}.{v[1]}'.lower()] = f'"{val}"' if texto else val
    return r


def reemplazar_enums(src, enums):
    def cambio(m):
        return enums.get(m.group(0).lower(), m.group(0))
    # fuera de los textos y sin tocar &variables.Metodo
    partes = re.split(r'("[^"\n]*"|\'[^\'\n]*\')', src)
    for i in range(0, len(partes), 2):
        partes[i] = re.sub(r'(?<![&\w.])[A-Za-z_]\w*\.[A-Za-z_]\w*(?![\w(])', cambio, partes[i])
    return ''.join(partes)


# ----------------------------------------------------------------------------- armado

def valores_iniciales(variables, enums):
    """V82: el "Initial Value" de las variables de GeneXus (ej. &DIVISOR = 11, &Ventas = Bool.Si, &Fecha = today()), como
    asignaciones al empezar. Antes no se pasaba y esas variables arrancaban vacías (CuitOk daba mal el dígito verificador)."""
    r = []
    for v in variables or []:
        iv = (v.get('props') or {}).get('ATT_INITIAL_VALUE')
        if iv is None or not str(iv).strip():
            continue
        val = reemplazar_enums(str(iv).strip(), enums)
        val = re.sub(r'(?i)^today\(\)$', 'Today()', val)
        val = re.sub(r'(?i)^now\(\)$', 'Now()', val)
        r.append(f'&{v["nombre"]} = {val}')
    return r


def armar(nombre, p, kb, enums, descripcion=None):
    partes = p['partes']
    reglas = re.sub(r'/\*.*?\*/', '', partes.get(P_RULES, '') or '', flags=re.S)
    reglas = '\n'.join(re.sub(r'//.*', '', l) for l in reglas.splitlines())
    parm = None
    m = re.search(r'(?is)\bparm\s*\(', reglas)
    otras_txt = reglas
    if m:
        # el Parm puede ocupar varios renglones: hasta cerrar el paréntesis
        prof, i = 0, m.end() - 1
        while i < len(reglas):
            prof += {'(': 1, ')': -1}.get(reglas[i], 0)
            if prof == 0:
                break
            i += 1
        dentro = ' '.join(reglas[m.end():i].split())
        parm = f'Parm({dentro});'
        fin = i + 1
        if fin < len(reglas) and reglas[fin:].lstrip().startswith(';'):
            fin = reglas.index(';', fin) + 1
        otras_txt = reglas[:m.start()] + reglas[fin:]
    otras = [l.strip() for l in otras_txt.splitlines() if l.strip()]
    problemas = []
    if otras:
        problemas.append('reglas que no son Parm: ' + ' | '.join(otras)[:200])
    vars_ = []
    for v in partes.get(P_VARIABLES, []) or []:
        n = v['nombre']
        if n in ('Pgmname', 'Pgmdesc', 'Time', 'Today', 'Output'):
            continue
        t = tipo_de(v['props'], kb)
        if t is None:
            problemas.append(f'&{n}: tipo no soportado ({v["props"].get("idBasedOn") or v["props"].get("ATTCUSTOMTYPE")})')
            t = 'Ext(Desconocido)'
        elif t.startswith(('Bc(', 'Ext(')) and not t.startswith('Ext(WebSession'):
            problemas.append(f'&{n}: {t} (el intérprete todavía no ejecuta sus métodos)')
        vars_.append((n, t))
    fuente = reemplazar_enums(partes.get(P_SOURCE, '') or '', enums).replace('\r\n', '\n').strip('\n')
    texto = descripcion or f'{p.get("desc") or nombre}. (completar: qué hace, en castellano)'
    ancho = max([len(n) for n, _ in vars_] + [8]) + 2
    out = ['/* Qué hace:']
    out += ['   ' + l for l in texto.strip().splitlines()]
    if problemas:
        out.append('')
        out += ['   REVISAR: ' + x for x in problemas]
    out.append('*/')
    if parm:
        out.append(parm)
    if vars_:
        out.append('Variables')
        out += [f'  &{n.ljust(ancho)}{t}' for n, t in vars_]
        out.append('EndVariables')
    out.append('')
    ini = valores_iniciales(partes.get(P_VARIABLES, []), enums)
    if ini:
        out.append('/*\tValores iniciales de las variables (en GeneXus, su "Initial Value")\t*/')
        out += ini
        out.append('')
    out.append(fuente)
    return '\n'.join(out).rstrip() + '\n', problemas


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('nombres', nargs='*')
    ap.add_argument('--todos', action='store_true')
    ap.add_argument('--salida', required=True)
    ap.add_argument('--descripciones', help='YAML Nombre: "Qué hace"')
    ap.add_argument('--pisar', action='store_true', help='reescribir aunque el .proc ya exista')
    a = ap.parse_args()
    procs = leer_procs(a.xml)
    kb = cargar_kb(a.xml)
    enums = enumerados(kb)
    desc = {}
    if a.descripciones:
        import yaml
        desc = yaml.safe_load(open(a.descripciones, encoding='utf-8')) or {}
    nombres = sorted(procs) if a.todos else a.nombres
    os.makedirs(a.salida, exist_ok=True)
    escritos = revisar = 0
    for n in nombres:
        real = next((k for k in procs if k.lower() == n.lower()), None)
        if real is None:
            print(f'!! {n}: no está en la KB')
            continue
        destino = os.path.join(a.salida, real + '.proc')
        if os.path.exists(destino) and not a.pisar:
            print(f'-- {real}: ya existe (no lo piso; --pisar para reescribir)')
            continue
        texto, problemas = armar(real, procs[real], kb, enums, desc.get(real))
        open(destino, 'w', encoding='utf-8').write(texto)
        escritos += 1
        if problemas:
            revisar += 1
            if not a.todos:
                print(f'   {real}: REVISAR {problemas}')
    print(f'{escritos} procedimientos escritos en {a.salida} ({revisar} con cosas para revisar)')


if __name__ == '__main__':
    main()
