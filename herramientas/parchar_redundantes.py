#!/usr/bin/env python3
"""
V41 - Fórmulas redundantes de GeneXus (isRedundant="True" en la estructura de la TRN) en los YAML existentes,
SIN regenerarlos:
  - las que están en el YAML como fórmula pero sin 'guardar: true' -> se les agrega (el motor las graba, como GeneXus);
  - las que faltan -> se agregan (ocultas si no están en la pantalla), con lo que necesitan sus fórmulas.
Antes se tomaba como redundante solo la fórmula que estaba en un índice de la tabla, y se escapaban muchas
(PRSTotal, PRSRazonSocial, PRSCUIT...): GeneXus las guarda en la tabla y los reportes las leen de ahí.

  python3 parchar_redundantes.py OptimistU12NEW.xml --trn ../src/main/resources/trn [--solo PRS,PED] [--ver]
"""
import argparse, os, re
import yaml
import gx2yaml as g
import parchar_formulas as pf


def marcadas(trn):
    r = []
    def rec(lv):
        for a in lv['atts']:
            if a.get('red') and a['n'] not in r:
                r.append(a['n'])
        for s in lv['niveles']:
            rec(s)
    for lv in trn['niveles']:
        rec(lv)
    return r


def cierre_llave(texto, ini):
    """Posición de la '}' que cierra el mapa '{' que empieza en ini (respeta comillas)."""
    prof, i, com = 0, ini, None
    while i < len(texto):
        ch = texto[i]
        if com:
            if ch == com and texto[i - 1] != '\\':
                com = None
        elif ch in '"\'':
            com = ch
        elif ch == '{':
            prof += 1
        elif ch == '}':
            prof -= 1
            if prof == 0:
                return i
        i += 1
    return -1


def campos_yaml(texto):
    doc = yaml.safe_load(texto) or {}
    r = set()
    for c in (doc.get('campos') or []):
        if isinstance(c, dict): r.add(str(c.get('nombre')))
    for d in (doc.get('detalles') or []):
        for c in (d.get('campos') or []) if isinstance(d, dict) else []:
            if isinstance(c, dict): r.add(str(c.get('nombre')))
    return r


def marcar_guardar(texto, nombres, atributos_kb=()):
    """Agrega 'guardar: true' a los campos fórmula de la lista que no lo tienen. Devuelve (texto, cambiados).
    Si la fórmula usa un atributo que no está en el YAML (ej. un detalle que no se generó), no se marca:
    no se podría calcular al grabar."""
    cambiados = []
    presentes = campos_yaml(texto)
    for n in nombres:
        m = re.search(r'\{\s*nombre:\s*' + re.escape(n) + r'\s*,', texto)
        if not m:
            continue
        fin = cierre_llave(texto, m.start())
        if fin < 0:
            continue
        campo = texto[m.start():fin + 1]
        if 'formula:' not in campo or re.search(r'\bguardar:\s*true\b', campo):
            continue
        usados = {x for x in re.findall(r'\b[A-Za-z_]\w*\b', campo.split('formula:', 1)[1]) if x in atributos_kb}
        if usados - presentes - {n}:
            print(f'   {n}: no se marca (usa {sorted(usados - presentes)}, que no están en el YAML)')
            continue
        texto = texto[:fin] + ', guardar: true' + texto[fin:]
        cambiados.append(n)
    return texto, cambiados


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('--trn', required=True)
    ap.add_argument('--solo', default='')
    ap.add_argument('--ver', action='store_true')
    a = ap.parse_args()
    kb = pf.cargar_kb(a.xml)
    M = g._modelo(kb)
    solo = {x.strip().lower() for x in a.solo.split(',') if x.strip()}
    tot_add = tot_mark = archivos = 0
    for archivo in sorted(os.listdir(a.trn)):
        if not archivo.endswith('.yaml'):
            continue
        ruta = os.path.join(a.trn, archivo)
        texto = open(ruta, encoding='utf-8').read()
        m = re.search(r'^transaccion:\s*(\S+)', texto, re.M)
        if not m or (solo and m.group(1).lower() not in solo):
            continue
        n = m.group(1)
        if n not in kb['trns']:
            continue
        reds = marcadas(kb['trns'][n])
        if not reds:
            continue
        doc = yaml.safe_load(texto) or {}
        presentes = set()
        for c in (doc.get('campos') or []):
            if isinstance(c, dict): presentes.add(str(c.get('nombre')))
        for d in (doc.get('detalles') or []):
            for c in (d.get('campos') or []) if isinstance(d, dict) else []:
                if isinstance(c, dict): presentes.add(str(c.get('nombre')))
        faltan = [r for r in reds if r not in presentes and M.formula(r)]
        nuevo, cambios = texto, []
        if faltan:
            try:
                generado = g.generar(kb, n, set(), extra=set(faltan))
                nuevo, cambios = pf.parchar(texto, generado, M, kb)
            except Exception as e:
                print(f'!! {archivo}: no se pudieron agregar {faltan} ({type(e).__name__}: {e})')
        nuevo, marcados = marcar_guardar(nuevo, reds, kb['attrs'])
        agregados = [r for r in faltan if re.search(r'\{\s*nombre:\s*' + re.escape(r) + r'\s*,', nuevo)]
        if not agregados and not marcados:
            continue
        archivos += 1
        tot_add += len(agregados)
        tot_mark += len(marcados)
        print(f'== {archivo}: agregadas {len(agregados)} {agregados if agregados else ""}; marcadas guardar {len(marcados)}')
        falt = [r for r in faltan if r not in agregados]
        if falt:
            print(f'   REVISAR: no se agregaron {falt}')
        if not a.ver and nuevo != texto:
            open(ruta, 'w', encoding='utf-8').write(nuevo)
    print(f'{archivos} TRN; {tot_add} fórmulas agregadas, {tot_mark} marcadas con guardar')


if __name__ == '__main__':
    main()
