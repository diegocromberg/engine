#!/usr/bin/env python3
"""
V66j - Data Providers de GeneXus -> proc/<Nombre>.proc (el lenguaje que interpreta el motor).

Un Data Provider que devuelve una colección de un SDT se traduce a un procedimiento que la arma con For Each:

    SDTCPBC                                  For each order ... where ...
    Order ... where ...                          &DPItem = New()
    {                                            &DPItem.CPBCId = CPBCId
        CPBCId = CPBCId                          &DPCol1 = New()
        CPBCDet                                  For each                      (anidado: los renglones del de afuera)
        {                                            &DPItem1 = New()
            CPBCDetItem                              &DPItem1.CPBCDetId = CPBCDetId
            {                                        &DPCol1.Add(&DPItem1)
                CPBCDetId = CPBCDetId            EndFor
            }                                    &DPItem.CPBCDet = &DPCol1
        }                                        &DPLista.Add(&DPItem)
    }                                        EndFor

El resultado es el último parámetro (out: &DPLista), así que se usa igual que en GeneXus:
    For &SDTCPBC in DPCPBCConta.Udp(...)

Uso:
  python3 gx2dp.py OptimistU12NEW.xml DPCPBCConta DPMovStock ... --salida ../src/main/resources/proc
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gx2proc  # noqa: E402

T_DP = '2a9e9aba-d2de-4801-ae7f-5e3819222daf'
P_SOURCE = '1d8aeb5a'
P_RULES = '9b0a32a3'
P_VARIABLES = 'e4c4ade7'


def leer_dps(xml, nombres):
    dps = {}
    pila = []
    for ev, el in ET.iterparse(xml, events=('start', 'end')):
        if ev == 'start':
            pila.append(el.tag)
            continue
        pila.pop()
        if len(pila) == 2 and el.tag == 'Object':
            if el.get('type') == T_DP and el.get('name') in nombres:
                partes = {}
                for p in el.findall('Part'):
                    t = (p.get('type') or '')[:8]
                    if t in (P_SOURCE, P_RULES):
                        partes[t] = p.findtext('Source') or ''
                    elif t == P_VARIABLES:
                        partes[t] = [{'nombre': v.get('Name'),
                                      'props': {x.findtext('Name'): x.findtext('Value') for x in v.iter('Property')}}
                                     for v in p.findall('Variable')]
                dps[el.get('name')] = {'desc': el.get('description'), 'partes': partes}
            el.clear()
    return dps


def sin_comentarios(txt):
    txt = re.sub(r'/\*.*?\*/', '', txt, flags=re.S)
    out = []
    for l in txt.splitlines():
        partes = re.split(r'("[^"\n]*"|\'[^\'\n]*\')', l)
        for i in range(0, len(partes), 2):
            if '//' in partes[i]:
                partes[i] = partes[i].split('//', 1)[0]
                partes = partes[:i + 1]
                break
        out.append(''.join(partes).rstrip())
    return '\n'.join(out)


def clausulas(texto):
    """'Order a, b where x where y' (uno o varios renglones) -> (orden, [condiciones])."""
    t = ' '.join(texto.split())
    orden, wheres = None, []
    trozos = re.split(r'(?i)\b(order|where)\b', t)
    k = 1
    while k < len(trozos):
        clave, cuerpo = trozos[k].lower(), trozos[k + 1].strip()
        if clave == 'order':
            orden = cuerpo
        elif cuerpo:
            wheres.append(cuerpo)
        k += 2
    return orden, wheres


class Traductor:
    def __init__(self):
        self.out = []
        self.n = 0
        self.vars = []      # (nombre, tipo)

    def emitir(self, nivel, texto):
        self.out.append('\t' * nivel + texto)

    def grupo(self, lineas, i, nivel, item, sdt):
        """Recorre los renglones de un bloque { ... } (ya pasado el '{'). Devuelve el índice después del '}'."""
        while i < len(lineas):
            l = lineas[i].strip()
            if not l:
                i += 1
                continue
            if l == '}':
                return i + 1
            m = re.match(r'^(\w+)\s*=\s*(.+)$', l)
            if m:
                self.emitir(nivel, f'{item}.{m.group(1)}\t=\t{m.group(2).strip()}')
                i += 1
                continue
            m = re.match(r'^(\w+)(?:\s+(.*?))?\s*(\{)?$', l)
            if not m:
                raise ValueError(f'no entiendo el renglón: {l}')
            nombre, extra, llave = m.group(1), m.group(2) or '', m.group(3)
            j = i + 1
            cab = [extra] if extra else []
            if not llave:
                while j < len(lineas) and lineas[j].strip() != '{':
                    cab.append(lineas[j].strip())
                    j += 1
                j += 1
            orden, wheres = clausulas(' '.join(cab))
            # grupo colección: puede tener adentro el nivel del ítem (CPBCDetItem { ... }) o directamente los campos
            self.n += 1
            col, it = f'&DPCol{self.n}', f'&DPItem{self.n}'
            self.vars += [(col[1:], f'Coleccion({sdt}.{nombre}Item)'), (it[1:], f'Sdt({sdt}.{nombre}Item)')]
            self.emitir(nivel, f'{col} = New()')
            self.emitir(nivel, 'For each' + (f' order {orden}' if orden else ''))
            for w in wheres:
                self.emitir(nivel + 1, f'where {w}')
            self.emitir(nivel + 1, f'{it} = New()')
            k = j
            while k < len(lineas) and not lineas[k].strip():
                k += 1
            mi = re.match(r'^(\w+)\s*(\{)?$', lineas[k].strip()) if k < len(lineas) else None
            es_item = mi and not re.match(r'^\w+\s*=', lineas[k].strip()) and (
                mi.group(2) or (k + 1 < len(lineas) and lineas[k + 1].strip() == '{'))
            if es_item:
                k = k + 1 if mi.group(2) else k + 2
                k = self.grupo(lineas, k, nivel + 1, it, sdt)
                while k < len(lineas) and not lineas[k].strip():
                    k += 1
                if k >= len(lineas) or lineas[k].strip() != '}':
                    raise ValueError(f'{nombre}: esperaba el cierre del grupo')
                k += 1
            else:
                k = self.grupo(lineas, k, nivel + 1, it, sdt)
            self.emitir(nivel + 1, f'{col}.Add({it})')
            self.emitir(nivel, 'EndFor')
            self.emitir(nivel, f'{item}.{nombre}\t=\t{col}')
            i = k
        raise ValueError('falta un }')


def traducir(nombre, dp, kb, enums):
    partes = dp['partes']
    src = sin_comentarios(gx2proc.reemplazar_enums(partes.get(P_SOURCE, ''), enums).replace('\r\n', '\n'))
    lineas = src.split('\n')
    # encabezado: nombre del SDT [Order ...] y las cláusulas hasta el primer '{'
    i = 0
    while not lineas[i].strip():
        i += 1
    m = re.match(r'^(\w+)\s*(.*)$', lineas[i].strip())
    sdt, cab = m.group(1), [m.group(2)]
    i += 1
    while lineas[i].strip() != '{':
        cab.append(lineas[i].strip())
        i += 1
    orden, wheres = clausulas(' '.join(cab))
    t = Traductor()
    t.emitir(0, '&DPLista = New()')
    t.emitir(0, 'For each' + (f' order {orden}' if orden else ''))
    for w in wheres:
        t.emitir(1, f'where {w}')
    t.emitir(1, '&DPItem = New()')
    fin = t.grupo(lineas, i + 1, 1, '&DPItem', sdt)
    if any(l.strip() for l in lineas[fin:]):
        raise ValueError('hay algo después del último }')
    t.emitir(1, '&DPLista.Add(&DPItem)')
    t.emitir(0, 'EndFor')

    reglas = sin_comentarios(partes.get(P_RULES, '') or '')
    mp = re.search(r'(?is)\bparm\s*\((.*?)\)\s*;', reglas)
    parm = ' '.join(mp.group(1).split()) if mp else ''
    parm = re.sub(r'\s*:\s*', ': ', parm)
    parm = (parm + ', ' if parm else '') + 'out: &DPLista'
    vars_ = [('DPLista', f'Coleccion({sdt})'), ('DPItem', f'Sdt({sdt})')]
    usadas = set(re.findall(r'&(\w+)', parm + '\n' + '\n'.join(t.out)))
    for v in partes.get(P_VARIABLES, []) or []:
        if v['nombre'] in usadas and v['nombre'] not in ('DPLista', 'DPItem'):
            vars_.append((v['nombre'], gx2proc.tipo_de(v['props'], kb) or 'VarChar(100)'))
    vars_ += t.vars
    ancho = max(len(n) for n, _ in vars_) + 2
    out = ['/* Qué hace:',
           f'   Data Provider {nombre} de GeneXus ({dp.get("desc") or nombre}), traducido con herramientas/gx2dp.py:',
           f'   devuelve la colección de {sdt} (último parámetro) con sus renglones.',
           '*/',
           f'Parm({parm});',
           'Variables']
    out += [f'  &{n.ljust(ancho)}{ti}' for n, ti in vars_]
    out += ['EndVariables', ''] + t.out
    return '\n'.join(out) + '\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('nombres', nargs='+')
    ap.add_argument('--salida', required=True)
    a = ap.parse_args()
    kb = gx2proc.cargar_kb(a.xml)
    enums = gx2proc.enumerados(kb)
    dps = leer_dps(a.xml, set(a.nombres))
    os.makedirs(a.salida, exist_ok=True)
    for n in a.nombres:
        if n not in dps:
            print(f'{n}: no está en la KB (o no es un Data Provider)')
            continue
        try:
            txt = traducir(n, dps[n], kb, enums)
        except ValueError as e:
            print(f'{n}: ERROR {e}')
            continue
        open(os.path.join(a.salida, n + '.proc'), 'w', encoding='utf-8').write(txt)
        print(f'{n}: listo')


if __name__ == '__main__':
    main()
